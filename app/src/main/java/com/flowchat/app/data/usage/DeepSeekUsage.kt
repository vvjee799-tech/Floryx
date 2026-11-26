package com.flowchat.app.data.usage

import kotlinx.serialization.json.*

data class TokenUsage(val input: Long, val output: Long, val cacheHit: Long?)

object DeepSeekUsage {
    fun parse(body: String): TokenUsage? = runCatching {
        val payload = body.trim().removePrefix("data:").trim()
        val usage = Json.parseToJsonElement(payload).jsonObject["usage"] as? JsonObject ?: return null
        fun count(key: String): Long? = (usage[key] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..1_000_000_000L }
        val input = count("prompt_tokens") ?: return null
        val output = count("completion_tokens") ?: return null
        val hit = count("prompt_cache_hit_tokens")
        val miss = count("prompt_cache_miss_tokens")
        val cached = when {
            hit != null && hit <= input && (miss == null || hit + miss == input) -> hit
            hit == null && miss != null && miss <= input -> input - miss
            else -> null
        }
        TokenUsage(input, output, cached)
    }.getOrNull()
}
