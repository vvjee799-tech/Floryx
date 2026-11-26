package com.flowchat.app.presentation.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.StringRes
import com.flowchat.app.R
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Native keyword detector seam; unit tests use a fake, production uses sherpa-onnx. */
internal interface KeywordDetector : AutoCloseable {
    fun accept(frame: FloatArray): String?
    fun reset()
}

/** Microphone seam so instrumentation can replay a PCM fixture without a real microphone. */
internal interface KwsAudioSource : AutoCloseable {
    fun read(buffer: FloatArray): Int
}

internal object KwsModelPack {
    const val VERSION = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
    const val ARCHIVE_BYTES = 32_885_699L
    const val ARCHIVE_SHA256 = "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6"
    const val ENCODER = "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
    const val DECODER = "decoder-epoch-13-avg-2-chunk-8-left-64.onnx"
    const val JOINER = "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx"
    const val TOKENS = "tokens.txt"
    val files = mapOf(ENCODER to 4_600_657L, DECODER to 759_829L, JOINER to 86_629L, TOKENS to 1_928L)
    const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$VERSION.tar.bz2"

    fun isComplete(directory: File): Boolean =
        files.all { (name, size) -> directory.resolve(name).let { it.isFile && it.length() == size } }
}

/** Installs the pinned archive into private storage with the same verify-before-publish rules as X-ASR. */
internal class KwsModelStore(
    private val root: File,
    private val fetch: suspend (target: File) -> Unit = ::downloadKwsArchive
) {
    private val installed = root.resolve(KwsModelPack.VERSION)

    fun modelDirectory(): File? = installed.takeIf(KwsModelPack::isComplete)

    suspend fun install(): File = withContext(Dispatchers.IO) {
        installLock.withLock {
            modelDirectory()?.let { return@withLock it }
            if (!root.mkdirs() && !root.isDirectory) throw IOException("Cannot create KWS storage")
            val staging = root.resolve(".installing")
            if (!staging.deleteRecursively() || !staging.mkdir()) throw IOException("Cannot prepare KWS storage")
            try {
                if (root.usableSpace < KwsModelPack.ARCHIVE_BYTES + KwsModelPack.files.values.sum()) {
                    throw IOException("Insufficient space for the KWS model")
                }
                val archive = staging.resolve("model.tar.bz2")
                fetch(archive)
                verify(archive)
                val extracted = staging.resolve("model")
                if (!extracted.mkdir()) throw IOException("Cannot create KWS model directory")
                extract(archive, extracted)
                if (!installed.deleteRecursively()) throw IOException("Cannot replace incomplete KWS model")
                Files.move(extracted.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                installed
            } finally {
                if (!staging.deleteRecursively()) Log.w("KwsModel", "Could not remove KWS staging")
            }
        }
    }

    private fun verify(archive: File) {
        if (archive.length() != KwsModelPack.ARCHIVE_BYTES) throw IOException("KWS archive size mismatch")
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val expected = KwsModelPack.ARCHIVE_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!MessageDigest.isEqual(digest.digest(), expected)) throw IOException("KWS archive hash mismatch")
    }

    private fun extract(archive: File, directory: File) {
        val remaining = KwsModelPack.files.keys.toMutableSet()
        val buffer = ByteArray(64 * 1024)
        archive.inputStream().buffered().use { raw ->
            TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                while (remaining.isNotEmpty()) {
                    val entry = tar.nextEntry ?: throw IOException("KWS archive is incomplete")
                    val name = entry.name.removePrefix("${KwsModelPack.VERSION}/")
                    val expected = KwsModelPack.files[name] ?: continue
                    if (entry.name != "${KwsModelPack.VERSION}/$name" || !remaining.remove(name) ||
                        !entry.isFile || entry.isLink || entry.isSymbolicLink || entry.size != expected
                    ) {
                        throw IOException("KWS archive entry mismatch: $name")
                    }
                    directory.resolve(name).outputStream().buffered().use { output ->
                        var copied = 0L
                        while (copied < expected) {
                            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), expected - copied).toInt())
                            if (read <= 0) throw IOException("KWS archive truncated: $name")
                            output.write(buffer, 0, read)
                            copied += read
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val installLock = Mutex()
    }
}

@SuppressLint("MissingPermission")
internal class KwsRecordSource : KwsAudioSource {
    private val shorts = ShortArray(WAKE_FRAME_SAMPLES)
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        WAKE_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(
            AudioRecord.getMinBufferSize(
                WAKE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ),
            WAKE_FRAME_SAMPLES * Short.SIZE_BYTES * 8,
        ),
    )

    init {
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "KWS AudioRecord unavailable" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "KWS recording did not start" }
        } catch (failure: RuntimeException) {
            record.release()
            throw failure
        }
    }

    override fun read(buffer: FloatArray): Int {
        val count = record.read(shorts, 0, minOf(shorts.size, buffer.size), AudioRecord.READ_BLOCKING)
        if (count > 0) {
            for (index in 0 until count) buffer[index] = shorts[index] / 32768f
        }
        return count
    }

    override fun close() {
        runCatching { record.stop() }
        record.release()
    }
}

internal class SherpaKeywordDetector(
    directory: File,
    keywordsFile: File
) : KeywordDetector {
    private val spotter = KeywordSpotter(
        null,
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = WAKE_SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = directory.resolve(KwsModelPack.ENCODER).absolutePath,
                    decoder = directory.resolve(KwsModelPack.DECODER).absolutePath,
                    joiner = directory.resolve(KwsModelPack.JOINER).absolutePath,
                ),
                tokens = directory.resolve(KwsModelPack.TOKENS).absolutePath,
                numThreads = 1,
            ),
            maxActivePaths = 4,
            keywordsFile = keywordsFile.absolutePath,
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        ),
    )
    private val stream: OnlineStream = spotter.createStream()

    override fun accept(frame: FloatArray): String? {
        stream.acceptWaveform(frame, WAKE_SAMPLE_RATE)
        while (spotter.isReady(stream)) spotter.decode(stream)
        val keyword = spotter.getResult(stream).keyword
        if (keyword.isNullOrEmpty()) return null
        spotter.reset(stream)
        return keyword
    }

    override fun reset() {
        runCatching { spotter.reset(stream) }
    }

    override fun close() {
        runCatching { stream.release() }
        runCatching { spotter.release() }
    }
}

/**
 * Debug-only always-on-while-panel-open prototype. It owns one microphone reader, a 150 ms
 * pre-roll ring and a command channel so the wake word and the command start are handed to
 * X-ASR without reopening the microphone. Audio never leaves memory.
 */
internal class KwsWakeWordController private constructor(
    private val context: Context,
    private val audioGuard: AssistantAudioGuard?,
    private val pack: XasrVoicePack?,
    private val phonemes: WakeWordPhonemes?,
    private val sourceFactory: () -> KwsAudioSource,
    private val detectorFactory: (File, File) -> KeywordDetector,
) : WakeWordController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(WakeWordState())
    override val state: StateFlow<WakeWordState> = mutable
    override var onWakeWord: (() -> Unit)? = null

    private val gate = WakeWordGate()
    private val detectorLock = Any()
    private var job: Job? = null
    private var source: KwsAudioSource? = null
    private var detector: KeywordDetector? = null
    private var lease: AssistantAudioGuard.Lease? = null
    private var commandChannel: Channel<FloatArray>? = null
    private var commandInput: CommandPcmInput? = null
    @Volatile private var speaking = false

    override fun start(keyword: String): Boolean {
        val cleaned = keyword.trim()
        if (cleaned.isEmpty()) return false
        val encoded = encode(cleaned) ?: return failWith(R.string.assistant_wake_word_unsupported)
        if (job != null) return updateKeyword(cleaned)
        if (pack?.isReady() != true) return failWith(R.string.assistant_wake_word_pack_missing)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return failWith(R.string.assistant_voice_permission)
        }
        mutable.value = WakeWordState(running = true, keyword = cleaned, status = R.string.assistant_wake_word_preparing)
        job = scope.launch {
            try {
                val directory = modelDirectory()
                val keywords = keywordsFile(encoded, cleaned)
                val created = detectorFactory(directory, keywords)
                val acquired = audioGuard?.acquireCapture { message -> scope.launch { fail(message) } }
                if (acquired is AssistantAudioGuard.AcquireResult.Blocked) {
                    created.close()
                    fail(acquired.message)
                    return@launch
                }
                lease = (acquired as? AssistantAudioGuard.AcquireResult.Acquired)?.lease
                synchronized(detectorLock) { detector = created }
                val opened = sourceFactory()
                source = opened
                mutable.value = mutable.value.copy(status = R.string.assistant_wake_word_listening)
                withContext(Dispatchers.IO) { captureLoop(opened) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                fail(R.string.assistant_voice_permission)
            } catch (_: LinkageError) {
                fail(R.string.assistant_voice_unavailable)
            } catch (failure: Exception) {
                Log.w("KwsWakeWord", "Wake word session stopped", failure)
                fail(R.string.assistant_wake_word_failed)
            } finally {
                cleanup()
            }
        }
        return true
    }

    private suspend fun captureLoop(opened: KwsAudioSource) {
        val frame = FloatArray(WAKE_FRAME_SAMPLES)
        while (currentCoroutineContext().isActive) {
            val count = opened.read(frame)
            if (count <= 0) {
                delay(5)
                continue
            }
            val chunk = frame.copyOf(count)
            gate.onFrame(chunk)
            val channel = commandChannel
            if (channel != null) {
                channel.trySend(chunk)
                continue
            }
            if (speaking) continue
            val generation = gate.currentGeneration()
            val hit = synchronized(detectorLock) { detector?.accept(chunk) } ?: continue
            if (!gate.detectionAllowed(generation)) {
                synchronized(detectorLock) { detector?.reset() }
                continue
            }
            val preRoll = gate.beginCommand()
            val newChannel = Channel<FloatArray>(capacity = 1024)
            commandChannel = newChannel
            commandInput = CommandPcmInput(preRoll, newChannel)
            mutable.value = mutable.value.copy(
                detections = mutable.value.detections + 1,
                status = R.string.assistant_voice_listening,
            )
            withContext(Dispatchers.Main) { onWakeWord?.invoke() }
            Log.i("KwsWakeWord", "Detected $hit")
        }
    }

    override fun takeCommandInput(): AssistantSpeechInput? {
        val input = commandInput ?: return null
        commandInput = null
        return pack?.commandInput { input } ?: run {
            input.close()
            null
        }
    }

    override fun onCommandFinished() {
        val channel = commandChannel
        commandChannel = null
        channel?.close()
        commandInput = null
        gate.endCommand()
        synchronized(detectorLock) { detector?.reset() }
        if (mutable.value.running) {
            mutable.value = mutable.value.copy(status = R.string.assistant_wake_word_listening)
        }
    }

    override fun setPlaybackActive(active: Boolean) {
        if (speaking == active) return
        speaking = active
        gate.setPlaybackActive(active)
        synchronized(detectorLock) { detector?.reset() }
    }

    override fun updateKeyword(keyword: String): Boolean {
        val cleaned = keyword.trim()
        if (cleaned.isEmpty()) return false
        val encoded = encode(cleaned) ?: return failWith(R.string.assistant_wake_word_unsupported)
        if (job == null) {
            mutable.value = mutable.value.copy(keyword = cleaned)
            return true
        }
        return try {
            val model = localModelDirectory() ?: return failWith(R.string.assistant_wake_word_pack_missing)
            val created = detectorFactory(model, keywordsFile(encoded, cleaned))
            gate.invalidateKeyword()
            onCommandFinished()
            synchronized(detectorLock) {
                val previous = detector
                detector = created
                previous?.close()
            }
            mutable.value = mutable.value.copy(keyword = cleaned, status = R.string.assistant_wake_word_listening)
            true
        } catch (failure: Exception) {
            Log.w("KwsWakeWord", "Cannot switch wake word", failure)
            fail(R.string.assistant_wake_word_failed)
            false
        }
    }

    override fun stop() {
        val running = job
        job = null
        running?.cancel()
        cleanup()
        mutable.value = WakeWordState(keyword = mutable.value.keyword)
    }

    private fun cleanup() {
        commandChannel?.close()
        commandChannel = null
        commandInput = null
        gate.endCommand()
        source?.let { runCatching { it.close() } }
        source = null
        synchronized(detectorLock) {
            detector?.let { runCatching { it.close() } }
            detector = null
        }
        lease?.let { runCatching { it.close() } }
        lease = null
    }

    private fun fail(@StringRes message: Int) {
        val running = job
        job = null
        running?.cancel()
        cleanup()
        mutable.value = mutable.value.copy(
            running = false,
            status = null,
            error = message,
        )
    }

    private fun failWith(@StringRes message: Int): Boolean {
        mutable.value = mutable.value.copy(running = false, status = null, error = message)
        return false
    }

    private fun encode(keyword: String): String? = phonemes?.encode(keyword)

    private suspend fun modelDirectory(): File {
        localModelDirectory()?.let { return it }
        return KwsModelStore(context.noBackupFilesDir.resolve("kws-model")).install()
    }

    private fun localModelDirectory(): File? {
        context.getExternalFilesDir(null)?.resolve("kws-model")?.let {
            if (KwsModelPack.isComplete(it)) return it
        }
        return File(context.noBackupFilesDir, "kws-model/${KwsModelPack.VERSION}")
            .takeIf(KwsModelPack::isComplete)
    }

    private fun keywordsFile(encoded: String, keyword: String): File {
        val directory = context.cacheDir.resolve("kws")
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create KWS keywords")
        val file = directory.resolve("keywords.txt")
        file.writeText(keywordLine(encoded, keyword), Charsets.UTF_8)
        return file
    }

    private class CommandPcmInput(
        private val preRoll: FloatArray,
        private val channel: Channel<FloatArray>,
    ) : XasrPcmInput {
        private var offset = 0
        private var closed = false

        override suspend fun read(): FloatArray {
            if (closed) {
                delay(20)
                return FloatArray(0)
            }
            if (offset < preRoll.size) {
                val end = minOf(offset + WAKE_FRAME_SAMPLES, preRoll.size)
                val chunk = preRoll.copyOfRange(offset, end)
                offset = end
                return chunk
            }
            return channel.receiveCatching().getOrNull() ?: run {
                closed = true
                FloatArray(0)
            }
        }

        override fun close() {
            closed = true
            channel.close()
        }
    }

    companion object {
        fun create(context: Context): WakeWordController? {
            val pack = XasrVoicePack.forDevice(context) ?: return null
            val phonemes = runCatching {
                context.assets.open("kws_ppinyin.tsv").use(WakeWordPhonemes::load)
            }.getOrNull() ?: return null
            return KwsWakeWordController(
                context = context,
                audioGuard = SystemAssistantAudioGuard(context),
                pack = pack,
                phonemes = phonemes,
                sourceFactory = { KwsRecordSource() },
                detectorFactory = { directory, keywords -> SherpaKeywordDetector(directory, keywords) },
            )
        }

        /** Test seam: injects the microphone source and detector while keeping the production flow. */
        internal fun createForTest(
            context: Context,
            pack: XasrVoicePack?,
            sourceFactory: () -> KwsAudioSource,
            detectorFactory: (File, File) -> KeywordDetector,
            audioGuard: AssistantAudioGuard? = null,
        ): WakeWordController? {
            val phonemes = runCatching {
                context.assets.open("kws_ppinyin.tsv").use(WakeWordPhonemes::load)
            }.getOrNull() ?: return null
            return KwsWakeWordController(
                context = context,
                audioGuard = audioGuard,
                pack = pack,
                phonemes = phonemes,
                sourceFactory = sourceFactory,
                detectorFactory = detectorFactory,
            )
        }
    }
}

private suspend fun downloadKwsArchive(target: File) {
    HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
            }
        }
    }.use { client ->
        client.prepareGet(KwsModelPack.DOWNLOAD_URL).execute { response ->
            if (response.status != HttpStatusCode.OK) throw IOException("KWS HTTP ${response.status.value}")
            val body = response.bodyAsChannel()
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    val read = body.readAvailable(buffer)
                    if (read == -1) break
                    received += read
                    if (received > KwsModelPack.ARCHIVE_BYTES) throw IOException("KWS archive too large")
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}