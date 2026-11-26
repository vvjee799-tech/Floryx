package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Pairs fresh source-departure and whole-window reads after one acknowledged tap. Has no input API. */
internal object PageTransitionObservationVerifier {
    suspend fun complete(source: DeviceScreenSnapshot, witness: DeviceScreenSnapshot?, first: DeviceToolResult,
        dispatchAccepted: Boolean, readDeparture: suspend () -> DeviceToolResult,
        readDestination: suspend () -> DeviceToolResult, check: () -> Unit): DeviceToolResult {
        var latest = first
        var groups = 0
        var reason = "departure_unverified"
        val trace = StringBuilder()
        fun record(label: String, screen: DeviceScreenSnapshot?) {
            trace.append('\n').append(label).append('=')
            if (screen == null) { trace.append("unavailable"); return }
            // Never copy page text, selectors or editable values into diagnostic evidence.
            trace.append("id:").append(screen.screenId).append(";at:").append(screen.capturedAt)
                .append(";package:").append(screen.packageName).append(";activity:").append(screen.activityName)
                .append(";window:").append(screen.windowId).append(";geometry:").append(screen.geometry)
                .append(";context_sha256:").append(hash(screen.interactionContext.toString()))
                .append(";page_sha256:").append(screen.windowContentSha256)
                .append(";scope_sha256:").append(hash("${screen.observationScope}|${screen.elementQuery}"))
                .append(";error:").append(screen.observationErrorCode)
        }
        fun finish(result: DeviceToolResult): DeviceToolResult {
            // Successful ordinary navigation retains its original model context footprint.
            if (groups == 1 && reason == "verified") return result
            return result.copy(summary = result.summary +
            "\nnavigation_observation_groups=$groups; navigation_dispatch_count=${if (dispatchAccepted) 1 else 0}; original_action_replayed=false" +
            "\nnavigation_recheck_reason=$reason" + if (groups > 1 || reason != "verified") trace else "")
        }
        fun unknown() = finish(latest.copy(success = false, errorCode = latest.errorCode ?: "ui_navigation_unverified",
            actionOutcome = DeviceActionOutcome.Unverified, pageTransition = null,
            summary = "The dispatched action's navigation result could not be verified in bounded comparable observations. " +
                "Do not replay it or treat navigation as a save/send receipt."))
        if (!dispatchAccepted || !DevicePageTransitionPolicy.sourceMatches(source, witness)) return unknown()
        record("navigation_source", source); record("navigation_witness", witness)
        val baselineTime = maxOf(source.capturedAt, requireNotNull(witness).capturedAt)
        var previousTime = baselineTime
        val verified = withTimeoutOrNull(3_600L) {
            repeat(3) { group ->
                currentCoroutineContext().ensureActive(); check()
                if (group > 0) latest = readDeparture()
                currentCoroutineContext().ensureActive(); check()
                val departure = latest.screen
                record("navigation_departure_${group + 1}", departure)
                if (departure == null || departure.capturedAt <= previousTime) {
                    reason = "departure_stale_or_unavailable"; return@withTimeoutOrNull unknown()
                }
                if (!DevicePageTransitionPolicy.needsWindowObservation(source, departure) ||
                    !sameDisplay(source, departure)) {
                    reason = "departure_unverified"; return@withTimeoutOrNull unknown()
                }
                groups++
                latest = readDestination()
                currentCoroutineContext().ensureActive(); check()
                val destination = latest.screen
                record("navigation_destination_${group + 1}", destination)
                if (destination != null && (destination.capturedAt <= departure.capturedAt || !sameDisplay(source, destination))) {
                    reason = "destination_stale_or_display_changed"; return@withTimeoutOrNull unknown()
                }
                reason = DevicePageTransitionPolicy.rejectionReason(source, witness, departure, latest) ?: "verified"
                trace.append("\nnavigation_pair_").append(group + 1).append("_reason=").append(reason)
                if (reason == "verified") return@withTimeoutOrNull finish(requireNotNull(
                    DevicePageTransitionPolicy.complete(source, witness, departure, latest)))
                // Scope ambiguity, sensitive/secure failures and invalid ownership never permit a wider read.
                if (destination?.sensitive == true || latest.errorCode !in setOf(null, "screen_unavailable",
                        "background_screen_unavailable", "screen_changed_during_capture") ||
                    reason !in setOf("destination_unavailable", "destination_incomplete", "window_changed_between_reads",
                        "geometry_changed_between_reads", "interaction_context_changed_between_reads", "content_changed_between_reads"))
                    return@withTimeoutOrNull unknown()
                previousTime = destination?.capturedAt ?: departure.capturedAt
            }
            unknown()
        }
        return verified ?: run { reason = "observation_time_limit"; unknown() }
    }

    private fun sameDisplay(source: DeviceScreenSnapshot, screen: DeviceScreenSnapshot): Boolean {
        val before = source.geometry ?: return false
        val after = screen.geometry ?: return false
        return before.displayId == after.displayId && before.displayWidth == after.displayWidth &&
            before.displayHeight == after.displayHeight && before.rotation == after.rotation
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
