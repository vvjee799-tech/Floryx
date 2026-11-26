package com.flowchat.app.presentation.assistant

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.flowchat.app.R
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Recognizes a turn locally, using installed model files or instrumentation assets. */
internal class XasrSpeechInput(
    private val assets: AssetManager? = null,
    private val openAudio: () -> XasrPcmInput = ::XasrMicrophone,
    private val locateModelDirectory: suspend () -> File? = { null },
    private val onMeasurement: ((XasrMeasurement) -> Unit)? = null
) : AssistantSpeechInput {
    private var job: Job? = null

    override fun start(listener: AssistantSpeechInput.Listener) {
        check(job == null)
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val transcript = recognizerSlot.withLock { capture(listener) }
                withContext(Dispatchers.Main) {
                    if (transcript.isBlank()) {
                        listener.error(R.string.assistant_voice_no_speech)
                    } else {
                        listener.endOfSpeech()
                        listener.result(transcript)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                withContext(Dispatchers.Main) { listener.error(R.string.assistant_voice_permission) }
            } catch (failure: LinkageError) {
                Log.w("XasrSpeechInput", "Local recognition runtime unavailable", failure)
                withContext(Dispatchers.Main) { listener.error(R.string.assistant_voice_unavailable) }
            } catch (failure: Exception) {
                if (failure !is RuntimeException && failure !is IOException) throw failure
                Log.w("XasrSpeechInput", "Local recognition interrupted", failure)
                withContext(Dispatchers.Main) { listener.error(R.string.assistant_voice_interrupted) }
            }
        }
    }

    override fun close() {
        job?.cancel()
        job = null
    }

    private suspend fun capture(listener: AssistantSpeechInput.Listener): String {
        val beforeLoad = onMeasurement?.let { memory() }
        val preparationStarted = SystemClock.elapsedRealtime()
        val models = locateModelDirectory()
        check(models != null || assets != null) { "Missing X-ASR model source" }
        val preparationMs = SystemClock.elapsedRealtime() - preparationStarted
        currentCoroutineContext().ensureActive()
        val loadStarted = SystemClock.elapsedRealtime()
        val recognizer = OnlineRecognizer(assets.takeIf { models == null }, recognizerConfig(models))
        val loadMs = SystemClock.elapsedRealtime() - loadStarted
        var loaded = beforeLoad
        var duringCapture = beforeLoad
        var audioSamples = 0
        var outcome = "interrupted"
        try {
            loaded = onMeasurement?.let { memory() }
            currentCoroutineContext().ensureActive()
            val stream = recognizer.createStream()
            try {
                openAudio().use { audio ->
                    withContext(Dispatchers.Main) { listener.ready() }
                    val listeningSince = SystemClock.elapsedRealtime()
                    var text = ""
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val samples = audio.read()
                        audioSamples += samples.size
                        if (samples.isNotEmpty()) {
                            stream.acceptWaveform(samples, SAMPLE_RATE)
                            while (recognizer.isReady(stream)) recognizer.decode(stream)
                            val updated = recognizer.getResult(stream).text.trim()
                            if (updated.isNotBlank() && updated != text) {
                                text = updated
                                withContext(Dispatchers.Main) { listener.partial(text) }
                            }
                            if (text.isNotBlank() && recognizer.isEndpoint(stream)) {
                                outcome = "endpoint"
                                return text
                            }
                        }
                        val elapsed = SystemClock.elapsedRealtime() - listeningSince
                        if (text.isBlank() && elapsed >= NO_SPEECH_MS) {
                            outcome = "no_speech"
                            return ""
                        }
                        check(elapsed < MAX_LISTENING_MS) { "X-ASR endpoint did not arrive" }
                    }
                }
            } finally {
                try {
                    duringCapture = onMeasurement?.let { memory() }
                } finally {
                    stream.release()
                }
            }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            recognizer.release()
            if (onMeasurement != null) {
                onMeasurement(XasrMeasurement(
                    preparationMs, loadMs, checkNotNull(beforeLoad), checkNotNull(loaded),
                    checkNotNull(duringCapture), audioSamples, outcome
                ))
            }
        }
    }

    private fun recognizerConfig(directory: File?): OnlineRecognizerConfig {
        fun modelPath(name: String) = directory?.resolve(name)?.absolutePath ?: name
        return OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = modelPath(XasrModelPack.ENCODER),
                    decoder = modelPath(XasrModelPack.DECODER),
                    joiner = modelPath(XasrModelPack.JOINER),
                ),
                tokens = modelPath(XasrModelPack.TOKENS),
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(rule2 = EndpointRule(true, 1.2f, 0f)),
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        )
    }

    private companion object {
        private val recognizerSlot = Mutex()
        private const val NO_SPEECH_MS = 5_000L
        private const val MAX_LISTENING_MS = 24_000L
    }
}

internal data class XasrMeasurement(
    val preparationMs: Long,
    val loadMs: Long,
    val beforeLoad: XasrMemory,
    val loaded: XasrMemory,
    val duringCapture: XasrMemory,
    val audioSamples: Int,
    val outcome: String,
)

internal data class XasrMemory(val pssKiB: Int, val peakRssKiB: Int?)

private fun memory(): XasrMemory {
    val pss = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }.totalPss
    val peak = File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmHWM:") }
            ?.removePrefix("VmHWM:")?.trim()?.substringBefore(' ')?.toIntOrNull()
    }
    return XasrMemory(pss, peak)
}

internal const val SAMPLE_RATE = 16_000
internal const val FRAME_SAMPLES = 320

internal interface XasrPcmInput : AutoCloseable {
    suspend fun read(): FloatArray
}

@SuppressLint("MissingPermission")
internal class XasrMicrophone : XasrPcmInput {
    private val buffer = ShortArray(FRAME_SAMPLES)
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME_SAMPLES * Short.SIZE_BYTES * 4,
        ),
    )

    init {
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        } catch (failure: RuntimeException) {
            record.release()
            throw failure
        }
    }

    val routedDeviceType: Int? get() = record.routedDevice?.type

    override suspend fun read(): FloatArray {
        val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
        check(count >= 0) { "AudioRecord read failed ($count)" }
        if (count == 0) delay(10)
        return FloatArray(count) { buffer[it] / 32768f }
    }

    override fun close() {
        try {
            record.stop()
        } finally {
            record.release()
        }
    }
}
