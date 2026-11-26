package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.ChatToolCallDelta
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object OpenAiStreamParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLine(line: String): ChatDelta? = parseDeltas(line).firstOrNull()

    fun parseDeltas(line: String): List<ChatDelta> {
        val trimmed = line.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return listOf(ChatDelta.Done)

        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            root.extractErrorMessage()?.let { return listOf(ChatDelta.Error(it)) }
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
            val content = choice["delta"]
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
            val reasoningContent = choice["delta"]
                ?.jsonObject
                ?.get("reasoning_content")
                ?.jsonPrimitive
                ?.contentOrNull ?: choice["delta"]?.jsonObject?.get("reasoning")?.jsonPrimitive?.contentOrNull
            val toolCallDeltas = choice["delta"]
                ?.jsonObject
                ?.extractToolCallDeltas()
                .orEmpty()
            buildList {
                if (!content.isNullOrEmpty()) add(ChatDelta.Content(content))
                if (!reasoningContent.isNullOrEmpty()) add(ChatDelta.Reasoning(reasoningContent))
                if (toolCallDeltas.isNotEmpty()) add(ChatDelta.ToolCallDelta(toolCallDeltas))
                when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
                    null -> Unit
                    "length" -> add(ChatDelta.Error("The response reached the model output limit and is incomplete."))
                    "content_filter" -> add(ChatDelta.Error("The provider stopped this response because of its content policy."))
                    else -> add(ChatDelta.Done)
                }
            }
        }.getOrElse { listOf(ChatDelta.Error("Malformed stream event")) }
    }

    fun parseCompletionBody(body: String): ChatDelta {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root.extractErrorMessage()?.let { return ChatDelta.Error(it) }
            val finish = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            if (finish == "length") return ChatDelta.Error("The response reached the model output limit and is incomplete.")
            if (finish == "content_filter") return ChatDelta.Error("The provider stopped this response because of its content policy.")
            val message = root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?: return ChatDelta.Error("Malformed response")
            val content = message["content"]?.jsonPrimitive?.contentOrNull
            val reasoningContent = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
            val toolCalls = message.extractToolCalls()
            when {
                toolCalls.isNotEmpty() -> ChatDelta.ToolCalls(toolCalls)
                !reasoningContent.isNullOrBlank() && !content.isNullOrBlank() ->
                    ChatDelta.FullResponse(reasoningText = reasoningContent, contentText = content)
                !content.isNullOrBlank() -> ChatDelta.Content(content)
                !reasoningContent.isNullOrBlank() -> ChatDelta.Reasoning(reasoningContent)
                else -> ChatDelta.Error("Provider returned an empty response.")
            }
        }.getOrElse { ChatDelta.Error("Malformed response") }
    }

    fun summarizeLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return "done"

        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val error = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            if (error != null) return "error length=${error.length}"
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return "no choices"
            val delta = choice["delta"]?.jsonObject
            val deltaKeys = delta?.keys?.joinToString(",").orEmpty()
            val contentLength = delta.stringLength("content")
            val reasoningLength = delta.stringLength("reasoning_content")
            val toolCallCount = delta?.get("tool_calls")?.jsonArray?.size ?: 0
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            "deltaKeys=[$deltaKeys] contentLength=$contentLength reasoningLength=$reasoningLength toolCalls=$toolCallCount finish=$finishReason"
        }.getOrDefault("malformed")
    }

    private fun JsonObject.extractErrorMessage(): String? {
        return this["error"]
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private fun JsonElement?.stringLength(key: String): Int {
        val value = this
            ?.jsonObject
            ?.get(key)
            ?.jsonPrimitive
            ?.contentOrNull
        return value?.length ?: 0
    }

    private fun JsonObject.extractToolCallDeltas(): List<ChatToolCallDelta> =
        this["tool_calls"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val toolCall = element.jsonObject
                val index = toolCall["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                val function = toolCall["function"]?.jsonObject
                ChatToolCallDelta(
                    index = index,
                    id = toolCall["id"]?.jsonPrimitive?.contentOrNull,
                    name = function?.get("name")?.jsonPrimitive?.contentOrNull,
                    argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull
                )
            }
            .orEmpty()

    private fun JsonObject.extractToolCalls(): List<ChatToolCall> =
        this["tool_calls"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val toolCall = element.jsonObject
                val function = toolCall["function"]?.jsonObject ?: return@mapNotNull null
                val id = toolCall["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
                ChatToolCall(id = id, name = name, arguments = arguments)
            }
            .orEmpty()
}
