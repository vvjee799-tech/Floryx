package com.flowchat.app.diagnostics

import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.data.network.ChatCompletionClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** In-process, debug-only access for opt-in attachment diagnostics. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AttachmentDiagnosticsEntryPoint {
    fun chats(): ChatRepository
    fun providers(): ProviderRepository
    fun client(): ChatCompletionClient
}
