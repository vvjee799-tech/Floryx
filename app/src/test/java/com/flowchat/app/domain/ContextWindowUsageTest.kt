package com.flowchat.app.domain

import com.flowchat.app.domain.model.ContextWindowUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContextWindowUsageTest {
    @Test fun percentageTracksUsageAndTheRingClampsOverBudget() {
        val normal = ContextWindowUsage(25_600, 32_000)
        assertEquals(80, normal.usedPercent)
        assertEquals(0.8f, normal.fraction, 0.0001f)
        val overflow = ContextWindowUsage(40_000, 32_000)
        assertEquals(125, overflow.usedPercent)
        assertEquals(1f, overflow.fraction, 0f)
        assertEquals(0, ContextWindowUsage(0, 32_000).usedPercent)
    }

    @Test fun extremeCountsDoNotOverflowAndInvalidBudgetsAreRejected() {
        assertEquals(Int.MAX_VALUE, ContextWindowUsage(Int.MAX_VALUE, 1).usedPercent)
        assertEquals(100, ContextWindowUsage(Int.MAX_VALUE, Int.MAX_VALUE).usedPercent)
        assertThrows(IllegalArgumentException::class.java) { ContextWindowUsage(-1, 32_000) }
        assertThrows(IllegalArgumentException::class.java) { ContextWindowUsage(1, 0) }
    }
}
