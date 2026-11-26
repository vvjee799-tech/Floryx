package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Locates the same exact intent in fresh UI. Only explicitly undispatched taps may be relocated. */
class UiTargetActionController(private val gateway: DeviceAssistantGateway, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var locateElapsedMillis: Long = 0
        private set

    suspend fun tap(request: DeviceUiTargetRequest, baseline: DeviceScreenSnapshot?): DeviceToolResult {
        val started = now()
        var dispatchMillis = 0L
        locateElapsedMillis = 0
        return try {
            locateAndTap(request, baseline) { index, target, id ->
                val dispatchStarted = now()
                try { gateway.tapLocatedUiTarget(index, target, id) }
                finally { dispatchMillis += (now() - dispatchStarted).coerceAtLeast(0) }
            }
        } finally {
            // Charge actual locating/relocating time, including failed/cancelled reads.
            // Dispatch and its result observation have their own task active-time limit.
            locateElapsedMillis = (now() - started - dispatchMillis).coerceAtLeast(0)
        }
    }

    private suspend fun locateAndTap(request: DeviceUiTargetRequest, baseline: DeviceScreenSnapshot?,
        dispatch: suspend (Int, DeviceUiSelector, String) -> DeviceToolResult): DeviceToolResult {
        var latest = baseline
        var attempts = 0
        var reads = 0
        fun rejected(code: String) = DeviceToolResult(false,
            if (code == "ui_target_ambiguous") {
                "No tap was dispatched for the exact target ($code). Multiple matching controls exist; narrow target with resource_id or description from the returned controls, or call observe_screen and tap the unique clickable row. No user tap or repeated permission is required by this result."
            } else {
                "No tap was dispatched for the exact target ($code). Inspect the returned evidence and choose another route if needed; no user tap or repeated permission is required by this result."
            },
            screen = latest, errorCode = code, actionOutcome = DeviceActionOutcome.NotDispatched)
        fun finish(result: DeviceToolResult) = result.copy(summary = result.summary +
            "\ntarget_action_attempts=$attempts; target_locate_reads=$reads; no_uncertain_action_replayed=true")
        if (baseline == null || baseline.screenId != request.screenId || baseline.sensitive || baseline.observationErrorCode != null ||
            baseline.windowId == null || baseline.geometry == null || baseline.interactionContext?.complete != true)
            return finish(rejected("ui_target_baseline_unavailable"))
        if (baseline.observationScope != null && request.observationScope != null && request.observationScope != baseline.observationScope)
            return finish(rejected("ui_target_scope_change_requires_observation"))
        val scope = request.observationScope ?: baseline.observationScope
        val started = now()
        fun sameWindow(screen: DeviceScreenSnapshot): Boolean = !screen.sensitive && screen.observationErrorCode == null &&
            screen.packageName == baseline.packageName && screen.activityName == baseline.activityName && screen.windowId == baseline.windowId &&
            screen.geometry?.displayId == baseline.geometry.displayId && screen.observationScope == scope
        val condition = DeviceScreenCondition(packageName = baseline.packageName, windowId = baseline.windowId,
            element = request.target, allowTapTargetDisambiguation = true)
        while (now() - started < request.timeoutMillis && attempts < 3) {
            currentCoroutineContext().ensureActive()
            val remaining = request.timeoutMillis - (now() - started)
            if (remaining < 100) break
            reads++
            val observed = gateway.waitForScreen(DeviceScreenWaitRequest(condition, remaining.coerceAtMost(5_000), scope))
            val screen = observed.screen ?: return finish(rejected("ui_target_observation_unavailable"))
            latest = screen
            if (!sameWindow(screen)) return finish(rejected("ui_target_context_changed"))
            if (!screen.searchComplete || screen.truncated || screen.elementQuery != request.target)
                return finish(rejected("ui_target_search_incomplete"))
            if (DeviceUiTargetPolicy.classifyQueryMatches(screen.elements, request.target) == DeviceUiTargetPolicy.MatchStatus.Ambiguous)
                return finish(rejected("ui_target_ambiguous"))
            val candidates = DeviceUiTargetPolicy.queryCandidates(screen.elements, request.target)
            if (candidates.any { it.enabled && !it.password && it.bounds.isVisible } && observed.success) {
                // Restore the complete pane before dispatch. Both the containing row and
                // nearby result feedback must be visible in comparable before/after reads;
                // an unchanged filtered button alone cannot prove that its action failed.
                reads++
                val full = if (scope == null) gateway.observeScreen() else gateway.observeScreen(scope)
                latest = full.screen
                val pane = full.screen ?: return finish(rejected("ui_target_feedback_scope_unavailable"))
                if (!full.success || !sameWindow(pane) || pane.truncated || !pane.searchComplete || pane.elementQuery != null)
                    return finish(rejected("ui_target_feedback_scope_incomplete"))
                when (val resolution = DeviceUiTargetPolicy.resolveDetailed(pane, request.target)) {
                    DeviceUiTargetPolicy.Resolution.Ambiguous -> return finish(rejected("ui_target_ambiguous"))
                    DeviceUiTargetPolicy.Resolution.NotReady -> Unit
                    is DeviceUiTargetPolicy.Resolution.Resolved -> {
                        val target = resolution.element
                        val current = requireNotNull(latest)
                        if (target.enabled && !target.password && target.clickable && "click" in target.supportedActions &&
                            current.interactionContext?.allowsPoint(current, target.bounds.centerX, target.bounds.centerY) == true) {
                            currentCoroutineContext().ensureActive()
                            if (now() - started >= request.timeoutMillis) break
                            attempts++
                            val result = dispatch(target.index, request.target, current.screenId)
                            latest = result.screen ?: current
                            // Dispatched, unverified, missing outcome, and exceptions never authorize a second tap.
                            if (result.actionOutcome != DeviceActionOutcome.NotDispatched ||
                                result.errorCode !in setOf("stale_screen", "ui_target_obscured", "ui_context_unavailable", "ui_target_changed")) return finish(result)
                        }
                    }
                }
            }
            val pause = minOf(120L, request.timeoutMillis - (now() - started))
            if (pause > 0) delay(pause)
        }
        return finish(rejected(if (attempts >= 3) "ui_target_relocation_limit" else "ui_target_not_ready"))
    }
}
