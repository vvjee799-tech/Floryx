package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceGestureLeg
import com.flowchat.app.domain.device.DeviceGesturePoint
import kotlinx.coroutines.*

/** Platform tokens must continue an existing stroke; release must never inject a fresh DOWN. */
internal class OwnedGestureRunner<T>(
    private val dispatch: (DeviceGestureLeg, T?) -> Pending<T>,
    private val check: suspend () -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    data class Pending<T>(val token: T, val completion: Deferred<Boolean>, val accepted: Boolean = true)
    var holdingTouch: Boolean = false
        private set

    suspend fun run(legs: List<DeviceGestureLeg>, betweenTapsMillis: Long = 80): Boolean {
        var owned: T? = null
        var point: DeviceGesturePoint? = null
        try {
            for ((index, leg) in legs.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (index > 0 && owned == null) delay(betweenTapsMillis)
                check(); currentCoroutineContext().ensureActive()
                // Android rejects an unchanged continuation because it generates no MotionEvents.
                // Keep the original pointer down while waiting instead of injecting empty strokes.
                if (owned != null && leg.keepDown && leg.from == point && leg.to == point) {
                    delay(leg.durationMillis)
                    continue
                }
                val started = nowMillis()
                val pending = dispatch(leg, owned)
                if (!pending.accepted) return false
                // A cancellation must not discard the callback that tells us whether a pointer is held.
                owned = pending.token.takeIf { leg.keepDown }; point = leg.to; holdingTouch = leg.keepDown
                val finished = withContext(NonCancellable) { withTimeoutOrNull(leg.durationMillis + 800) { pending.completion.await() } }
                if (finished == false) { owned = null; holdingTouch = false; return false }
                if (!leg.keepDown && finished == true) { owned = null; holdingTouch = false }
                if (finished == null && !leg.keepDown) { holdingTouch = true; error("ui_touch_release_unverified") }
                currentCoroutineContext().ensureActive()
                if (finished == null) return false
                if (leg.keepDown) {
                    val remaining = leg.durationMillis - (nowMillis() - started)
                    if (remaining > 0) delay(remaining)
                }
            }
            return true
        } finally {
            if (owned != null) withContext(NonCancellable) {
                val end = requireNotNull(point)
                val release = dispatch(DeviceGestureLeg(end, end, 1, false), owned)
                val ended = if (release.accepted) withTimeoutOrNull(800) { release.completion.await() } else null
                holdingTouch = ended == null
                if (ended == null) error("ui_touch_release_unverified")
            }
        }
    }
}
