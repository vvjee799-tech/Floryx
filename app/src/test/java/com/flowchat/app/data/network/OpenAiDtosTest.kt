package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.tools.AgentToolDefinitions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiDtosTest {
    private val json = Json { explicitNulls = false }
    private val request = ChatRequest(
        model = "deepseek-v4-flash",
        messages = listOf(ChatRequestMessage(role = "user", content = "hello")),
        temperature = 1.0,
        topP = 1.0,
        maxTokens = null
    )

    @Test
    fun disablesDeepSeekThinkingWhenTheConversationSwitchIsOff() {
        val provider = ProviderConfig(
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash"
        )
        val thinkingOffRequest = request.copy(enableThinking = false)

        val payload = json.encodeToString(thinkingOffRequest.toOpenAiRequest(provider))

        assertTrue(payload.contains(""""thinking":{"type":"disabled"}"""))
    }

    @Test
    fun enablesDeepSeekThinkingModeWhenConversationThinkingIsOn() {
        val provider = ProviderConfig(
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash"
        )
        val thinkingRequest = request.copy(enableThinking = true)

        val payload = json.encodeToString(thinkingRequest.toOpenAiRequest(provider))

        assertTrue(payload.contains(""""thinking":{"type":"enabled"}"""))
    }

    @Test
    fun streamingRequestExplicitlyIncludesStreamTrue() {
        val provider = ProviderConfig(
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash"
        )

        val payload = json.encodeToString(request.toOpenAiRequest(provider, stream = true))

        assertTrue(payload.contains(""""stream":true"""))
    }

    @Test
    fun recognizesDeepSeekModelsOnCompatibleEndpoints() {
        val provider = ProviderConfig(
            displayName = "OpenAI compatible",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "deepseek-v4-flash"
        )

        val payload = json.encodeToString(request.toOpenAiRequest(provider))

        assertTrue(payload.contains(""""thinking":{"type":"enabled"}"""))
    }

    @Test
    fun canCreateNonStreamingFallbackRequest() {
        val provider = ProviderConfig(
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash"
        )

        val payload = json.encodeToString(request.toOpenAiRequest(provider, stream = false))

        assertTrue(payload.contains(""""stream":false"""))
    }

    @Test
    fun serializesOpenAiCompatibleToolDefinitionsAndAutoToolChoice() {
        val toolRequest = request.copy(
            tools = listOf(AgentToolDefinitions.webSearch()),
            toolChoice = "auto",
            parallelToolCalls = false
        )

        val payload = json.encodeToString(toolRequest.toOpenAiRequest(stream = true))

        assertTrue(payload.contains(""""tools":["""))
        assertTrue(payload.contains(""""type":"function""""))
        assertTrue(payload.contains(""""name":"web_search""""))
        assertTrue(payload.contains(""""parameters":{"type":"object""""))
        assertTrue(payload.contains(""""tool_choice":"auto""""))
        assertTrue(payload.contains(""""parallel_tool_calls":false"""))
    }

    @Test
    fun serializesAssistantToolCallAndToolResultMessages() {
        val toolRequest = request.copy(
            messages = listOf(
                ChatRequestMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        AgentToolDefinitions.toolCall(
                            id = "call_1",
                            name = "web_search",
                            arguments = """{"query":"FlowChat"}"""
                        )
                    )
                ),
                ChatRequestMessage(
                    role = "tool",
                    content = "search result",
                    toolCallId = "call_1"
                )
            )
        )

        val payload = json.encodeToString(toolRequest.toOpenAiRequest(stream = true))

        assertTrue(payload.contains(""""role":"assistant""""))
        assertTrue(payload.contains(""""tool_calls":["""))
        assertTrue(payload.contains(""""id":"call_1""""))
        assertTrue(payload.contains(""""arguments":"{\"query\":\"FlowChat\"}""""))
        assertTrue(payload.contains(""""role":"tool""""))
        assertTrue(payload.contains(""""tool_call_id":"call_1""""))
        assertTrue(payload.contains(""""content":"search result""""))
    }

    @Test
    fun serializesAssistantContentAndReasoningForThinkingToolFollowUp() {
        val assistantMessage = ChatRequestMessage(
            role = "assistant",
            content = "I will check.",
            reasoningContent = "The user asked for current information.",
            toolCalls = listOf(
                AgentToolDefinitions.toolCall(
                    id = "call_1",
                    name = "web_search",
                    arguments = """{"query":"FlowChat"}"""
                )
            )
        )

        val payload = json.encodeToString(
            request.copy(messages = listOf(assistantMessage)).toOpenAiRequest(stream = true)
        )

        assertTrue(payload.contains(""""content":"I will check.""""))
        assertTrue(payload.contains(""""reasoning_content":"The user asked for current information.""""))
        assertTrue(payload.contains("\"tool_calls\":["))
    }
}
