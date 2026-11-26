package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ProviderConfig

object ScreenVisionSupport {
    /** Unknown/custom models are attempted without silently changing the user's model or provider. */
    fun knownTextOnly(provider: ProviderConfig?, model: String?): Boolean {
        val name = model.orEmpty().lowercase().substringAfterLast('/')
        if (provider?.apiHost() == "api.deepseek.com") {
            return name in setOf("deepseek-chat", "deepseek-reasoner", "deepseek-pro", "deepseek-v4-pro")
        }
        return name.startsWith("gpt-3.5") || name in setOf("gpt-4", "gpt-4-0613", "o1-mini", "o3-mini")
    }
}
