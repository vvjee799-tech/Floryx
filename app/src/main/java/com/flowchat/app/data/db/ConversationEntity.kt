package com.flowchat.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ThinkingEffort
import androidx.room.ColumnInfo

@Entity(
    tableName = "conversations",
    indices = [Index("providerId")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String,
    val modelName: String,
    val assistantName: String,
    val assistantAvatarPath: String?,
    val showAvatars: Boolean,
    val enableThinking: Boolean,
    val systemPrompt: String,
    val temperature: Double,
    val topP: Double,
    val maxTokens: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'auto'") val thinkingEffort: String = "auto",
    val contextCheckpoint: String? = null
)

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    providerId = providerId,
    modelName = modelName,
    assistantName = assistantName,
    assistantAvatarPath = assistantAvatarPath,
    showAvatars = showAvatars,
    enableThinking = enableThinking,
    systemPrompt = systemPrompt,
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    createdAt = createdAt,
    updatedAt = updatedAt,
    thinkingEffort = ThinkingEffort.fromStored(thinkingEffort)
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    providerId = providerId,
    modelName = modelName,
    assistantName = assistantName,
    assistantAvatarPath = assistantAvatarPath,
    showAvatars = showAvatars,
    enableThinking = enableThinking,
    systemPrompt = systemPrompt,
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    createdAt = createdAt,
    updatedAt = updatedAt,
    thinkingEffort = thinkingEffort.wireValue
)
