package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import java.util.UUID
import kotlinx.serialization.json.*

/** A parser owns one candidate/response. Native parts, including signatures, retain their boundaries. */
internal class GeminiStreamParser(private val json: Json) {
    private val event = StringBuilder()
    private val parts = mutableListOf<JsonObject>()
    private val callPrefix = "gemini-${UUID.randomUUID()}"
    var terminal: Boolean = false
        private set

    fun parseLine(line: String): List<ChatDelta> {
        if (terminal) return emptyList()
        if (line.isBlank()) return dispatchEvent()
        if (line.startsWith("data:")) {
            if (event.isNotEmpty()) event.append('\n')
            event.append(line.removePrefix("data:").removePrefix(" "))
        }
        return emptyList()
    }

    fun endOfInput(): List<ChatDelta> = buildList {
        addAll(dispatchEvent())
        if (!terminal) addAll(fail("Gemini 连接在回复完成前中断，请重试。"))
    }

    fun parseCompletion(body: String): List<ChatDelta> = buildList {
        addAll(parseEvent(body))
        if (!terminal) addAll(fail("Gemini 未返回完整回复，请重试。"))
    }

    private fun dispatchEvent(): List<ChatDelta> {
        if (event.isEmpty() || terminal) return emptyList()
        val payload = event.toString()
        event.clear()
        return parseEvent(payload)
    }

    private fun parseEvent(payload: String): List<ChatDelta> = try {
        val root = json.parseToJsonElement(payload).jsonObject
        val error = root["error"] as? JsonObject
        if (error != null) fail(geminiApiError(error["code"]?.jsonPrimitive?.intOrNull ?: 0, payload, json))
        else {
            val blocked = (root["promptFeedback"] as? JsonObject)?.text("blockReason").orEmpty()
            if (blocked.isNotBlank() && blocked != "BLOCK_REASON_UNSPECIFIED") fail("Gemini 拒绝了此请求：$blocked。")
            else {
                val candidate = (root["candidates"] as? JsonArray)?.firstOrNull {
                    (it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0) == 0
                }?.jsonObject
                if (candidate == null) emptyList() else buildList {
                    val incoming = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: JsonArray(emptyList())
                    incoming.forEach { element ->
                        val part = element.jsonObject
                        parts += part
                        part.text("text").takeIf { it.isNotEmpty() }?.let { text ->
                            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) add(ChatDelta.Reasoning(text))
                            else add(ChatDelta.Content(text))
                        }
                    }
                    val reason = candidate.text("finishReason")
                    if (reason.isNotBlank() && reason != "FINISH_REASON_UNSPECIFIED") {
                        if (reason == "STOP") addAll(complete()) else addAll(fail(when (reason) {
                            "MAX_TOKENS" -> "Gemini 已达到输出上限，回复未完成。请提高最大输出或降低思考程度后重试。"
                            "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "RECITATION", "SPII" -> "Gemini 未完成回复：$reason。"
                            "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS" -> "Gemini 工具调用未完成：$reason，请重试。"
                            else -> "Gemini 回复未完成：$reason，请重试。"
                        }))
                    }
                }
            }
        }
    } catch (_: Exception) {
        fail("Gemini 返回了无法解析的回复，请重试。")
    }

    private fun complete(): List<ChatDelta> {
        val calls = parts.mapNotNull { it["functionCall"] as? JsonObject }.mapIndexed { index, call ->
            val name = call.text("name")
            require(name.isNotBlank())
            val args = call["args"] ?: JsonObject(emptyMap())
            require(args is JsonObject)
            ChatToolCall(call.text("id").ifBlank { "$callPrefix-$index" }, name, args.toString())
        }
        if (calls.isEmpty() && parts.none { it["thought"]?.jsonPrimitive?.booleanOrNull != true && it.text("text").isNotBlank() }) {
            return fail("Gemini 未返回回复正文，请重试。")
        }
        terminal = true
        return listOf(ChatDelta.ProviderData(JsonArray(parts))) +
            if (calls.isNotEmpty()) listOf(ChatDelta.ToolCalls(calls)) else listOf(ChatDelta.Done)
    }

    private fun fail(message: String): List<ChatDelta> {
        terminal = true
        return listOf(ChatDelta.Error(message))
    }
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun geminiApiError(status: Int, body: String, json: Json): String {
    val detail = runCatching { json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject.text("message") }
        .getOrDefault("").take(1000)
    val message = when {
        detail.contains("location", true) && detail.contains("not supported", true) -> "当前网络出口地区不支持 Gemini API，请检查网络。"
        status == 401 || status == 403 -> "Gemini 认证或权限失败，请检查 API Key、API 限制和账号权限。"
        status == 429 -> "Gemini 配额不足或请求过于频繁，请检查配额后重试。"
        status == 404 -> "Gemini 模型不可用，请重新获取模型列表并选择可用模型。"
        status >= 500 -> "Gemini 服务暂时不可用，请稍后重试。"
        else -> "Gemini 请求失败。"
    }
    return "$message HTTP $status" + if (detail.isBlank()) "" else "：$detail"
}
