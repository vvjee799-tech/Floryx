package com.flowchat.app.data.network

import android.util.Log
import com.flowchat.app.BuildConfig
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.provider.geminiApiBaseUrl
import com.flowchat.app.domain.provider.geminiModelName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

internal class KtorGeminiClient(private val http: HttpClient, private val json: Json) : ChatCompletionClient {
    override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> = flow {
        val model = geminiModelName(request.model)
        val payload = request.toGeminiBody(provider, json)
        if (BuildConfig.DEBUG) Log.i("FlowChatNetwork", "request protocol=Gemini model=$model messages=${request.messages.size} hasApiKey=${!apiKey.isNullOrBlank()}")
        http.preparePost("${provider.geminiApiBaseUrl()}/models/$model:streamGenerateContent") {
            parameter("alt", "sse")
            timeout {
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 5 * 60 * 1000
                requestTimeoutMillis = 10 * 60 * 1000
            }
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "text/event-stream")
            if (!apiKey.isNullOrBlank()) header("x-goog-api-key", apiKey)
            if (provider.customHeadersJson.isNotBlank()) {
                json.parseToJsonElement(provider.customHeadersJson).jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
            }
            setBody(payload)
        }.execute { response ->
            if (BuildConfig.DEBUG) Log.i("FlowChatNetwork", "Gemini response status=${response.status.value}")
            if (!response.status.isSuccess()) {
                emit(ChatDelta.Error(geminiApiError(response.status.value, response.bodyAsText(), json).redact(apiKey)))
                return@execute
            }
            val parser = GeminiStreamParser(json)
            if (response.contentType()?.match(ContentType.Application.Json) == true) {
                parser.parseCompletion(response.bodyAsText()).forEach { emit(it.redactedError(apiKey)) }
            } else {
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead && !parser.terminal) {
                    val line = channel.readUTF8Line() ?: break
                    parser.parseLine(line).forEach { emit(it.redactedError(apiKey)) }
                }
                parser.endOfInput().forEach { emit(it.redactedError(apiKey)) }
                if (parser.terminal) {
                    // Consume the final HTTP bytes before returning this connection to its pool.
                    // A provider that leaves an otherwise finished SSE response open must not hold up tools.
                    try {
                        withTimeoutOrNull(1_500) {
                            while (!channel.isClosedForRead) if (channel.readUTF8Line() == null) break
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The candidate already ended explicitly; trailing transport bytes cannot undo it.
                    }
                }
            }
        }
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        if (BuildConfig.DEBUG) Log.w("FlowChatNetwork", "Gemini transport failure types=" +
            generateSequence(failure) { it.cause }.take(6).joinToString(" -> ") { it.javaClass.simpleName })
        val message = when {
            failure is IllegalArgumentException -> "Gemini 配置或请求无效：${failure.message.orEmpty()}"
            failure is HttpRequestTimeoutException || failure.javaClass.simpleName.contains("Timeout", true) ->
                "Gemini 请求超时，请检查网络或代理；也可降低思考程度后重试。"
            else -> "Gemini 连接中断，请检查网络或代理后重试。"
        }
        emit(ChatDelta.Error(message.redact(apiKey)))
    }
}

private fun String.redact(key: String?): String = if (key.isNullOrBlank()) this else replace(key, "[REDACTED]")
private fun ChatDelta.redactedError(key: String?): ChatDelta = if (this is ChatDelta.Error) copy(message = message.redact(key)) else this
