package com.flowchat.app.domain.provider

import com.flowchat.app.data.network.toOpenAiRequest
import com.flowchat.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekContextProfileTest {
    private val provider = ProviderConfig(displayName = "Custom", baseUrl = "https://api.deepseek.com", defaultModel = "ignored")
    private val request = ChatRequest("deepseek-flash", listOf(ChatRequestMessage("user", "hello")), 0.7, 1.0, null)

    @Test fun knownModelsAndOfficialPathsAreRecognizedWithoutUsingDisplayNames() {
        for (model in listOf("deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")) {
            for (path in listOf("", "/", "/v1", "/v1/", "/beta/")) {
                val selected = provider.copy(baseUrl = "https://api.deepseek.com$path")
                val profile = requireNotNull(DeepSeekContextProfile.resolve(selected, request.copy(model = model)))
                assertEquals(1_000_000, profile.windowTokens)
                assertEquals(65_536, profile.outputTokens)
            }
        }
    }

    @Test fun unverifiedEndpointsAndModelNamesDoNotAcquireTheOfficialCapacity() {
        val urls = listOf("http://api.deepseek.com", "https://api.deepseek.com.example.org", "https://other.test/api.deepseek.com",
            "https://api.deepseek.com/anthropic", "https://api.deepseek.com/custom/v1", "https://api.deepseek.com:8443",
            "https://other.test", "https://user@api.deepseek.com", "https://api.deepseek.com?v=1", "not a URL")
        urls.forEach { assertNull(it, DeepSeekContextProfile.resolve(provider.copy(baseUrl = it), request)) }
        listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v5", "deepseek/flash", "DeepSeek-Flash").forEach {
            assertNull(it, DeepSeekContextProfile.resolve(provider, request.copy(model = it)))
        }
        assertNull(DeepSeekContextProfile.resolve(null, request))
    }

    @Test fun outputDefaultsAndExplicitLimitsMatchTheActualWirePayload() {
        val cases = listOf(
            request to 65_536,
            request.copy(thinkingEffort = ThinkingEffort.Max) to 131_072,
            request.copy(thinkingEffort = ThinkingEffort.XHigh) to 65_536,
            request.copy(enableThinking = false, thinkingEffort = ThinkingEffort.Max) to 8_192,
            request.copy(maxTokens = 256) to 256,
            request.copy(maxTokens = 393_216) to 393_216,
            request.copy(enableThinking = false, maxTokens = 4_096) to 4_096
        )
        cases.forEach { (selected, expected) ->
            val profile = requireNotNull(DeepSeekContextProfile.resolve(provider, selected))
            assertEquals(expected, profile.outputTokens)
            assertEquals(expected, selected.toOpenAiRequest(provider).maxTokens)
            assertEquals(1_000_000 - expected - 50_000, profile.inputTokens)
        }
        assertNull(request.toOpenAiRequest(provider.copy(baseUrl = "https://proxy.test")).maxTokens)
    }

    @Test fun invalidExplicitOutputIsRejectedBeforeSendingAndNeverClamped() {
        listOf(0, -1, 393_217, Int.MAX_VALUE).forEach { value ->
            val invalid = request.copy(maxTokens = value)
            assertThrows(IllegalArgumentException::class.java) { DeepSeekContextProfile.resolve(provider, invalid) }
            assertThrows(IllegalArgumentException::class.java) { invalid.toOpenAiRequest(provider) }
        }
    }
}
