package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PageTransitionObservationVerifierTest {
    private val bounds = DeviceBounds(0, 0, 500, 900)
    private val root = DeviceUiElement(0, "View", "Source", "source", null, bounds, false, false, false, true, nodePath = "0/1")
    private val context = DeviceInteractionContext(listOf(DeviceWindowRegion(1, DeviceWindowKind.Application, 0, bounds, listOf(bounds), true, true)))
    private val source = DeviceScreenSnapshot("fixture", "Page", listOf(root), capturedAt = 1000, windowId = 1,
        geometry = DeviceScreenGeometry(bounds, 500, 900, 0), interactionContext = context,
        observationScope = DeviceObservationScope(DeviceUiSelector(description = "source")), observationRootPath = "0/1",
        windowContentSha256 = "a".repeat(64))
    private val witness = source.copy(capturedAt = 1010, screenId = "before-witness")
    private fun destination(time: Long, digest: Char = 'b') = source.copy(capturedAt = time, screenId = "destination-$time",
        elements = listOf(root.copy(text = "Destination", contentDescription = "destination")),
        observationScope = null, observationRootPath = null, windowContentSha256 = digest.toString().repeat(64))
    private fun departure(time: Long, digest: Char = 'b') = destination(time, digest).copy(elements = emptyList(),
        observationScope = source.observationScope, observationErrorCode = "observation_root_not_found", searchComplete = false)
    private fun read(screen: DeviceScreenSnapshot?) = DeviceToolResult(screen != null, "Read", screen = screen)

    @Test fun firstMatchingPairKeepsTheOriginalNavigationContextWithoutDiagnosticExpansion() = runBlocking {
        var reads = 0
        val expected = requireNotNull(DevicePageTransitionPolicy.complete(source, witness, departure(1100), read(destination(1200))))
        val result = PageTransitionObservationVerifier.complete(source, witness, read(departure(1100)), true,
            readDeparture = { error("One matching pair needs no retry") },
            readDestination = { reads++; read(destination(1200)) }, check = {})
        assertTrue(result.success); assertEquals(1, reads)
        assertEquals(expected.asToolContent(), result.asToolContent())
    }

    @Test fun strictOldComparisonRejectsTheFirstPairButFreshPairedReadsRecoverWithoutDispatch() = runBlocking {
        assertNull(DevicePageTransitionPolicy.complete(source, witness, departure(1100), read(destination(1200, 'c'))))
        var scoped = 0; var full = 0
        val result = PageTransitionObservationVerifier.complete(source, witness, read(departure(1100)), true,
            readDeparture = { scoped++; read(departure(1300, 'c')) },
            readDestination = { read(destination(if (++full == 1) 1200 else 1400, 'c')) }, check = {})
        assertTrue(result.asToolContent(), result.success)
        assertEquals(1, scoped); assertEquals(2, full)
        assertEquals(DeviceActionOutcome.ProgressObserved, result.actionOutcome)
        assertNotNull(result.pageTransition)
        assertTrue(result.summary.contains("navigation_observation_groups=2"))
        assertTrue(result.summary.contains("original_action_replayed=false"))
        assertTrue(result.asToolContent().contains("submitted_effect_verified=false"))
        assertFalse(result.summary.contains("Source"))
    }

    @Test fun changingGeometryAndInteractionContextAreRepairedOnlyByANewMatchingPair() = runBlocking {
        for (kind in listOf("geometry", "context", "window")) {
            var full = 0; var scoped = 0
            val changed = when (kind) {
                "geometry" -> destination(1200).copy(geometry = source.geometry!!.copy(windowBounds = DeviceBounds(0, 5, 500, 900)))
                "context" -> destination(1200).copy(interactionContext = context.copy(inputFocusWindowId = 1))
                else -> destination(1200).copy(windowId = 2, interactionContext = context.copy(windows = context.windows.map { it.copy(id = 2) }))
            }
            val nextDeparture = changed.copy(capturedAt = 1300, elements = emptyList(), observationScope = source.observationScope,
                observationErrorCode = "observation_root_not_found", searchComplete = false)
            val result = PageTransitionObservationVerifier.complete(source, witness, read(departure(1100)), true,
                readDeparture = { scoped++; read(nextDeparture) },
                readDestination = { read(changed.copy(capturedAt = if (++full == 1) 1200 else 1400)) }, check = {})
            assertTrue("$kind: ${result.asToolContent()}", result.success)
            assertEquals(1, scoped); assertEquals(2, full)
        }
    }

    @Test fun persistentContradictionsStopAfterThreePairsAndKeepTheUnknownGate() = runBlocking {
        var time = 1100L; var scoped = 0; var full = 0
        val result = PageTransitionObservationVerifier.complete(source, witness, read(departure(time)), true,
            readDeparture = { scoped++; read(departure(++time)) },
            readDestination = { full++; read(destination(++time, 'c')) }, check = {})
        assertFalse(result.success); assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome)
        assertNull(result.pageTransition); assertEquals(2, scoped); assertEquals(3, full)
        assertTrue(result.summary.contains("navigation_observation_groups=3"))
        assertTrue(result.summary.contains("content_changed_between_reads"))
        assertEquals(time, result.screen!!.capturedAt)
    }

    @Test fun everyNewDepartureMustBeFreshUnambiguousAndOnTheOriginalDisplay() = runBlocking {
        for (bad in listOf(departure(1100), departure(1300).copy(sensitive = true),
            departure(1300).copy(observationErrorCode = "observation_root_ambiguous"),
            departure(1300).copy(windowContentSha256 = null),
            departure(1300).copy(geometry = source.geometry!!.copy(displayId = 2)),
            source.copy(capturedAt = 1300), departure(1300).copy(observationScope = DeviceObservationScope(DeviceUiSelector(text = "other"))))) {
            var full = 0
            val result = PageTransitionObservationVerifier.complete(source, witness, read(departure(1100)), true,
                readDeparture = { read(bad) }, readDestination = { full++; read(destination(1200, 'c')) }, check = {})
            assertFalse(result.success); assertNull(result.pageTransition); assertEquals(1, full)
        }
    }

    @Test fun staleSensitiveDifferentDisplayAndSecureFailureNeverCertifyOrRetryAWiderRead() = runBlocking {
        for (bad in listOf(read(destination(1100)), read(destination(1200).copy(sensitive = true)),
            read(destination(1200).copy(geometry = source.geometry!!.copy(displayId = 2))),
            DeviceToolResult(false, "Secure", errorCode = "secure_window_blocked"))) {
            var full = 0
            val result = PageTransitionObservationVerifier.complete(source, witness, read(departure(1100)), true,
                readDeparture = { error("Unsafe evidence must not be retried") }, readDestination = { full++; bad }, check = {})
            assertFalse(result.success); assertNull(result.pageTransition); assertEquals(1, full)
        }
    }

    @Test fun unmatchedSourceOrUnacknowledgedDispatchCannotStartRecovery() = runBlocking {
        for ((before, accepted) in listOf(witness to false, witness.copy(truncated = true) to true, null to true)) {
            val result = PageTransitionObservationVerifier.complete(source, before, read(departure(1100)), accepted,
                readDeparture = { error("No recovery") }, readDestination = { error("No wider read") }, check = {})
            assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome); assertNull(result.pageTransition)
        }
    }

    @Test fun cancellationAndOwnershipChecksStopBeforeAnyMoreObservation() = runBlocking<Unit> {
        assertThrows(CancellationException::class.java) { runBlocking {
            PageTransitionObservationVerifier.complete(source, witness, read(departure(1100)), true,
                readDeparture = { error("Cancelled") }, readDestination = { error("Cancelled") },
                check = { throw CancellationException("User took over") })
        } }
    }

    @Test fun readDeadlinePreservesUncertaintyAndTheMostRecentAvailableEvidence() = runBlocking {
        val result = PageTransitionObservationVerifier.complete(source, witness, read(departure(1100)), true,
            readDeparture = { error("No retry after deadline") }, readDestination = { delay(4000); read(destination(1200)) }, check = {})
        assertFalse(result.success); assertNull(result.pageTransition)
        assertTrue(result.summary.contains("observation_time_limit"))
        assertEquals(1100L, result.screen!!.capturedAt)
    }
}
