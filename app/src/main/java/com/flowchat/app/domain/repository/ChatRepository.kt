package com.flowchat.app.domain.repository

import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.chat.TaskCheckpoint
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun getConversation(id: String): Conversation?
    suspend fun getMessages(conversationId: String): List<Message>
    /** Reconcile unfinished messages from a previous process before starting a new task. */
    suspend fun recoverInterruptedTasks(): Set<String> = emptySet()
    suspend fun updateExecutionContext(id: String, context: String)
    suspend fun getContextCheckpoint(conversationId: String): TaskCheckpoint?
    suspend fun saveContextCheckpoint(checkpoint: TaskCheckpoint, expected: TaskCheckpoint?): Boolean
    suspend fun createConversation(providerId: String, modelName: String): Conversation
    suspend fun updateConversationWorkspace(id: String, workspace: com.flowchat.app.domain.workspace.WorkspaceLocation?) {
        error("Workspace settings are unavailable.")
    }
    suspend fun updateConversationSettings(
        id: String,
        assistantName: String,
        assistantAvatarPath: String?,
        showAvatars: Boolean,
        enableThinking: Boolean,
        systemPrompt: String,
        temperature: Double,
        topP: Double,
        maxTokens: Int?,
        thinkingEffort: com.flowchat.app.domain.model.ThinkingEffort = com.flowchat.app.domain.model.ThinkingEffort.Auto
    )
    suspend fun updateThinkingSettings(id: String, enabled: Boolean, effort: com.flowchat.app.domain.model.ThinkingEffort)
    suspend fun updateConversationModel(id: String, providerId: String, modelName: String)
    suspend fun updateConversationTitle(id: String, title: String)
    suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
        modelName: String?,
        attachmentName: String? = null,
        attachmentText: String? = null,
        attachmentImagePath: String? = null,
        attachmentMimeType: String? = null
    ): Message
    suspend fun updateMessage(
        id: String,
        content: String,
        status: MessageStatus,
        reasoningContent: String = "",
        reasoningDurationMillis: Long? = null,
        replyPhase: com.flowchat.app.domain.model.ReplyPhase? = null
    )
    suspend fun markMessageFailed(id: String, error: String)
    suspend fun deleteConversation(id: String)
}
