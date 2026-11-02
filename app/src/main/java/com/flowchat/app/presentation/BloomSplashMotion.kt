package com.flowchat.app.presentation

internal const val BloomSplashDurationMillis = 1500
internal const val BloomSplashBrandReadyMillis = 1100
internal const val BloomSplashPetalCount = 6

internal data class BloomSplashFrame(
    val seedProgress: Float,
    val petalProgress: List<Float>,
    val wordmarkProgress: Float,
    val taglineProgress: Float,
    val splashAlpha: Float
)

// Shared timing functions keep the live draw path free of per-frame frame/list allocations.
internal object BloomSplashMotion {
    fun seed(elapsedMillis: Float) = progress(elapsedMillis, 0f, 240f)
    fun petal(elapsedMillis: Float, index: Int) = progress(elapsedMillis, 140f + index * 34f, 500f)
    fun wordmark(elapsedMillis: Float) = progress(elapsedMillis, 480f, 440f)
    fun tagline(elapsedMillis: Float) = progress(elapsedMillis, 820f, 240f)
    fun alpha(elapsedMillis: Float) = 1f - progress(elapsedMillis, 1220f, 280f)

    private fun progress(elapsedMillis: Float, start: Float, duration: Float): Float {
        val fraction = ((elapsedMillis - start) / duration).coerceIn(0f, 1f)
        // Smoothstep has zero velocity at both ends, avoiding an abrupt first
        // petal movement while preserving the allocation-free drawing path.
        return fraction * fraction * (3f - 2f * fraction)
    }
}

internal fun bloomSplashFrame(elapsedMillis: Float) = BloomSplashFrame(
    seedProgress = BloomSplashMotion.seed(elapsedMillis),
    petalProgress = List(BloomSplashPetalCount) { BloomSplashMotion.petal(elapsedMillis, it) },
    wordmarkProgress = BloomSplashMotion.wordmark(elapsedMillis),
    taglineProgress = BloomSplashMotion.tagline(elapsedMillis),
    splashAlpha = BloomSplashMotion.alpha(elapsedMillis)
)
