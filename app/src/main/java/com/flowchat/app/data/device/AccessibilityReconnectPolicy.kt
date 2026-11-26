package com.flowchat.app.data.device

import android.annotation.SuppressLint
import android.app.ApplicationExitInfo

internal data class AccessibilityReconnectReceipt(
    val authorizedPid: Int = 0,
    val authorizedAtMillis: Long = 0L,
    val revokedPid: Int = 0,
    val revokedAtMillis: Long = 0L,
    val handledExitPid: Int = 0,
    val handledExitTimestampMillis: Long = 0L
)

internal data class ProcessExitSnapshot(
    val pid: Int,
    val reason: Int,
    val timestampMillis: Long,
    val description: String?
)

internal enum class AccessibilityRecoveryAction {
    None,
    ReconnectSelected,
    RestoreSingleCleaner
}

/**
 * Decides whether a stopped Floryx process can reconnect an already-selected accessibility
 * service or restore one cleared alongside an individual Recents swipe (`single-cleaner`).
 *
 * Besides user stops, an unexpected death of the hosting process (crash, ANR, low memory,
 * signal, freezer or other system reclaim) also leaves the still-selected service in the
 * platform's crashed set, where Android never rebinds it on its own. Reconnecting such a
 * service keeps the user's existing selection and never restores a cleared one.
 */
// ApplicationExitInfo reason constants are compile-time inlined ints; every caller reads exit reasons
// only on API 30+ (ShizukuDeviceAssistantGateway.reconnectAccessibilityAfterStop), so nothing runs below R.
@SuppressLint("InlinedApi")
internal object AccessibilityReconnectPolicy {
    const val ClockSkewToleranceMillis = 1_000L
    const val CleanerRevocationRaceWindowMillis = 2_000L
    const val CrashLoopWindowMillis = 60_000L

    // ApplicationExitInfo.REASON_FREEZER is API 33; the value is stable platform API.
    private const val ReasonFreezer = 14

    /** Process deaths the user did not request that keep the service selected but marked crashed. */
    val UnexpectedDeathReasons: Set<Int> = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_OTHER,
        ReasonFreezer
    )

    private val CrashReasons: Set<Int> = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR
    )

    fun decide(
        packageName: String,
        powerModeEnabled: Boolean,
        serviceBound: Boolean,
        configuredSelected: Boolean?,
        latestExit: ProcessExitSnapshot?,
        receipt: AccessibilityReconnectReceipt
    ): AccessibilityRecoveryAction {
        if (!powerModeEnabled || serviceBound || latestExit == null) {
            return AccessibilityRecoveryAction.None
        }
        if (latestExit.reason in UnexpectedDeathReasons) {
            return if (configuredSelected == true && !alreadyHandledOrCrashLoop(latestExit, receipt)) {
                AccessibilityRecoveryAction.ReconnectSelected
            } else {
                AccessibilityRecoveryAction.None
            }
        }
        if (latestExit.reason != ApplicationExitInfo.REASON_USER_REQUESTED) {
            return AccessibilityRecoveryAction.None
        }
        return when {
            configuredSelected == true -> AccessibilityRecoveryAction.ReconnectSelected
            canRestoreAfterSingleCleaner(packageName, latestExit, receipt) ->
                AccessibilityRecoveryAction.RestoreSingleCleaner
            else -> AccessibilityRecoveryAction.None
        }
    }

    /** True when this exit was already handled, or a crash repeats soon after the previous recovery. */
    fun alreadyHandledOrCrashLoop(
        latestExit: ProcessExitSnapshot,
        receipt: AccessibilityReconnectReceipt
    ): Boolean {
        if (receipt.handledExitPid == latestExit.pid &&
            receipt.handledExitTimestampMillis == latestExit.timestampMillis
        ) {
            return true
        }
        if (latestExit.reason !in CrashReasons || receipt.handledExitTimestampMillis <= 0L) return false
        val sinceLastRecovery = latestExit.timestampMillis - receipt.handledExitTimestampMillis
        return sinceLastRecovery in 0 until CrashLoopWindowMillis
    }

    fun isSingleCleanerExit(packageName: String, reason: Int, description: String?): Boolean {
        if (reason != ApplicationExitInfo.REASON_USER_REQUESTED) return false
        val normalized = description?.trim() ?: return false
        return normalized.contains(packageName, ignoreCase = true) &&
            normalized.contains("single-cleaner", ignoreCase = true)
    }

    fun canRestoreAfterSingleCleaner(
        packageName: String,
        latestExit: ProcessExitSnapshot,
        receipt: AccessibilityReconnectReceipt
    ): Boolean {
        if (!isSingleCleanerExit(packageName, latestExit.reason, latestExit.description)) return false
        if (receipt.authorizedPid <= 0 || receipt.authorizedPid != latestExit.pid) return false
        if (latestExit.timestampMillis < receipt.authorizedAtMillis - ClockSkewToleranceMillis) return false
        if (receipt.handledExitPid == latestExit.pid &&
            receipt.handledExitTimestampMillis == latestExit.timestampMillis
        ) {
            return false
        }
        if (receipt.revokedPid == latestExit.pid) {
            val delta = latestExit.timestampMillis - receipt.revokedAtMillis
            if (delta !in -ClockSkewToleranceMillis..CleanerRevocationRaceWindowMillis) {
                return false
            }
        }
        return true
    }
}
