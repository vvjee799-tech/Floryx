package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.device.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReadinessTest {
    private val safe = DeviceSafetyState(screenLocked = false, screenInteractive = true, checkedAtMillis = 100L)

    @Test fun connectionDoesNotMeanAutomationIsEnabledOrReady() {
        val root = ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot, lastVerifiedAtMillis = 100L)
        val connected = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
        val disabled = AccessibilityConnectionState()
        assertEquals(DeviceReadiness.Off, deviceReadiness(false, root, connected, safe))
        assertEquals(DeviceReadiness.NeedsSetup, deviceReadiness(true, root, disabled, safe))
        assertEquals(DeviceReadiness.Ready, deviceReadiness(true, root, connected, safe))
        assertEquals(DeviceReadiness.Ready, deviceReadiness(true,
            root.copy(status = ShizukuConnectionStatus.RootReady), connected, safe))
        assertEquals(DeviceReadiness.ScreenOnly, deviceReadiness(true, ShizukuConnectionState(), connected, safe))
        assertEquals(DeviceReadiness.ScreenOnly, deviceReadiness(true, root.copy(lastVerifiedAtMillis = null), connected, safe))
    }

    @Test fun lockedScreenOffAndUnknownSafetyNeverReportReady() {
        val root = ShizukuConnectionState(ShizukuConnectionStatus.RootReady, lastVerifiedAtMillis = 100L)
        val connected = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
        assertEquals(DeviceReadiness.Paused, deviceReadiness(true, root, connected, safe.copy(screenLocked = true)))
        assertEquals(DeviceReadiness.Paused, deviceReadiness(true, root, connected, safe.copy(screenInteractive = false)))
        assertEquals(DeviceReadiness.Unverified, deviceReadiness(true, root, connected))
    }
}
