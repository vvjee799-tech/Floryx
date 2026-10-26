package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import java.net.URI

/** Official API capabilities verified 2026-10-26. 1M is budgeted conservatively as 1,000,000. */
data class DeepSeekContextProfile(val outputTokens: Int) {
    val windowTokens: Int = 1_000_000
    val safetyTokens: Int = 50_000
    val inputTokens: Int get() = windowTokens - outputTokens - safetyTokens

    companion object {
        const val MaxOutputTokens = 393_216
        private val models = setOf("deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")

        fun resolve(provider: ProviderConfig?, request: ChatRequest): DeepSeekContextProfile? {
            if (provider == null || request.model !in models) return null
            val official = runCatching {
                val uri = URI(provider.baseUrl)
                uri.scheme.equals("https", true) && uri.host.equals("api.deepseek.com", true) &&
                    uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath.orEmpty().trimEnd('/') in setOf("", "/v1", "/beta")
            }.getOrDefault(false)
            if (!official) return null
            val support = ThinkingSupport.resolve(provider, request.model)
            val output = request.maxTokens ?: when {
                !support.isEnabled(request.enableThinking) -> 8_192
                support.normalize(request.thinkingEffort) == ThinkingEffort.Max -> 131_072
                else -> 65_536
            }
            require(output in 1..MaxOutputTokens) {
                "DeepSeek max_tokens must be between 1 and $MaxOutputTokens. / DeepSeek 输出上限须为 1–$MaxOutputTokens Token。"
            }
            return DeepSeekContextProfile(output)
        }
    }
}
