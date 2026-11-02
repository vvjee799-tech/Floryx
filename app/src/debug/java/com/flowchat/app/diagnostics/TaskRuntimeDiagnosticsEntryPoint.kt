package com.flowchat.app.diagnostics

import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TaskRuntimeDiagnosticsEntryPoint {
    fun runtime(): ChatTaskRuntime
    fun journal(): TaskRunJournal
    fun root(): RootDeviceConnection
    fun settings(): AppSettingsStore
    fun deviceGateway(): com.flowchat.app.domain.device.DeviceAssistantGateway
    fun deviceTasks(): com.flowchat.app.domain.device.DeviceTaskControl
}
