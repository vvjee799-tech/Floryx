package com.flowchat.app.domain.device


/** Independent window evidence of navigation; never a receipt for a submitted effect. */
data class DevicePageTransition(
    val fromPackage: String,
    val toPackage: String,
    val displayId: Int,
    val fromPageSha256: String,
    val toPageSha256: String
)

object DevicePageTransitionPolicy {
    private val digest = Regex("[0-9a-f]{64}")
    private fun scanned(screen: DeviceScreenSnapshot): Boolean = !screen.sensitive &&
        screen.windowContentSha256?.matches(digest) == true && screen.windowId != null &&
        screen.geometry != null && screen.interactionContext?.complete == true &&
        screen.interactionContext.availableRegions(screen).isNotEmpty()

    private fun completePane(screen: DeviceScreenSnapshot): Boolean = scanned(screen) && !screen.truncated &&
        screen.searchComplete && screen.observationErrorCode == null && screen.elements.isNotEmpty()

    /** A fresh independent pane read also certifies its bounded whole-window scan. */
    fun sourceMatches(source: DeviceScreenSnapshot, witness: DeviceScreenSnapshot?): Boolean =
        source.observationScope != null && source.observationRootPath != null && completePane(source) &&
            witness != null && completePane(witness) && source.hasSameUi(witness)

    /** Only the caller's positively acknowledged dispatch may reach this check. */
    fun needsWindowObservation(source: DeviceScreenSnapshot, after: DeviceScreenSnapshot?): Boolean =
        source.observationScope != null && after != null && scanned(after) &&
            source.observationScope == after.observationScope && source.elementQuery == after.elementQuery &&
            (after.observationErrorCode == "observation_root_not_found" || after.observationErrorCode == null &&
                (source.packageName != after.packageName || source.activityName != after.activityName || source.windowId != after.windowId))

    /** Diagnostic reasons preserve every comparison used to certify the two independent reads. */
    fun rejectionReason(source: DeviceScreenSnapshot, witness: DeviceScreenSnapshot?, sourceAfter: DeviceScreenSnapshot?,
        destination: DeviceToolResult): String? {
        if (!sourceMatches(source, witness)) return "source_unverified"
        if (!needsWindowObservation(source, sourceAfter)) return "departure_unverified"
        val after = destination.screen ?: return "destination_unavailable"
        if (!destination.success || !scanned(after) || !after.searchComplete || after.observationErrorCode != null ||
            after.observationScope != null || after.elementQuery != null || after.elements.isEmpty()) return "destination_incomplete"
        val before = requireNotNull(witness)
        val rootProof = requireNotNull(sourceAfter)
        // Absence and destination must describe the same current window. Comparing
        // only the first 160 controls cannot establish this when their preview is unchanged.
        if (rootProof.packageName != after.packageName || rootProof.activityName != after.activityName ||
            rootProof.windowId != after.windowId) return "window_changed_between_reads"
        if (rootProof.geometry != after.geometry) return "geometry_changed_between_reads"
        if (rootProof.interactionContext?.copy(inputFocusIndex = null) != after.interactionContext?.copy(inputFocusIndex = null))
            return "interaction_context_changed_between_reads"
        if (rootProof.windowContentSha256 != after.windowContentSha256) return "content_changed_between_reads"
        val oldGeometry = requireNotNull(before.geometry)
        val newGeometry = requireNotNull(after.geometry)
        if (oldGeometry.displayId != newGeometry.displayId || oldGeometry.displayWidth != newGeometry.displayWidth ||
            oldGeometry.displayHeight != newGeometry.displayHeight || oldGeometry.rotation != newGeometry.rotation) return "display_changed"
        if (before.windowContentSha256 == after.windowContentSha256) return "source_content_unchanged"
        return null
    }

    fun complete(source: DeviceScreenSnapshot, witness: DeviceScreenSnapshot?, sourceAfter: DeviceScreenSnapshot?,
        destination: DeviceToolResult): DeviceToolResult? {
        if (rejectionReason(source, witness, sourceAfter, destination) != null) return null
        val before = requireNotNull(witness)
        val after = requireNotNull(destination.screen)
        val newGeometry = requireNotNull(after.geometry)
        // Global digest changes alone never certify a tap: the source root must
        // independently disappear or its window must change, with no ambiguous scan.
        return destination.copy(success = true, errorCode = null,
            summary = "The destination window and source-root departure were independently verified in complete bounded scans. Continue the original goal using fresh observations. " +
                (if (after.truncated) "The control preview remains truncated; observe the exact known goal or a unique destination pane before choosing another target. " else "") +
                "Navigation progress does not verify a save, send or other submitted effect.",
            before = before.packageName, after = after.packageName, actionOutcome = DeviceActionOutcome.ProgressObserved,
            pageTransition = DevicePageTransition(before.packageName, after.packageName, newGeometry.displayId,
                requireNotNull(before.windowContentSha256), requireNotNull(after.windowContentSha256)))
    }
}
