package com.flowchat.app.data.device

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.flowchat.app.domain.device.DeviceScreenImage
import com.flowchat.app.domain.device.ScreenImageRegion
import com.flowchat.app.domain.device.ScreenPixelBuffer
import com.flowchat.app.domain.model.ChatImage
import java.io.ByteArrayOutputStream
import java.util.Base64

internal object ScreenImageEncoder {
    const val MaxEdge = 3840
    const val MaxBytes = 4 * 1024 * 1024

    fun encode(source: Bitmap, screenId: String, capturedAt: Long, region: ScreenImageRegion? = null): DeviceScreenImage {
        require(source.width > 0 && source.height > 0)
        val crop = region?.pixels(source.width, source.height)?.let { Rect(it.left, it.top, it.right, it.bottom) }
            ?: Rect(0, 0, source.width, source.height)
        var scale = minOf(1f, MaxEdge.toFloat() / maxOf(crop.width(), crop.height()))
        repeat(4) {
            val width = (crop.width() * scale).toInt().coerceAtLeast(1)
            val height = (crop.height() * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(source, crop, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                // Keep text and fine strokes lossless whenever the payload budget permits.
                for ((format, quality) in listOf(Bitmap.CompressFormat.PNG to 100, Bitmap.CompressFormat.JPEG to 95, Bitmap.CompressFormat.JPEG to 85)) {
                    val encoded = ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(format, quality, output)) { "Screenshot encoding failed." }
                        output.toByteArray()
                    }
                    if (encoded.size <= MaxBytes) return DeviceScreenImage(
                        ChatImage(if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg", Base64.getEncoder().encodeToString(encoded)),
                        screenId, width, height, capturedAt, source.width, source.height, region,
                        visualKey = ScreenImageMatcher.fingerprint(bitmap),
                        comparisonPixels = if (format == Bitmap.CompressFormat.PNG && maxOf(width, height) <= 800) {
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            ScreenPixelBuffer(width, height, pixels)
                        } else null)
                }
            } finally { bitmap.recycle() }
            scale *= .75f
        }
        throw ScreenCaptureFailure("screen_image_too_large")
    }
}

internal class ScreenCaptureFailure(val code: String) : Exception(code)
