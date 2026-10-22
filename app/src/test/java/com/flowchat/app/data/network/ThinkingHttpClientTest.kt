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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThinkingHttpClientTest {
    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }
    private fun request(model: String) = ChatRequest(model, listOf(ChatRequestMessage("user", "hello")), 0.7, 1.0, null,
        enableThinking = true, thinkingEffort = ThinkingEffort.High)
    private fun http(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(this@ThinkingHttpClientTest.json) }
        install(HttpTimeout)
    }


    @Test fun truncatedStreamsNeverCompleteRetryOrExecutePartialTools() = runTest {
        val partials = listOf(
            """{"choices":[{"delta":{"content":"partial"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"run_command","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}""")
        for (partial in partials) {
            var calls = 0
            val engine = MockEngine { calls++; respond("data: $partial\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream")) }
            http(engine).use { client ->
                val provider = ProviderConfig(displayName = "Test", baseUrl = "https://example.test/v1", defaultModel = "test")
                val deltas = KtorOpenAiCompatibleClient(client, json).streamChat(request("test"), provider, null).toList()
                assertEquals(1, calls)
                assertTrue(deltas.last() is ChatDelta.Error)
                assertTrue(deltas.none { it is ChatDelta.ToolCalls || it is ChatDelta.Done })
            }
        }
    }

    @Test fun aRejectedParameterDoesNotTriggerAnotherPaidRequest() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""{"error":{"message":"unsupported parameter"}}""", HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        http(engine).use { client ->
            val p = ProviderConfig(displayName = "Test", baseUrl = "https://example.test/v1", defaultModel = "deepseek-v4-pro")
            val deltas = KtorOpenAiCompatibleClient(client, json).streamChat(request(p.defaultModel), p, "test-only-key").toList()
            assertEquals(1, deltas.filterIsInstance<ChatDelta.Error>().size)
            assertEquals(1, calls)
        }
    }

    @Test fun chatFallbackKeepsTheSameThinkingSettings() = runTest {
        val sent = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            assertEquals("/chat/completions", request.url.encodedPath)
            sent += json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            if (sent.size == 1) respond("data: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            else respond("""{"choices":[{"message":{"content":"hello"}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        http(engine).use { client ->
            val provider = ProviderConfig(displayName = "DeepSeek", baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-v4-pro")
            val deltas = KtorOpenAiCompatibleClient(client, json).streamChat(request(provider.defaultModel).copy(enableThinking = false), provider, "test-only-key").toList()
            assertTrue(deltas.contains(ChatDelta.Content("hello")))
        }
        assertEquals(listOf(true, false), sent.map { it["stream"]!!.jsonPrimitive.boolean })
        assertTrue(sent.all { it["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content == "disabled" })
        assertTrue(sent.none { it.containsKey("reasoning_effort") })
    }

    @Test fun claudeUsesNativeAuthAndParsesThinkingAndText() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/messages", request.url.encodedPath)
            assertEquals("test-only-key", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            assertNull(request.headers[HttpHeaders.Authorization])
            val payload = json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals("adaptive", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("high", payload["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            respond(listOf(
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"summary"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque"}}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}""",
                """{"type":"message_stop"}"""
            ).joinToString("\n\n") { "data: $it" } + "\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        http(engine).use { client ->
            val provider = ProviderConfig(displayName = "Claude", baseUrl = "https://api.anthropic.com/v1", defaultModel = "claude-sonnet-4-6")
            val deltas = KtorOpenAiCompatibleClient(client, json).streamChat(request(provider.defaultModel), provider, "test-only-key").toList()
            assertEquals(listOf(ChatDelta.Reasoning("summary")), deltas.filterIsInstance<ChatDelta.Reasoning>())
            assertEquals(listOf(ChatDelta.Content("answer")), deltas.filterIsInstance<ChatDelta.Content>())
            assertEquals("opaque", deltas.filterIsInstance<ChatDelta.ProviderData>().single().blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        }
    }

    @Test fun gpt6UsesResponsesAndEmitsToolCallsOnlyAfterCompletedReasoning() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/responses", request.url.encodedPath)
            val payload = json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals("high", payload["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals(false, payload["store"]!!.jsonPrimitive.boolean)
            respond("""data: {"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs_1","encrypted_content":"opaque"},{"type":"function_call","call_id":"call_1","name":"observe_screen","arguments":"{}"}]}}

""", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        http(engine).use { client ->
            val provider = ProviderConfig(displayName = "GPT", baseUrl = "https://api.openai.com/v1", defaultModel = "gpt-6-astra")
            val deltas = KtorOpenAiCompatibleClient(client, json).streamChat(request(provider.defaultModel), provider, "test-only-key").toList()
            assertTrue(deltas.first() is ChatDelta.ProviderData)
            assertEquals(ChatDelta.ToolCalls(listOf(ChatToolCall("call_1", "observe_screen", "{}"))), deltas.last())
        }
    }
}
