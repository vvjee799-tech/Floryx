package com.flowchat.app.presentation.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordCoreTest {
    @Test
    fun ringBufferKeepsNewestSamples() {
        val ring = FloatRingBuffer(5)
        ring.write(floatArrayOf(1f, 2f, 3f))
        ring.write(floatArrayOf(4f, 5f, 6f))
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f), ring.snapshotLast(5), 0f)
        assertArrayEquals(floatArrayOf(5f, 6f), ring.snapshotLast(2), 0f)
        assertArrayEquals(floatArrayOf(), ring.snapshotLast(0), 0f)
    }

    @Test
    fun changingKeywordImmediatelyInvalidatesTheOldWord() {
        val gate = WakeWordGate(preRollSamples = 4, ringCapacity = 8)
        val oldGeneration = gate.currentGeneration()
        gate.onFrame(floatArrayOf(1f, 2f, 3f, 4f))
        assertTrue(gate.detectionAllowed(oldGeneration))

        gate.invalidateKeyword()

        assertFalse(gate.detectionAllowed(oldGeneration))
        assertTrue(gate.detectionAllowed(gate.currentGeneration()))
        assertArrayEquals(floatArrayOf(), gate.beginCommand(), 0f)
    }

    @Test
    fun playbackEchoIsDroppedAndCannotLeaveATail() {
        val gate = WakeWordGate(preRollSamples = 4, ringCapacity = 8)
        gate.setPlaybackActive(true)
        gate.onFrame(floatArrayOf(1f, 2f, 3f, 4f))
        assertFalse(gate.detectionAllowed(gate.currentGeneration()))
        assertArrayEquals(floatArrayOf(), gate.beginCommand(), 0f)

        gate.endCommand()
        gate.setPlaybackActive(false)
        gate.onFrame(floatArrayOf(5f, 6f))
        assertArrayEquals(floatArrayOf(5f, 6f), gate.beginCommand(), 0f)
    }

    @Test
    fun commandStartKeepsPreRollAndFollowingFramesWithoutAGap() {
        val gate = WakeWordGate(preRollSamples = 4, ringCapacity = 16)
        for (sample in 1..10) gate.onFrame(floatArrayOf(sample.toFloat()))
        val preRoll = gate.beginCommand()
        assertArrayEquals(floatArrayOf(7f, 8f, 9f, 10f), preRoll, 0f)

        // The controller sends every later frame to the ASR channel; concatenating the pre-roll
        // and those frames must reproduce the original stream from 7..12 with no duplicated frame.
        gate.onFrame(floatArrayOf(11f))
        gate.onFrame(floatArrayOf(12f))
        assertArrayEquals(
            floatArrayOf(7f, 8f, 9f, 10f, 11f, 12f),
            preRoll + floatArrayOf(11f, 12f),
            0f
        )
        gate.endCommand()
        assertArrayEquals(floatArrayOf(), gate.beginCommand(), 0f)
    }

    @Test
    fun commandBufferKeepsOnlyTheNewestSamples() {
        val buffer = CommandAudioBuffer(maxSamples = 5)
        buffer.append(floatArrayOf(1f, 2f, 3f))
        buffer.append(floatArrayOf(4f, 5f, 6f))
        assertEquals(3, buffer.size)
        assertEquals(listOf(4f, 5f, 6f), buffer.snapshot().flatMap { it.toList() })
        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun phonemesEncodeChineseAndWholeWordAlias() {
        val phonemes = WakeWordPhonemes(
            mapOf(
                "小" to "x iǎo",
                "弗" to "f ú",
                "floryx" to "f ú l uò l ǐ k è s ī",
            )
        )
        assertEquals("x iǎo f ú", phonemes.encode("小弗"))
        assertEquals("f ú l uò l ǐ k è s ī", phonemes.encode("Floryx"))
        assertNull(phonemes.encode("小?"))
        assertNull(phonemes.encode("  "))
    }

    @Test
    fun stripWakeWordKeepsCommandStart() {
        assertEquals("打开系统设置", stripWakeWord("弗洛里克斯，打开系统设置", "弗洛里克斯"))
        assertEquals("现在几点", stripWakeWord("弗洛里克斯现在几点", "弗洛里克斯"))
        assertEquals("打开系统设置", stripWakeWord("打开系统设置", "弗洛里克斯"))
        assertEquals("", stripWakeWord("弗洛里克斯", "弗洛里克斯"))
    }

    @Test
    fun keywordLineUsesTheModelFormat() {
        assertEquals(
            "x iǎo f ú :1.5 #0.25 @小弗\n",
            keywordLine("x iǎo f ú", "小弗")
        )
    }
}