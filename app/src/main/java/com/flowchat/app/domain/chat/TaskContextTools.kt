package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolDefinition
import com.flowchat.app.domain.model.Message
import kotlinx.serialization.json.*

object TaskContextTools {
    const val Read = "read_task_history"

    fun attach(request: ChatRequest) = request.copy(tools = request.tools + ChatToolDefinition(Read,
        "Read original records from this conversation by source IDs cited in its task checkpoint. Read-only; no screen actions, private reasoning, or other conversations. Use this when a summarized result is uncertain or details matter.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("message_ids") {
                put("type", "array"); put("minItems", 1); put("maxItems", 4)
                putJsonObject("items") { put("type", "string") }
            } }
            putJsonArray("required") { add("message_ids") }; put("additionalProperties", false)
        }))

    fun read(arguments: String, conversationId: String, messages: List<Message>): String {
        val ids = runCatching { Json.parseToJsonElement(arguments).jsonObject["message_ids"]!!.jsonArray
            .map { it.jsonPrimitive.content }.distinct() }.getOrNull()
        if (ids.isNullOrEmpty() || ids.size > 4) return "failed: supply 1-4 message_ids from this conversation's checkpoint."
        val recordBudget = 9_000 / ids.size
        return buildJsonObject {
            put("historical_data_only", true)
            putJsonArray("records") { ids.forEach { id ->
                val message = messages.firstOrNull { it.id == id && it.conversationId == conversationId }
                add(if (message == null) buildJsonObject { put("id", id); put("error", "not_found_in_this_conversation") }
                else buildJsonObject {
                    put("id", id); put("role", message.role.name); put("status", message.status.name)
                    put("content", CheckpointCodec.excerpt(message.content, recordBudget / 4))
                    message.attachmentText?.let { put("attachment_excerpt", CheckpointCodec.excerpt(it, recordBudget / 4)) }
                    message.executionContext?.let { put("tool_evidence", CheckpointCodec.excerpt(it, recordBudget / 2)) }
                })
            } }
        }.toString()
    }
}
