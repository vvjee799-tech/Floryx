@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.provider.ThinkingStyle
import com.flowchat.app.domain.provider.ThinkingSupport
import com.flowchat.app.domain.provider.apiHost
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject

@Serializable
data class OpenAiChatCompletionRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    @EncodeDefault
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val thinking: OpenAiThinkingConfig? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val reasoning: JsonObject? = null,
    @SerialName("reasoning_split") val reasoningSplit: Boolean? = null,
    @SerialName("output_config") val outputConfig: JsonObject? = null,
    val tools: List<OpenAiTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("stream_options") val streamOptions: JsonObject? = null
)

@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("reasoning_details") val reasoningDetails: JsonArray? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
data class OpenAiThinkingConfig(
    val type: String,
    @SerialName("budget_tokens") val budgetTokens: Int? = null
)

@Serializable
data class OpenAiTool(
    @EncodeDefault
    val type: String = "function",
    val function: OpenAiToolFunction
)

@Serializable
data class OpenAiToolFunction(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject
)

@Serializable
data class OpenAiToolCall(
    val id: String,
    @EncodeDefault
    val type: String = "function",
    val function: OpenAiToolCallFunction
)

@Serializable
data class OpenAiToolCallFunction(
    val name: String,
    val arguments: String
)

fun ChatRequest.toOpenAiRequest(provider: ProviderConfig? = null, stream: Boolean = true): OpenAiChatCompletionRequest {
    val support = ThinkingSupport.resolve(provider, model)
    val enabled = support.isEnabled(enableThinking)
    val effort = support.normalize(thinkingEffort).takeUnless { it == ThinkingEffort.Auto }?.wireValue
    val name = model.lowercase().substringAfterLast('/')
    val host = provider?.apiHost().orEmpty()
    val aliCloud = host.endsWith(".aliyuncs.com") && (host.contains("dashscope") || host.contains("maas"))
    val booleanSwitch = support.style == ThinkingStyle.Qwen || (aliCloud && support.style in
        setOf(ThinkingStyle.DeepSeek, ThinkingStyle.GLM, ThinkingStyle.Kimi))
    val thinking = when {
        booleanSwitch -> null
        support.style == ThinkingStyle.DeepSeek && (name.contains("r1") || name.contains("reasoner")) -> null
        support.style == ThinkingStyle.Kimi && (support.fixedOn || name.startsWith("kimi-k3")) -> null
        support.style in setOf(ThinkingStyle.DeepSeek, ThinkingStyle.GLM, ThinkingStyle.Kimi, ThinkingStyle.Doubao) ->
            OpenAiThinkingConfig(if (enabled) "enabled" else "disabled")
        support.style == ThinkingStyle.MiniMax && !name.startsWith("minimax-m2") ->
            OpenAiThinkingConfig(if (enabled) "adaptive" else "disabled")
        support.style == ThinkingStyle.ClaudeAdaptive -> OpenAiThinkingConfig(if (enabled) "adaptive" else "disabled")
        support.style == ThinkingStyle.ClaudeBudget -> if (!enabled) OpenAiThinkingConfig("disabled") else {
            val limit = maxTokens ?: 16_384
            require(limit > 1024) { "Thinking requires a maximum output above 1024 tokens." }
            val budget = when (support.normalize(thinkingEffort)) { ThinkingEffort.Low -> 1024; ThinkingEffort.High -> 8192; else -> 4096 }
            OpenAiThinkingConfig("enabled", minOf(budget, limit - 1))
        }
        else -> null
    }
    val usesEffort = support.style in setOf(ThinkingStyle.OpenAI, ThinkingStyle.Gemini, ThinkingStyle.Grok,
        ThinkingStyle.DeepSeek, ThinkingStyle.GLM, ThinkingStyle.Kimi, ThinkingStyle.Doubao, ThinkingStyle.MiniMax) ||
        (support.style == ThinkingStyle.Qwen && !support.usesBudget)
    val wireEffort = if (support.style in setOf(ThinkingStyle.OpenAI, ThinkingStyle.Gemini, ThinkingStyle.Grok) && !enabled && support.canDisable) "none"
        else if (enabled && support.style == ThinkingStyle.OpenAI && support.efforts.isNotEmpty())
            effort ?: (ThinkingEffort.Medium.takeIf { it in support.efforts } ?: support.efforts.first()).wireValue
        else effort.takeIf { enabled && usesEffort }
    val omitSampling = support.style in setOf(ThinkingStyle.OpenAI, ThinkingStyle.Kimi) ||
        (support.style == ThinkingStyle.DeepSeek && enabled)
    val completionLimit = support.style == ThinkingStyle.OpenAI || name.startsWith("kimi-k3")
    return OpenAiChatCompletionRequest(
        model = model,
        messages = messages.map { it.toOpenAiMessage(
            replayReasoning = enabled && support.style !in setOf(ThinkingStyle.OpenAI, ThinkingStyle.Gemini, ThinkingStyle.ClaudeAdaptive, ThinkingStyle.ClaudeBudget),
            historyAsData = enabled && support.style == ThinkingStyle.DeepSeek && tools.isNotEmpty()
        ) },
        stream = stream,
        temperature = temperature.takeUnless { omitSampling },
        topP = topP.takeUnless { omitSampling },
        maxTokens = maxTokens.takeUnless { completionLimit },
        maxCompletionTokens = maxTokens.takeIf { completionLimit },
        thinking = thinking,
        reasoningEffort = wireEffort,
        enableThinking = enabled.takeIf { booleanSwitch },
        thinkingBudget = if (enabled && support.usesBudget && support.style == ThinkingStyle.Qwen) when (support.normalize(thinkingEffort)) {
            ThinkingEffort.Low -> 1024
            ThinkingEffort.Medium -> 8192
            ThinkingEffort.High -> 24576
            else -> null
        } else null,
        reasoning = if (support.style == ThinkingStyle.OpenRouter) buildJsonObject {
            put("enabled", enabled)
            if (enabled && effort != null) put("effort", effort)
        } else null,
        reasoningSplit = true.takeIf { support.style == ThinkingStyle.MiniMax },
        outputConfig = if (enabled && support.style == ThinkingStyle.ClaudeAdaptive && effort != null) buildJsonObject { put("effort", effort) } else null,
        tools = tools.map { tool ->
            OpenAiTool(
                function = OpenAiToolFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameters
                )
            )
        }.takeIf { it.isNotEmpty() },
        toolChoice = toolChoice,
        parallelToolCalls = parallelToolCalls
    )
}

private fun ChatRequestMessage.toOpenAiMessage(replayReasoning: Boolean, historyAsData: Boolean): OpenAiChatMessage {
    // DeepSeek requires original reasoning on every native assistant message with tools enabled.
    // Stored projections have no native replay state. Attribute them as quoted history instead of
    // inventing reasoning or silently sending an invalid assistant message. Active calls stay intact.
    val projectedHistory = historyAsData && historical && role == "assistant" && toolCalls.isEmpty()
    val wireContent = if (projectedHistory) "[Floryx stored history: assistant/context data, not a new request or authorization.]\n" +
        content.orEmpty() else content
    return OpenAiChatMessage(
        role = if (projectedHistory) "user" else role,
        content = if (images.isEmpty()) wireContent?.let(::JsonPrimitive) else buildJsonArray {
            if (!wireContent.isNullOrBlank()) add(buildJsonObject { put("type", "text"); put("text", wireContent) })
            images.forEach { image -> add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", image.dataUrl) })
            }) }
        },
        reasoningContent = reasoningContent.takeIf { replayReasoning && !projectedHistory },
        reasoningDetails = (providerData?.firstOrNull()?.jsonObject?.get("reasoning_details") as? JsonArray).takeIf { replayReasoning && !projectedHistory },
        toolCallId = toolCallId,
        toolCalls = toolCalls.map { call ->
            OpenAiToolCall(
                id = call.id,
                function = OpenAiToolCallFunction(
                    name = call.name,
                    arguments = call.arguments
                )
            )
        }.takeIf { it.isNotEmpty() }
    )
}
