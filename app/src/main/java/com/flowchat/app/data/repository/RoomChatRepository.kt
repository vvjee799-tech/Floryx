package com.flowchat.app.data.repository

import com.flowchat.app.data.db.ConversationDao
import com.flowchat.app.data.db.MessageDao
import com.flowchat.app.data.db.toDomain
import com.flowchat.app.data.db.toEntity
import com.flowchat.app.data.attachment.ImageAttachmentStore
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.TaskCheckpoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RoomChatRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val imageAttachmentStore: ImageAttachmentStore? = null,
    private val appSettingsStore: com.flowchat.app.data.preferences.AppSettingsStore? = null
) : ChatRepository {
    private val recoveryMutex = Mutex()
    private var recoveryResult: Set<String>? = null

    override suspend fun recoverInterruptedTasks(): Set<String> = recoveryMutex.withLock {
        recoveryResult ?: messageDao.recoverInterruptedTasks(System.currentTimeMillis()).also { recoveryResult = it }
    }

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeConversations().map { rows -> rows.map { it.toDomain() } }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeMessages(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getConversation(id: String): Conversation? =
        conversationDao.getConversation(id)?.toDomain()

    override suspend fun getMessages(conversationId: String): List<Message> =
        messageDao.getMessages(conversationId).map { it.toDomain() }

    override suspend fun updateExecutionContext(id: String, context: String) {
        messageDao.updateExecutionContext(id, context)
    }

    override suspend fun getContextCheckpoint(conversationId: String): TaskCheckpoint? =
        CheckpointCodec.decode(messageDao.getContextCheckpoint(conversationId))

    override suspend fun saveContextCheckpoint(checkpoint: TaskCheckpoint, expected: TaskCheckpoint?): Boolean =
        messageDao.saveContextCheckpoint(checkpoint, expected)

    override suspend fun createConversation(providerId: String, modelName: String): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            title = "New chat",
            providerId = providerId,
            modelName = modelName,
            createdAt = now,
            updatedAt = now,
            workspace = appSettingsStore?.state?.value?.defaultWorkspace
        )
        conversationDao.upsert(conversation.toEntity())
        return conversation
    }

    override suspend fun updateConversationWorkspace(id: String, workspace: com.flowchat.app.domain.workspace.WorkspaceLocation?) {
        conversationDao.updateWorkspace(id, com.flowchat.app.domain.workspace.WorkspaceCodec.encode(workspace), System.currentTimeMillis())
    }

    override suspend fun updateConversationSettings(
        id: String,
        assistantName: String,
        assistantAvatarPath: String?,
        showAvatars: Boolean,
        enableThinking: Boolean,
        systemPrompt: String,
        temperature: Double,
        topP: Double,
        maxTokens: Int?,
        thinkingEffort: com.flowchat.app.domain.model.ThinkingEffort
    ) {
        conversationDao.updateSettings(
            id,
            assistantName,
            assistantAvatarPath,
            showAvatars,
            enableThinking,
            systemPrompt,
            temperature,
            topP,
            maxTokens,
            System.currentTimeMillis(),
            thinkingEffort.wireValue
        )
    }

    override suspend fun updateThinkingSettings(id: String, enabled: Boolean, effort: com.flowchat.app.domain.model.ThinkingEffort) {
        conversationDao.updateThinkingSettings(id, enabled, effort.wireValue, System.currentTimeMillis())
    }

    override suspend fun updateConversationModel(id: String, providerId: String, modelName: String) {
        conversationDao.updateModel(id, providerId, modelName, System.currentTimeMillis())
    }

    override suspend fun updateConversationTitle(id: String, title: String) {
        conversationDao.updateTitle(id, title, System.currentTimeMillis())
    }

    override suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
        modelName: String?,
        attachmentName: String?,
        attachmentText: String?,
        attachmentImagePath: String?,
        attachmentMimeType: String?
    ): Message {
        val now = System.currentTimeMillis()
        val message = Message(
            conversationId = conversationId,
            role = role,
            content = content,
            attachmentName = attachmentName,
            attachmentText = attachmentText,
            attachmentImagePath = attachmentImagePath,
            attachmentMimeType = attachmentMimeType,
            status = status,
            modelName = modelName,
            createdAt = now,
            updatedAt = now
        )
        messageDao.upsert(message.toEntity())
        conversationDao.touch(conversationId, now)
        return message
    }

    override suspend fun updateMessage(
        id: String,
        content: String,
        status: MessageStatus,
        reasoningContent: String,
        reasoningDurationMillis: Long?,
        replyPhase: com.flowchat.app.domain.model.ReplyPhase?
    ) {
        messageDao.updateContentReasoningAndStatus(
            id,
            content,
            reasoningContent,
            reasoningDurationMillis,
            status.name,
            System.currentTimeMillis(),
            replyPhase?.name
        )
    }

    override suspend fun markMessageFailed(id: String, error: String) {
        updateMessage(id, error, MessageStatus.Failed)
    }

    override suspend fun deleteConversation(id: String) {
        val paths = messageDao.getMessages(id).mapNotNull { it.attachmentImagePath }
        conversationDao.delete(id)
        paths.forEach { imageAttachmentStore?.discard(it) }
    }
}
