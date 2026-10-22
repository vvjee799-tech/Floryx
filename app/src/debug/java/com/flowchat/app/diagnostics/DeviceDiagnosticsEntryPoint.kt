package com.flowchat.app.diagnostics

import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** In-process instrumentation access only; not an exported Android component. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeviceDiagnosticsEntryPoint {
    fun bridge(): FloatingAssistantBridge
    fun gateway(): DeviceAssistantGateway
    fun launcher(): AppLauncher
}
