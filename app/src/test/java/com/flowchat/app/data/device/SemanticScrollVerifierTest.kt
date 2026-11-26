package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SemanticScrollVerifierTest {
    private val bounds = DeviceBounds(0, 0, 500, 900)
    private val root = DeviceUiElement(0, "WebView", "Article", null, "id/body", bounds,
        false, false, true, true, nodePath = "root/body", supportedActions = listOf("scroll_forward"))
    private val before = DeviceScreenSnapshot("fixture", "Page", listOf(root) + (1..3).map {
        DeviceUiElement(it, "TextView", "Paragraph $it", null, "id/p$it", DeviceBounds(0, it * 100, 500, it * 100 + 80),
            false, false, false, true, parentIndex = 0, nodePath = "root/body/$it")
    }, capturedAt = 1000, windowId = 2, geometry = DeviceScreenGeometry(bounds, 500, 900, 0),
        interactionContext = DeviceInteractionContext(listOf(DeviceWindowRegion(2, DeviceWindowKind.Application, 0, bounds, listOf(bounds), true, true))))
    private fun observed(screen: DeviceScreenSnapshot?) = DeviceToolResult(screen != null, "Read", screen = screen)
    private val first get() = observed(before.copy(capturedAt = 1100, screenId = "first"))

    @Test fun legacyUnknownBecomesOnlyAVerifiedCurrentViewAfterThreeFreshCompleteReads() = runBlocking {
        assertEquals(DeviceActionOutcome.Unverified, DeviceActionOutcomePolicy.complete(before, first, "Dispatched", scrollIndex = 0).actionOutcome)
        var reads = 0
        val result = ScrollObservationVerifier.complete(before, first, 0, "Dispatched", true,
            read = { observed(before.copy(capturedAt = 1200L + ++reads * 100, screenId = "read-$reads")) }, check = {})
        assertEquals(2, reads)
        assertFalse(result.success)
        assertEquals(DeviceActionOutcome.StateObserved, result.actionOutcome)
        assertTrue(result.summary.contains("scroll_dispatch_count=1"))
        assertTrue(result.summary.contains("not a business submission"))
    }

    @Test fun delayedReadableProgressUsesANewObservationAndNeverAnotherDispatch() = runBlocking {
        var reads = 0
        val changed = before.copy(capturedAt = 1200, elements = before.elements.map { if (it.index == 3) it.copy(text = "New paragraph") else it })
        val result = ScrollObservationVerifier.complete(before, first, 0, "Dispatched", true,
            read = { reads++; observed(changed) }, check = {})
        assertEquals(1, reads); assertEquals(DeviceActionOutcome.ProgressObserved, result.actionOutcome)
    }

    @Test fun missingPartialChangedContextStaleTimeAndSparseCanvasKeepTheUnknownGate() = runBlocking {
        for (bad in listOf(null, before.copy(capturedAt = 1200, truncated = true),
            before.copy(capturedAt = 1200, searchComplete = false), before.copy(capturedAt = 1200, windowId = 3),
            before.copy(capturedAt = 1200, geometry = before.geometry!!.copy(displayId = 8)),
            before.copy(capturedAt = 1200, sensitive = true), before.copy(capturedAt = 1100),
            before.copy(capturedAt = 1200, interactionContext = null))) {
            val result = ScrollObservationVerifier.complete(before, first, 0, "Dispatched", true, read = { observed(bad) }, check = {})
            assertEquals("Invalid read: $bad", DeviceActionOutcome.Unverified, result.actionOutcome)
        }
        val canvas = before.copy(elements = listOf(root))
        assertEquals(DeviceActionOutcome.Unverified, ScrollObservationVerifier.complete(canvas,
            observed(canvas.copy(capturedAt = 1100)), 0, "Dispatched", true, read = { error("No unsupported recovery") }, check = {}).actionOutcome)
        assertEquals(DeviceActionOutcome.Unverified, ScrollObservationVerifier.complete(before, first, 0, "Unknown dispatch", false,
            read = { error("No dispatch acknowledgement") }, check = {}).actionOutcome)
    }

    @Test fun cancellationAndOwnerCheckStopTheReadOnlyFollowUp() = runBlocking {
        var reads = 0
        assertThrows(CancellationException::class.java) { runBlocking {
            ScrollObservationVerifier.complete(before, first, 0, "Dispatched", true,
                read = { reads++; first }, check = { throw CancellationException("user stopped") })
        } }
        assertEquals(0, reads)
    }

    @Test fun aMovingControlledScrollNeedsStableFreshReadsBeforeProgressCanResume() = runBlocking {
        fun moved(time: Long, text: String) = observed(before.copy(capturedAt = time,
            elements = before.elements.map { if (it.index == 3) it.copy(text = text) else it }))
        var reads = 0
        val result = ScrollObservationVerifier.complete(before, moved(1100, "After scroll"), 0, "Dispatched", true,
            read = { moved(1200L + ++reads * 100, "After scroll") }, check = {}, requireStableResult = true)
        assertEquals(1, reads); assertEquals(DeviceActionOutcome.ProgressObserved, result.actionOutcome)
        assertTrue(result.summary.contains("scroll_observation_reads=2"))
        reads = 0
        val delayed = ScrollObservationVerifier.complete(before, moved(1100, "Moving"), 0, "Dispatched", true,
            read = { moved(1200L + ++reads * 100, "After scroll") }, check = {}, requireStableResult = true)
        assertEquals(2, reads); assertEquals(DeviceActionOutcome.ProgressObserved, delayed.actionOutcome)
        reads = 0
        val moving = ScrollObservationVerifier.complete(before, moved(1100, "Moving 0"), 0, "Dispatched", true,
            read = { moved(1200L + ++reads * 100, "Moving $reads") }, check = {}, requireStableResult = true)
        assertEquals(2, reads); assertEquals(DeviceActionOutcome.Unverified, moving.actionOutcome)
    }
}
