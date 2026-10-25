package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.provider.ThinkingStyle
import com.flowchat.app.domain.provider.ThinkingSupport
import com.flowchat.app.domain.provider.apiHost
import kotlinx.serialization.json.*

internal enum class ModelProtocol { ChatCompletions, Responses, Anthropic }

internal fun modelProtocol(provider: ProviderConfig, model: String): ModelProtocol = when {
    provider.apiHost() == "api.anthropic.com" -> ModelProtocol.Anthropic
    provider.apiHost() == "api.openai.com" && (model.startsWith("gpt-6", true) ||
        model.contains("codex", true) || model.matches(Regex("(?:gpt-5[^/]*|o[13])-pro(?:-.*)?"))) -> ModelProtocol.Responses
    provider.apiHost() == "api.x.ai" && Regex("grok-4\\.[3-9]").containsMatchIn(model) -> ModelProtocol.Responses
    else -> ModelProtocol.ChatCompletions
}

internal fun ModelProtocol.path() = when (this) {
    ModelProtocol.ChatCompletions -> "/chat/completions"
    ModelProtocol.Responses -> "/responses"
    ModelProtocol.Anthropic -> "/messages"
}

internal fun ChatRequest.toWireBody(provider: ProviderConfig, stream: Boolean, json: Json): JsonObject = when (modelProtocol(provider, model)) {
    ModelProtocol.ChatCompletions -> json.encodeToJsonElement(toOpenAiRequest(provider, stream)).jsonObject
    ModelProtocol.Responses -> toResponsesBody(provider, stream)
    ModelProtocol.Anthropic -> toAnthropicBody(provider, stream, json)
}

private fun ChatRequest.toResponsesBody(provider: ProviderConfig, stream: Boolean): JsonObject = buildJsonObject {
    val support = ThinkingSupport.resolve(provider, model)
    val enabled = support.isEnabled(enableThinking)
    val effort = support.normalize(thinkingEffort)
    put("model", model)
    put("stream", stream)
    put("store", false)
    putJsonArray("include") { add("reasoning.encrypted_content") }
    if (support.supported) putJsonObject("reasoning") {
        if (!enabled && support.canDisable) put("effort", "none")
        else if (effort != ThinkingEffort.Auto) put("effort", effort.wireValue)
        else if (support.style == ThinkingStyle.OpenAI && !model.startsWith("gpt-6", true)) {
            put("effort", (ThinkingEffort.Medium.takeIf { it in support.efforts } ?: support.efforts.firstOrNull() ?: ThinkingEffort.High).wireValue)
        }
        // All GPT-6 models reason even when no effort is supplied. Preserve vendor defaults.
    }
    maxTokens?.let { put("max_output_tokens", it) }
    putJsonArray("input") {
        messages.forEach { message ->
            if (message.role == "assistant" && message.providerData != null) {
                message.providerData.forEach { add(it) }
            } else if (message.role == "tool") {
                add(buildJsonObject {
                    put("type", "function_call_output"); put("call_id", message.toolCallId)
                    if (message.images.isEmpty()) put("output", message.content.orEmpty()) else putJsonArray("output") {
                        add(buildJsonObject { put("type", "input_text"); put("text", message.content.orEmpty()) })
                        message.images.forEach { image -> add(buildJsonObject { put("type", "input_image"); put("image_url", image.dataUrl) }) }
                    }
                })
            } else {
                if (!message.content.isNullOrBlank() || message.images.isNotEmpty()) add(buildJsonObject {
                    put("role", message.role)
                    if (message.images.isEmpty()) put("content", message.content) else putJsonArray("content") {
                        if (!message.content.isNullOrBlank()) add(buildJsonObject { put("type", "input_text"); put("text", message.content) })
                        message.images.forEach { image -> add(buildJsonObject { put("type", "input_image"); put("image_url", image.dataUrl) }) }
                    }
                })
                message.toolCalls.forEach { call ->
                    add(buildJsonObject { put("type", "function_call"); put("call_id", call.id); put("name", call.name); put("arguments", call.arguments) })
                }
            }
        }
    }
    if (tools.isNotEmpty()) {
        putJsonArray("tools") { tools.forEach { tool -> add(buildJsonObject {
            put("type", "function"); put("name", tool.name); put("description", tool.description)
            put("parameters", tool.parameters); put("strict", false)
        }) } }
        toolChoice?.let { put("tool_choice", it) }
        parallelToolCalls?.let { put("parallel_tool_calls", it) }
    }
}

private fun ChatRequest.toAnthropicBody(provider: ProviderConfig, stream: Boolean, json: Json): JsonObject = buildJsonObject {
    val support = ThinkingSupport.resolve(provider, model)
    val enabled = support.isEnabled(enableThinking)
    val effort = support.normalize(thinkingEffort)
    val limit = maxTokens ?: 16_384
    put("model", model)
    put("stream", stream)
    put("max_tokens", limit)
    val system = messages.filter { it.role in setOf("system", "developer") }.mapNotNull { it.content }.joinToString("\n\n")
    if (system.isNotBlank()) put("system", system)
    if (support.supported) putJsonObject("thinking") {
        when {
            !enabled -> put("type", "disabled")
            support.style == ThinkingStyle.ClaudeBudget -> {
                require(limit > 1024) { "Thinking requires a maximum output above 1024 tokens. Increase the output limit or turn thinking off." }
                put("type", "enabled")
                val budget = when (effort) { ThinkingEffort.Low -> 1024; ThinkingEffort.High -> 8192; else -> 4096 }
                put("budget_tokens", minOf(budget, limit - 1))
            }
            else -> { put("type", "adaptive"); put("display", "summarized") }
        }
    }
    if (enabled && support.style == ThinkingStyle.ClaudeAdaptive && effort != ThinkingEffort.Auto) {
        putJsonObject("output_config") { put("effort", effort.wireValue) }
    }
    val converted = mutableListOf<JsonObject>()
    messages.filterNot { it.role in setOf("system", "developer") }.forEach { message ->
        val role = if (message.role == "tool") "user" else message.role
        val blocks = if (message.role == "assistant" && message.providerData != null) message.providerData else buildJsonArray {
            if (message.role == "tool") {
                add(buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", message.toolCallId)
                    if (message.images.isEmpty()) put("content", message.content.orEmpty()) else putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", message.content.orEmpty()) })
                        message.images.forEach { image -> add(buildJsonObject {
                            put("type", "image"); putJsonObject("source") {
                                put("type", "base64"); put("media_type", image.mimeType); put("data", image.base64)
                            }
                        }) }
                    }
                })
            } else {
                message.images.forEach { image -> add(buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") { put("type", "base64"); put("media_type", image.mimeType); put("data", image.base64) }
                }) }
                if (!message.content.isNullOrBlank()) add(buildJsonObject { put("type", "text"); put("text", message.content) })
                message.toolCalls.forEach { call -> add(buildJsonObject {
                    put("type", "tool_use"); put("id", call.id); put("name", call.name)
                    put("input", json.parseToJsonElement(call.arguments))
                }) }
            }
        }
        if (blocks.isNotEmpty()) {
            if (converted.lastOrNull()?.get("role")?.jsonPrimitive?.content == role) {
                val previous = converted.removeAt(converted.lastIndex)
                converted += buildJsonObject { put("role", role); put("content", JsonArray(previous["content"]!!.jsonArray + blocks)) }
            } else converted += buildJsonObject { put("role", role); put("content", blocks) }
        }
    }
    put("messages", JsonArray(converted))
    if (tools.isNotEmpty()) {
        putJsonArray("tools") { tools.forEach { tool -> add(buildJsonObject {
            put("name", tool.name); put("description", tool.description); put("input_schema", tool.parameters)
        }) } }
        putJsonObject("tool_choice") {
            put("type", if (toolChoice == "none") "none" else "auto")
            if (parallelToolCalls == false) put("disable_parallel_tool_use", true)
        }
    }
}

/** Retains encrypted reasoning/signatures only in memory for the current tool loop. */
internal class NativeModelStreamParser(private val protocol: ModelProtocol, private val json: Json) {
    private val blocks = sortedMapOf<Int, JsonObject>()
    private val partialInputs = mutableMapOf<Int, StringBuilder>()
    private var textEmitted = false
    private var reasoningEmitted = false

    fun parseLine(line: String): List<ChatDelta> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return listOf(ChatDelta.Done)
        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val type = root.string("type")
            if (type == "error" || root["error"] is JsonObject) return listOf(ChatDelta.Error(root.errorMessage()))
            when (protocol) {
                ModelProtocol.ChatCompletions -> emptyList()
                ModelProtocol.Responses -> when (type) {
                    "response.output_text.delta" -> listOf(ChatDelta.Content(root.string("delta"))).also { textEmitted = true }
                    "response.reasoning_summary_text.delta" -> listOf(ChatDelta.Reasoning(root.string("delta"))).also { reasoningEmitted = true }
                    "response.completed" -> completion(root["response"]!!.jsonObject)
                    "response.failed", "response.incomplete" -> listOf(ChatDelta.Error(root["response"]?.jsonObject?.errorMessage() ?: "The model response was incomplete."))
                    else -> emptyList()
                }
                ModelProtocol.Anthropic -> when (type) {
                    "content_block_start" -> {
                        val index = root["index"]!!.jsonPrimitive.int
                        val block = root["content_block"]!!.jsonObject
                        blocks[index] = block
                        if (block.string("type") == "tool_use") partialInputs[index] = StringBuilder()
                        emptyList()
                    }
                    "content_block_delta" -> {
                        val index = root["index"]!!.jsonPrimitive.int
                        val delta = root["delta"]!!.jsonObject
                        when (delta.string("type")) {
                            "text_delta" -> {
                                appendBlock(index, "text", delta.string("text")); textEmitted = true
                                listOf(ChatDelta.Content(delta.string("text")))
                            }
                            "thinking_delta" -> {
                                appendBlock(index, "thinking", delta.string("thinking")); reasoningEmitted = true
                                listOf(ChatDelta.Reasoning(delta.string("thinking")))
                            }
                            "signature_delta" -> { appendBlock(index, "signature", delta.string("signature")); emptyList() }
                            "input_json_delta" -> { partialInputs.getOrPut(index) { StringBuilder() }.append(delta.string("partial_json")); emptyList() }
                            else -> emptyList()
                        }
                    }
                    "message_stop" -> {
                        partialInputs.forEach { (index, input) ->
                            if (input.isNotBlank()) blocks[index] = JsonObject(blocks.getValue(index) + ("input" to json.parseToJsonElement(input.toString())))
                        }
                        completion(buildJsonObject { put("content", JsonArray(blocks.values.toList())) })
                    }
                    else -> emptyList()
                }
            }
        }.getOrElse { listOf(ChatDelta.Error("Malformed model stream event.")) }
    }

    fun parseCompletion(body: String): List<ChatDelta> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["error"] is JsonObject || root.string("status") in setOf("failed", "incomplete")) listOf(ChatDelta.Error(root.errorMessage())) else completion(root)
    }.getOrElse { listOf(ChatDelta.Error("Malformed model response.")) }

    private fun appendBlock(index: Int, key: String, value: String) {
        val current = blocks.getValue(index)
        blocks[index] = JsonObject(current + (key to JsonPrimitive(current.string(key) + value)))
    }

    private fun completion(root: JsonObject): List<ChatDelta> {
        val content = root[if (protocol == ModelProtocol.Responses) "output" else "content"]?.jsonArray
            ?: return listOf(ChatDelta.Error("Provider returned an empty response."))
        val calls = mutableListOf<ChatToolCall>()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        content.forEach { element ->
            val block = element.jsonObject
            when (block.string("type")) {
                "text" -> text.append(block.string("text"))
                "thinking" -> reasoning.append(block.string("thinking"))
                "message" -> block["content"]?.jsonArray?.forEach { part ->
                    if (part.jsonObject.string("type") == "output_text") text.append(part.jsonObject.string("text"))
                }
                "reasoning" -> block["summary"]?.jsonArray?.forEach { reasoning.append(it.jsonObject.string("text")) }
                "function_call" -> calls += ChatToolCall(block.string("call_id"), block.string("name"), block.string("arguments"))
                "tool_use" -> calls += ChatToolCall(block.string("id"), block.string("name"), (block["input"] ?: JsonObject(emptyMap())).toString())
            }
        }
        return buildList {
            add(ChatDelta.ProviderData(content))
            if (!reasoningEmitted && reasoning.isNotEmpty()) add(ChatDelta.Reasoning(reasoning.toString()))
            if (!textEmitted && text.isNotEmpty()) add(ChatDelta.Content(text.toString()))
            if (calls.isNotEmpty()) add(ChatDelta.ToolCalls(calls)) else add(ChatDelta.Done)
        }
    }
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.errorMessage(): String = (this["error"] as? JsonObject)?.string("message")
    ?.takeIf { it.isNotBlank() } ?: string("message").ifBlank { "The model response failed or was incomplete." }
