package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceScreenSnapshot
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
    suspend fun observe(waitForChangeFrom: DeviceScreenSnapshot? = null): DeviceScreenSnapshot? {
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
                previous?.hasSameUi(waitForChangeFrom) != false && now - started < ChangeWaitMillis
            if (!waitingForChange && previous != null && now - stableSince >= QuietMillis) return previous
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
            if (current == null || previous?.hasSameUi(current) != true) stableSince = lastRead
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
