package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DevicePageTransitionTest {
    private val bounds = DeviceBounds(0, 0, 500, 900)
    private val root = DeviceUiElement(0, "View", "Source", "source", null, bounds, false, false, false, true, nodePath = "0/1")
    private val context = DeviceInteractionContext(listOf(DeviceWindowRegion(1, DeviceWindowKind.Application, 1, bounds, listOf(bounds), true, true)))
    private val window = DeviceScreenSnapshot("app", "Page", listOf(root), windowId = 1,
        geometry = DeviceScreenGeometry(bounds, 500, 900, 0), interactionContext = context, windowContentSha256 = "a".repeat(64))
    private val source = window.copy(observationScope = DeviceObservationScope(DeviceUiSelector(description = "source")), observationRootPath = "0/1")
    private val witness = source.copy(screenId = "independent-before")
    private val destination = window.copy(elements = listOf(root.copy(text = "Destination", contentDescription = "destination")), windowContentSha256 = "b".repeat(64))
    private fun absence(after: DeviceScreenSnapshot = destination) = after.copy(elements = emptyList(), observationScope = source.observationScope,
        observationRootPath = null, observationErrorCode = "observation_root_not_found", searchComplete = false, truncated = false)
    private fun result(screen: DeviceScreenSnapshot) = DeviceToolResult(true, "Read", screen = screen)

    @Test fun replacedRootHasIndependentNavigationEvidenceButNoSubmitReceipt() {
        val outcome = requireNotNull(DevicePageTransitionPolicy.complete(source, witness, absence(), result(destination)))
        assertEquals(DeviceActionOutcome.ProgressObserved, outcome.actionOutcome)
        assertNotNull(outcome.pageTransition)
        assertTrue(outcome.asToolContent().contains("submitted_effect_verified=false"))
        assertNotEquals(outcome.pageTransition!!.fromPageSha256, outcome.pageTransition!!.toPageSha256)
        assertEquals(DeviceActionOutcome.Unverified, DeviceActionOutcomePolicy.complete(source, result(destination), "Tap").actionOutcome)
    }

    @Test fun unchanged160ControlPreviewCanStillProveACompleteWindowNavigationWithoutExposingMoreIndices() {
        val preview = window.copy(elements = (0 until 160).map { root.copy(index = it, text = "Outside $it", contentDescription = null) }, truncated = true, searchComplete = true)
        val after = preview.copy(windowContentSha256 = destination.windowContentSha256)
        assertTrue(preview.hasSameActionContent(after))
        val outcome = requireNotNull(DevicePageTransitionPolicy.complete(source, witness, absence(after), result(after)))
        assertTrue(outcome.success); assertTrue(outcome.screen!!.truncated); assertEquals(160, outcome.screen!!.elements.size)
        assertEquals(destination.windowContentSha256, outcome.pageTransition!!.toPageSha256)
        assertFalse(DevicePageTransitionPolicy.sourceMatches(source, preview))
    }

    @Test fun missingDuplicateStaleIncompleteOrUncertifiedSourceCannotProveNavigation() {
        for (bad in listOf(null, witness.copy(truncated = true), witness.copy(elements = emptyList()),
            witness.copy(elements = listOf(root, root.copy(index = 1))), witness.copy(windowId = 2),
            witness.copy(observationRootPath = "0/2"), witness.copy(windowContentSha256 = null),
            witness.copy(windowContentSha256 = "partial"), witness.copy(searchComplete = false)))
            assertNull(DevicePageTransitionPolicy.complete(source, bad, absence(), result(destination)))
    }

    @Test fun rootPresenceAmbiguityIncompleteScansAndMismatchedDestinationNeverCertifyNavigation() {
        for (bad in listOf(source.copy(windowContentSha256 = destination.windowContentSha256),
            absence().copy(observationErrorCode = "observation_root_ambiguous"),
            absence().copy(observationErrorCode = "observation_scope_incomplete", windowContentSha256 = null),
            absence().copy(windowContentSha256 = "c".repeat(64)), absence().copy(windowId = 2),
            absence().copy(observationScope = DeviceObservationScope(DeviceUiSelector(text = "other")))))
            assertNull(DevicePageTransitionPolicy.complete(source, witness, bad, result(destination)))
        // Unrelated outside changes cannot turn an unchanged source pane into a verified effect.
        assertEquals(DeviceActionOutcome.Unverified, DeviceActionOutcomePolicy.complete(source,
            result(source.copy(windowContentSha256 = destination.windowContentSha256)), "Tap").actionOutcome)
    }

    @Test fun sensitiveUnavailableUncertifiedAndDifferentDisplayDestinationsRemainUnverified() {
        for (bad in listOf(destination.copy(sensitive = true), destination.copy(windowContentSha256 = null),
            destination.copy(searchComplete = false), destination.copy(windowContentSha256 = source.windowContentSha256),
            destination.copy(observationErrorCode = "observation_root_ambiguous"), destination.copy(interactionContext = context.copy(complete = false)),
            destination.copy(geometry = destination.geometry!!.copy(displayId = 2)), source))
            assertNull(DevicePageTransitionPolicy.complete(source, witness, absence(bad), result(bad)))
        assertNull(DevicePageTransitionPolicy.complete(source, witness, absence(), DeviceToolResult(false, "Failed", screen = destination)))
    }

    @Test fun failedPreDispatchScopesNeverRequestWiderObservation() {
        assertFalse(DevicePageTransitionPolicy.needsWindowObservation(source, source.copy(observationErrorCode = "observation_root_ambiguous")))
        assertFalse(DevicePageTransitionPolicy.needsWindowObservation(source, source.copy(observationErrorCode = "observation_scope_incomplete", truncated = true, windowContentSha256 = null)))
        assertTrue(DevicePageTransitionPolicy.needsWindowObservation(source, absence()))
    }
}
