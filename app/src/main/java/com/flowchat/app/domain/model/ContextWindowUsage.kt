package com.flowchat.app.domain.model

/** The current request's estimated context, separate from cumulative account or conversation usage. */
data class ContextWindowUsage(
    val usedTokens: Int,
    val totalTokens: Int,
    val isCompacting: Boolean = false
) {
    init {
        require(usedTokens >= 0 && totalTokens > 0)
    }

    val fraction: Float get() = (usedTokens.toDouble() / totalTokens).coerceIn(0.0, 1.0).toFloat()
    val usedPercent: Int get() = ((usedTokens.toLong() * 100 + totalTokens / 2) / totalTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
