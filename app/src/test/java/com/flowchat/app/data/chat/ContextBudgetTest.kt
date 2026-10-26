package com.flowchat.app.data.chat

import com.flowchat.app.domain.model.ChatImage
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.ProviderConfig
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {
    @Test fun cachedDeepSeekProjectionMatchesTheFullWireAcrossStreamAndSettingsChanges() {
        val provider = provider("api.deepseek.com", "deepseek-flash")
        val estimator = ContextBudget.Estimator()
        val prefix = List(100) { ChatRequestMessage(if (it % 2 == 0) "user" else "assistant",
            "中文🙂\\\"code".repeat(200), historical = it % 2 != 0) }
        var request = request(provider.defaultModel, prefix).copy(tools = listOf(
            com.flowchat.app.domain.model.ChatToolDefinition("read", "Read evidence", buildJsonObject { put("type", "object") })))
        repeat(4) { index ->
            request = request.copy(messages = prefix + ChatRequestMessage("assistant", "answer".repeat(index * 100),
                reasoningContent = "reason".repeat(index * 50)))
            assertEquals(ContextBudget.request(request, provider), estimator.request(request, provider))
            assertEquals(ContextBudget.request(request, provider), estimator.request(request, provider))
        }
        listOf(request.copy(enableThinking = false), request.copy(tools = emptyList()),
            request.copy(maxTokens = 131_072), request.copy(messages = prefix + ChatRequestMessage("tool", "Frame",
                toolCallId = "call", images = listOf(ChatImage("image/png", "a".repeat(100_000)))))).forEach {
            assertEquals(ContextBudget.request(it, provider), estimator.request(it, provider))
        }
        val other = provider.copy(baseUrl = "https://proxy.test")
        assertEquals(ContextBudget.request(request, other), estimator.request(request, other))
    }

    @Test
    fun deepSeekReplayedReasoningCountsButOpenAiUnreplayedReasoningDoesNot() {
        val reasoning = "r".repeat(12_000)
        val assistant = ChatRequestMessage(
            role = "assistant",
            content = "Checking",
            reasoningContent = reasoning,
            toolCalls = listOf(ChatToolCall("call-1", "read_task_history", "{}"))
        )
        val deepSeek = provider("api.deepseek.com", "deepseek-flash")
        val deepSeekRequest = request(deepSeek.defaultModel, listOf(ChatRequestMessage("user", "Inspect"), assistant))
        val deepSeekWithoutReasoning = deepSeekRequest.copy(messages = deepSeekRequest.messages.dropLast(1) + assistant.copy(reasoningContent = null))
        assertTrue(ContextBudget.request(deepSeekRequest, deepSeek) -
            ContextBudget.request(deepSeekWithoutReasoning, deepSeek) > 3_000)

        val openAi = provider("api.openai.com", "gpt-6-astra")
        val openAiRequest = deepSeekRequest.copy(model = openAi.defaultModel)
        val openAiWithoutReasoning = openAiRequest.copy(messages = openAiRequest.messages.dropLast(1) + assistant.copy(reasoningContent = null))
        assertEquals(ContextBudget.request(openAiWithoutReasoning, openAi), ContextBudget.request(openAiRequest, openAi))
    }

    @Test
    fun responsesAndAnthropicCountNativeProviderBlocksOnlyOnce() {
        val cases = listOf(
            provider("api.openai.com", "gpt-6-astra") to buildJsonArray {
                add(buildJsonObject { put("type", "reasoning"); put("encrypted_content", "opaque") })
            },
            provider("api.anthropic.com", "claude-sonnet-4-6") to buildJsonArray {
                add(buildJsonObject { put("type", "thinking"); put("thinking", "opaque"); put("signature", "signed") })
            }
        )
        cases.forEach { (provider, blocks) ->
            val native = ChatRequestMessage(
                role = "assistant",
                content = "visible".repeat(2_000),
                reasoningContent = "reasoning".repeat(2_000),
                toolCalls = listOf(ChatToolCall("call-1", "read_task_history", "{\"large\":\"${"x".repeat(8_000)}\"}")),
                providerData = blocks
            )
            val full = request(provider.defaultModel, listOf(ChatRequestMessage("user", "Inspect"), native))
            val blocksOnly = full.copy(messages = full.messages.dropLast(1) + native.copy(
                content = null, reasoningContent = null, toolCalls = emptyList()
            ))

            assertEquals(provider.baseUrl, ContextBudget.request(blocksOnly, provider), ContextBudget.request(full, provider))
        }
    }

    @Test
    fun imagePayloadLengthDoesNotChangeTheEstimateOrTheRequest() {
        val provider = provider("api.deepseek.com", "deepseek-flash")
        val shortImage = ChatImage("image/png", "a")
        val longImage = shortImage.copy(base64 = "a".repeat(100_000))
        val shortRequest = request(provider.defaultModel, listOf(
            ChatRequestMessage("user", "Look", images = listOf(shortImage)),
            ChatRequestMessage("tool", "Frame", toolCallId = "call-1", images = listOf(shortImage))
        ))
        val longRequest = shortRequest.copy(messages = shortRequest.messages.map { message ->
            message.copy(images = message.images.map { longImage })
        })

        assertEquals(ContextBudget.request(shortRequest, provider), ContextBudget.request(longRequest, provider))
        assertTrue(ContextBudget.request(shortRequest, provider) > ContextBudget.request(
            request(provider.defaultModel, listOf(ChatRequestMessage("user", "Look"),
                ChatRequestMessage("tool", "Frame", toolCallId = "call-1"))), provider
        ))
        assertEquals(shortImage, shortRequest.messages.first().images.single())
        assertEquals(longImage, longRequest.messages.first().images.single())
    }

    @Test
    fun outerTransportEscapingDoesNotInflateDecodedModelText() {
        val provider = provider("api.deepseek.com", "deepseek-flash")
        fun withToolText(value: String) = request(provider.defaultModel, listOf(
            ChatRequestMessage("user", "Read this result"),
            ChatRequestMessage("tool", value, toolCallId = "call-1")
        ))
        val plain = ContextBudget.request(withToolText("x".repeat(10_000)), provider)
        val backslashes = ContextBudget.request(withToolText("\\".repeat(10_000)), provider)
        val quotes = ContextBudget.request(withToolText("\"".repeat(10_000)), provider)

        assertEquals(plain, backslashes)
        assertEquals(plain, quotes)

        // Escapes that are part of a nested JSON result remain decoded message content.
        val nestedJson = """{"message":"\"quoted\"","path":"C:\\temp"}"""
        val empty = ContextBudget.request(withToolText(""), provider)
        assertEquals(ContextBudget.text(nestedJson),
            ContextBudget.request(withToolText(nestedJson), provider) - empty)
    }

    @Test
    fun unknownProviderKeepsTheLegacyEstimateAndInvalidWireConfigFallsBack() {
        val request = request("claude-sonnet-4-5", listOf(
            ChatRequestMessage("user", "你好", images = listOf(ChatImage("image/png", "payload"))),
            ChatRequestMessage("assistant", "Answer", reasoningContent = "Thinking")
        )).copy(maxTokens = 512)
        val legacy = 1_024 + request.messages.sumOf { message ->
            ContextBudget.text(message.content.orEmpty() + message.reasoningContent.orEmpty() +
                message.toolCalls.joinToString { it.name + it.arguments } +
                message.providerData.orEmpty().toString()) + message.images.size * 1_024
        }

        assertEquals(legacy, ContextBudget.request(request))
        assertEquals(legacy, ContextBudget.request(request, provider("api.anthropic.com", request.model)))
        assertEquals("payload", request.messages.first().images.single().base64)
        assertEquals(2, ContextBudget.text("你好"))
    }

    private fun request(model: String, messages: List<ChatRequestMessage>) = ChatRequest(
        model = model,
        messages = messages,
        temperature = 0.7,
        topP = 1.0,
        maxTokens = 2_048,
        enableThinking = true
    )

    private fun provider(host: String, model: String) = ProviderConfig(
        displayName = "Test",
        baseUrl = "https://$host/v1",
        defaultModel = model
    )
}
