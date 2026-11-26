package com.flowchat.app.diagnostics

import com.flowchat.app.domain.chat.TaskEffectRegistry.Companion.contentSha256
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.*

/** Taps the acceptance client's serialized body before it is dispatched. */
internal fun HttpClient.observeF06OutboundBody(observe: (String) -> Unit) {
    plugin(HttpSend).intercept { request ->
        val body = checkNotNull(request.body as? TextContent) { "F06 expected a serialized JSON request body" }
        observe(body.text)
        execute(request)
    }
}

/** Records decoded result hashes and narrow compatibility fields without storing the request body. */
internal fun summarizeF06OutboundEvidence(request: ChatRequest, body: String): JsonObject {
    val payload = Json.parseToJsonElement(body).jsonObject
    val messages = payload.getValue("messages").jsonArray.map { it.jsonObject }
    val functions = (payload["tools"] as? JsonArray).orEmpty().map { it.jsonObject.getValue("function").jsonObject }
    val compatibilityAnswer = functions.singleOrNull { it["name"] == JsonPrimitive(WorkspaceToolDefinitions.AnswerCompatibility) }
    val sentResults = messages.filter { it["role"] == JsonPrimitive("tool") }
    val sentById = sentResults.associateBy { it["tool_call_id"] }
    val sentCalls = messages.flatMap { (it["tool_calls"] as? JsonArray).orEmpty() }
        .associate { it.jsonObject["id"] to it.jsonObject["function"]?.jsonObject?.get("name") }
    val expectedResults = request.messages.filter { it.role == "tool" }.associateBy { it.toolCallId }
    val workspaceCalls = request.messages.flatMap { it.toolCalls }.filter {
        it.name == WorkspaceToolDefinitions.Stat || it.name == WorkspaceToolDefinitions.Search
    }
    return buildJsonObject {
        put("tool_choice", payload["tool_choice"].stringValue())
        put("tool_names", JsonArray(functions.map { it.getValue("name") }))
        put("compatibility_answer_description_sha256", compatibilityAnswer?.get("description").stringValue()?.let(::contentSha256))
        put("tool_result_count", sentResults.size)
        put("workspace_results", buildJsonArray {
            for (call in workspaceCalls) {
                val id = JsonPrimitive(call.id)
                val expected = expectedResults[call.id]?.content
                val content = sentById[id]?.get("content").stringValue()
                val result = content?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                val compatibility = result?.get("compatibility_evidence") as? JsonObject
                add(buildJsonObject {
                    put("tool", call.name)
                    put("tool_call_id", id)
                    put("call_present", sentCalls[id] == JsonPrimitive(call.name))
                    put("result_utf8_bytes", content?.toByteArray(Charsets.UTF_8)?.size)
                    put("result_sha256", content?.let(::contentSha256))
                    put("matches_request_result", content != null && content == expected)
                    if (call.name == WorkspaceToolDefinitions.Stat) {
                        put("external_commands_and_libraries_abi", compatibility?.get("external_commands_and_libraries_abi").stringValue())
                        put("abi_compatibility", compatibility?.get("abi_compatibility").stringValue())
                    } else {
                        put("query_sha256", result?.get("query").stringValue()?.let(::contentSha256))
                        put("conclusion_scope_sha256", result?.get("conclusion_scope").stringValue()?.let(::contentSha256))
                        put("byte_start", (result?.get("byte_start") as? JsonPrimitive)?.longOrNull)
                        put("byte_end_exclusive", (result?.get("byte_end_exclusive") as? JsonPrimitive)?.longOrNull)
                        put("whole_file_scanned", (result?.get("whole_file_scanned") as? JsonPrimitive)?.booleanOrNull)
                    }
                })
            }
        })
    }
}

private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
