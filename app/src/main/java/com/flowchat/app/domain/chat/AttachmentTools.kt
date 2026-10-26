package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolDefinition
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Reads a bounded page of text already stored on a user message in this conversation. */
object AttachmentTools {
    const val Read = "read_attachment"
    const val MaxPageCharacters = 6_000

    fun attach(request: ChatRequest): ChatRequest {
        if (request.tools.any { it.name == Read }) return request
        return request.copy(
            tools = request.tools + ChatToolDefinition(
                name = Read,
                description = "Read another page of a text attachment stored in this conversation. Use message_id from an attached-file preview, or omit it for the latest readable attachment. Offsets count stored characters. This is read-only and cannot read images, private reasoning, or another conversation.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("message_id") {
                            put("type", "string")
                            put("description", "The local message_id of the attachment. Omit for the latest readable text attachment.")
                        }
                        putJsonObject("offset") {
                            put("type", "integer")
                            put("minimum", 0)
                            put("description", "Zero-based character offset; defaults to 0. Use next_offset to continue.")
                        }
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", MaxPageCharacters)
                            put("description", "Maximum stored characters to return; defaults to $MaxPageCharacters.")
                        }
                    }
                    putJsonArray("required") { }
                    put("additionalProperties", false)
                }
            ),
            toolChoice = request.toolChoice ?: "auto",
            parallelToolCalls = request.parallelToolCalls ?: false
        )
    }

    fun read(arguments: String, conversationId: String, messages: List<Message>): String {
        val args = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
            ?: return error("invalid_arguments")
        if (args.keys.any { it !in AllowedArguments }) return error("invalid_arguments")
        val messageId = args["message_id"]?.let { element ->
            val primitive = element as? JsonPrimitive ?: return error("invalid_message_id")
            if (!primitive.isString || primitive.content.isBlank() || primitive.content.length > 128) {
                return error("invalid_message_id")
            }
            primitive.content
        }
        val offset = integerArgument(args, "offset", 0) ?: return error("invalid_offset")
        val limit = integerArgument(args, "limit", MaxPageCharacters) ?: return error("invalid_limit")
        if (offset < 0 || limit !in 1..MaxPageCharacters) return error(if (offset < 0) "invalid_offset" else "invalid_limit")

        val message = if (messageId != null) {
            messages.firstOrNull { it.id == messageId && it.conversationId == conversationId && it.role == MessageRole.User }
        } else {
            messages.lastOrNull {
                it.conversationId == conversationId && it.role == MessageRole.User &&
                    it.attachmentImagePath == null && !it.attachmentText.isNullOrBlank()
            }
        } ?: return error("not_found_in_this_conversation")
        val text = message.attachmentText?.takeIf { it.isNotBlank() && message.attachmentImagePath == null }
            ?: return error("attachment_not_readable")
        if (offset >= text.length || (offset > 0 && text[offset].isLowSurrogate())) {
            return error("invalid_offset")
        }

        var end = (offset.toLong() + limit).coerceAtMost(text.length.toLong()).toInt()
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        if (end == offset) return error("invalid_limit")
        return buildJsonObject {
            put("message_id", message.id)
            put("name", message.attachmentName.orEmpty().take(MaxNameCharacters))
            put("offset", offset)
            put("text", text.substring(offset, end))
            put("total", text.length)
            put("has_more", end < text.length)
            put("next_offset", if (end < text.length) JsonPrimitive(end) else JsonNull)
            put("source_truncated", text.endsWith(TruncationMarker))
        }.toString()
    }

    private fun integerArgument(args: JsonObject, name: String, default: Int): Int? {
        val value = args[name] ?: return default
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.takeUnless { it.isString }?.intOrNull
    }

    private fun error(code: String): String = buildJsonObject { put("error", code) }.toString()

    private val AllowedArguments = setOf("message_id", "offset", "limit")
    private const val MaxNameCharacters = 256
    private const val TruncationMarker = "\n\n<content-truncated />"
}
