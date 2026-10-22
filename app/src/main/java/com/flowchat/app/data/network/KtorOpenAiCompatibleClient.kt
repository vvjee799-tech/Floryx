package com.flowchat.app.data.network

import android.util.Log
import com.flowchat.app.BuildConfig
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.ChatToolCallDelta
import com.flowchat.app.domain.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import com.flowchat.app.domain.provider.apiHost

@Singleton
class KtorOpenAiCompatibleClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) : ChatCompletionClient {
    override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> = flow {
        val protocol = modelProtocol(provider, request.model)
        if (protocol != ModelProtocol.ChatCompletions) {
            emitAll(streamNative(request, provider, apiKey, protocol))
            return@flow
        }
        var sawOutput = false
        var sawError = false
        val toolCallAccumulator = OpenAiToolCallAccumulator()
        val reasoningMetadata = ChatReasoningMetadata(json)
        val payload = request.toOpenAiRequest(provider, stream = true)
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "request host=${provider.apiHost()} model=${request.model} messages=${request.messages.size} " +
                    "hasApiKey=${!apiKey.isNullOrBlank()} stream=${payload.stream} thinking=${payload.thinking?.type}"
            )
        }
        val statement = httpClient.preparePost {
            url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            timeout {
                requestTimeoutMillis = ChatRequestTimeoutMillis
                socketTimeoutMillis = ChatSocketTimeoutMillis
                connectTimeoutMillis = ChatConnectTimeoutMillis
            }
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "text/event-stream")
            if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            provider.parseCustomHeaders().forEach { (key, value) ->
                header(key, value)
            }
            setBody(payload)
        }

        statement.execute { response ->
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "response status=${response.status.value}")
            }
            if (!response.status.isSuccess()) {
                sawError = true
                emit(ChatDelta.Error(response.errorText()))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (BuildConfig.DEBUG) {
                    OpenAiStreamParser.summarizeLine(line)?.let { Log.i(TAG, "stream $it") }
                }
                reasoningMetadata.readLine(line)
                for (delta in OpenAiStreamParser.parseDeltas(line)) when (delta) {
                    ChatDelta.Done -> {
                        reasoningMetadata.event()?.let { emit(it) }
                        val toolCalls = toolCallAccumulator.build()
                        if (toolCalls.isNotEmpty()) {
                            emit(ChatDelta.ToolCalls(toolCalls))
                        } else if (sawOutput) {
                            emit(delta)
                        }
                        return@execute
                    }
                    is ChatDelta.Content -> {
                        sawOutput = true
                        emit(delta)
                    }
                    is ChatDelta.Reasoning -> {
                        sawOutput = true
                        emit(delta)
                    }
                    is ChatDelta.FullResponse -> {
                        sawOutput = true
                        emit(delta)
                    }
                    is ChatDelta.ToolCallDelta -> {
                        sawOutput = true
                        toolCallAccumulator.append(delta.calls)
                    }
                    is ChatDelta.ToolCalls -> {
                        emit(delta)
                        return@execute
                    }
                    is ChatDelta.ProviderData -> emit(delta)
                    is ChatDelta.Error -> {
                        sawError = true
                        emit(delta)
                        return@execute
                    }
                }
            }
            // EOF is not a successful finish: never execute a possibly truncated tool call.
            if (sawOutput || toolCallAccumulator.build().isNotEmpty()) {
                sawError = true
                emit(ChatDelta.Error("The model stream ended before the response completed. Please retry."))
            }
        }
        if (!sawOutput && !sawError && toolCallAccumulator.build().isEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "stream produced no content; retrying with non-streaming request")
            }
            val fallback = completeChat(request, provider, apiKey)
            fallback.forEach { delta ->
                if (delta is ChatDelta.FullResponse) {
                    emit(ChatDelta.Reasoning(delta.reasoningText))
                    emit(ChatDelta.Content(delta.contentText))
                } else emit(delta)
            }
            if (fallback.none { it is ChatDelta.ToolCalls || it is ChatDelta.Error }) emit(ChatDelta.Done)
        }
    }

    private fun streamNative(
        request: ChatRequest,
        provider: ProviderConfig,
        apiKey: String?,
        protocol: ModelProtocol
    ): Flow<ChatDelta> = flow {
        suspend fun statement(stream: Boolean) = httpClient.preparePost {
            url(provider.baseUrl.trimEnd('/') + protocol.path())
            timeout {
                requestTimeoutMillis = ChatRequestTimeoutMillis
                socketTimeoutMillis = ChatSocketTimeoutMillis
                connectTimeoutMillis = ChatConnectTimeoutMillis
            }
            contentType(ContentType.Application.Json)
            if (stream) header(HttpHeaders.Accept, "text/event-stream")
            if (protocol == ModelProtocol.Anthropic) {
                header("anthropic-version", "2023-06-01")
                if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
            } else if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            provider.parseCustomHeaders().forEach { (key, value) -> header(key, value) }
            setBody(request.toWireBody(provider, stream, json))
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "request host=${provider.apiHost()} model=${request.model} protocol=$protocol")
        var sawOutput = false
        var sawError = false
        var completed = false
        val parser = NativeModelStreamParser(protocol, json)
        statement(true).execute { response ->
            if (!response.status.isSuccess()) {
                sawError = true
                emit(ChatDelta.Error(response.errorText()))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                val deltas = parser.parseLine(line)
                deltas.forEach { delta ->
                    if (delta is ChatDelta.Content || delta is ChatDelta.Reasoning || delta is ChatDelta.ToolCalls) sawOutput = true
                    if (delta is ChatDelta.Error) sawError = true
                    if (delta is ChatDelta.Done || delta is ChatDelta.ToolCalls) completed = true
                    emit(delta)
                }
                if (completed || sawError) return@execute
            }
        }
        if (sawOutput && !completed && !sawError) {
            emit(ChatDelta.Error("The model stream ended before the response completed. Please retry."))
        } else if (!sawOutput && !sawError) {
            val response = statement(false).execute()
            if (!response.status.isSuccess()) emit(ChatDelta.Error(response.errorText()))
            else NativeModelStreamParser(protocol, json).parseCompletion(response.bodyAsText()).forEach { emit(it) }
        }
    }

    private suspend fun completeChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): List<ChatDelta> {
        val payload = request.toOpenAiRequest(provider, stream = false)
        val response = httpClient.preparePost {
            url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            timeout {
                requestTimeoutMillis = ChatRequestTimeoutMillis
                socketTimeoutMillis = ChatSocketTimeoutMillis
                connectTimeoutMillis = ChatConnectTimeoutMillis
            }
            contentType(ContentType.Application.Json)
            if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            provider.parseCustomHeaders().forEach { (key, value) ->
                header(key, value)
            }
            setBody(payload)
        }.execute()
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "fallback response status=${response.status.value}")
        }
        if (!response.status.isSuccess()) {
            return listOf(ChatDelta.Error(response.errorText()))
        }
        val body = response.bodyAsText()
        val parsed = OpenAiStreamParser.parseCompletionBody(body)
        return buildList {
            ChatReasoningMetadata.fromCompletion(body, json)?.let { add(it) }
            if (parsed is ChatDelta.ToolCalls) {
                val message = json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject
                (message["reasoning_content"] ?: message["reasoning"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(ChatDelta.Reasoning(it)) }
                message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(ChatDelta.Content(it)) }
            }
            add(parsed)
        }
    }

    private fun ProviderConfig.parseCustomHeaders(): Map<String, String> {
        if (customHeadersJson.isBlank() || customHeadersJson.trim() == "{}") return emptyMap()
        return runCatching {
            json.parseToJsonElement(customHeadersJson).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private suspend fun HttpResponse.errorText(): String {
        val text = runCatching { body<String>() }.getOrDefault("")
        return "HTTP ${status.value}: ${text.ifBlank { status.description }}"
    }

    private companion object {
        const val TAG = "FlowChatNetwork"
        const val ChatConnectTimeoutMillis = 30_000L
        const val ChatSocketTimeoutMillis = 5 * 60 * 1000L
        const val ChatRequestTimeoutMillis = 10 * 60 * 1000L
    }
}

private class OpenAiToolCallAccumulator {
    private val calls = linkedMapOf<Int, MutableToolCall>()

    fun append(deltas: List<ChatToolCallDelta>) {
        deltas.forEach { delta ->
            val call = calls.getOrPut(delta.index) { MutableToolCall() }
            delta.id?.let { call.id = it }
            delta.name?.let { call.name = it }
            delta.argumentsDelta?.let { call.arguments.append(it) }
        }
    }

    fun build(): List<ChatToolCall> =
        calls.values.mapNotNull { call ->
            val id = call.id ?: return@mapNotNull null
            val name = call.name ?: return@mapNotNull null
            ChatToolCall(id = id, name = name, arguments = call.arguments.toString())
        }

    private class MutableToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
