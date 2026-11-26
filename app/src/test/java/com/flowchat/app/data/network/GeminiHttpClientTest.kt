package com.flowchat.app.data.network

import com.flowchat.app.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiHttpClientTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val provider = ProviderConfig(displayName = "Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai", defaultModel = "models/gemini-3.5-flash")
    private val request = ChatRequest(provider.defaultModel, listOf(ChatRequestMessage("user", "hello")), 0.7, 1.0, null)
    private fun http(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
        install(ContentNegotiation) { json(this@GeminiHttpClientTest.json) }
    }

    @Test fun existingOpenAiConfigurationsUseNativeAuthAndStreamingWithoutKeysInTheUrl() = runTest {
        var calls = 0
        val engine = MockEngine { sent ->
            calls++
            assertEquals("/v1beta/models/gemini-3.5-flash:streamGenerateContent", sent.url.encodedPath)
            assertEquals("sse", sent.url.parameters["alt"])
            assertEquals("test-key", sent.headers["x-goog-api-key"])
            assertNull(sent.headers[HttpHeaders.Authorization])
            assertFalse(sent.url.toString().contains("test-key"))
            val body = json.parseToJsonElement((sent.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertTrue(body.containsKey("contents"))
            assertFalse(body.containsKey("messages"))
            respond("""data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"summary"},{"text":"hello"}]},"finishReason":"STOP"}]}

""", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        http(engine).use { client ->
            val deltas = KtorOpenAiCompatibleClient(client, json).streamChat(request, provider, "test-key").toList()
            assertTrue(deltas.contains(ChatDelta.Reasoning("summary")))
            assertTrue(deltas.contains(ChatDelta.Content("hello")))
            assertEquals(ChatDelta.Done, deltas.last())
        }
        assertEquals(1, calls)
    }

    @Test fun paginatedCatalogUsesNativeIdsAndFiltersNonChatModels() = runTest {
        var pages = 0
        val engine = MockEngine { sent ->
            pages++
            assertEquals("/v1beta/models", sent.url.encodedPath)
            assertEquals("test-key", sent.headers["x-goog-api-key"])
            assertNull(sent.headers[HttpHeaders.Authorization])
            val body = if (pages == 1) {
                assertNull(sent.url.parameters["pageToken"])
                """{"models":[{"name":"models/gemini-3.5-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]},{"name":"models/gemini-3-pro-image","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"second"}"""
            } else {
                assertEquals("second", sent.url.parameters["pageToken"])
                """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-3.5-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-3.1-flash-tts-preview","supportedGenerationMethods":["generateContent"]}]}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        http(engine).use { client ->
            assertEquals(listOf("gemini-3.5-flash", "gemini-3.8-flash"), KtorModelCatalogClient(client, json).listModels(provider, "test-key"))
        }
        assertEquals(2, pages)
    }

    @Test fun quotaFailuresAreActionableAndNeverTriggerAnotherPaidRequestOrRevealKeys() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("""{"error":{"code":429,"message":"Quota exceeded test-key"}}""",
            HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/json")) }
        http(engine).use { client ->
            val error = KtorOpenAiCompatibleClient(client, json).streamChat(request, provider, "test-key").toList().single() as ChatDelta.Error
            assertTrue(error.message.contains("配额"))
            assertFalse(error.message.contains("test-key"))
        }
        assertEquals(1, calls)
    }

    @Test fun truncatedStreamsNeverRetryOrExecutePartialFunctions() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("""data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"run_command","args":{}}}]}}]}

""", headers = headersOf(HttpHeaders.ContentType, "text/event-stream")) }
        http(engine).use { client ->
            val deltas = KtorOpenAiCompatibleClient(client, json).streamChat(request, provider, "test-key").toList()
            assertTrue(deltas.last() is ChatDelta.Error)
            assertTrue(deltas.none { it is ChatDelta.ToolCalls || it is ChatDelta.Done })
        }
        assertEquals(1, calls)
    }

    @Test fun errorsInsideSuccessfulSseResponsesAlsoRedactCredentials() = runTest {
        val engine = MockEngine { respond("data: {\"error\":{\"code\":429,\"message\":\"Quota exceeded test-key\"}}\n\n",
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream")) }
        http(engine).use { client ->
            val error = KtorOpenAiCompatibleClient(client, json).streamChat(request, provider, "test-key").toList().single() as ChatDelta.Error
            assertTrue(error.message.contains("429"))
            assertFalse(error.message.contains("test-key"))
        }
    }

    @Test fun cancellingARequestPropagatesCancellation() = runTest {
        val engine = MockEngine { throw CancellationException("cancelled by user") }
        http(engine).use { client ->
            val failure = runCatching { KtorOpenAiCompatibleClient(client, json).streamChat(request, provider, "test-key").toList() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }
    }

    @Test fun directGoogleCallsUseTheDedicatedClientAndGatewaysKeepTheirOriginalClient() = runTest {
        var nativeCalls = 0
        var genericCalls = 0
        val native = MockEngine {
            nativeCalls++
            respond("""{"candidates":[{"content":{"parts":[{"text":"native"}]},"finishReason":"STOP"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val generic = MockEngine {
            genericCalls++
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"gateway\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        http(generic).use { original -> http(native).use { dedicated ->
            val client = KtorOpenAiCompatibleClient(original, json, dedicated)
            assertTrue(client.streamChat(request, provider, "test-key").toList().contains(ChatDelta.Content("native")))
            assertEquals(0, genericCalls)
            assertTrue(client.streamChat(request, provider.copy(baseUrl = "https://gateway.test/v1"), "test-key").toList().contains(ChatDelta.Content("gateway")))
        } }
        assertEquals(1, nativeCalls)
        assertEquals(1, genericCalls)
    }
}
