package com.flowchat.app.domain.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutomationTimingsTest {
    @Test fun diagnosticsAreBoundedAndNeverChangeFailureOrCancellation() = runBlocking {
        AutomationTimings.clear()
        repeat(600) { AutomationTimings.measure("screen.read_controls") { it } }
        assertEquals(512, AutomationTimings.snapshot().size)
        val cancellation = CancellationException("stop")
        try { AutomationTimings.measure("device.action") { throw cancellation } }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertFalse(AutomationTimings.snapshot().last().completed)
        assertTrue(AutomationTimings.snapshot().all { it.durationNanos >= 0 })
        AutomationTimings.clear()
    }
}
