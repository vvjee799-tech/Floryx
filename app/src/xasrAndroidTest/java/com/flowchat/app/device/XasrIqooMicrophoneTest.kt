package com.flowchat.app.device

import com.flowchat.app.presentation.assistant.SAMPLE_RATE
import com.flowchat.app.presentation.assistant.XasrMeasurement
import com.flowchat.app.presentation.assistant.XasrMicrophone
import com.flowchat.app.presentation.assistant.XasrPcmInput
import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.AssistantSpeechInput
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Acoustic replay from the iQOO speaker into its microphone, with no task dispatch. */
class XasrIqooMicrophoneTest {
    @Test
    fun measuresRealMicrophoneAndLocalTurnBoundaries() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runXasrIqooMicrophone") == "true")
        requireIqoo()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val outputDirName = args.getString("micOutputDir", "assistant-xasr-iqoo-mic")
        val selectedIds = args.getString("micSampleIds")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        val selectedFixtures = if (selectedIds == null) iqooSpeechFixtures else {
            iqooSpeechFixtures.filter { it.id in selectedIds }.also { check(it.isNotEmpty()) }
        }
        val directory = File(context.getExternalFilesDir(null), outputDirName)
        check(directory.mkdir()) { "Use a fresh microphone evidence directory" }
        val files = StagedXasrFiles(assets, context.cacheDir.resolve("assistant-xasr-iqoo-cache"))
        val harness = SystemAssistantDiagnosticTest()
        val panel = harness.open(AssistantPanelActivity::class.java)
        val rows = JSONArray()
        val evidence = JSONObject()
            .put("device", Build.MODEL)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("audioPath", "on-device speaker to built-in microphone")
            .put("officialRequests", 0)
            .put("samples", rows)
        fun save() = File(directory, "result.json").writeText(evidence.toString(2))
        save()
        try {
            ensureTestMicrophonePermission(panel, harness)
            for (sample in selectedFixtures) {
                val row = JSONObject().put("id", sample.id)
                rows.put(row)
                save()
                val capture = IqooAcousticCapture(assets, files)
                val armedAt = SystemClock.elapsedRealtime()
                try {
                    withContext(Dispatchers.Main) { capture.start(QuietListener) }
                    val output = capture.replay(context, sample.asset, armedAt)
                    val valid = output.inputDevice == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                        output.outputDevice == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    val normalizedTranscript = output.transcript?.let(::normalizeSpeech).orEmpty()
                    row.put("expected", sample.phrase)
                        .put("acousticRoutesVerified", valid)
                        .put("exact", output.transcript?.let(sample::exact) == true)
                        .put("slotsPassed", valid && output.transcript?.let(sample::slotsPassed) == true && !output.cutOffPlayback)
                        .put("unexpectedDeepSeek", sample.negative && normalizedTranscript.contains("deepseek"))
                        .put("transcriptLength", normalizedTranscript.length)
                        .put("matchedPhraseMask", sample.phrase.map { ch ->
                            if (!ch.isLetterOrDigit()) ch
                            else if (normalizedTranscript.contains(normalizeSpeech(ch.toString()))) ch
                            else '_'
                        }.joinToString(""))
                    output.writeTo(row, keepTranscript = output.transcript?.let(sample::exact) == true)
                } finally {
                    withContext(Dispatchers.Main) { capture.close() }
                    save()
                }
            }
            evidence.put("scoredSamples", rows.length())
                .put("slotsPassed", (0 until rows.length()).count { rows.getJSONObject(it).optBoolean("slotsPassed") })
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.Main) { panel.finish() }
                withContext(NonCancellable + Dispatchers.IO) { files.remove() }
            } finally {
                save()
            }
        }
    }
}

internal fun requireIqoo() {
    check(Build.MODEL == "V2302A" && Build.MANUFACTURER == "vivo" && Build.SUPPORTED_ABIS.first() == "arm64-v8a")
}

internal suspend fun ensureTestMicrophonePermission(panel: Activity, harness: SystemAssistantDiagnosticTest) {
    val permission = Manifest.permission.RECORD_AUDIO
    if (panel.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
    val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
    withContext(Dispatchers.Main) { panel.requestPermissions(arrayOf(permission), 120) }
    harness.await("w01-microphone-permission") {
        if (panel.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) true
        else {
            val root = automation.rootInActiveWindow
            val packageName = root?.packageName?.toString()
            if (packageName in setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.vivo.permissionmanager")) {
                root?.findAccessibilityNodeInfosByViewId("$packageName:id/permission_allow_foreground_only_button")
                    ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            false
        }
    }
}

internal data class AcousticFixture(
    val id: String,
    val asset: String,
    val phrase: String,
    val slots: List<String>,
    val negative: Boolean = false,
) {
    fun exact(text: String) = normalizeSpeech(text) == normalizeSpeech(phrase)

    fun slotsPassed(text: String): Boolean {
        val normalized = normalizeSpeech(text)
        var after = 0
        for (slot in slots) {
            val target = normalizeSpeech(slot)
            val index = normalized.indexOf(target, after)
            if (index < 0) return false
            after = index + target.length
        }
        return !negative || !normalized.contains("deepseek")
    }
}

internal fun normalizeSpeech(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
    .lowercase()
    .filter(Char::isLetterOrDigit)

internal val iqooSpeechFixtures = listOf(
    AcousticFixture("multiply", "01-yaoyao.wav", "请问六乘八等于多少", listOf("六乘八", "等于多少")),
    AcousticFixture("add", "01-huihui.wav", "请算一下三加四等于多少", listOf("三加四", "等于多少")),
    AcousticFixture("timer", "06-yaoyao.wav", "设置一个十二分钟的倒计时", listOf("设置", "十二分钟", "倒计时")),
    AcousticFixture("deepseek-yaoyao", "08-yaoyao.wav", "在DeepSeek里查一下翻译功能", listOf("DeepSeek", "翻译功能")),
    AcousticFixture("deepseek-male", "d02-zm_009.wav", "打开DeepSeek看看模型列表", listOf("打开", "DeepSeek", "模型列表")),
    AcousticFixture("deepseek-female", "d03-zf_001.wav", "我想知道DeepSeek能不能离线运行", listOf("DeepSeek", "离线运行")),
    AcousticFixture("telegram", "a02-zm_009.wav", "打开Telegram查看新消息", listOf("打开", "Telegram", "新消息")),
    AcousticFixture("discord", "a04-zf_001.wav", "在Discord里查找社区活动", listOf("Discord", "查找", "社区活动")),
    AcousticFixture("deep-sea", "n01-zm_009.wav", "请翻译deep sea这个短语", listOf("翻译", "deep sea", "短语"), negative = true),
    AcousticFixture("deep-sleep", "n02-zf_001.wav", "搜索deep sleep的文章", listOf("搜索", "deep sleep", "文章"), negative = true),
)

internal data class AcousticResult(
    val transcript: String?,
    val error: Int?,
    val inputDevice: Int?,
    val outputDevice: Int?,
    val peak: Float,
    val readyMs: Long,
    val clipEndedAt: Long,
    val finalAt: Long,
    val cutOffPlayback: Boolean,
    val report: XasrMeasurement?,
) {
    fun writeTo(row: JSONObject, keepTranscript: Boolean) {
        row.put("transcript", transcript?.takeIf { keepTranscript } ?: JSONObject.NULL)
            .put("nonMatchingTranscriptOmitted", !keepTranscript && !transcript.isNullOrBlank())
            .put("errorResource", error ?: JSONObject.NULL)
            .put("inputDevice", inputDevice ?: JSONObject.NULL).put("outputDevice", outputDevice ?: JSONObject.NULL)
            .put("micSignalPeak", peak.toDouble()).put("clickToListeningMs", readyMs)
            .put("audioEndToFinalMs", finalAt - clipEndedAt).put("prematureEndpoint", cutOffPlayback)
        report?.let {
            row.put("prepareMs", it.preparationMs).put("loadMs", it.loadMs).put("outcome", it.outcome)
                .put("audioMs", it.audioSamples * 1000L / SAMPLE_RATE)
                .put("pssBeforeKiB", it.beforeLoad.pssKiB).put("pssLoadedKiB", it.loaded.pssKiB)
                .put("peakRssKiB", it.duringCapture.peakRssKiB ?: JSONObject.NULL)
        }
    }
}

/** The player and recorder share no PCM. The recognizer only receives AudioRecord samples. */
internal class IqooAcousticCapture(
    private val assets: AssetManager,
    files: StagedXasrFiles,
    private val allowSubmission: ((String) -> Boolean)? = null,
) : AssistantSpeechInput {
    private data class Recognition(val text: String?, val error: Int?, val time: Long)
    private val ready = CompletableDeferred<Long>()
    private val final = CompletableDeferred<Recognition>()
    @Volatile private var measurement: XasrMeasurement? = null
    @Volatile private var inputDevice: Int? = null
    @Volatile private var peak = 0f
    @Volatile private var playbackEnded = false
    private val engine = XasrPrototypeSpeechInput(assets, openAudio = {
        val microphone = XasrMicrophone()
        object : XasrPcmInput {
            override suspend fun read(): FloatArray {
                val samples = microphone.read()
                if (samples.isNotEmpty()) {
                    inputDevice = microphone.routedDeviceType ?: inputDevice
                    peak = maxOf(peak, samples.maxOf { abs(it) })
                }
                return samples
            }

            override fun close() = microphone.close()
        }
    }, locateModelDirectory = { files.prepare() }, onMeasurement = { measurement = it })

    override fun start(listener: AssistantSpeechInput.Listener) = engine.start(object : AssistantSpeechInput.Listener {
        override fun ready() {
            ready.complete(SystemClock.elapsedRealtime())
            listener.ready()
        }

        override fun partial(text: String) = listener.partial(text)
        override fun endOfSpeech() = listener.endOfSpeech()

        override fun result(text: String) {
            final.complete(Recognition(text, null, SystemClock.elapsedRealtime()))
            if (allowSubmission == null || playbackEnded && inputDevice == AudioDeviceInfo.TYPE_BUILTIN_MIC && allowSubmission.invoke(text)) listener.result(text)
            else listener.error(R.string.assistant_voice_no_speech)
        }

        override fun error(message: Int) {
            final.complete(Recognition(null, message, SystemClock.elapsedRealtime()))
            listener.error(message)
        }
    })

    override fun close() = engine.close()

    suspend fun replay(context: Context, asset: String, started: Long): AcousticResult {
        val listeningAt = withTimeout(20_000) { ready.await() }
        val (outputDevice, playbackEnd) = withContext(Dispatchers.IO) {
            playFixture(context, assets, asset).also { playbackEnded = true }
        }
        val result = withTimeout(12_000) { final.await() }
        return AcousticResult(result.text, result.error, inputDevice, outputDevice, peak, listeningAt - started,
            playbackEnd, result.time, result.time < playbackEnd, measurement)
    }
}

private suspend fun playFixture(context: Context, assets: AssetManager, asset: String): Pair<Int?, Long> {
    val clip = WaveReader.readWave(assets, asset)
    check(clip.sampleRate == SAMPLE_RATE && clip.samples.isNotEmpty())
    val pcm = ShortArray(clip.samples.size) { (clip.samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    val audio = context.getSystemService(AudioManager::class.java)
    val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    try {
        check(track.setPreferredDevice(speaker))
        check(track.write(pcm, 0, pcm.size) == pcm.size)
        track.play()
        val outputDevice = withTimeout(1_000) {
            while (track.routedDevice == null) delay(10)
            track.routedDevice?.type
        }
        check(outputDevice == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { "Test clip did not route to the iQOO built-in speaker" }
        withTimeout(pcm.size * 1000L / SAMPLE_RATE + 2_000L) {
            while (track.playbackHeadPosition < pcm.size) delay(10)
        }
        return outputDevice to SystemClock.elapsedRealtime()
    } finally {
        track.release()
    }
}

private object QuietListener : AssistantSpeechInput.Listener {
    override fun ready() = Unit
    override fun partial(text: String) = Unit
    override fun endOfSpeech() = Unit
    override fun result(text: String) = Unit
    override fun error(message: Int) = Unit
}
