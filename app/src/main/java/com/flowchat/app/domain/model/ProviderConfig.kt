package com.flowchat.app.domain.model

import java.util.UUID

data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val customHeadersJson: String = "{}",
    val apiKeyAlias: String? = null,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    /** Null preserves automatic discovery for existing configurations. */
    val conversationModels: List<String>? = null
)
