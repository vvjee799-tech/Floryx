@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.flowchat.app.data.chat

import com.flowchat.app.data.network.toWireBody
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Conservative estimates, not a provider tokenizer or a claim about a model's exact context window. */
object ContextBudget {
    private const val BaseTokens = 1_024
    private const val ImageTokens = 1_024
    private val wireJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun text(text: String): Int = (text.toByteArray(Charsets.UTF_8).size + 2) / 3

    fun request(request: ChatRequest, provider: ProviderConfig? = null): Int {
        if (provider == null) return legacyRequest(request)
        val imageCount = request.messages.sumOf { it.images.size }
        val withoutImageBytes = request.copy(messages = request.messages.map { message ->
            if (message.images.isEmpty()) message else message.copy(
                images = message.images.map { image -> image.copy(base64 = "") }
            )
        })
        // The same serializer chooses what is actually replayed for each protocol. An invalid
        // configuration is still reported by the real send path, not by budget estimation.
        return try {
            BaseTokens + wireContent(withoutImageBytes.toWireBody(provider, stream = true, json = wireJson)) +
                imageCount * ImageTokens
        } catch (_: Exception) {
            legacyRequest(request)
        }
    }

    /** Count decoded string values once; JSON transport escaping is not part of model text. */
    private fun wireContent(element: JsonElement): Int = when (element) {
        is JsonObject -> 2 + element.entries.sumOf { (key, value) -> text(key) + 1 + wireContent(value) }
        is JsonArray -> 2 + element.sumOf { wireContent(it) }
        is JsonPrimitive -> text(element.content) + 1
    }

    private fun legacyRequest(request: ChatRequest): Int = BaseTokens +
        request.tools.sumOf { text(it.name + it.description + it.parameters) } +
        request.messages.sumOf { message ->
            text(message.content.orEmpty() + message.reasoningContent.orEmpty() +
                message.toolCalls.joinToString { it.name + it.arguments } +
                message.providerData.orEmpty().toString()) + message.images.size * ImageTokens
        }
}
