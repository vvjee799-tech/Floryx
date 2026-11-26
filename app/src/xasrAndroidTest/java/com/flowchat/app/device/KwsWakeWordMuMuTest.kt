package com.flowchat.app.device

import android.content.res.AssetManager
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.KwsAudioSource
import com.flowchat.app.presentation.assistant.KwsWakeWordController
import com.flowchat.app.presentation.assistant.SherpaKeywordDetector
import com.flowchat.app.presentation.assistant.WakeWordPlugins
import com.flowchat.app.presentation.assistant.WakeWordSettings
import com.flowchat.app.presentation.assistant.XasrVoicePack
import com.k2fsa.sherpa.onnx.WaveReader
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MuMu-only end-to-end replay: a PCM file drives the real sherpa-onnx KWS detector, the
 * command audio continues into the real local X-ASR model, and the existing assistant panel
 * submits exactly one task. No external model request is made; the task model is the local
 * loopback fixture.
 */
@RunWith(AndroidJUnit4::class)
class KwsWakeWordMuMuTest {
    @Test
    fun injectedPcmWakesSubmitsOneCommandAndReleases() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runKwsWake") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val chats = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java).chats()
        val settings = WakeWordSettings(context).apply {
            enabled = false
            keyword = "弗洛里克斯"
        }
        val pack = XasrVoicePack.forDevice(context) ?: error("X-ASR voice pack is unavailable")
        check(pack.isReady()) { "The local X-ASR model directory is not installed" }
        val closedSources = AtomicInteger()
        val controller = KwsWakeWordController.createForTest(
            context = context,
            pack = pack,
            sourceFactory = { PacedAssetSource(instrumentation.context.assets, closedSources) },
            detectorFactory = { directory, keywords -> SherpaKeywordDetector(directory, keywords) },
            audioGuard = null,
        ) ?: error("The wake-word controller could not be created")
        WakeWordPlugins.factory = { controller }
        val harness = SystemAssistantDiagnosticTest()
        val evidenceDir = File(context.getExternalFilesDir(null), "kws-wake-e2e").apply { mkdirs() }
        try {
            harness.fixture({
                TaskRuntimeDiagnosticTest.GatedModel(prefix = "LOCAL_KWS_", suffix = "已打开系统设置。")
            }) { fixture ->
                val panel = harness.open(AssistantPanelActivity::class.java)
                harness.await("wake-row") {
                    withContext(Dispatchers.Main) {
                        panel.findViewById<View>(R.id.assistant_wake_row)?.visibility == View.VISIBLE
                    }
                }
                harness.await("panel-ready") {
                    withContext(Dispatchers.Main) {
                        panel.findViewById<Button>(R.id.assistant_voice)?.isEnabled == true
                    }
                }
                withContext(Dispatchers.Main) {
                    check(controller.start("弗洛里克斯")) { "Controller rejected the wake word" }
                }
                val startedAt = SystemClock.elapsedRealtime()
                harness.await("wake-detected") { controller.state.value.detections == 1 }
                val detectedMs = SystemClock.elapsedRealtime() - startedAt
                harness.await("one-request") { fixture.model.requests.get() == 1 }
                val users = chats.getMessages(fixture.owner).filter { it.role == MessageRole.User }
                assertEquals(1, users.size)
                assertTrue(
                    "command start was lost: ${users.single().content}",
                    users.single().content.trimStart().startsWith("打开系统设置"),
                )
                fixture.model.release()
                harness.await("answer-visible") {
                    withContext(Dispatchers.Main) {
                        panel.findViewById<TextView>(R.id.assistant_output)?.text?.toString()
                            ?.contains("已打开系统设置") == true
                    }
                }
                delay(800)
                assertEquals(1, chats.getMessages(fixture.owner).count { it.role == MessageRole.User })
                assertEquals(1, fixture.model.requests.get())
                assertTrue(controller.state.value.running)
                harness.captureVoicePanel(panel, evidenceDir, "kws-answer.png")
                File(evidenceDir, "result.json").writeText(JSONObject()
                    .put("keyword", "弗洛里克斯")
                    .put("audio", "kws-wake-command.wav (6.94 s, 16 kHz mono)")
                    .put("detections", controller.state.value.detections)
                    .put("wakeToDetectedMs", detectedMs)
                    .put("userMessages", 1)
                    .put("transcript", users.single().content)
                    .put("modelRequests", fixture.model.requests.get())
                    .put("answerVisible", true)
                    .put("sourceClosed", closedSources.get())
                    .toString(2))
            }
        } finally {
            controller.stop()
            settings.enabled = false
            withContext(NonCancellable) { settings.keyword = "" }
            WakeWordPlugins.factory = null
        }
        assertEquals(1, closedSources.get())
        assertFalse(controller.state.value.running)
    }

    private class PacedAssetSource(
        assets: AssetManager,
        private val closed: AtomicInteger,
    ) : KwsAudioSource {
        private val samples = WaveReader.readWave(assets, "kws-wake-command.wav").samples
        private val startedAt = SystemClock.elapsedRealtime()
        private var offset = 0
        private var frames = 0

        override fun read(buffer: FloatArray): Int {
            frames++
            val due = startedAt + frames * 20L
            val wait = due - SystemClock.elapsedRealtime()
            if (wait > 0) Thread.sleep(wait)
            if (offset >= samples.size) {
                Thread.sleep(20)
                return 0
            }
            val count = minOf(buffer.size, samples.size - offset)
            samples.copyInto(buffer, 0, offset, offset + count)
            offset += count
            return count
        }

        override fun close() {
            closed.incrementAndGet()
        }
    }
}