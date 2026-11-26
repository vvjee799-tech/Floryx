package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.DeviceSafetyState
import com.flowchat.app.domain.device.ShizukuConnectionState

internal enum class DeviceReadiness { Off, NeedsSetup, ScreenOnly, Ready, Paused, Unverified }

internal fun deviceReadiness(
    enabled: Boolean,
    connection: ShizukuConnectionState,
    accessibility: AccessibilityConnectionState,
    safety: DeviceSafetyState = DeviceSafetyState()
): DeviceReadiness = when {
    !enabled -> DeviceReadiness.Off
    safety.screenLocked == true || safety.screenInteractive == false -> DeviceReadiness.Paused
    safety.screenLocked == null || safety.screenInteractive == null -> DeviceReadiness.Unverified
    !accessibility.isConnected -> DeviceReadiness.NeedsSetup
    !connection.isConnected || connection.lastVerifiedAtMillis == null -> DeviceReadiness.ScreenOnly
    else -> DeviceReadiness.Ready
}
