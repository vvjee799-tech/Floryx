package com.flowchat.app.data.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.flowchat.app.domain.device.DeviceScreenImage
import com.flowchat.app.domain.device.VisualTapTarget
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs

/** Checks the target neighborhood as well as the scene; unrelated small animations may continue. */
internal object ScreenImageMatcher {
    data class Comparison(val sceneDifference: Double, val targetDifference: Double) {
        val stableForTap: Boolean get() = sceneDifference <= .12 && targetDifference <= .025
        val pixelsChanged: Boolean get() = sceneDifference > .002 || targetDifference > .005
        val targetOrSceneChanged: Boolean get() = !stableForTap
    }

    fun compare(before: DeviceScreenImage, after: DeviceScreenImage, target: VisualTapTarget): Comparison {
        require(before.sourceWidth == after.sourceWidth && before.sourceHeight == after.sourceHeight &&
            before.region == after.region && before.geometry == after.geometry && before.captureBounds == after.captureBounds) {
            "Image geometry changed."
        }
        val old = decode(before)
        try {
            val current = decode(after)
            try {
                fun difference(left: Int, top: Int, right: Int, bottom: Int, maxSamplesPerEdge: Int): Double {
                    var changed = 0; var count = 0
                    val stepX = ((right - left) / maxSamplesPerEdge).coerceAtLeast(1)
                    val stepY = ((bottom - top) / maxSamplesPerEdge).coerceAtLeast(1)
                    for (y in top until bottom step stepY) for (x in left until right step stepX) {
                        val a = old.getPixel(x, y)
                        val b = current.getPixel((x.toLong() * current.width / old.width).toInt().coerceAtMost(current.width - 1),
                            (y.toLong() * current.height / old.height).toInt().coerceAtMost(current.height - 1))
                        if (maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)),
                                abs(Color.blue(a) - Color.blue(b))) > 28) changed++
                        count++
                    }
                    return changed.toDouble() / count.coerceAtLeast(1)
                }
                val x = (target.x.toLong() * old.width / 1000).toInt().coerceAtMost(old.width - 1)
                val y = (target.y.toLong() * old.height / 1000).toInt().coerceAtMost(old.height - 1)
                val radius = (minOf(old.width, old.height) / 30).coerceAtLeast(8)
                return Comparison(
                    difference(0, 0, old.width, old.height, 100),
                    difference((x - radius).coerceAtLeast(0), (y - radius).coerceAtLeast(0),
                        (x + radius + 1).coerceAtMost(old.width), (y + radius + 1).coerceAtMost(old.height), 80)
                )
            } finally { current.recycle() }
        } finally { old.recycle() }
    }

    fun fingerprint(bitmap: Bitmap): String {
        val samples = ByteArray(32 * 48 * 3)
        var index = 0
        for (row in 0 until 48) for (column in 0 until 32) {
            val pixel = bitmap.getPixel((column * bitmap.width / 32).coerceAtMost(bitmap.width - 1),
                (row * bitmap.height / 48).coerceAtMost(bitmap.height - 1))
            samples[index++] = (Color.red(pixel) / 32).toByte()
            samples[index++] = (Color.green(pixel) / 32).toByte()
            samples[index++] = (Color.blue(pixel) / 32).toByte()
        }
        return MessageDigest.getInstance("SHA-256").digest(samples).joinToString("") { "%02x".format(it) }
    }

    private fun decode(image: DeviceScreenImage): Bitmap {
        val bytes = Base64.getDecoder().decode(image.image.base64)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Screen image could not be decoded." }
    }
}
