package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

/** Read-only, bounded pixel observation of one previously captured crop. Never dispatches input. */
internal class ScreenRegionWaiter(
    private val capture: suspend () -> DeviceToolResult,
    private val nowMillis: () -> Long,
    private val difference: suspend (DeviceScreenImage, DeviceScreenImage) -> Double = { before, after ->
        withContext(Dispatchers.Default) {
            ScreenImageMatcher.compare(before, after, VisualTapTarget(before.screenId, 500, 500, "region observation")).sceneDifference
        }
    }
) {
    suspend fun observe(request: DeviceScreenWaitRequest, baseline: VisualFrameGuard.Frame?): DeviceToolResult {
        val condition = requireNotNull(request.condition)
        if (baseline == null || baseline.image.region == null || condition.regionChangedFrom != baseline.image.screenId ||
            (request.imageRegion != null && request.imageRegion != baseline.image.region) ||
            baseline.screen.observationScope != request.observationScope) return DeviceToolResult(false,
            "First observe this window with image_region, then use that exact image_screen_id and observation_scope. No action was dispatched.",
            errorCode = "screen_region_baseline_unavailable", conditionMatched = false, actionOutcome = DeviceActionOutcome.NotDispatched)
        val started = nowMillis()
        var latest: DeviceToolResult? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val sampled = nowMillis()
            val remaining = request.timeoutMillis - (sampled - started)
            val result = if (remaining > 0) withTimeoutOrNull(remaining) { capture() } else null
            if (result == null) return (latest ?: DeviceToolResult(false, "No completed frame was available.")).copy(
                success = false, errorCode = "screen_condition_timeout", summary = "The bounded region observation timed out. No action was replayed.",
                conditionMatched = false, actionOutcome = DeviceActionOutcome.Unverified)
            latest = result
            val screen = result.screen
            val image = result.screenImage
            if (!result.success || image == null || screen == null) return result.copy(success = false,
                errorCode = result.errorCode ?: result.imageErrorCode ?: "screen_region_unavailable",
                conditionMatched = false, actionOutcome = DeviceActionOutcome.Unverified)
            if (screen.sensitive || screen.packageName != baseline.screen.packageName || screen.windowId != baseline.screen.windowId ||
                screen.geometry != baseline.screen.geometry || screen.interactionContext != baseline.screen.interactionContext ||
                screen.observationScope != baseline.screen.observationScope || screen.observationRootPath != baseline.screen.observationRootPath ||
                image.region != baseline.image.region || image.geometry != baseline.image.geometry || image.captureBounds != baseline.image.captureBounds ||
                image.sourceWidth != baseline.image.sourceWidth || image.sourceHeight != baseline.image.sourceHeight) return result.copy(success = false,
                errorCode = "screen_region_target_changed", summary = "The observed window, crop or interaction context changed; pixel comparison stopped. Observe again. No action was replayed.",
                conditionMatched = false, actionOutcome = DeviceActionOutcome.Unverified)
            val changed = try { difference(baseline.image, image) >= MinChangedFraction }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { return result.copy(success = false, errorCode = "screen_region_comparison_unavailable",
                summary = "The region pixels could not be compared. Inspect the evidence before continuing; no action was replayed.",
                conditionMatched = false, actionOutcome = DeviceActionOutcome.Unverified) }
            val elapsed = nowMillis() - started
            if (condition.matches(screen, regionChanged = changed) && elapsed <= request.timeoutMillis) return result.copy(
                summary = "At least 1% of sampled pixels in the exact observed crop changed after ${elapsed}ms. This proves regional visual progress only; verify the requested business result separately.",
                conditionMatched = true, actionOutcome = DeviceActionOutcome.ProgressObserved)
            if (elapsed >= request.timeoutMillis) return result.copy(success = false, errorCode = "screen_condition_timeout",
                summary = "The requested region change was not verified within ${request.timeoutMillis}ms. No action was replayed.",
                conditionMatched = false, actionOutcome = DeviceActionOutcome.Unverified)
            delay(minOf((PollMillis - (nowMillis() - sampled)).coerceAtLeast(1), request.timeoutMillis - elapsed))
        }
    }

    private companion object { const val PollMillis = 550L; const val MinChangedFraction = .01 }
}
