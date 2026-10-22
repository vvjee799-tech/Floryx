package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import kotlinx.serialization.json.*

/** Signed/encrypted reasoning details must survive a tool call without entering visible text. */
internal class ChatReasoningMetadata(private val json: Json) {
    private val details = linkedMapOf<String, JsonObject>()

    fun readLine(line: String) {
        if (!line.trimStart().startsWith("data:")) return
        runCatching {
            val root = json.parseToJsonElement(line.trim().removePrefix("data:").trim()).jsonObject
            val delta = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return
            delta["reasoning_details"]?.jsonArray?.forEachIndexed { position, element ->
                val part = element.jsonObject
                val key = part["index"]?.jsonPrimitive?.content ?: part["id"]?.jsonPrimitive?.contentOrNull ?: position.toString()
                val previous = details[key].orEmpty()
                details[key] = JsonObject(previous + part.filterValues { it != JsonNull }.mapValues { (name, value) ->
                    if (name in setOf("text", "summary", "data", "signature") && value is JsonPrimitive && previous[name] is JsonPrimitive)
                        JsonPrimitive(previous.getValue(name).jsonPrimitive.content + value.content)
                    else value
                })
            }
        }
    }

    fun event(): ChatDelta.ProviderData? = if (details.isEmpty()) null else event(JsonArray(details.values.toList()))

    companion object {
        fun fromCompletion(body: String, json: Json): ChatDelta.ProviderData? = runCatching {
            val message = json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            (message?.get("reasoning_details") as? JsonArray)?.let(::event)
        }.getOrNull()

        private fun event(details: JsonArray) = ChatDelta.ProviderData(buildJsonArray {
            add(buildJsonObject { put("reasoning_details", details) })
        })
    }
}
