package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import java.net.URI
import java.util.Locale

/** Known DeepSeek models on official or compatible endpoints. 1M is budgeted as 1,000,000. */
data class DeepSeekContextProfile(val outputTokens: Int) {
    val windowTokens: Int = 1_000_000
    val safetyTokens: Int = 50_000
    val inputTokens: Int get() = windowTokens - outputTokens - safetyTokens

    companion object {
        const val MaxOutputTokens = 393_216
        private val models = setOf(
            "deepseek-flash", "deepseek-v4-pro", "deepseek-v4-pro-0813",
            "deepseek-v4-flash", "deepseek-v4-flash-0731", "deepseek-v4-flash-vision-exp",
            "deepseek-v4.1-flash", "deepseek-v4.1-flash-1017"
        )

        fun resolve(provider: ProviderConfig?, request: ChatRequest): DeepSeekContextProfile? {
            // Normalize only for capability lookup; preserve the gateway's exact model ID on the wire.
            val model = request.model.trim().lowercase(Locale.ROOT).substringAfterLast('/')
            if (provider == null || model !in models) return null
            if (provider.isGoogleGemini() || provider.apiHost() == "api.anthropic.com") return null
            val compatible = runCatching {
                val uri = URI(provider.baseUrl)
                (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
            }.getOrDefault(false)
            if (!compatible) return null
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
