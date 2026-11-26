package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.*

internal data class AdaptiveScrollDispatch(
    val accepted: Boolean,
    val inputMode: String = "gesture",
    val beforeRelease: DeviceScreenSnapshot? = null
)

/** One exploratory movement, then at most two local corrections supported by newly observed target/anchors. */
internal class AdaptiveScrollController(private val now: () -> Long) {
    private var historyKey: String? = null
    private var historyScreen: DeviceScreenSnapshot? = null
    private var gain = 1.0

    suspend fun scroll(
        initial: DeviceScreenSnapshot, request: DeviceScrollRequest, owner: String?, minimumPixels: Int,
        read: suspend () -> DeviceScreenSnapshot?, capture: suspend () -> DeviceToolResult,
        dispatch: suspend (DeviceScreenSnapshot, DeviceSwipePath, Int) -> AdaptiveScrollDispatch, check: () -> Unit
    ): DeviceToolResult {
        val started = now()
        var before = initial
        var area = DeviceAdaptiveScroll.area(before, request) ?: return rejected(before, "ui_scroll_container_ambiguous",
            "Choose the intended scrollable container from the fresh screen; no gesture was dispatched.")
        val original = area.container
        val key = "$owner|${before.packageName}|${before.windowId}|${before.geometry}|${original?.nodePath}|${original?.contentDescription}|${request.target}|${request.vertical}"
        if (key != historyKey || historyScreen?.hasSameUi(before) != true) gain = 1.0
        historyKey = key
        var gestures = 0; var corrections = 0; var fingerPixels = 0; var duration = 0
        var motion: DeviceScrollMotion? = null
        var allAccepted = true
        var lastInputMode = "gesture"
        var previousDirection: Int? = null
        var forcedDelta: Int? = null
        var lastResult = DeviceToolResult(true, "Observed scroll area.", screen = before)

        fun result(success: Boolean, state: String, settled: Boolean = true, code: String? = null): DeviceToolResult {
            historyScreen = before
            return lastResult.copy(success = success, errorCode = code,
                summary = when {
                    state == "ready" -> "The exact scroll target is visible in a clear operating area. Use its fresh index to perform the requested action."
                    !settled -> "Content is still moving or loading. Observe its stable state before another action; no gesture was replayed."
                    success -> "Scroll progress was observed inside the selected area. Measured displacement is reported separately when available. Continue toward the task's target."
                    else -> "Scroll effect/target remains unverified ($state). Inspect loading/end indicators or use another search/location approach; do not repeat the same gesture blindly."
                }, actionOutcome = when {
                    state == "ready" -> DeviceActionOutcome.TargetVerified
                    success -> DeviceActionOutcome.ProgressObserved
                    gestures == 0 -> DeviceActionOutcome.NotDispatched
                    allAccepted && settled && state in setOf("no_progress", "not_visible", "needs_refinement", "obscured", "lost_unverified", "ambiguous") -> DeviceActionOutcome.StateObserved
                    else -> DeviceActionOutcome.Unverified
                },
                scrollFeedback = DeviceScrollFeedback(if (lastInputMode == "gesture") fingerPixels else 0, duration,
                    motion, state, gestures, corrections, now() - started, settled, lastInputMode))
        }

        repeat(3) { iteration ->
            currentCoroutineContext().ensureActive(); check()
            val matches = DeviceAdaptiveScroll.targets(before, area, request.target)
            if (matches.size > 1) return result(false, "ambiguous", code = "ui_scroll_target_ambiguous")
            val target = matches.singleOrNull()
            if (target != null && (DeviceAdaptiveScroll.targetReady(before, area, target, request.vertical) ||
                lastInputMode == "show_target" && DeviceAdaptiveScroll.shownTargetReady(before, area, target, request.vertical))) return result(true, "ready")
            val extent = if (request.vertical) area.bounds.height else area.bounds.width
            val delta = forcedDelta ?: target?.let { DeviceAdaptiveScroll.targetDelta(area, it, request.vertical) }
            if (target != null && delta == 0) return result(false, "obscured", code = "ui_scroll_target_obscured")
            val exploratory = extent * (request.distancePercent ?: 55) / 100
            // With a target, preserve overlap even when the model asks for a long swipe.
            val contentBudget = delta ?: (if (request.target != null) minOf(exploratory, extent * 3 / 5) else exploratory) * request.contentSign
            val pixels = if (delta != null || request.target != null) (abs(contentBudget) / gain).roundToInt()
                else abs(contentBudget)
            val direction = DeviceAdaptiveScroll.direction(request.vertical, contentBudget)
            val path = before.interactionContext?.swipe(before, direction, request.distancePercent ?: 55,
                area.bounds, area.excluded, pixels.coerceAtLeast(minimumPixels))
                ?: return if (gestures == 0) rejected(before, "ui_scroll_path_blocked", "No unobscured gesture lane exists inside the selected container.")
                else result(false, "path_changed", code = "ui_scroll_path_blocked")
            fingerPixels = if (request.vertical) abs(path.startY - path.endY) else abs(path.startX - path.endX)
            // A minimum duration plus distance-sensitive speed avoids flicking short rows out of view.
            duration = request.durationMillis ?: (fingerPixels / .8).roundToInt().coerceIn(220, 900)
            val needsPixels = DeviceAdaptiveScroll.descendants(before, area.container).count { !it.scrollable && it.displayText.isNotBlank() } < 3
            val oldImage = if (needsPixels) capture().takeIf { it.success && it.screen?.hasSameUi(before) == true }?.screenImage else null
            check()
            val movement = try { dispatch(before, path, duration) }
            catch (error: DeviceDispatchRejected) {
                val fresh = read()
                return rejected(fresh ?: before, error.code, "Scroll context changed before dispatch. Locate again from this fresh observation.")
            }
            gestures++
            lastInputMode = movement.inputMode
            allAccepted = allAccepted && movement.accepted
            if (previousDirection?.let { it * contentBudget < 0 } == true) corrections++
            previousDirection = if (contentBudget >= 0) 1 else -1
            val observed = settle(before, read, check)
            val after = observed.first ?: return result(false, "screen_unavailable", settled = false, code = "screen_unavailable")
            lastResult = DeviceToolResult(true, "Observed scroll result.", screen = after)
            if (!sameWindow(before, after) || after.sensitive || after.observationErrorCode != null) {
                before = after
                return result(false, "context_changed", code = if (after.sensitive) "sensitive_screen_blocked" else "ui_scroll_context_changed")
            }
            val afterArea = DeviceAdaptiveScroll.area(after, request, original)
                ?: return result(false, "container_changed", code = "ui_scroll_container_changed")
            val previousContainer = area.container
            val currentContainer = afterArea.container
            // A collapsing app bar can expand the same list upward without moving its sides or
            // bottom. Keep the resolved identity; a genuine move or resize still aborts.
            val expandedByHeader = previousContainer != null && currentContainer != null &&
                previousContainer.bounds.left == currentContainer.bounds.left &&
                previousContainer.bounds.right == currentContainer.bounds.right &&
                previousContainer.bounds.bottom == currentContainer.bounds.bottom &&
                currentContainer.bounds.top < previousContainer.bounds.top
            if (previousContainer != null && previousContainer.bounds != currentContainer?.bounds && !expandedByHeader) {
                before = after
                return result(false, "container_moved", code = "ui_scroll_container_moved")
            }
            motion = DeviceAdaptiveScroll.measure(before, after, area, request.vertical)
            val contentChanged = DeviceAdaptiveScroll.contentsChanged(before, after, area)
            val newTargets = DeviceAdaptiveScroll.targets(after, afterArea, request.target)
            if (motion == null && target != null && movement.accepted && observed.second) {
                movement.beforeRelease?.let { held -> motion = measureAcrossRelease(before, held, after, area, request.vertical) }
            }
            if ((motion == null || motion?.contentPixels == 0) && oldImage != null && observed.second) {
                val imageResult = capture()
                if (imageResult.success && imageResult.screen?.hasSameUi(after) == true && imageResult.screenImage != null) {
                    lastResult = imageResult
                    val pixels = withContext(Dispatchers.Default) { ScrollImageMotionEstimator.measure(oldImage, imageResult.screenImage, area.bounds, request.vertical) }
                    if (pixels != null) motion = pixels
                }
            }
            if (motion != null) {
                if (abs(requireNotNull(motion).contentPixels) > 2 && fingerPixels > 0)
                    gain = (abs(requireNotNull(motion).contentPixels).toDouble() / fingerPixels).coerceIn(.25, 6.0)
            }
            forcedDelta = null
            // Losing a previously visible target is an overshoot ONLY if measured displacement places it beyond an edge.
            if (target != null && newTargets.isEmpty() && motion != null) {
                val center = (if (request.vertical) target.bounds.centerY else target.bounds.centerX) - requireNotNull(motion).contentPixels
                val low = if (request.vertical) afterArea.bounds.top else afterArea.bounds.left
                val high = if (request.vertical) afterArea.bounds.bottom else afterArea.bounds.right
                val inset = extent / 5
                if (center < low || center >= high) forcedDelta = (center - center.coerceIn(low + inset, high - inset))
                    .coerceIn(-abs(requireNotNull(motion).contentPixels) / 2, abs(requireNotNull(motion).contentPixels) / 2)
            }
            before = after; area = afterArea
            if (!movement.accepted) return result(false, "dispatch_unconfirmed", code = "ui_action_failed")
            if (!observed.second) return result(false, "moving", settled = false, code = "ui_scroll_unsettled")
            if (newTargets.size == 1 && (DeviceAdaptiveScroll.targetReady(after, afterArea, newTargets.single(), request.vertical) ||
                lastInputMode == "show_target" && DeviceAdaptiveScroll.shownTargetReady(after, afterArea, newTargets.single(), request.vertical))) return result(true, "ready")
            if (motion?.contentPixels == 0 && !contentChanged) return result(false, "no_progress", code = "ui_scroll_no_progress")
            if (request.target == null || newTargets.isEmpty() && forcedDelta == null) return result(
                motion?.contentPixels?.let { abs(it) > 2 } == true || contentChanged,
                if (target != null) "lost_unverified" else "not_visible", code = if (motion == null && !contentChanged) "ui_scroll_motion_unverified" else null)
            if (iteration == 2) return result(false, "needs_refinement", code = "ui_scroll_target_unverified")
        }
        error("Unreachable")
    }

    private fun measureAcrossRelease(before: DeviceScreenSnapshot, held: DeviceScreenSnapshot, after: DeviceScreenSnapshot,
        area: DeviceScrollArea, vertical: Boolean): DeviceScrollMotion? {
        val container = area.container ?: return null
        if (held.sensitive || !held.searchComplete || held.observationErrorCode != null ||
            before.activityName != held.activityName || held.activityName != after.activityName || !sameWindow(before, held) ||
            DeviceAdaptiveScroll.resolveContainer(held, container)?.bounds != container.bounds) return null

        // A release can move the content beyond the starting viewport. Each pair still needs its own matching rows.
        val drag = DeviceAdaptiveScroll.measure(before, held, area, vertical) ?: return null
        val release = DeviceAdaptiveScroll.measure(held, after, area, vertical) ?: return null
        if ((drag.contentPixels < 0 && release.contentPixels > 0) ||
            (drag.contentPixels > 0 && release.contentPixels < 0)) return null
        return DeviceScrollMotion(drag.contentPixels + release.contentPixels, "anchors_across_release", minOf(drag.anchors, release.anchors))
    }

    private suspend fun settle(baseline: DeviceScreenSnapshot, read: suspend () -> DeviceScreenSnapshot?, check: () -> Unit): Pair<DeviceScreenSnapshot?, Boolean> {
        val started = now()
        var stableSince = started
        var previous: DeviceScreenSnapshot? = null
        while (now() - started < 2_200) {
            delay(90); currentCoroutineContext().ensureActive(); check()
            val current = read()
            if (current == null || previous?.hasSameUi(current) != true) stableSince = now()
            previous = current
            if (current != null && (current.sensitive || !sameWindow(baseline, current))) return current to false
            if (current != null && now() - stableSince >= 240) return current to true
        }
        return previous to false
    }

    private fun sameWindow(before: DeviceScreenSnapshot, after: DeviceScreenSnapshot) =
        before.packageName == after.packageName && before.windowId == after.windowId && before.geometry == after.geometry &&
            before.interactionContext == after.interactionContext && before.hasSameObservationScope(after)

    private fun rejected(screen: DeviceScreenSnapshot, code: String, message: String) = DeviceToolResult(false, message,
        screen = screen, errorCode = code, actionOutcome = DeviceActionOutcome.NotDispatched)
}
