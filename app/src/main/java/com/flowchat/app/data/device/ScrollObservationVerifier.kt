package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Read-only follow-up to one acknowledged scroll. Never dispatches or certifies a submit. */
internal object ScrollObservationVerifier {
    suspend fun complete(before: DeviceScreenSnapshot, first: DeviceToolResult, index: Int, summary: String,
        dispatchAccepted: Boolean, read: suspend () -> DeviceToolResult, check: () -> Unit,
        requireStableResult: Boolean = false): DeviceToolResult {
        var latest = first
        var reads = 1
        fun classify() = DeviceActionOutcomePolicy.complete(before, latest, summary, scrollIndex = index)
        fun finish(result: DeviceToolResult) = result.copy(summary = result.summary +
            "\nscroll_observation_reads=$reads; scroll_dispatch_count=1; original_scroll_replayed=false")
        fun unknown() = finish(classify().copy(success = false, errorCode = latest.errorCode ?: "ui_scroll_unverified",
            actionOutcome = DeviceActionOutcome.Unverified,
            summary = "$summary The current container could not be verified with complete comparable fresh reads. Do not replay the scroll."))
        if (!dispatchAccepted) return finish(latest.copy(success = false, errorCode = "ui_action_failed",
            summary = "$summary Dispatch was not acknowledged; its result remains unknown.", actionOutcome = DeviceActionOutcome.Unverified))
        val target = before.elements.singleOrNull { it.index == index && it.scrollable && it.enabled }
            ?: return finish(classify())
        fun contents(screen: DeviceScreenSnapshot?): List<DeviceUiElement>? {
            if (screen == null || before.sensitive || screen.sensitive || before.truncated || screen.truncated ||
                !before.searchComplete || !screen.searchComplete || before.observationErrorCode != null || screen.observationErrorCode != null ||
                before.windowId == null || before.geometry == null || before.interactionContext?.complete != true ||
                screen.interactionContext?.complete != true || before.packageName != screen.packageName || before.activityName != screen.activityName ||
                before.windowId != screen.windowId || before.geometry != screen.geometry || before.interactionContext != screen.interactionContext ||
                !before.hasSameObservationScope(screen) || before.elementQuery != null || screen.elementQuery != null) return null
            val container = DeviceAdaptiveScroll.resolveContainer(screen, target)?.takeIf { it.bounds == target.bounds && it.enabled }
                ?: return null
            return DeviceAdaptiveScroll.descendants(screen, container).filter { it.index != container.index }
                .map { it.copy(index = 0, parentIndex = null) }
        }
        val original = contents(before) ?: return unknown()
        // Sparse/custom-drawn surfaces need visual evidence; equal accessibility trees do not establish their current content.
        if (original.filter { !it.password && !it.editable && it.displayText.isNotBlank() }.map { it.displayText }.distinct().size < 3)
            return if (requireStableResult) unknown() else finish(classify())
        var previous = contents(latest.screen) ?: return unknown()
        var previousTime = latest.screen!!.capturedAt
        if (!latest.success || previousTime <= before.capturedAt) return unknown()
        repeat(2) {
            val classified = classify()
            if (!requireStableResult && classified.actionOutcome != DeviceActionOutcome.Unverified) return finish(classified)
            currentCoroutineContext().ensureActive(); check(); delay(120)
            currentCoroutineContext().ensureActive(); check()
            latest = read(); reads++
            val current = contents(latest.screen) ?: return unknown()
            val time = latest.screen!!.capturedAt
            if (!latest.success || time <= previousTime) return unknown()
            val result = classify()
            if (!requireStableResult && result.actionOutcome != DeviceActionOutcome.Unverified) return finish(result)
            if (previous == current && requireStableResult && result.actionOutcome == DeviceActionOutcome.ProgressObserved)
                return finish(result.copy(summary = "The single scroll's current readable container is now stable in fresh comparable observations. " +
                    "Readable progress was observed; inspect the intended target. No gesture was replayed and no business submission was verified."))
            if (reads == 3 && previous == current && current == original) return finish(latest.copy(success = false,
                errorCode = "ui_scroll_no_progress", before = before.packageName, after = latest.screen?.packageName,
                actionOutcome = DeviceActionOutcome.StateObserved,
                summary = "The scroll was acknowledged once. Three fresh complete observations verified the same readable container state; " +
                    "no position change was established. Inspect visible blockers or choose another search/navigation route. Do not repeat this scroll. " +
                    "This verifies only the current view, not a business submission or the user's completed goal."))
            previous = current; previousTime = time
        }
        return if (requireStableResult) unknown() else finish(classify())
    }
}
