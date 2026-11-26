package com.flowchat.app.domain.model

import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val providerId: String,
    val modelName: String,
    val assistantName: String = "",
    val assistantAvatarPath: String? = null,
    val showAvatars: Boolean = false,
    val enableThinking: Boolean = true,
    val systemPrompt: String = "",
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxTokens: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.Auto,
    val workspace: com.flowchat.app.domain.workspace.WorkspaceLocation? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)
