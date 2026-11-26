package com.flowchat.app.data.device

import android.graphics.BitmapFactory
import com.flowchat.app.domain.device.*
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bounded translation matching. Blank, animated, periodic or ambiguous images return unknown. */
internal object ScrollImageMotionEstimator {
    fun measure(before: DeviceScreenImage, after: DeviceScreenImage, viewport: DeviceBounds, vertical: Boolean,
        diagnostic: (String) -> Unit = {}): DeviceScrollMotion? {
        fun unknown(reason: String): DeviceScrollMotion? { diagnostic(reason); return null }
        if (before.geometry != after.geometry || before.captureBounds != after.captureBounds || before.region != null || after.region != null ||
            before.width != after.width || before.height != after.height) return unknown("image_geometry_changed")
        val capture = before.captureBounds ?: return unknown("capture_bounds_missing")
        val bounds = capture.intersect(viewport) ?: return unknown("viewport_outside_image")
        fun buffer(image: DeviceScreenImage): ScreenPixelBuffer {
            image.comparisonPixels?.let { return it }
            val bytes = Base64.getDecoder().decode(image.image.base64)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Image unavailable")
            return try { val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                ScreenPixelBuffer(bitmap.width, bitmap.height, pixels) } finally { bitmap.recycle() }
        }
        return runCatching {
            val old = buffer(before); val current = buffer(after)
            val left = ((bounds.left - capture.left).toLong() * old.width / capture.width).toInt()
            val right = ((bounds.right - capture.left).toLong() * old.width / capture.width).toInt()
            val top = ((bounds.top - capture.top).toLong() * old.height / capture.height).toInt()
            val bottom = ((bounds.bottom - capture.top).toLong() * old.height / capture.height).toInt()
            val axisStart = if (vertical) top else left; val axisEnd = if (vertical) bottom else right
            // Fixed toolbars/footers are not content motion. Sample the interior on the motion axis.
            val inset = (axisEnd - axisStart) * 8 / 100
            val start = axisStart + inset; val end = axisEnd - inset
            val crossStart = if (vertical) left else top; val crossEnd = if (vertical) right else bottom
            val length = end - start
            if (length < 40 || crossEnd - crossStart < 24) return unknown("viewport_too_small")
            fun intensity(pixel: Int) = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
            fun pixel(buffer: ScreenPixelBuffer, axis: Int, cross: Int) = if (vertical) buffer.pixel(cross, axis) else buffer.pixel(axis, cross)
            // Keep textured pixels only. Static background must not dominate the best displacement.
            val points = buildList {
                for (axis in start + 3 until end - 3 step maxOf(1, length / 200))
                    for (cross in crossStart + 3 until crossEnd - 3 step maxOf(1, (crossEnd - crossStart) / 96)) {
                        val color = intensity(pixel(old, axis, cross))
                        if (abs(color - intensity(pixel(old, axis + 2, cross))) > 18 ||
                            abs(color - intensity(pixel(old, axis, cross + 2))) > 18) add(axis to cross)
                    }
            }
            if (points.size < 24) return unknown("too_few_edges:${points.size}")
            fun score(shift: Int): Pair<Int, Double>? {
                var error = 0.0; var count = 0
                for ((axis, cross) in points) if (axis - shift in start until end) {
                    error += abs(intensity(pixel(old, axis, cross)) - intensity(pixel(current, axis - shift, cross))); count++
                }
                return if (count < 24 || count * 5 < points.size * 3) null else shift to error / count
            }
            // Evaluate every native-pixel offset. Coarse shortlist selection can prefer another
            // repeated row and omit the true short movement before refinement even starts.
            val scores = (-length * 3 / 5..length * 3 / 5).mapNotNull(::score).sortedBy { it.second }
            val best = scores.firstOrNull() ?: return unknown("no_scores")
            val runnerUp = scores.firstOrNull { abs(it.first - best.first) > 4 } ?: return unknown("no_alternative_scores")
            diagnostic("points=${points.size} best=$best runnerUp=$runnerUp")
            if (best.second > 22 || runnerUp.second < best.second * 1.35 + .4) return unknown("ambiguous_translation")
            val scale = if (vertical) capture.height.toDouble() / old.height else capture.width.toDouble() / old.width
            DeviceScrollMotion((best.first * scale).roundToInt(), "pixels")
        }.getOrElse { unknown("comparison_failure:${it.javaClass.simpleName}") }
    }
}
