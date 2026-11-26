package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.ChatImage
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ScrollImageMotionEstimatorTest {
    private val width = 200
    private val height = 500
    private val bounds = DeviceBounds(0, 0, width, height)
    private fun image(pixels: IntArray) = DeviceScreenImage(ChatImage("image/png", "unused"), "fixture", width, height,
        sourceWidth = width, sourceHeight = height, geometry = DeviceScreenGeometry(bounds, width, height, 0),
        captureBounds = bounds, comparisonPixels = ScreenPixelBuffer(width, height, pixels))

    @Test fun aShortTranslationBetweenCoarseSamplesIsMeasuredAtPixelResolution() {
        val random = Random(51)
        val old = IntArray(width * height) { -0x1000000 or random.nextInt(0xffffff) }
        val moved = IntArray(width * height) { index ->
            val y = index / width; if (y + 37 < height) old[(y + 37) * width + index % width] else -1
        }
        assertEquals(37, ScrollImageMotionEstimator.measure(image(old), image(moved), bounds, true)!!.contentPixels)
    }
    @Test fun blankAndExactlyRepeatingImagesDoNotInventDisplacement() {
        val blank = image(IntArray(width * height) { -1 })
        assertNull(ScrollImageMotionEstimator.measure(blank, blank, bounds, true))
        val repeated = image(IntArray(width * height) { index -> if (index / width % 20 < 10) -1 else -0x1000000 })
        assertNull(ScrollImageMotionEstimator.measure(repeated, repeated, bounds, true))
    }
}
