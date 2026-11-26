package com.flowchat.app.data.network

import com.flowchat.app.data.chat.ContextBudget
import com.flowchat.app.domain.chat.TaskExecutionBudget
import com.flowchat.app.domain.chat.TaskExecutionBudgetLimits
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the actual HTTP fallback paths; every request stays inside MockEngine. */
@RunWith(RobolectricTestRunner::class)
class TaskModelFallbackBudgetTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun chatCompletionsFallbackCannotDispatchWithOnlyOneModelReservation() = runTest {
        exerciseFallback(native = false, allowance = 1)
    }

    @Test fun chatCompletionsFallbackReservesAndSerializesItsOwnCappedRequest() = runTest {
        exerciseFallback(native = false, allowance = 2)
    }

    @Test fun responsesFallbackCannotDispatchWithOnlyOneModelReservation() = runTest {
        exerciseFallback(native = true, allowance = 1)
    }

    @Test fun responsesFallbackReservesAndSerializesItsOwnCappedRequest() = runTest {
        exerciseFallback(native = true, allowance = 2)
    }

    private suspend fun exerciseFallback(native: Boolean, allowance: Int) {
        val provider = ProviderConfig(
            id = "fixture", displayName = "Fixture",
            baseUrl = if (native) "https://api.openai.com/v1" else "https://fixture.example/v1",
            defaultModel = if (native) "gpt-6-astra" else "fixture-chat"
        )
        val request = ChatRequest(provider.defaultModel,
            listOf(ChatRequestMessage("user", "A bounded fallback request")),
            temperature = 0.0, topP = 1.0, maxTokens = 100, enableThinking = false)
        val budget = TaskExecutionBudget(limits = TaskExecutionBudgetLimits(
            stageModelCalls = allowance, totalModelCalls = allowance), clockMillis = { 0L })
        val dispatched = mutableListOf<JsonObject>()
        val reserved = mutableListOf<ChatRequest>()
        val estimates = mutableListOf<Long>()
        val refusal = IllegalStateException("model dispatch allowance exhausted")
        var preparationAttempts = 0
        val taskBudget = TaskModelRequestBudget { original, selectedProvider ->
            preparationAttempts += 1
            assertEquals(provider, selectedProvider)
            val prepared = original.copy(maxTokens = if (preparationAttempts == 1) 31 else 17)
            val estimate = ContextBudget.request(prepared, selectedProvider).toLong() + requireNotNull(prepared.maxTokens)
            if (!budget.reserveModel(estimate).canDispatch) throw refusal
            reserved += prepared
            estimates += estimate
            // Accounting succeeds before its corresponding HTTP request exists.
            assertEquals(dispatched.size + 1, reserved.size)
            prepared
        }
        val engine = MockEngine { actual ->
            assertEquals(if (native) "/v1/responses" else "/v1/chat/completions", actual.url.encodedPath)
            dispatched += json.parseToJsonElement(
                (actual.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals(dispatched.size, reserved.size)
            if (dispatched.size == 1) {
                // A successful but entirely empty stream triggers the existing fallback.
                respond("", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                val completion = if (native)
                    """{"output":[{"type":"message","content":[{"type":"output_text","text":"fallback answer"}]}]}"""
                else """{"choices":[{"message":{"content":"fallback answer"}}]}"""
                respond(completion, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        HttpClient(engine) {
            install(HttpTimeout)
            install(ContentNegotiation) { json(this@TaskModelFallbackBudgetTest.json) }
        }.use { http ->
            val client = KtorOpenAiCompatibleClient(http, json)
            val result = runCatching {
                withContext(taskBudget) { client.budgetedStreamChat(request, provider, null).toList() }
            }
            assertEquals(2, preparationAttempts)
            if (allowance == 1) {
                assertEquals(refusal::class, result.exceptionOrNull()!!::class)
                assertEquals(refusal.message, result.exceptionOrNull()!!.message)
            } else {
                assertTrue(result.getOrThrow().contains(ChatDelta.Content("fallback answer")))
            }
        }

        assertEquals(allowance, dispatched.size)
        assertEquals(allowance, reserved.size)
        assertEquals(allowance, budget.snapshot().totalModelCalls)
        assertEquals(estimates.sum(), budget.snapshot().totalEstimatedTokens)
        assertEquals(if (allowance == 1) listOf(true) else listOf(true, false),
            dispatched.map { it.getValue("stream").jsonPrimitive.boolean })
        val capField = if (native) "max_output_tokens" else "max_tokens"
        assertEquals(if (allowance == 1) listOf(31) else listOf(31, 17),
            dispatched.map { it.getValue(capField).jsonPrimitive.int })
        dispatched.forEachIndexed { index, body ->
            assertEquals(reserved[index].toWireBody(provider, stream = index == 0, json = json), body)
        }
    }
}
