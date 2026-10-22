package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import java.net.URI
import java.util.Locale

enum class ThinkingStyle { Unsupported, OpenAI, DeepSeek, Gemini, Qwen, ClaudeAdaptive, ClaudeBudget, GLM, Kimi, Doubao, Grok, MiniMax, OpenRouter }

data class ThinkingSupport(
    val style: ThinkingStyle,
    val canDisable: Boolean = true,
    val efforts: List<ThinkingEffort> = emptyList(),
    val fixedOn: Boolean = false,
    val usesBudget: Boolean = false
) {
    val supported get() = style != ThinkingStyle.Unsupported
    fun isEnabled(requested: Boolean) = supported && (fixedOn || !canDisable || requested)
    fun normalize(effort: ThinkingEffort) = effort.takeIf { it == ThinkingEffort.Auto || it in efforts } ?: ThinkingEffort.Auto

    companion object {
        private val lowHighMax = listOf(ThinkingEffort.Low, ThinkingEffort.High, ThinkingEffort.Max)
        private val standard = listOf(ThinkingEffort.Low, ThinkingEffort.Medium, ThinkingEffort.High)
        private val extended = standard + ThinkingEffort.XHigh
        private val full = extended + ThinkingEffort.Max

        /** Match the endpoint as well as model IDs; never use display names or arbitrary URL substrings. */
        fun resolve(provider: ProviderConfig?, model: String): ThinkingSupport {
            val name = model.lowercase(Locale.ROOT).substringAfterLast('/')
            val host = provider?.apiHost().orEmpty()
            if (host == "openrouter.ai") return resolve(null, model).let {
                if (it.supported) it.copy(style = ThinkingStyle.OpenRouter, usesBudget = false) else it
            }
            return when {
                name.startsWith("gpt-6") -> ThinkingSupport(ThinkingStyle.OpenAI, canDisable = false, efforts = full, fixedOn = true)
                name.startsWith("gpt-5") && name.contains("codex") -> ThinkingSupport(ThinkingStyle.OpenAI, canDisable = false,
                    fixedOn = true, efforts = if (name.startsWith("gpt-5-codex") || name.startsWith("gpt-5.1")) standard else extended)
                name == "gpt-5-pro" || name.startsWith("gpt-5-pro-") ->
                    ThinkingSupport(ThinkingStyle.OpenAI, canDisable = false, fixedOn = true, efforts = listOf(ThinkingEffort.High))
                name.startsWith("gpt-5") && name.contains("pro") ->
                    ThinkingSupport(ThinkingStyle.OpenAI, canDisable = false, fixedOn = true, efforts = listOf(ThinkingEffort.Medium, ThinkingEffort.High, ThinkingEffort.XHigh))
                name.startsWith("gpt-5") && !name.contains("chat") -> {
                    val firstGeneration = name.matches(Regex("gpt-5(?:-(?:mini|nano))?(?:-\\d{4}-\\d{2}-\\d{2})?"))
                    ThinkingSupport(ThinkingStyle.OpenAI, canDisable = !firstGeneration && !name.contains("pro"),
                        efforts = if (firstGeneration) listOf(ThinkingEffort.Minimal) + standard
                            else if (name.startsWith("gpt-5.1")) standard else extended)
                }
                name.startsWith("o1-preview") || name.startsWith("o1-mini") -> ThinkingSupport(ThinkingStyle.OpenAI, canDisable = false, fixedOn = true)
                name.matches(Regex("o[134](?:-.*)?")) -> ThinkingSupport(ThinkingStyle.OpenAI, canDisable = false, efforts = standard, fixedOn = true)
                name.startsWith("deepseek") && (name.contains("v4") || name.contains("v3.2") || name == "deepseek-flash") ->
                    ThinkingSupport(ThinkingStyle.DeepSeek, efforts = if (name.contains("v4") || name == "deepseek-flash") lowHighMax else emptyList())
                name.startsWith("deepseek") && (name.contains("reasoner") || name.contains("r1")) ->
                    ThinkingSupport(ThinkingStyle.DeepSeek, canDisable = false, fixedOn = true)
                name.startsWith("gemini-2.5") -> ThinkingSupport(ThinkingStyle.Gemini,
                    canDisable = !name.contains("pro"), efforts = standard, fixedOn = name.contains("pro"))
                name.startsWith("gemini-3") || name.startsWith("gemini-4") -> ThinkingSupport(ThinkingStyle.Gemini,
                    canDisable = false, fixedOn = true,
                    efforts = if (name.startsWith("gemini-3-pro") || name.startsWith("gemini-3.1-pro"))
                        listOf(ThinkingEffort.Low, ThinkingEffort.High) else standard)
                name.startsWith("qwen3.8") -> ThinkingSupport(ThinkingStyle.Qwen,
                    efforts = listOf(ThinkingEffort.Low, ThinkingEffort.Medium, ThinkingEffort.XHigh))
                name.startsWith("qwen3") || name.startsWith("qwen-plus") || name.startsWith("qwen-flash") -> {
                    val thinkingOnly = name.contains("thinking")
                    val instructOnly = name.contains("instruct") || name.contains("coder")
                    if (instructOnly) ThinkingSupport(ThinkingStyle.Unsupported)
                    else ThinkingSupport(ThinkingStyle.Qwen, canDisable = !thinkingOnly, fixedOn = thinkingOnly,
                        efforts = standard, usesBudget = true)
                }
                name.startsWith("qwq") || name.startsWith("qvq") -> ThinkingSupport(ThinkingStyle.Qwen,
                    canDisable = false, fixedOn = true)
                name.startsWith("claude-") -> claude(name)
                name.startsWith("glm-5.3") -> ThinkingSupport(ThinkingStyle.GLM, canDisable = false, fixedOn = true, efforts = lowHighMax)
                name.startsWith("glm-5.2") -> ThinkingSupport(ThinkingStyle.GLM, efforts = listOf(ThinkingEffort.High, ThinkingEffort.Max))
                name.startsWith("glm-5") || name.startsWith("glm-4.5") || name.startsWith("glm-4.6") || name.startsWith("glm-4.7") ->
                    ThinkingSupport(ThinkingStyle.GLM)
                name.startsWith("kimi-k3") -> ThinkingSupport(ThinkingStyle.Kimi, canDisable = false, fixedOn = true, efforts = lowHighMax)
                name.startsWith("kimi-k2.5") || name.startsWith("kimi-k2.6") -> ThinkingSupport(ThinkingStyle.Kimi)
                name.startsWith("kimi") && (name.contains("thinking") || name.contains("k2.7-code")) ->
                    ThinkingSupport(ThinkingStyle.Kimi, canDisable = false, fixedOn = true)
                name.startsWith("doubao-seed") || name.startsWith("doubao-1-5-thinking") ->
                    ThinkingSupport(ThinkingStyle.Doubao, efforts = listOf(ThinkingEffort.Minimal) + standard)
                name.startsWith("grok-3-mini") -> ThinkingSupport(ThinkingStyle.Grok, canDisable = false, fixedOn = true,
                    efforts = listOf(ThinkingEffort.Low, ThinkingEffort.High))
                name.startsWith("grok-4.7") || name.startsWith("grok-4.6") ->
                    ThinkingSupport(ThinkingStyle.Grok, canDisable = false, fixedOn = true, efforts = extended)
                name.startsWith("grok-4.5") -> ThinkingSupport(ThinkingStyle.Grok, canDisable = false, fixedOn = true, efforts = standard)
                name.startsWith("grok-4.3") -> ThinkingSupport(ThinkingStyle.Grok, efforts = standard)
                name.startsWith("grok-4") && !name.contains("non-reasoning") ->
                    ThinkingSupport(ThinkingStyle.Grok, canDisable = false, fixedOn = true)
                name.startsWith("minimax-m3.1") -> ThinkingSupport(ThinkingStyle.MiniMax,
                    canDisable = false, fixedOn = true, efforts = full)
                name.startsWith("minimax-m3") -> ThinkingSupport(ThinkingStyle.MiniMax)
                name.startsWith("minimax-m2") -> ThinkingSupport(ThinkingStyle.MiniMax, canDisable = false, fixedOn = true)
                else -> ThinkingSupport(ThinkingStyle.Unsupported)
            }
        }

        private fun claude(name: String): ThinkingSupport {
            val modern = Regex("(?:opus|sonnet)-(?:4[.-][6-9]|5)").containsMatchIn(name) ||
                name.contains("haiku-5") || name.contains("mythos") || name.contains("fable")
            if (modern) {
                val fixed = name.contains("opus-5-5") || name.contains("opus-5.5") ||
                    name.contains("sonnet-5-5") || name.contains("sonnet-5.5") || name.contains("fable") || name.contains("mythos")
                val hasXHigh = !name.contains("4-6") && !name.contains("4.6")
                return ThinkingSupport(ThinkingStyle.ClaudeAdaptive, canDisable = !fixed, fixedOn = fixed,
                    efforts = if (hasXHigh) full else standard + ThinkingEffort.Max)
            }
            val legacyThinking = name.contains("3-7") || name.contains("3.7") ||
                name.contains("sonnet-4") || name.contains("opus-4") || name.contains("haiku-4-5")
            return if (legacyThinking) ThinkingSupport(ThinkingStyle.ClaudeBudget, efforts = standard, usesBudget = true)
                else ThinkingSupport(ThinkingStyle.Unsupported)
        }
    }
}

fun ProviderConfig.apiHost(): String = runCatching { URI(baseUrl).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
