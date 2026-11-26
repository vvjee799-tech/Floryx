package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceScreenWaitRequest
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceActionOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Observes a stable control tree, including Canvas windows with no readable controls.
 * Events wake the waiter; bounded polling also handles apps that omit accessibility events.
 * This does not claim that pixels are stable or that the user's task has completed. */
internal class ScreenReadinessWaiter(
    private val readScreen: suspend () -> DeviceScreenSnapshot?,
    private val changes: StateFlow<Long>,
    private val nowMillis: () -> Long
) {
    /** A matched predicate, rather than unrelated animations, terminates this read-only wait. */
    suspend fun observeCondition(request: DeviceScreenWaitRequest, baseline: DeviceScreenSnapshot? = null): DeviceToolResult {
        val started = nowMillis()
        var revision = changes.value
        var latest: DeviceScreenSnapshot? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            latest = readScreen()
            val elapsed = nowMillis() - started
            if (latest?.sensitive == true) return DeviceToolResult(false, "A sensitive screen was detected; observation stopped.",
                errorCode = "sensitive_screen_blocked", screen = latest, actionOutcome = DeviceActionOutcome.Unverified, conditionMatched = false)
            val matched = latest?.let { screen -> request.condition?.matches(screen) ?: (baseline != null &&
                baseline.hasSameObservationScope(screen) && screen.observationErrorCode == null && !baseline.hasSameUi(screen)) } == true
            if (matched && elapsed <= request.timeoutMillis) return DeviceToolResult(true,
                "The requested screen condition was verified after ${elapsed}ms. This verifies only that condition, not the entire task.",
                screen = latest, actionOutcome = DeviceActionOutcome.TargetVerified, conditionMatched = true)
            if (elapsed >= request.timeoutMillis) return DeviceToolResult(false,
                "The requested screen condition was not verified within ${request.timeoutMillis}ms. No action was replayed. Inspect the latest evidence before deciding what to do next.",
                errorCode = "screen_condition_timeout", screen = latest, actionOutcome = DeviceActionOutcome.Unverified, conditionMatched = false)
            withTimeoutOrNull(minOf(PollMillis, request.timeoutMillis - elapsed)) { changes.first { it != revision } }
            val throttle = minOf(MinReadIntervalMillis - (nowMillis() - started - elapsed), request.timeoutMillis - (nowMillis() - started))
            if (throttle > 0) delay(throttle)
            revision = changes.value
        }
    }

    suspend fun observe(waitForChangeFrom: DeviceScreenSnapshot? = null): DeviceScreenSnapshot? {
        fun sameRead(first: DeviceScreenSnapshot?, second: DeviceScreenSnapshot): Boolean =
            first?.hasSameUi(second) == true &&
                (first.observationErrorCode == null && (first.observationScope != null || first.elementQuery != null) ||
                    first.windowContentSha256 == second.windowContentSha256)
        val started = nowMillis()
        var revision = changes.value
        var previous = readScreen()
        var stableSince = nowMillis()
        var lastRead = stableSince
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = nowMillis()
            // Home/Back may be leaving the sensitive baseline. Do not return that old window
            // before the accepted navigation has had a chance to replace it.
            if (previous?.sensitive == true && (waitForChangeFrom?.sensitive != true ||
                    !previous.hasSameUi(waitForChangeFrom))) return previous
            val waitingForChange = waitForChangeFrom != null &&
                (previous == null || sameRead(previous, waitForChangeFrom)) && now - started < ChangeWaitMillis
            if (!waitingForChange && previous != null && previous.interactionContext?.complete != false && now - stableSince >= QuietMillis) return previous
            if (now - started >= MaxWaitMillis) return previous
            val wait = minOf(PollMillis, MaxWaitMillis - (now - started),
                if (waitingForChange) ChangeWaitMillis - (now - started) else (QuietMillis - (now - stableSince)).coerceAtLeast(1))
            withTimeoutOrNull(wait) { changes.first { it != revision } }
            // Avoid rebuilding the tree on every event in a busy/animated window.
            val throttle = MinReadIntervalMillis - (nowMillis() - lastRead)
            if (throttle > 0) delay(throttle)
            revision = changes.value
            val current = readScreen()
            lastRead = nowMillis()
            if (current == null || !sameRead(previous, current)) stableSince = lastRead
            previous = current
        }
    }

    private companion object {
        const val QuietMillis = 120L
        const val PollMillis = 120L
        const val MinReadIntervalMillis = 40L
        const val ChangeWaitMillis = 700L
        const val MaxWaitMillis = 1_200L
    }
}
