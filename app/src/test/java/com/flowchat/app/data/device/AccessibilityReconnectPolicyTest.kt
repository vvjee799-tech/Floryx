package com.flowchat.app.data.device

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityReconnectPolicyTest {
    private val pkg = "com.floryx.app.debug"

    @Test fun selectsReconnectWhenConfiguredRemainsSelectedAfterUserStop() {
        val exit = ProcessExitSnapshot(
            pid = 4200,
            reason = ApplicationExitInfo.REASON_USER_REQUESTED,
            timestampMillis = 20_000L,
            description = "fully stop $pkg/0 by user request"
        )

        val action = AccessibilityReconnectPolicy.decide(
            packageName = pkg,
            powerModeEnabled = true,
            serviceBound = false,
            configuredSelected = true,
            latestExit = exit,
            receipt = AccessibilityReconnectReceipt()
        )

        assertEquals(AccessibilityRecoveryAction.ReconnectSelected, action)
    }

    @Test fun selectsSingleCleanerRestoreWithMatchingAuthorizedPid() {
        val exit = singleCleanerExit(pid = 17776, timestampMillis = 50_000L)
        val receipt = AccessibilityReconnectReceipt(authorizedPid = 17776, authorizedAtMillis = 10_000L)

        val action = AccessibilityReconnectPolicy.decide(
            packageName = pkg,
            powerModeEnabled = true,
            serviceBound = false,
            configuredSelected = null,
            latestExit = exit,
            receipt = receipt
        )

        assertEquals(AccessibilityRecoveryAction.RestoreSingleCleaner, action)
    }

    @Test fun allowsSingleCleanerRevocationWithinRaceWindow() {
        val exit = singleCleanerExit(pid = 17776, timestampMillis = 50_000L)
        for (revokedAt in listOf(48_000L, 49_950L, 50_500L)) {
            val receipt = AccessibilityReconnectReceipt(
                authorizedPid = 17776,
                authorizedAtMillis = 10_000L,
                revokedPid = 17776,
                revokedAtMillis = revokedAt
            )
            assertEquals(
                "Expected restore for revokedAt=$revokedAt",
                AccessibilityRecoveryAction.RestoreSingleCleaner,
                AccessibilityReconnectPolicy.decide(pkg, true, false, false, exit, receipt)
            )
        }
    }

    @Test fun rejectsManualDisableBeforeSwipe() {
        val exit = singleCleanerExit(pid = 17776, timestampMillis = 50_000L)
        val receipt = AccessibilityReconnectReceipt(
            authorizedPid = 17776,
            authorizedAtMillis = 10_000L,
            revokedPid = 17776,
            revokedAtMillis = 47_999L
        )

        val action = AccessibilityReconnectPolicy.decide(pkg, true, false, null, exit, receipt)

        assertEquals(AccessibilityRecoveryAction.None, action)
    }

    @Test fun rejectsNonSingleCleanerNonUserRequestedPidMismatchAndClockRewind() {
        val validReceipt = AccessibilityReconnectReceipt(authorizedPid = 17776, authorizedAtMillis = 50_000L)
        val cases = listOf(
            ProcessExitSnapshot(17776, ApplicationExitInfo.REASON_USER_REQUESTED, 60_000L, "fully stop $pkg/0 by user request"),
            ProcessExitSnapshot(17776, ApplicationExitInfo.REASON_USER_REQUESTED, 60_000L, "stop $pkg due to force-stop"),
            ProcessExitSnapshot(17776, ApplicationExitInfo.REASON_PACKAGE_UPDATED, 60_000L, "stop $pkg due to single-cleaner"),
            ProcessExitSnapshot(17776, ApplicationExitInfo.REASON_USER_REQUESTED, 60_000L, "stop other.app due to single-cleaner"),
            ProcessExitSnapshot(99999, ApplicationExitInfo.REASON_USER_REQUESTED, 60_000L, "stop $pkg due to single-cleaner"),
            ProcessExitSnapshot(17776, ApplicationExitInfo.REASON_USER_REQUESTED, 48_999L, "stop $pkg due to single-cleaner")
        )
        for (exit in cases) {
            assertEquals(
                "Expected None for $exit",
                AccessibilityRecoveryAction.None,
                AccessibilityReconnectPolicy.decide(pkg, true, false, null, exit, validReceipt)
            )
        }
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(
                pkg,
                true,
                false,
                null,
                singleCleanerExit(17776, 60_000L),
                AccessibilityReconnectReceipt(authorizedPid = 0, authorizedAtMillis = 50_000L)
            )
        )
    }

    @Test fun handlesExitOnlyOnceAndRespectsPowerModeOrBoundService() {
        val exit = singleCleanerExit(pid = 17776, timestampMillis = 60_000L)
        val handledReceipt = AccessibilityReconnectReceipt(
            authorizedPid = 17776,
            authorizedAtMillis = 50_000L,
            handledExitPid = 17776,
            handledExitTimestampMillis = 60_000L
        )

        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(pkg, true, false, null, exit, handledReceipt)
        )
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(
                pkg,
                powerModeEnabled = false,
                serviceBound = false,
                configuredSelected = true,
                latestExit = exit,
                receipt = AccessibilityReconnectReceipt(authorizedPid = 17776, authorizedAtMillis = 50_000L)
            )
        )
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(
                pkg,
                powerModeEnabled = true,
                serviceBound = true,
                configuredSelected = true,
                latestExit = exit,
                receipt = AccessibilityReconnectReceipt(authorizedPid = 17776, authorizedAtMillis = 50_000L)
            )
        )
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(pkg, true, false, true, null, AccessibilityReconnectReceipt())
        )
    }

    @Test fun reconnectsStillSelectedServiceAfterUnexpectedProcessDeath() {
        for (reason in AccessibilityReconnectPolicy.UnexpectedDeathReasons) {
            val exit = ProcessExitSnapshot(pid = 5100, reason = reason, timestampMillis = 80_000L, description = null)
            assertEquals(
                "Expected reconnect for reason=$reason",
                AccessibilityRecoveryAction.ReconnectSelected,
                AccessibilityReconnectPolicy.decide(pkg, true, false, true, exit, AccessibilityReconnectReceipt())
            )
        }
    }

    @Test fun neverRestoresClearedSelectionAfterUnexpectedProcessDeath() {
        val receipt = AccessibilityReconnectReceipt(authorizedPid = 5100, authorizedAtMillis = 10_000L)
        for (configured in listOf(false, null)) {
            for (reason in AccessibilityReconnectPolicy.UnexpectedDeathReasons) {
                val exit = ProcessExitSnapshot(5100, reason, 80_000L, "stop $pkg due to single-cleaner")
                assertEquals(
                    "Expected None for reason=$reason configured=$configured",
                    AccessibilityRecoveryAction.None,
                    AccessibilityReconnectPolicy.decide(pkg, true, false, configured, exit, receipt)
                )
            }
        }
    }

    @Test fun ignoresExitReasonsTiedToPermissionPackageOrUserState() {
        val reasons = listOf(
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED
        )
        for (reason in reasons) {
            val exit = ProcessExitSnapshot(5100, reason, 80_000L, null)
            assertEquals(
                "Expected None for reason=$reason",
                AccessibilityRecoveryAction.None,
                AccessibilityReconnectPolicy.decide(pkg, true, false, true, exit, AccessibilityReconnectReceipt())
            )
        }
    }

    @Test fun handlesUnexpectedDeathOnceAndStopsCrashLoops() {
        val crash = ProcessExitSnapshot(5100, ApplicationExitInfo.REASON_CRASH, 80_000L, null)
        val handledSame = AccessibilityReconnectReceipt(handledExitPid = 5100, handledExitTimestampMillis = 80_000L)
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(pkg, true, false, true, crash, handledSame)
        )

        val recentRecovery = AccessibilityReconnectReceipt(handledExitPid = 5000, handledExitTimestampMillis = 30_000L)
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(pkg, true, false, true, crash, recentRecovery)
        )

        val oldRecovery = AccessibilityReconnectReceipt(handledExitPid = 5000, handledExitTimestampMillis = 10_000L)
        assertEquals(
            AccessibilityRecoveryAction.ReconnectSelected,
            AccessibilityReconnectPolicy.decide(pkg, true, false, true, crash, oldRecovery)
        )

        val lowMemory = ProcessExitSnapshot(5100, ApplicationExitInfo.REASON_LOW_MEMORY, 80_000L, null)
        assertEquals(
            "Reclaim is not a crash loop",
            AccessibilityRecoveryAction.ReconnectSelected,
            AccessibilityReconnectPolicy.decide(pkg, true, false, true, lowMemory, recentRecovery)
        )
    }

    @Test fun unexpectedDeathStillRespectsPowerModeAndBoundService() {
        val exit = ProcessExitSnapshot(5100, ApplicationExitInfo.REASON_LOW_MEMORY, 80_000L, null)
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(pkg, false, false, true, exit, AccessibilityReconnectReceipt())
        )
        assertEquals(
            AccessibilityRecoveryAction.None,
            AccessibilityReconnectPolicy.decide(pkg, true, true, true, exit, AccessibilityReconnectReceipt())
        )
    }

    @Test fun reconnectStoreTransitionsTrackAuthorizationRevocationAndHandledExit() {
        val store = AccessibilityReconnectStore()

        store.recordAuthorized(pid = 0, nowMillis = 1_000L)
        assertEquals(AccessibilityReconnectReceipt(), store.snapshot())

        store.recordAuthorized(pid = 100, nowMillis = 2_000L)
        store.recordRevokedIfAuthorized(pid = 200, nowMillis = 3_000L)
        assertEquals(AccessibilityReconnectReceipt(authorizedPid = 100, authorizedAtMillis = 2_000L), store.snapshot())

        store.recordRevokedIfAuthorized(pid = 100, nowMillis = 4_000L)
        assertEquals(
            AccessibilityReconnectReceipt(
                authorizedPid = 100,
                authorizedAtMillis = 2_000L,
                revokedPid = 100,
                revokedAtMillis = 4_000L
            ),
            store.snapshot()
        )

        store.markExitHandled(pid = 100, timestampMillis = 5_000L)
        store.clearAuthorization()
        assertEquals(
            AccessibilityReconnectReceipt(
                handledExitPid = 100,
                handledExitTimestampMillis = 5_000L
            ),
            store.snapshot()
        )
    }

    private fun singleCleanerExit(pid: Int, timestampMillis: Long) = ProcessExitSnapshot(
        pid = pid,
        reason = ApplicationExitInfo.REASON_USER_REQUESTED,
        timestampMillis = timestampMillis,
        description = "stop $pkg due to single-cleaner"
    )
}
