package com.flowchat.app.domain.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray

data class ChatRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    val temperature: Double,
    val topP: Double,
    val maxTokens: Int?,
    val enableThinking: Boolean = true,
    val tools: List<ChatToolDefinition> = emptyList(),
    val toolChoice: String? = null,
    val parallelToolCalls: Boolean? = null,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.Auto
)

data class ChatRequestMessage(
    val role: String,
    val content: String? = null,
    val reasoningContent: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val providerData: JsonArray? = null,
    val images: List<ChatImage> = emptyList()
)

data class ChatImage(val mimeType: String, val base64: String) {
    val dataUrl: String get() = "data:$mimeType;base64,$base64"
}

data class ChatToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ChatToolCallDelta(
    val index: Int,
    val id: String?,
    val name: String?,
    val argumentsDelta: String?
)

sealed interface ChatDelta {
    data class Content(val text: String) : ChatDelta
    data class Reasoning(val text: String) : ChatDelta
    data class FullResponse(val reasoningText: String, val contentText: String) : ChatDelta
    data class ToolCallDelta(val calls: List<ChatToolCallDelta>) : ChatDelta
    data class ToolCalls(val calls: List<ChatToolCall>) : ChatDelta
    /** Opaque provider blocks needed to continue thinking across tool calls. Never display or log. */
    data class ProviderData(val blocks: JsonArray) : ChatDelta
    data object Done : ChatDelta
    data class Error(val message: String) : ChatDelta
}
