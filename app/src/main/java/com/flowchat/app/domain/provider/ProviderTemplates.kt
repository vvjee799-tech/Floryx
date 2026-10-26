package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ProviderConfig

object ProviderTemplates {
    fun defaultCustomProvider(): ProviderConfig = ProviderConfig(
        id = CUSTOM_PROVIDER_ID,
        displayName = "Custom configuration",
        baseUrl = "",
        defaultModel = ""
    )

    fun popularPresets(): List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "preset-chatgpt",
            displayName = "ChatGPT",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-5.4-mini"
        ),
        ProviderPreset(
            id = "preset-deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-flash"
        ),
        ProviderPreset(
            id = "preset-gemini",
            displayName = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-3.5-flash"
        ),
        ProviderPreset("preset-claude", "Claude", "https://api.anthropic.com/v1", "claude-sonnet-4-6"),
        ProviderPreset("preset-qwen", "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        ProviderPreset("preset-glm", "GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7"),
        ProviderPreset("preset-kimi", "Kimi", "https://api.moonshot.cn/v1", "kimi-k2.5"),
        ProviderPreset("preset-grok", "Grok", "https://api.x.ai/v1", "grok-4.3"),
        ProviderPreset("preset-minimax", "MiniMax", "https://api.minimax.io/v1", "MiniMax-M2.7"),
        ProviderPreset("preset-doubao", "Doubao", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-0-pro-260215")
    )

    const val CUSTOM_PROVIDER_ID = "provider-custom"
}

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val iconResName: String? = null
)
