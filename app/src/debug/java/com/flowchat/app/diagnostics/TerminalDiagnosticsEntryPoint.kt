package com.flowchat.app.diagnostics

import com.flowchat.app.data.terminal.TerminalManager
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.DeviceAssistantGateway
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TerminalDiagnosticsEntryPoint {
    fun terminal(): TerminalManager
    fun settings(): AppSettingsStore
    fun device(): DeviceAssistantGateway
}
