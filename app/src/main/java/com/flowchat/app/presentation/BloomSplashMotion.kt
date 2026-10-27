package com.flowchat.app.presentation

internal const val BloomSplashDurationMillis = 1100
internal const val BloomSplashBrandReadyMillis = 740

internal data class BloomSplashFrame(
    val seedProgress: Float,
    val petalProgress: List<Float>,
    val wordmarkProgress: Float,
    val taglineProgress: Float,
    val splashAlpha: Float
)

internal fun bloomSplashFrame(elapsedMillis: Float): BloomSplashFrame {
    fun progress(start: Float, duration: Float): Float {
        val remaining = 1f - ((elapsedMillis - start) / duration).coerceIn(0f, 1f)
        return 1f - remaining * remaining * remaining
    }
    return BloomSplashFrame(
        seedProgress = progress(0f, 180f),
        petalProgress = List(6) { progress(120f + it * 26f, 250f) },
        wordmarkProgress = progress(360f, 340f),
        taglineProgress = progress(500f, 200f),
        splashAlpha = 1f - progress(880f, 220f)
    )
}
