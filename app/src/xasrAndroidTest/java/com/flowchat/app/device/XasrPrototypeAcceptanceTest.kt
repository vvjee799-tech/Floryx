package com.flowchat.app.device

import com.flowchat.app.presentation.assistant.FRAME_SAMPLES
import com.flowchat.app.presentation.assistant.SAMPLE_RATE
import com.flowchat.app.presentation.assistant.XasrMeasurement
import com.flowchat.app.presentation.assistant.XasrMemory
import com.flowchat.app.presentation.assistant.XasrPcmInput
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.SystemAssistantDiagnosticsEntryPoint
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.AssistantPanelView
import com.flowchat.app.presentation.assistant.AssistantVoicePlayback
import com.k2fsa.sherpa.onnx.WaveReader
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class XasrPrototypeAcceptanceTest {
    @Test
    fun localPcmFromAssistantPanelExitsSilenceCancelsAndSubmitsOneTask() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runXasrPrototype") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val modelSource = arguments.getString("xasrModelSource", "asset")
        check(modelSource in setOf("asset", "private_files"))
        val stagedFiles = if (modelSource == "private_files") {
            StagedXasrFiles(assets, context.cacheDir.resolve("assistant-xasr-model-qa"))
        } else null
        val assistant = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
        val chats = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java).chats()
        val directory = File(context.getExternalFilesDir(null), "assistant-xasr-prototype").apply { mkdirs() }
        val evidence = JSONObject()
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("audio", "offline Microsoft Yaoyao PCM replay")
            .put("taskModel", "loopback scripted")
            .put("modelSource", modelSource)
        val harness = SystemAssistantDiagnosticTest()
        val measurements = Channel<XasrMeasurement>(Channel.UNLIMITED)
        suspend fun measurement(phase: String): XasrMeasurement {
            return withTimeout(25_000) { measurements.receive() }.also {
                evidence.put(phase, it.toJson())
                File(directory, "result.json").writeText(evidence.toString(2))
            }
        }
        val expectedAnswer = "六乘八等于四十八。"
        harness.fixture({ TaskRuntimeDiagnosticTest.GatedModel(prefix = "六乘八", suffix = "等于四十八。") }) { fixture ->
            val playbackAnswer = CompletableDeferred<String>()
            val playback = AssistantVoicePlayback { text -> playbackAnswer.complete(text) }
            val panel = harness.open(AssistantPanelActivity::class.java)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val presenter = withContext(Dispatchers.Main) { assistant.panels().create(playback) }
            val openedSources = AtomicInteger()
            val closedSources = AtomicInteger()
            val clipEndedAt = AtomicLong()
            var inputInstances = 0
            val view = withContext(Dispatchers.Main) {
                AssistantPanelView(panel, presenter, {}, {}, { error("Voice invoked manual send") },
                    requestVoicePermission = { granted, _ -> granted() },
                    voiceAvailable = { true },
                    speechInput = {
                        val playSpeech = ++inputInstances > 1
                        XasrPrototypeSpeechInput(assets, openAudio = {
                            openedSources.incrementAndGet()
                            val samples = if (playSpeech) {
                                WaveReader.readWave(assets, "01-yaoyao.wav").let {
                                    check(it.sampleRate == SAMPLE_RATE)
                                    it.samples
                                }
                            } else FloatArray(0)
                            PacedPcm(samples, clipEndedAt, closedSources)
                        }, locateModelDirectory = { stagedFiles?.prepare() },
                            onMeasurement = { check(measurements.trySend(it).isSuccess) })
                    },
                ).also { view ->
                    panel.setContentView(view)
                    scope.launch { presenter.state.collect(view::render) }
                }
            }
            val voice = panel.findViewById<Button>(R.id.assistant_voice)
            val status = panel.findViewById<TextView>(R.id.assistant_status)
            val error = panel.findViewById<TextView>(R.id.assistant_error)
            suspend fun clickVoice() = withContext(Dispatchers.Main) { assertTrue(voice.performClick()) }
            try {
                harness.await("xasr-panel-ready") { withContext(Dispatchers.Main) { voice.isEnabled } }
                assertEquals(0, withContext(Dispatchers.Main) { inputInstances })
                assertEquals(0, openedSources.get())
                val silenceClickAt = SystemClock.elapsedRealtime()
                clickVoice()
                harness.await("xasr-first-listening") {
                    withContext(Dispatchers.Main) { status.text.toString() == panel.getString(R.string.assistant_voice_listening) }
                }
                evidence.put("firstClickToListeningMs", SystemClock.elapsedRealtime() - silenceClickAt)
                assertEquals("no_speech", measurement("silence").outcome)
                harness.await("xasr-silence-returned-to-ready") {
                    withContext(Dispatchers.Main) {
                        voice.text.toString() == panel.getString(R.string.assistant_voice_start) &&
                            error.visibility == View.VISIBLE &&
                            error.text.toString() == panel.getString(R.string.assistant_voice_no_speech)
                    }
                }
                evidence.put("silenceClickToExitMs", SystemClock.elapsedRealtime() - silenceClickAt)
                assertEquals(1, closedSources.get())
                assertEquals(0, fixture.model.requests.get())
                harness.captureVoicePanel(panel, directory, "01-silence.png")

                clickVoice()
                harness.await("xasr-cancel-has-partial") {
                    withContext(Dispatchers.Main) { status.text.contains("请问") }
                }
                val cancelAt = SystemClock.elapsedRealtime()
                clickVoice()
                assertEquals("cancelled", measurement("cancelled").outcome)
                evidence.put("cancelClickToReleaseMs", SystemClock.elapsedRealtime() - cancelAt)
                assertEquals(2, closedSources.get())
                assertTrue(chats.getMessages(fixture.owner).isEmpty())
                assertEquals(0, fixture.model.requests.get())

                clipEndedAt.set(0)
                clickVoice()
                harness.await("xasr-final-has-partial") {
                    withContext(Dispatchers.Main) {
                        status.text.contains("请问") &&
                            !panel.findViewById<Button>(R.id.assistant_send).isEnabled
                    }
                }
                assertEquals(0, fixture.model.requests.get())
                harness.captureVoicePanel(panel, directory, "02-partial.png")
                assertEquals("endpoint", measurement("completedCapture").outcome)
                assertEquals(3, closedSources.get())
                assertTrue(clipEndedAt.get() > 0)
                harness.await("xasr-one-model-request") { fixture.model.requests.get() == 1 }
                val firstRequestMs = SystemClock.elapsedRealtime() - clipEndedAt.get()
                assertFalse(playbackAnswer.isCompleted)
                fixture.model.release()
                assertEquals(expectedAnswer, withTimeout(15_000) { playbackAnswer.await() })
                harness.await("xasr-answer-visible") {
                    withContext(Dispatchers.Main) {
                        panel.findViewById<TextView>(R.id.assistant_output).text.toString() == expectedAnswer
                    }
                }
                val visibleAnswerMs = SystemClock.elapsedRealtime() - clipEndedAt.get()
                harness.await("xasr-task-complete") {
                    chats.getMessages(fixture.owner).lastOrNull()?.status == MessageStatus.Complete &&
                        withContext(Dispatchers.Main) { voice.isEnabled && status.visibility == View.GONE }
                }
                delay(500)
                harness.captureVoicePanel(panel, directory, "03-answer.png")
                val messages = chats.getMessages(fixture.owner)
                val transcript = messages.single { it.role == MessageRole.User }.content
                assertEquals("请问六乘八等于多少", transcript.trimEnd('？', '?', '。'))
                assertEquals(expectedAnswer, messages.last().content)
                assertEquals(MessageStatus.Complete, messages.last().status)
                assertTrue(fixture.model.bodies.single().contains(transcript))
                assertTrue(chats.getMessages(fixture.editor).isEmpty())
                assertEquals(1, fixture.model.requests.get())
                assertEquals(3, openedSources.get())
                evidence.put("passed", true)
                    .put("transcript", transcript)
                    .put("answer", expectedAnswer)
                    .put("requests", 1)
                    .put("userMessages", 1)
                    .put("manualSendActions", 0)
                    .put("silenceOrCancelledRequests", 0)
                    .put("clipEndToFirstRequestMs", firstRequestMs)
                    .put("clipEndToVisibleAnswerMs", visibleAnswerMs)
                    .put("closedPcmSources", closedSources.get())
                    .put("stagedModelBytes", stagedFiles?.sizeBytes() ?: 0)
                File(directory, "result.json").writeText(evidence.toString(2))
            } finally {
                withContext(Dispatchers.Main) { view.cancelRecording(); playback.stop(); presenter.close(); scope.cancel() }
                withContext(NonCancellable + Dispatchers.IO) { stagedFiles?.remove() }
            }
        }
    }

    private class PacedPcm(
        private val signal: FloatArray,
        private val clipEndedAt: AtomicLong,
        private val closedSources: AtomicInteger,
    ) : XasrPcmInput {
        private val startedAt = SystemClock.elapsedRealtime()
        private var offset = 0

        override suspend fun read(): FloatArray {
            val next = offset + FRAME_SAMPLES
            val dueAt = startedAt + next * 1_000L / SAMPLE_RATE
            val wait = dueAt - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            val samples = FloatArray(FRAME_SAMPLES)
            if (offset < signal.size) {
                signal.copyInto(samples, startIndex = offset, endIndex = minOf(next, signal.size))
                if (next >= signal.size) clipEndedAt.set(SystemClock.elapsedRealtime())
            }
            offset = next
            return samples
        }

        override fun close() {
            closedSources.incrementAndGet()
        }
    }

    private fun XasrMeasurement.toJson() = JSONObject()
        .put("preparationMs", preparationMs)
        .put("loadMs", loadMs)
        .put("beforeLoad", beforeLoad.toJson())
        .put("loaded", loaded.toJson())
        .put("duringCapture", duringCapture.toJson())
        .put("audioMs", audioSamples * 1_000L / SAMPLE_RATE)
        .put("outcome", outcome)

    private fun XasrMemory.toJson() = JSONObject()
        .put("pssKiB", pssKiB)
        .put("processPeakRssKiB", peakRssKiB)
}
