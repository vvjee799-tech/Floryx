package com.flowchat.app.diagnostics

import com.flowchat.app.presentation.assistant.AssistantPanelFactory
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemAssistantDiagnosticsEntryPoint {
    fun database(): com.flowchat.app.data.db.FlowChatDatabase
    fun panels(): AssistantPanelFactory
    fun bridge(): FloatingAssistantBridge
}
