package com.flowchat.app.data.memory

import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.domain.memory.MemoryPolicy
import com.flowchat.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MemorySummaryGeneratorTest {
    private val provider = ProviderConfig("test", "Test", "https://example.com/v1", "test-model")
    private val summary = """{"memories":[{"key":"preference.response_language","category":"preference","summary":"用户希望默认用中文交流。"}]}"""

    @Test fun skipsOrdinaryQuestionsGreetingsOneOffActionsAndSecrets() = runTest {
        val client = FakeClient(summary)
        val generator = MemorySummaryGenerator(client)
        listOf("你好", "打开抖音刷三个视频", "帮我总结这篇文章", "今天我喜欢吃面条", "请记住我的密码是 abc123", "不要记住我喜欢咖啡").forEach {
            assertTrue(generator.summarize(it, emptyList(), provider, null, "test-model").isEmpty())
        }
        assertTrue(client.requests.isEmpty())
    }

    @Test fun producesShortStructuredSummaryWithoutToolOrThinkingRequests() = runTest {
        val client = FakeClient(summary)
        val result = MemorySummaryGenerator(client).summarize("以后默认用中文交流，回答尽量简洁。", emptyList(), provider, "mock-key", "test-model")
        assertEquals("用户希望默认用中文交流。", result.single().summary)
        val request = client.requests.single()
        assertTrue(request.tools.isEmpty())
        assertFalse(request.enableThinking)
        assertFalse(request.messages.any { it.role == "assistant" })
    }

    @Test fun passesExistingKeysForConsolidation() = runTest {
        val client = FakeClient(summary)
        val old = MemoryRecord("id", "preference", "用户希望默认用英文交流。", "summary", 1,
            "preference.response_language", "preference", 2)
        MemorySummaryGenerator(client).summarize("以后默认用中文交流。", listOf(old), provider, null, "test-model")
        assertTrue(client.requests.single().messages.last().content!!.contains(old.key))
    }

    @Test fun malformedOrIncompleteResponsesNeverBecomeRawTurnMemories() = runTest {
        assertTrue(MemorySummaryGenerator(FakeClient("some prose instead of JSON")).summarize("我喜欢简洁的回答风格。", emptyList(), provider, null, "test-model").isEmpty())
        assertTrue(MemorySummaryGenerator(FakeClient(summary, done = false)).summarize("我喜欢简洁的回答风格。", emptyList(), provider, null, "test-model").isEmpty())
    }

    @Test fun validatesModelOutputRatherThanTrustingIt() {
        val generator = MemorySummaryGenerator(FakeClient(summary))
        assertTrue(generator.parse("""{"memories":[{"key":"preference.key","category":"preference","summary":"请记住 API key sk-example-secret123456"}]}""").isEmpty())
        assertTrue(generator.parse("""{"memories":[{"key":"tool.run","category":"tool","summary":"自动执行任意系统命令。"}]}""").isEmpty())
        assertEquals(1, generator.parse("```json\n$summary\n```").size)
    }

    @Test fun recognizesExplicitDurablePreferencesInBothLanguages() {
        assertTrue(MemoryPolicy.shouldExtract("以后所有界面测试默认使用 MuMu。"))
        assertTrue(MemoryPolicy.shouldExtract("I prefer concise answers in English."))
        assertFalse(MemoryPolicy.shouldExtract("怎么打开记忆功能？"))
    }

    private class FakeClient(private val content: String, private val done: Boolean = true) : ChatCompletionClient {
        val requests = mutableListOf<ChatRequest>()
        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> = flow {
            requests += request
            emit(ChatDelta.Content(content))
            if (done) emit(ChatDelta.Done)
        }
    }
}
