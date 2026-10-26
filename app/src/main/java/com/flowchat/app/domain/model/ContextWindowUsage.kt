package com.flowchat.app.domain.model

/** The current request's estimated context, separate from cumulative account or conversation usage. */
data class ContextWindowUsage(
    val usedTokens: Int,
    val totalTokens: Int,
    val isCompacting: Boolean = false,
    /** Null means a local fallback budget, not an identified model capability. */
    val modelWindowTokens: Int? = null,
    val outputReserveTokens: Int = 0
) {
    init {
        require(usedTokens >= 0 && totalTokens > 0)
        require(modelWindowTokens == null || modelWindowTokens >= totalTokens)
        require(outputReserveTokens >= 0)
    }

    val fraction: Float get() = (usedTokens.toDouble() / totalTokens).coerceIn(0.0, 1.0).toFloat()
    val usedPercent: Int get() = ((usedTokens.toLong() * 100 + totalTokens / 2) / totalTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
