package com.flowchat.app.domain.device

import com.flowchat.app.domain.model.ChatImage

data class DeviceScreenImage(
    val image: ChatImage,
    val screenId: String,
    val width: Int,
    val height: Int,
    val capturedAt: Long = System.currentTimeMillis(),
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
    val region: ScreenImageRegion? = null,
    val geometry: DeviceScreenGeometry? = null,
    /** Full source bitmap bounds in display coordinates, before cropping/resizing. */
    val captureBounds: DeviceBounds? = null,
    /** Coarse pixel fingerprint for in-memory progress tracking, not screenshot storage. */
    val visualKey: String? = null,
    /** Bounded native detail pixels, retained only in memory to avoid decoding our own PNG again. */
    val comparisonPixels: ScreenPixelBuffer? = null
) {
    override fun toString(): String = "DeviceScreenImage(screenId=$screenId, width=$width, height=$height)"
}

class ScreenPixelBuffer(val width: Int, val height: Int, private val pixels: IntArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) }
    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
    override fun toString(): String = "ScreenPixelBuffer(width=$width, height=$height)"
}
