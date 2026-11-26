package com.flowchat.app.presentation.assistant

import com.flowchat.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantVoiceCaptureWakeTest {
    private class FakeInput : AssistantSpeechInput {
        var listener: AssistantSpeechInput.Listener? = null
        var closes = 0

        override fun start(listener: AssistantSpeechInput.Listener) {
            this.listener = listener
        }

        override fun close() {
            closes++
        }
    }

    private class FakeGuard(private val blockedReason: Int? = null) : AssistantAudioGuard {
        var acquires = 0
        var releases = 0

        override fun acquireCapture(onInterrupt: (message: Int) -> Unit): AssistantAudioGuard.AcquireResult {
            acquires++
            blockedReason?.let { return AssistantAudioGuard.AcquireResult.Blocked(it) }
            return AssistantAudioGuard.AcquireResult.Acquired(object : AssistantAudioGuard.Lease {
                override fun close() {
                    releases++
                }
            })
        }
    }

    @Test
    fun preparedInputSkipsTheGuardAndReleasesExactlyOnce() {
        val input = FakeInput()
        val guard = FakeGuard()
        var submitted = ""
        var finished = 0
        val capture = AssistantVoiceCapture(
            { error("prepared audio must not create another input") },
            { _, _ -> },
            { transcript, _ -> submitted = transcript },
            guard,
        )

        capture.startWith(input, alreadyGuarded = true) { finished++ }

        assertTrue(capture.isActive)
        assertEquals(0, guard.acquires)
        input.listener!!.ready()
        input.listener!!.result("打开系统设置")

        assertEquals("打开系统设置", submitted)
        assertEquals(1, input.closes)
        assertEquals(1, finished)
        assertFalse(capture.isActive)
    }

    @Test
    fun blockedGuardReleasesPreparedInputAndNotifiesTheWakeSession() {
        val input = FakeInput()
        val guard = FakeGuard(R.string.assistant_voice_mic_busy)
        var submitted = ""
        var finished = 0
        var status: Int? = null
        val capture = AssistantVoiceCapture(
            { FakeInput() },
            { message, _ -> status = message },
            { transcript, _ -> submitted = transcript },
            guard,
        )

        capture.startWith(input, alreadyGuarded = false) { finished++ }

        assertFalse(capture.isActive)
        assertEquals(1, input.closes)
        assertEquals(1, finished)
        assertEquals(R.string.assistant_voice_mic_busy, status)
        assertEquals("", submitted)
        assertEquals(1, guard.acquires)
    }

    @Test
    fun unguardedPreparedInputAcquiresAndReleasesTheGuard() {
        val input = FakeInput()
        val guard = FakeGuard()
        val capture = AssistantVoiceCapture({ FakeInput() }, { _, _ -> }, { _ -> }, guard)

        capture.startWith(input, alreadyGuarded = false)

        assertEquals(1, guard.acquires)
        input.listener!!.error(R.string.assistant_voice_interrupted)
        assertEquals(1, guard.releases)
        assertEquals(1, input.closes)
    }

    @Test
    fun secondPreparedInputIsClosedWithoutTouchingTheActiveSession() {
        val first = FakeInput()
        val second = FakeInput()
        val capture = AssistantVoiceCapture({ FakeInput() }, { _, _ -> }, { _ -> }, null)

        capture.startWith(first, alreadyGuarded = true)
        capture.startWith(second, alreadyGuarded = true)

        assertEquals(0, first.closes)
        assertEquals(1, second.closes)
        assertTrue(capture.isActive)
        first.listener!!.result("继续")
        assertEquals(1, first.closes)
    }
}