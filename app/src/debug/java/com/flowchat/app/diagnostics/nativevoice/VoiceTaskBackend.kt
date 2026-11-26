package com.flowchat.app.diagnostics.nativevoice

import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.presentation.chat.ChatTaskRuntime

internal interface VoiceTaskBackend {
    val runtimeMode: String
    val chats: ChatRepository
    val journal: TaskRunJournal
    val runtime: ChatTaskRuntime

    suspend fun createConversation(): Conversation
    suspend fun reusableConversation(owner: String): Conversation? = null
    suspend fun submit(conversation: Conversation, text: String): Boolean
    fun isWaitingForUnlock(owner: String): Boolean = false
    fun isWaitingForUser(owner: String): Boolean = false
    fun waitingForUserPrompt(owner: String): String? = null
    suspend fun resume(owner: String): Boolean = false
    fun isPanelYieldRequested(owner: String): Boolean = false
    fun ackPanelYield(owner: String) = Unit
    fun clearPanelYield(owner: String) = Unit
}
