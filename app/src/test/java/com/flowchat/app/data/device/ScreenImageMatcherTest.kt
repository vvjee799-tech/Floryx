package com.flowchat.app.data.device

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.flowchat.app.domain.device.DeviceScreenImage
import com.flowchat.app.domain.device.VisualTapTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenImageMatcherTest {
    private val target = VisualTapTarget("screen", 700, 700, "blue square")

    private fun image(moved: Boolean = false, activated: Boolean = false, animateElsewhere: Boolean = false,
        background: Int = Color.WHITE): DeviceScreenImage {
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            val paint = Paint().apply { color = if (activated) Color.GREEN else Color.BLUE }
            val center = if (moved) 240f else 560f
            canvas.drawRect(center - 60, 780f, center + 60, 900f, paint)
            if (animateElsewhere) { paint.color = Color.RED; canvas.drawRect(10f, 10f, 100f, 80f, paint) }
            ScreenImageEncoder.encode(bitmap, "screen", 1)
        } finally { bitmap.recycle() }
    }

    @Test fun unchangedTargetToleratesSmallUnrelatedAnimation() {
        val before = image()
        val unchanged = ScreenImageMatcher.compare(before, image(), target)
        assertTrue(unchanged.stableForTap)
        assertFalse(unchanged.pixelsChanged)
        val animated = ScreenImageMatcher.compare(before, image(animateElsewhere = true), target)
        assertTrue(animated.stableForTap)
        assertTrue(animated.pixelsChanged)
        assertFalse(animated.targetOrSceneChanged)
    }

    @Test fun movingOrReplacingTheTargetIsDetectedEvenWithoutAnyUiControls() {
        for (after in listOf(image(moved = true), image(activated = true), image(background = Color.DKGRAY))) {
            val comparison = ScreenImageMatcher.compare(image(), after, target)
            assertFalse(comparison.stableForTap)
            assertTrue(comparison.pixelsChanged)
            assertTrue(comparison.targetOrSceneChanged)
        }
    }

    @Test fun progressFingerprintsReflectPixelsAndNeverDependOnScreenIdsOrCaptureTimes() {
        val first = image()
        assertEquals(first.visualKey, image().visualKey)
        assertNotEquals(first.visualKey, image(activated = true).visualKey)
        assertTrue(ScreenImageMatcher.compare(first.copy(screenId = "old", capturedAt = 0), first.copy(screenId = "new", capturedAt = 99), target).stableForTap)
        assertTrue(runCatching { ScreenImageMatcher.compare(first, first.copy(sourceWidth = 700), target) }.isFailure)
    }
}
