package com.flowchat.app.domain.model

import java.util.UUID

enum class MessageRole(val apiRole: String) {
    System("system"),
    User("user"),
    Assistant("assistant"),
    Tool("tool"),
    Error("error")
}

enum class MessageStatus {
    Pending,
    Sent,
    Streaming,
    Stopped,
    Cancelled,
    Complete,
    Failed
}

enum class ReplyPhase { Answer, Commentary }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val attachmentName: String? = null,
    val attachmentText: String? = null,
    val reasoningContent: String = "",
    val reasoningDurationMillis: Long = 0L,
    val status: MessageStatus = MessageStatus.Complete,
    val modelName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val attachmentImagePath: String? = null,
    val attachmentMimeType: String? = null,
    val replyPhase: ReplyPhase = ReplyPhase.Answer
)
