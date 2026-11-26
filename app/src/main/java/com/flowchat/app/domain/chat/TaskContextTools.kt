package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolDefinition
import com.flowchat.app.domain.model.Message
import kotlinx.serialization.json.*

object TaskContextTools {
    const val Read = "read_task_history"
    private const val MaxContentPageCharacters = 4_000

    fun attach(request: ChatRequest) = request.copy(tools = request.tools + ChatToolDefinition(Read,
        "Read stored records from this conversation by source IDs cited in its task checkpoint. The default result returns excerpts. " +
            "For omitted message text, use one ID in message_ids with content_offset=0, then follow next_content_offset until null. " +
            "For attachment pages, use read_attachment with that message ID. Stored tool evidence may already contain omissions. " +
            "This is read-only and excludes private reasoning and other conversations.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("message_ids") {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", 4)
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("content_offset") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("description", "Zero-based UTF-16 offset in the stored message body. Supply exactly one message ID.")
                }
            }
            putJsonArray("required") { add("message_ids") }
            put("additionalProperties", false)
        }))

    fun read(arguments: String, conversationId: String, messages: List<Message>): String {
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val ids = runCatching {
            args?.get("message_ids")?.jsonArray?.map { it.jsonPrimitive.content }?.distinct()
        }.getOrNull()
        if (ids.isNullOrEmpty() || ids.size > 4) return "failed: supply 1-4 message_ids from this conversation's checkpoint."
        val contentOffset = args?.get("content_offset")?.let { value ->
            val offset = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (ids.size != 1 || offset == null || offset < 0) {
                return "failed: content_offset requires one message_id and a non-negative integer offset."
            }
            offset
        }
        val recordBudget = 9_000 / ids.size
        return buildJsonObject {
            put("historical_data_only", true)
            putJsonArray("records") { ids.forEach { id ->
                val message = messages.firstOrNull { it.id == id && it.conversationId == conversationId }
                add(if (message == null) buildJsonObject { put("id", id); put("error", "not_found_in_this_conversation") }
                else buildJsonObject {
                    put("id", id)
                    put("role", message.role.name)
                    put("status", message.status.name)
                    if (contentOffset != null) {
                        putContentPage(message.content, contentOffset)
                    } else {
                        val contentLimit = recordBudget / 4
                        put("content", CheckpointCodec.excerpt(message.content, contentLimit))
                        if (message.content.length > contentLimit) put("content_length", message.content.length)
                        message.attachmentText?.let { put("attachment_excerpt", CheckpointCodec.excerpt(it, contentLimit)) }
                        message.executionContext?.let { put("tool_evidence", CheckpointCodec.excerpt(it, recordBudget / 2)) }
                    }
                })
            } }
        }.toString()
    }

    private fun JsonObjectBuilder.putContentPage(text: String, offset: Int) {
        put("content_length", text.length)
        if (offset !in 0..text.length || text.splitsCharacterAt(offset)) {
            put("error", "invalid_content_offset")
            return
        }
        val end = offset + minOf(MaxContentPageCharacters, text.length - offset)
        val pageEnd = if (text.splitsCharacterAt(end)) end - 1 else end
        put("content_offset", offset)
        put("content", text.substring(offset, pageEnd))
        put("next_content_offset", if (pageEnd < text.length) JsonPrimitive(pageEnd) else JsonNull)
    }

    private fun String.splitsCharacterAt(offset: Int): Boolean =
        offset > 0 && offset < length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()
}
