package com.flowchat.app.presentation.assistant

import android.os.Looper
import com.flowchat.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantVoiceW05Test {
    private class FakeSpeechInput : AssistantSpeechInput {
        var listener: AssistantSpeechInput.Listener? = null
        var startCount = 0
        var closeCount = 0
        var throwOnStart: RuntimeException? = null

        override fun start(listener: AssistantSpeechInput.Listener) {
            startCount++
            throwOnStart?.let { throw it }
            this.listener = listener
        }

        override fun close() {
            closeCount++
        }
    }

    private class FakeAudioGuard(
        private val blockedReason: Int? = null,
    ) : AssistantAudioGuard {
        var acquireCount = 0
        var closeCount = 0
        var interruptHandler: ((Int) -> Unit)? = null

        override fun acquireCapture(onInterrupt: (message: Int) -> Unit): AssistantAudioGuard.AcquireResult {
            acquireCount++
            blockedReason?.let { return AssistantAudioGuard.AcquireResult.Blocked(it) }
            interruptHandler = onInterrupt
            return AssistantAudioGuard.AcquireResult.Acquired(object : AssistantAudioGuard.Lease {
                override fun close() {
                    closeCount++
                }
            })
        }
    }

    @Test
    fun audioGuardBlocksStartWhenMicIsBusyOrInCallWithoutStartingRecognizer() {
        val input = FakeSpeechInput()
        val guard = FakeAudioGuard(R.string.assistant_voice_mic_busy)
        var status: Int? = null
        val submissions = mutableListOf<String>()
        val capture = AssistantVoiceCapture({ input }, { feedback, _ -> status = feedback }, { text, _ ->
            submissions += text
        }, guard)

        capture.start()

        assertFalse(capture.isActive)
        assertEquals(0, input.startCount)
        assertEquals(1, input.closeCount)
        assertEquals(R.string.assistant_voice_mic_busy, status)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun liveAudioFocusOrHeadsetInterruptionRetiresCaptureAndDiscardsPartial() {
        val input = FakeSpeechInput()
        val guard = FakeAudioGuard()
        var status: Int? = null
        var partial = ""
        val submissions = mutableListOf<String>()
        val capture = AssistantVoiceCapture({ input }, { feedback, p ->
            status = feedback
            partial = p
        }, { text, _ -> submissions += text }, guard)

        capture.start()
        assertTrue(capture.isActive)
        input.listener!!.ready()
        input.listener!!.partial("刚才那句话")
        assertEquals("刚才那句话", partial)

        guard.interruptHandler!!.invoke(R.string.assistant_voice_device_changed)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(capture.isActive)
        assertEquals(1, input.closeCount)
        assertEquals(1, guard.closeCount)
        assertEquals(R.string.assistant_voice_device_changed, status)
        assertEquals("", partial)
        input.listener!!.result("延迟到达的结果不应提交")
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun permissionRevocationMidCaptureReportsPermissionErrorAndReleasesLease() {
        val input = FakeSpeechInput().apply { throwOnStart = SecurityException("revoked") }
        val guard = FakeAudioGuard()
        var status: Int? = null
        val capture = AssistantVoiceCapture({ input }, { feedback, _ -> status = feedback }, { _, _ -> }, guard)

        capture.start()

        assertFalse(capture.isActive)
        assertEquals(R.string.assistant_voice_permission, status)
        assertEquals(1, input.closeCount)
        assertEquals(1, guard.closeCount)
    }

    @Test
    fun followUpWindowStartsWhenMicrophoneIsReadyNotWhenRecognizerStarts() {
        val input = FakeSpeechInput()
        val guard = FakeAudioGuard()
        var status: Int? = null
        val capture = AssistantVoiceCapture({ input }, { feedback, _ -> status = feedback }, { _, _ -> }, guard)
        val looper = Shadows.shadowOf(Looper.getMainLooper())

        capture.start(followUp = true, timeoutMs = 2_000L)
        // Simulated offline model load longer than the follow-up window.
        looper.idleFor(java.time.Duration.ofMillis(4_000L))
        assertTrue("Loading must not consume the follow-up window", capture.isActive)

        input.listener!!.ready()
        looper.idleFor(java.time.Duration.ofMillis(1_900L))
        assertTrue(capture.isActive)
        looper.idleFor(java.time.Duration.ofMillis(200L))
        assertFalse("Silent follow-up closes after the window from readiness", capture.isActive)
        assertNull(status)
        assertEquals(1, guard.closeCount)
    }

    @Test
    fun followUpClosesQuietlyWhenRecognizerNeverBecomesReady() {
        val input = FakeSpeechInput()
        val guard = FakeAudioGuard()
        var status: Int? = 0
        val capture = AssistantVoiceCapture({ input }, { feedback, _ -> status = feedback }, { _, _ -> }, guard)
        val looper = Shadows.shadowOf(Looper.getMainLooper())

        capture.start(followUp = true, timeoutMs = 2_000L)
        looper.idleFor(java.time.Duration.ofMillis(AssistantVoiceCapture.FOLLOW_UP_PREPARE_TIMEOUT_MS - 100L))
        assertTrue(capture.isActive)
        looper.idleFor(java.time.Duration.ofMillis(200L))
        assertFalse(capture.isActive)
        assertNull(status)
        assertEquals(1, input.closeCount)
        assertEquals(1, guard.closeCount)
    }

    @Test
    fun followUpDoesNotCutOffSpeechThatStartedInsideTheWindow() {
        val input = FakeSpeechInput()
        val guard = FakeAudioGuard()
        val submissions = mutableListOf<Pair<String, Boolean>>()
        val capture = AssistantVoiceCapture({ input }, { _, _ -> }, { text, followUp -> submissions += text to followUp }, guard)
        val looper = Shadows.shadowOf(Looper.getMainLooper())

        capture.start(followUp = true, timeoutMs = 2_000L)
        input.listener!!.ready()
        looper.idleFor(java.time.Duration.ofMillis(1_500L))
        input.listener!!.partial("再乘以")
        looper.idleFor(java.time.Duration.ofMillis(3_000L))
        assertTrue("An utterance in progress must not be dropped by the short window", capture.isActive)
        input.listener!!.result("再乘以二呢")
        assertEquals(listOf("再乘以二呢" to true), submissions)
    }

    @Test
    fun regularCaptureTimeoutStillCountsFromStart() {
        val input = FakeSpeechInput()
        val guard = FakeAudioGuard()
        var status: Int? = null
        val capture = AssistantVoiceCapture({ input }, { feedback, _ -> status = feedback }, { _, _ -> }, guard)
        val looper = Shadows.shadowOf(Looper.getMainLooper())

        capture.start(followUp = false, timeoutMs = 2_000L)
        input.listener!!.ready()
        looper.idleFor(java.time.Duration.ofMillis(2_100L))
        assertFalse(capture.isActive)
        assertEquals(R.string.assistant_voice_interrupted, status)
    }

    @Test
    fun followUpModeClearsStatusSilentlyOnNoSpeechAndPassesFollowUpFlag() {
        val input = FakeSpeechInput()
        val guard = FakeAudioGuard()
        var status: Int? = null
        val submissions = mutableListOf<Pair<String, Boolean>>()
        val capture = AssistantVoiceCapture({ input }, { feedback, _ -> status = feedback }, { text, followUp ->
            submissions += text to followUp
        }, guard)

        capture.start(followUp = true, timeoutMs = 2_000L)
        assertTrue(capture.isActive)
        assertTrue(capture.isFollowUp)
        assertEquals(R.string.assistant_voice_followup_listening, status)

        input.listener!!.error(R.string.assistant_voice_no_speech)
        assertFalse(capture.isActive)
        assertNull(status)
        assertEquals(1, guard.closeCount)

        capture.start(followUp = true, timeoutMs = 2_000L)
        input.listener!!.result("再加五等于多少")
        assertEquals(listOf("再加五等于多少" to true), submissions)
        assertNull(status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun playbackTracksNormalCompletionSeparatelyFromInterruptionUnavailableAndStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var nextOutcome = CompletableDeferred<VoicePlaybackResult>()
            val playback = AssistantVoicePlayback(VoicePlaybackSpeaker { nextOutcome.await() })

            val onFirst = playback.expectResult()
            onFirst("三加四等于七。")
            advanceUntilIdle()
            assertEquals(VoicePlaybackState.Speaking, playback.state.value)
            assertEquals("三加四等于七。", playback.speakingText.value)
            assertEquals(0L, playback.completedGeneration.value)

            nextOutcome.complete(VoicePlaybackResult.Completed)
            advanceUntilIdle()
            assertEquals(VoicePlaybackState.Idle, playback.state.value)
            assertEquals("", playback.speakingText.value)
            val completedGen = playback.completedGeneration.value
            assertTrue(completedGen > 0L)

            nextOutcome = CompletableDeferred()
            playback.expectResult()("继续播报另一段。")
            advanceUntilIdle()
            nextOutcome.complete(VoicePlaybackResult.Interrupted)
            advanceUntilIdle()
            assertEquals(VoicePlaybackState.Interrupted, playback.state.value)
            assertEquals(completedGen, playback.completedGeneration.value)

            nextOutcome = CompletableDeferred()
            playback.expectResult()("再次尝试。")
            advanceUntilIdle()
            playback.stop()
            advanceUntilIdle()
            assertEquals(VoicePlaybackState.Idle, playback.state.value)
            assertEquals(completedGen, playback.completedGeneration.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun localStopCommandsAndSelfEchoGuardPreventFalseTriggers() {
        assertTrue(isLocalVoiceStopCommand("停止"))
        assertTrue(isLocalVoiceStopCommand("别说了。"))
        assertTrue(isLocalVoiceStopCommand("  Stop! "))
        assertFalse(isLocalVoiceStopCommand("帮我查一下怎么停止一个服务"))
        assertTrue(isFollowUpDismissCommand("不用了。"))
        assertFalse(isFollowUpDismissCommand("再算一下三加五"))

        assertTrue(isLikelySelfEcho("停止", "操作已停止"))
        assertTrue(isLikelySelfEcho("三加四等于七", "三加四等于七。"))
        assertFalse(isLikelySelfEcho("停止", "三加四等于七。"))
    }

    @Test
    fun playbackBargeInStopsOnlyOnNonEchoStopCommand() {
        val input = FakeSpeechInput()
        var spoken = "操作已停止"
        var stopCount = 0
        val bargeIn = PlaybackBargeInController({ input }, { spoken }) { stopCount++ }

        bargeIn.start()
        assertTrue(bargeIn.isActive)
        input.listener!!.partial("停止")
        assertEquals(0, stopCount)
        assertTrue(bargeIn.isActive)

        input.listener!!.result("三加四等于七")
        assertEquals(0, stopCount)
        assertFalse(bargeIn.isActive)

        spoken = "三加四等于七。"
        bargeIn.start()
        input.listener!!.partial("请算一下五乘九")
        assertEquals(0, stopCount)
        input.listener!!.partial("停止。")
        assertEquals(1, stopCount)
        assertFalse(bargeIn.isActive)
    }
}
