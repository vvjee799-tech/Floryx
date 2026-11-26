package com.flowchat.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
import com.flowchat.app.presentation.assistant.AssistantAudioGuard
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.AssistantPanelView
import com.flowchat.app.presentation.assistant.AssistantSpeechInput
import com.flowchat.app.presentation.assistant.AssistantVoicePlayback
import com.flowchat.app.presentation.assistant.SystemAssistantAudioGuard
import com.flowchat.app.presentation.assistant.VoicePlaybackResult
import com.flowchat.app.presentation.assistant.VoicePlaybackSpeaker
import com.flowchat.app.presentation.assistant.VoicePlaybackState
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in W05 end-to-end diagnostics: audio conflict, interruption, permission recovery, local stop, and follow-up. */
class AssistantVoiceW05AcceptanceTest {
    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("runAssistantVoiceW05Diagnostics") == "true"
    )

    private class ScriptedSpeechInput : AssistantSpeechInput {
        lateinit var listener: AssistantSpeechInput.Listener
        var startCount = 0
        var closeCount = 0

        override fun start(listener: AssistantSpeechInput.Listener) {
            startCount++
            this.listener = listener
        }

        override fun close() {
            closeCount++
        }
    }

    @Test
    @SuppressLint("MissingPermission")
    fun audioConflictFocusLossAndPermissionRecoveryKeepZeroTasks() = runBlocking<Unit> {
        enabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val assistant = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
        val directory = File(context.getExternalFilesDir(null), "assistant-voice-w05").apply { mkdirs() }
        val harness = SystemAssistantDiagnosticTest()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val evidence = JSONObject()

        harness.fixture { fixture ->
            var nextOutcome = CompletableDeferred<VoicePlaybackResult>()
            val syntheticPlayback = AssistantVoicePlayback(VoicePlaybackSpeaker { nextOutcome.await() })
            val panel = harness.open(AssistantPanelActivity::class.java)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val input = ScriptedSpeechInput()
            var hasPermission = true
            var latestInterrupt: ((Int) -> Unit)? = null
            val realGuard = SystemAssistantAudioGuard(context)
            val guard = object : AssistantAudioGuard {
                override fun acquireCapture(onInterrupt: (message: Int) -> Unit): AssistantAudioGuard.AcquireResult {
                    latestInterrupt = onInterrupt
                    return realGuard.acquireCapture(onInterrupt)
                }
            }
            val presenter = withContext(Dispatchers.Main) { assistant.panels().create(syntheticPlayback) }
            try {
                val view = withContext(Dispatchers.Main) {
                    AssistantPanelView(
                        context = panel,
                        presenter = presenter,
                        onClose = {},
                        onFullChat = {},
                        onAccepted = { error("Voice invoked manual send") },
                        requestVoicePermission = { granted, denied -> if (hasPermission) granted() else denied() },
                        voiceAvailable = { true },
                        speechInput = { input },
                        audioGuard = guard,
                        followUpWindowMs = 0L,
                        hasVoicePermission = { hasPermission }
                    ).also {
                        panel.setContentView(it)
                        scope.launch { presenter.state.collect(it::render) }
                    }
                }
                val voice = view.findViewById<Button>(R.id.assistant_voice)
                val status = view.findViewById<TextView>(R.id.assistant_status)
                val error = view.findViewById<TextView>(R.id.assistant_error)
                harness.await("w05-panel-ready") { withContext(Dispatchers.Main) { voice.isEnabled } }

                // 1. Live audio focus theft during capture retires session and discards partial transcript.
                withContext(Dispatchers.Main) {
                    assertTrue(voice.performClick())
                    input.listener.ready()
                    input.listener.partial("正在说话时来电或抢焦点")
                    assertTrue(status.text.contains("正在说话时来电或抢焦点"))
                }
                val competingFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
                    .build()
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(competingFocus))
                var osFocusLossDelivered = false
                try {
                    osFocusLossDelivered = withTimeoutOrNull(2_000) {
                        while (withContext(Dispatchers.Main) {
                                error.visibility != View.VISIBLE ||
                                    error.text.toString() != panel.getString(R.string.assistant_voice_focus_lost)
                            }) {
                            delay(50)
                        }
                        true
                    } == true
                } finally {
                    audioManager.abandonAudioFocusRequest(competingFocus)
                }
                if (!osFocusLossDelivered) {
                    requireNotNull(latestInterrupt).invoke(R.string.assistant_voice_focus_lost)
                    harness.await("w05-focus-interrupted-fallback") {
                        withContext(Dispatchers.Main) {
                            voice.text.toString() == panel.getString(R.string.assistant_voice_start) &&
                                error.visibility == View.VISIBLE &&
                                error.text.toString() == panel.getString(R.string.assistant_voice_focus_lost)
                        }
                    }
                }
                assertEquals(1, input.closeCount)
                assertEquals(0, fixture.model.requests.get())
                assertTrue(data.chats().getMessages(fixture.owner).isEmpty())
                evidence.put("liveFocusLossDeliveredByOs", osFocusLossDelivered)
                evidence.put("liveFocusLossRetiredCapture", true)

                // 2. Real competing AudioRecord mic-busy detection (if platform HAL exposes activeRecordingConfigurations).
                var realMicBusyChecked = false
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val sampleRate = 16_000
                    val minBytes = maxOf(
                        AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                        4096
                    )
                    val holder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBytes
                    )
                    try {
                        if (holder.state == AudioRecord.STATE_INITIALIZED) {
                            holder.startRecording()
                            delay(250)
                            if (audioManager.activeRecordingConfigurations.isNotEmpty()) {
                                val startsBefore = input.startCount
                                withContext(Dispatchers.Main) { assertTrue(voice.performClick()) }
                                harness.await("w05-mic-busy-blocked") {
                                    withContext(Dispatchers.Main) {
                                        error.text.toString() == panel.getString(R.string.assistant_voice_mic_busy)
                                    }
                                }
                                assertEquals(startsBefore, input.startCount)
                                realMicBusyChecked = true
                            }
                        }
                    } finally {
                        runCatching { holder.stop() }
                        holder.release()
                    }
                }
                evidence.put("realMicBusyChecked", realMicBusyChecked)

                // 3. Playback interruption state renders dedicated message instead of Unavailable.
                withContext(Dispatchers.Main) {
                    syntheticPlayback.expectResult()("这是一段正在播报的答复内容。")
                }
                harness.await("w05-playback-speaking") {
                    presenter.state.value.voicePlayback == VoicePlaybackState.Speaking
                }
                nextOutcome.complete(VoicePlaybackResult.Interrupted)
                harness.await("w05-playback-interrupted-ui") {
                    withContext(Dispatchers.Main) {
                        presenter.state.value.voicePlayback == VoicePlaybackState.Interrupted &&
                            error.visibility == View.VISIBLE &&
                            error.text.toString() == panel.getString(R.string.assistant_voice_playback_interrupted)
                    }
                }
                delay(150)
                harness.captureVoicePanel(panel, directory, "01-playback-interrupted.png")
                evidence.put("playbackInterruptedUiVerified", true)

                // 4. Real on-device TextToSpeech + AudioFocus theft check (when offline TTS is installed on device).
                val realTtsPlayback = AssistantVoicePlayback(context)
                var realTtsFocusInterrupted = false
                try {
                    withContext(Dispatchers.Main) {
                        realTtsPlayback.expectResult()("请问六乘八等于四十八，正在测试真实播报被音频焦点中断时的恢复状态。")
                    }
                    val startedRealTts = withTimeoutOrNull(4_000) {
                        realTtsPlayback.state.first { it == VoicePlaybackState.Speaking }
                        true
                    } == true
                    if (startedRealTts) {
                        delay(350)
                        val ttsFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
                            .build()
                        if (audioManager.requestAudioFocus(ttsFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            try {
                                val after = withTimeoutOrNull(3_000) {
                                    realTtsPlayback.state.first { it != VoicePlaybackState.Speaking }
                                }
                                if (after != null) {
                                    assertEquals(VoicePlaybackState.Interrupted, after)
                                    realTtsFocusInterrupted = true
                                }
                            } finally {
                                audioManager.abandonAudioFocusRequest(ttsFocus)
                            }
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) { realTtsPlayback.stop() }
                }
                evidence.put("realTtsFocusInterrupted", realTtsFocusInterrupted)

                // 5. Permission revocation & recovery clears permission error on refresh.
                hasPermission = false
                withContext(Dispatchers.Main) { assertTrue(voice.performClick()) }
                harness.await("w05-permission-denied") {
                    withContext(Dispatchers.Main) {
                        error.visibility == View.VISIBLE &&
                            error.text.toString() == panel.getString(R.string.assistant_voice_permission)
                    }
                }
                hasPermission = true
                withContext(Dispatchers.Main) { view.refreshVoicePermissionState() }
                harness.await("w05-permission-recovered") {
                    withContext(Dispatchers.Main) {
                        error.text.toString() != panel.getString(R.string.assistant_voice_permission)
                    }
                }
                assertEquals(0, fixture.model.requests.get())
                assertTrue(data.chats().getMessages(fixture.owner).isEmpty())
                evidence.put("permissionRevocationRecovered", true)
                evidence.put("passed", true)
                File(directory, "conflict-result.json").writeText(evidence.toString(2))
            } finally {
                withContext(Dispatchers.Main) {
                    syntheticPlayback.stop()
                    presenter.close()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun voiceFollowUpWindowAndLocalStopRunOnSameTaskWithoutExtraConfirmations() = runBlocking<Unit> {
        enabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val assistant = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
        val directory = File(context.getExternalFilesDir(null), "assistant-voice-w05").apply { mkdirs() }
        val harness = SystemAssistantDiagnosticTest()

        harness.fixture({ TaskRuntimeDiagnosticTest.GatedModel(prefix = "结果是", suffix = "七。") }) { fixture ->
            var nextOutcome = CompletableDeferred<VoicePlaybackResult>()
            val playback = AssistantVoicePlayback(VoicePlaybackSpeaker { nextOutcome.await() })
            val panel = harness.open(AssistantPanelActivity::class.java)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val input = ScriptedSpeechInput()
            val bargeIn = ScriptedSpeechInput()
            val presenter = withContext(Dispatchers.Main) { assistant.panels().create(playback) }
            try {
                val view = withContext(Dispatchers.Main) {
                    AssistantPanelView(
                        context = panel,
                        presenter = presenter,
                        onClose = {},
                        onFullChat = {},
                        onAccepted = { error("Voice invoked manual send") },
                        requestVoicePermission = { granted, _ -> granted() },
                        voiceAvailable = { true },
                        speechInput = { input },
                        audioGuard = SystemAssistantAudioGuard(context),
                        followUpWindowMs = 4_000L,
                        bargeInInput = { bargeIn },
                        hasVoicePermission = { true }
                    ).also {
                        panel.setContentView(it)
                        scope.launch { presenter.state.collect(it::render) }
                    }
                }
                val voice = view.findViewById<Button>(R.id.assistant_voice)
                val status = view.findViewById<TextView>(R.id.assistant_status)
                val error = view.findViewById<TextView>(R.id.assistant_error)
                harness.await("w05-followup-panel-ready") { withContext(Dispatchers.Main) { voice.isEnabled } }

                // Turn 1 -> playback -> echo ignored -> spoken "停止" interrupts playback locally (0 cloud requests).
                withContext(Dispatchers.Main) {
                    assertTrue(voice.performClick())
                    input.listener.ready()
                    input.listener.result("请算一下三加四等于多少")
                }
                harness.await("w05-turn1-request") { fixture.model.requests.get() == 1 }
                fixture.model.release()
                harness.await("w05-turn1-speaking") {
                    presenter.state.value.voicePlayback == VoicePlaybackState.Speaking && bargeIn.startCount == 1
                }
                withContext(Dispatchers.Main) {
                    bargeIn.listener.partial("结果是七")
                    assertEquals(VoicePlaybackState.Speaking, presenter.state.value.voicePlayback)
                    bargeIn.listener.partial("停止")
                }
                harness.await("w05-bargein-stopped-locally") {
                    presenter.state.value.voicePlayback == VoicePlaybackState.Idle &&
                        status.visibility == View.GONE
                }
                assertEquals(1, fixture.model.requests.get())

                // Turn 2 -> normal TTS completion opens automatic short follow-up window -> Turn 3 on SAME task.
                fixture.model.prepareNext()
                nextOutcome = CompletableDeferred()
                withContext(Dispatchers.Main) {
                    assertTrue(voice.performClick())
                    input.listener.ready()
                    input.listener.result("再加五等于多少")
                }
                harness.await("w05-turn2-request") { fixture.model.requests.get() == 2 }
                fixture.model.release()
                harness.await("w05-turn2-speaking") {
                    presenter.state.value.voicePlayback == VoicePlaybackState.Speaking
                }
                val startsBeforeFollowUp = input.startCount
                nextOutcome.complete(VoicePlaybackResult.Completed)
                harness.await("w05-followup-window-opened") {
                    withContext(Dispatchers.Main) {
                        input.startCount == startsBeforeFollowUp + 1 &&
                            status.visibility == View.VISIBLE &&
                            status.text.toString() == panel.getString(R.string.assistant_voice_followup_listening)
                    }
                }
                harness.captureVoicePanel(panel, directory, "02-followup-listening.png")

                fixture.model.prepareNext()
                nextOutcome = CompletableDeferred()
                withContext(Dispatchers.Main) {
                    input.listener.ready()
                    input.listener.result("再乘二呢")
                }
                harness.await("w05-turn3-followup-request") { fixture.model.requests.get() == 3 }
                fixture.model.release()
                harness.await("w05-turn3-speaking") {
                    presenter.state.value.voicePlayback == VoicePlaybackState.Speaking
                }
                nextOutcome.complete(VoicePlaybackResult.Completed)
                harness.await("w05-followup-reopened-then-silent-timeout") {
                    withContext(Dispatchers.Main) { input.startCount == startsBeforeFollowUp + 2 }
                }
                withContext(Dispatchers.Main) {
                    input.listener.error(R.string.assistant_voice_no_speech)
                }
                harness.await("w05-followup-silent-exit") {
                    withContext(Dispatchers.Main) {
                        voice.text.toString() == panel.getString(R.string.assistant_voice_start) &&
                            status.visibility == View.GONE &&
                            error.visibility == View.GONE
                    }
                }
                harness.captureVoicePanel(panel, directory, "03-followup-completed.png")

                val messages = data.chats().getMessages(fixture.owner)
                assertEquals(
                    listOf("请算一下三加四等于多少", "再加五等于多少", "再乘二呢"),
                    messages.filter { it.role == MessageRole.User }.map { it.content }
                )
                assertEquals(MessageStatus.Complete, messages.last().status)
                assertEquals(3, fixture.model.requests.get())
                assertTrue(data.chats().getMessages(fixture.editor).isEmpty())

                File(directory, "followup-result.json").writeText(
                    JSONObject()
                        .put("passed", true)
                        .put("requests", fixture.model.requests.get())
                        .put("userMessagesInSameOwner", 3)
                        .put("manualSendActions", 0)
                        .put("bargeInEchoIgnored", true)
                        .put("bargeInLocalStopRequests", 0)
                        .put("silentFollowUpExitErrorVisible", false)
                        .toString(2)
                )
            } finally {
                withContext(Dispatchers.Main) {
                    playback.stop()
                    presenter.close()
                    scope.cancel()
                }
            }
        }
    }

    /**
     * B2 timing probe: the scripted recognizer reports ready() 4.2 s after the follow-up opens
     * (mirroring X-ASR's observed 3.78-4.21 s load), then stays silent, then speaks inside a
     * later window and only finishes after that window has elapsed.
     */
    @Test
    fun followUpWindowCountsFromDelayedReadyAndSubmitsOncePerSession() = runBlocking<Unit> {
        enabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val assistant = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
        val directory = File(context.getExternalFilesDir(null), "assistant-voice-w05").apply { mkdirs() }
        val harness = SystemAssistantDiagnosticTest()
        val windowMs = 6_000L
        val readyDelayMs = 4_200L

        harness.fixture({ TaskRuntimeDiagnosticTest.GatedModel(prefix = "RESULT ", suffix = " DONE") }) { fixture ->
            var nextOutcome = CompletableDeferred<VoicePlaybackResult>()
            val playback = AssistantVoicePlayback(VoicePlaybackSpeaker { nextOutcome.await() })
            val panel = harness.open(AssistantPanelActivity::class.java)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val input = ScriptedSpeechInput()
            val presenter = withContext(Dispatchers.Main) { assistant.panels().create(playback) }
            try {
                val view = withContext(Dispatchers.Main) {
                    AssistantPanelView(
                        context = panel,
                        presenter = presenter,
                        onClose = {},
                        onFullChat = {},
                        onAccepted = { error("Voice invoked manual send") },
                        requestVoicePermission = { granted, _ -> granted() },
                        voiceAvailable = { true },
                        speechInput = { input },
                        audioGuard = null,
                        followUpWindowMs = windowMs,
                        hasVoicePermission = { true }
                    ).also {
                        panel.setContentView(it)
                        scope.launch { presenter.state.collect(it::render) }
                    }
                }
                val voice = view.findViewById<Button>(R.id.assistant_voice)
                val status = view.findViewById<TextView>(R.id.assistant_status)
                val error = view.findViewById<TextView>(R.id.assistant_error)
                harness.await("b2-panel-ready") { withContext(Dispatchers.Main) { voice.isEnabled } }

                // Turn 1 -> normal playback completion opens a short follow-up capture.
                withContext(Dispatchers.Main) {
                    assertTrue(voice.performClick())
                    input.listener.ready()
                    input.listener.result("first turn")
                }
                harness.await("b2-turn1-request") { fixture.model.requests.get() == 1 }
                fixture.model.release()
                harness.await("b2-turn1-speaking") { presenter.state.value.voicePlayback == VoicePlaybackState.Speaking }
                val startsBeforeFirstFollowUp = input.startCount
                nextOutcome.complete(VoicePlaybackResult.Completed)
                harness.await("b2-first-followup-open") {
                    withContext(Dispatchers.Main) {
                        input.startCount == startsBeforeFirstFollowUp + 1 &&
                            status.visibility == View.VISIBLE &&
                            status.text.toString() == panel.getString(R.string.assistant_voice_followup_listening)
                    }
                }
                val followUpOpenedAt = SystemClock.elapsedRealtime()

                // X-ASR load: the short window must not be consumed while waiting for ready().
                delay(readyDelayMs)
                val stillListening = withContext(Dispatchers.Main) {
                    voice.text.toString() == panel.getString(R.string.assistant_voice_cancel) &&
                        status.visibility == View.VISIBLE &&
                        error.visibility == View.GONE
                }
                assertTrue("Follow-up closed while the recognizer was still loading", stillListening)
                val readyAt = SystemClock.elapsedRealtime()
                val readyDelayObserved = readyAt - followUpOpenedAt
                withContext(Dispatchers.Main) { input.listener.ready() }

                // Silence after ready(): quiet exit at the window boundary, no error banner.
                withTimeout(15_000) {
                    while (withContext(Dispatchers.Main) {
                            voice.text.toString() != panel.getString(R.string.assistant_voice_start)
                        }
                    ) delay(50)
                }
                val quietAfterReady = SystemClock.elapsedRealtime() - readyAt
                assertTrue("Silent follow-up exited before the window: ${quietAfterReady}ms", quietAfterReady >= windowMs - 1_000)
                assertTrue("Silent follow-up outlived the window: ${quietAfterReady}ms", quietAfterReady <= windowMs + 3_000)
                withContext(Dispatchers.Main) {
                    assertEquals(View.GONE, error.visibility)
                    assertEquals(View.GONE, status.visibility)
                }
                assertEquals(1, fixture.model.requests.get())

                // Turn 2 -> follow-up again; speech starts inside the window and finishes after it.
                fixture.model.prepareNext()
                nextOutcome = CompletableDeferred()
                withContext(Dispatchers.Main) {
                    assertTrue(voice.performClick())
                    input.listener.ready()
                    input.listener.result("second turn")
                }
                harness.await("b2-turn2-request") { fixture.model.requests.get() == 2 }
                fixture.model.release()
                harness.await("b2-turn2-speaking") { presenter.state.value.voicePlayback == VoicePlaybackState.Speaking }
                val startsBeforeSecondFollowUp = input.startCount
                nextOutcome.complete(VoicePlaybackResult.Completed)
                harness.await("b2-second-followup-open") {
                    withContext(Dispatchers.Main) {
                        input.startCount == startsBeforeSecondFollowUp + 1 &&
                            status.text.toString() == panel.getString(R.string.assistant_voice_followup_listening)
                    }
                }
                val secondWindowStart = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main) { input.listener.ready() }
                delay(1_000)
                withContext(Dispatchers.Main) { input.listener.partial("follow up speech") }
                val partialAt = SystemClock.elapsedRealtime() - secondWindowStart
                delay(windowMs + 800)
                val speakingAfterWindow = withContext(Dispatchers.Main) {
                    voice.text.toString() == panel.getString(R.string.assistant_voice_cancel) &&
                        status.visibility == View.VISIBLE &&
                        error.visibility == View.GONE
                }
                assertTrue("Speech in progress was cut by the short window", speakingAfterWindow)
                val resultAt = SystemClock.elapsedRealtime() - secondWindowStart
                withContext(Dispatchers.Main) { input.listener.result("follow up answer") }
                harness.await("b2-followup-submitted-once") { fixture.model.requests.get() == 3 }

                // Keep playback pending so a fourth follow-up does not open during assertions.
                nextOutcome = CompletableDeferred()
                fixture.model.release()
                harness.await("b2-followup-complete") {
                    data.chats().getMessages(fixture.owner).lastOrNull()?.status == MessageStatus.Complete
                }

                val messages = data.chats().getMessages(fixture.owner)
                val userTexts = messages.filter { it.role == MessageRole.User }.map { it.content }
                assertEquals(listOf("first turn", "second turn", "follow up answer"), userTexts)
                assertEquals(3, fixture.model.requests.get())

                File(directory, "followup-ready-timing-result.json").writeText(
                    JSONObject()
                        .put("passed", true)
                        .put("followUpWindowMs", windowMs)
                        .put("readyDelayObservedMs", readyDelayObserved)
                        .put("stillListeningAfterReadyDelay", stillListening)
                        .put("quietExitAfterReadyMs", quietAfterReady)
                        .put("quietExitErrorVisible", false)
                        .put("partialAtMsAfterReady", partialAt)
                        .put("resultAtMsAfterReady", resultAt)
                        .put("requests", fixture.model.requests.get())
                        .put("userMessagesInSameOwner", userTexts.size)
                        .put("followUpSubmissions", 1)
                        .put("manualSendActions", 0)
                        .toString(2)
                )
                harness.captureVoicePanel(panel, directory, "04-followup-ready-timing.png")
            } finally {
                withContext(Dispatchers.Main) {
                    playback.stop()
                    presenter.close()
                    scope.cancel()
                }
            }
        }
    }
}
