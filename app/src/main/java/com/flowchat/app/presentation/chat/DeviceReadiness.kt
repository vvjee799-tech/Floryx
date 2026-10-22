package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionState

internal enum class DeviceReadiness { Off, NeedsSetup, ScreenOnly, Ready }

internal fun deviceReadiness(
    enabled: Boolean,
    connection: ShizukuConnectionState,
    accessibility: AccessibilityConnectionState
): DeviceReadiness = when {
    !enabled -> DeviceReadiness.Off
    !accessibility.isConnected -> DeviceReadiness.NeedsSetup
    !connection.isConnected -> DeviceReadiness.ScreenOnly
    else -> DeviceReadiness.Ready
}
