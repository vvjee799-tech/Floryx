package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.device.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReadinessTest {
    @Test fun connectionDoesNotMeanAutomationIsEnabledOrReady() {
        val root = ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot)
        val connected = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
        val disabled = AccessibilityConnectionState()
        assertEquals(DeviceReadiness.Off, deviceReadiness(false, root, connected))
        assertEquals(DeviceReadiness.NeedsSetup, deviceReadiness(true, root, disabled))
        assertEquals(DeviceReadiness.Ready, deviceReadiness(true, root, connected))
        assertEquals(DeviceReadiness.ScreenOnly, deviceReadiness(true, ShizukuConnectionState(), connected))
    }
}
