package com.flowchat.app.data.db

import androidx.room.ColumnInfo
import com.flowchat.app.domain.model.ReplyPhase
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val attachmentName: String?,
    val attachmentText: String?,
    val reasoningContent: String,
    val reasoningDurationMillis: Long,
    val status: String,
    val modelName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val attachmentImagePath: String? = null,
    val attachmentMimeType: String? = null,
    @ColumnInfo(defaultValue = "'Answer'") val replyPhase: String = ReplyPhase.Answer.name
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = MessageRole.valueOf(role),
    content = content,
    attachmentName = attachmentName,
    attachmentText = attachmentText,
    reasoningContent = reasoningContent,
    reasoningDurationMillis = reasoningDurationMillis,
    status = MessageStatus.valueOf(status),
    modelName = modelName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    attachmentImagePath = attachmentImagePath,
    attachmentMimeType = attachmentMimeType,
    replyPhase = runCatching { ReplyPhase.valueOf(replyPhase) }.getOrDefault(ReplyPhase.Answer)
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    attachmentName = attachmentName,
    attachmentText = attachmentText,
    reasoningContent = reasoningContent,
    reasoningDurationMillis = reasoningDurationMillis,
    status = status.name,
    modelName = modelName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    attachmentImagePath = attachmentImagePath,
    attachmentMimeType = attachmentMimeType,
    replyPhase = replyPhase.name
)
