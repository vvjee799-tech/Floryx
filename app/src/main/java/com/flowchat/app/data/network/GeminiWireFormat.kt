package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.provider.ThinkingSupport
import com.flowchat.app.domain.provider.geminiModelName
import kotlinx.serialization.json.*

internal fun ChatRequest.toGeminiBody(provider: ProviderConfig, json: Json): JsonObject = buildJsonObject {
    val system = messages.filter { it.role == "system" || it.role == "developer" }
        .mapNotNull { it.content?.takeIf(String::isNotBlank) }.joinToString("\n\n")
    if (system.isNotBlank()) putJsonObject("systemInstruction") {
        putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
    }
    val contents = mutableListOf<JsonObject>()
    // Keep parallel calls together, followed by all their results, in their original order.
    val pendingCalls = mutableMapOf<String, Pair<String, String?>>()
    messages.filterNot { it.role == "system" || it.role == "developer" }.forEach { message ->
        val role = if (message.role == "assistant") "model" else "user"
        val parts = when {
            message.role == "assistant" && message.providerData != null -> message.providerData.also { blocks ->
                val nativeCalls = blocks.mapNotNull { it.jsonObject["functionCall"] as? JsonObject }
                message.toolCalls.forEachIndexed { index, call ->
                    pendingCalls[call.id] = call.name to nativeCalls.getOrNull(index)?.get("id")?.jsonPrimitive?.contentOrNull
                }
            }
            message.role == "tool" -> buildJsonArray {
                val call = requireNotNull(pendingCalls.remove(message.toolCallId)) { "Gemini tool result has no matching function call." }
                add(buildJsonObject { putJsonObject("functionResponse") {
                    put("name", call.first)
                    call.second?.let { put("id", it) }
                    val result = runCatching { json.parseToJsonElement(message.content.orEmpty()) }.getOrNull()
                    put("response", result as? JsonObject ?: buildJsonObject { put("result", result ?: JsonPrimitive(message.content.orEmpty())) })
                    if (message.images.isNotEmpty()) putJsonArray("parts") {
                        message.images.forEach { image -> add(buildJsonObject { putJsonObject("inlineData") {
                            put("mimeType", image.mimeType); put("data", image.base64)
                        } }) }
                    }
                } })
            }
            else -> buildJsonArray {
                if (!message.content.isNullOrBlank()) add(buildJsonObject { put("text", message.content) })
                message.images.forEach { image -> add(buildJsonObject { putJsonObject("inlineData") {
                    put("mimeType", image.mimeType); put("data", image.base64)
                } }) }
                message.toolCalls.forEach { call ->
                    pendingCalls[call.id] = call.name to null
                    add(buildJsonObject { putJsonObject("functionCall") {
                        put("name", call.name); put("args", json.parseToJsonElement(call.arguments))
                    } })
                }
            }
        }
        if (parts.isNotEmpty()) {
            if (contents.lastOrNull()?.get("role")?.jsonPrimitive?.content == role) {
                val previous = contents.removeAt(contents.lastIndex)
                contents += buildJsonObject { put("role", role); put("parts", JsonArray(previous.getValue("parts").jsonArray + parts)) }
            } else contents += buildJsonObject { put("role", role); put("parts", parts) }
        }
    }
    put("contents", JsonArray(contents))
    putJsonObject("generationConfig") {
        put("temperature", temperature)
        put("topP", topP)
        maxTokens?.let { put("maxOutputTokens", it) }
        val support = ThinkingSupport.resolve(provider, model)
        if (support.supported) putJsonObject("thinkingConfig") {
            val enabled = support.isEnabled(enableThinking)
            val effort = support.normalize(thinkingEffort)
            put("includeThoughts", enabled)
            if (geminiModelName(model).startsWith("gemini-2.5")) {
                val budget = when {
                    !enabled -> 0
                    effort == ThinkingEffort.Low -> 1024
                    effort == ThinkingEffort.Medium -> 8192
                    effort == ThinkingEffort.High -> 24576
                    else -> -1
                }
                put("thinkingBudget", if (budget > 0 && maxTokens != null) minOf(budget, maxTokens - 1).also {
                    val name = geminiModelName(model)
                    require(it >= when { name.contains("pro") -> 128; name.contains("lite") -> 512; else -> 1 }) {
                        "Gemini thinking requires a larger maximum output. Increase the output limit."
                    }
                } else budget)
            } else if (effort != ThinkingEffort.Auto) put("thinkingLevel", effort.wireValue)
            else if (geminiModelName(model).startsWith("gemini-4")) put("thinkingLevel", "medium")
        }
    }
    if (tools.isNotEmpty()) {
        putJsonArray("tools") { add(buildJsonObject { putJsonArray("functionDeclarations") {
            tools.forEach { tool -> add(buildJsonObject {
                put("name", tool.name); put("description", tool.description); put("parametersJsonSchema", tool.parameters)
            }) }
        } }) }
        putJsonObject("toolConfig") { putJsonObject("functionCallingConfig") {
            put("mode", when (toolChoice) { "none" -> "NONE"; "required" -> "ANY"; else -> "AUTO" })
        } }
    }
}
