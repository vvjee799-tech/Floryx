package com.flowchat.app.domain.device

import com.flowchat.app.domain.model.ChatImage
import org.junit.Assert.*
import org.junit.Test

class VisualTapTargetTest {
    private val geometry = DeviceScreenGeometry(DeviceBounds(100, 200, 900, 1400), 1080, 1920, 0)
    private val screen = DeviceScreenSnapshot("canvas.app", "Canvas", emptyList(), screenId = "image-1", windowId = 7, geometry = geometry)
    private val image = DeviceScreenImage(ChatImage("image/png", "synthetic"), screen.screenId, 400, 600,
        sourceWidth = 800, sourceHeight = 1200, geometry = geometry, captureBounds = geometry.windowBounds)
    private val target = VisualTapTarget(screen.screenId, 500, 500, "blue button")

    @Test fun resizedAndCroppedImagesMapBackThroughTheActualWindowOffset() {
        assertEquals(VisualTapPoint(500, 800), image.visualTapPoint(target))
        val crop = image.copy(width = 200, height = 300, region = ScreenImageRegion(25, 50, 75, 100))
        assertEquals(VisualTapPoint(500, 1100), crop.visualTapPoint(target))
        assertEquals(VisualTapPoint(300, 800), crop.visualTapPoint(target.copy(x = 0, y = 0)))
        assertEquals(VisualTapPoint(699, 1399), crop.visualTapPoint(target.copy(x = 1000, y = 1000)))
    }

    @Test fun aBiasedFullScreenProposalIsRefinedWithoutAddingAnInventedOffset() {
        for ((width, height) in listOf(1260 to 2800, 2800 to 1260, 1440 to 2560)) {
            val geometry = DeviceScreenGeometry(DeviceBounds(0, 0, width, height), width, height, 0)
            val full = image.copy(width = width, height = height, sourceWidth = width, sourceHeight = height,
                geometry = geometry, captureBounds = geometry.windowBounds)
            val proposed = target.copy(x = 700, y = 650)
            assertTrue(full.needsVisualRefinement)
            val region = full.visualRefinementRegion(proposed)
            val pixels = region.pixels(width, height)
            val detail = full.copy(region = region, width = pixels.width, height = pixels.height)
            assertFalse(detail.needsVisualRefinement)
            val expectedX = width * .7
            val expectedY = height * .68
            val refined = target.copy(x = kotlin.math.round((expectedX - pixels.left) * 1000 / pixels.width).toInt(),
                y = kotlin.math.round((expectedY - pixels.top) * 1000 / pixels.height).toInt())
            val actual = requireNotNull(detail.visualTapPoint(refined))
            assertTrue(kotlin.math.abs(actual.x - expectedX) <= 1.1)
            assertTrue(kotlin.math.abs(actual.y - expectedY) <= 1.1)
        }
    }

    @Test fun focusedImagesStayInsideThePreviouslyVisibleCropEvenAtItsEdges() {
        val cropped = image.copy(region = ScreenImageRegion(20, 55, 90, 100), sourceWidth = 1260, sourceHeight = 2800)
        for (point in listOf(target.copy(x = 0, y = 0), target.copy(x = 1000, y = 1000))) {
            val focus = cropped.visualRefinementRegion(point)
            assertTrue(focus.left >= 20 && focus.top >= 55 && focus.right <= 90 && focus.bottom <= 100)
            assertFalse(cropped.copy(region = focus).needsVisualRefinement)
        }
    }

    @Test fun fullDisplayCapturesCannotTapSystemBarsOutsideTheAppWindow() {
        val full = image.copy(sourceWidth = 1080, sourceHeight = 1920, width = 540, height = 960,
            geometry = geometry.copy(windowBounds = DeviceBounds(0, 80, 1080, 1800)), captureBounds = DeviceBounds(0, 0, 1080, 1920))
        assertNull(full.visualTapPoint(target.copy(y = 0)))
        assertNull(full.visualTapPoint(target.copy(y = 1000)))
        assertEquals(VisualTapPoint(1079, 960), full.visualTapPoint(target.copy(x = 1000)))
        assertNull(image.copy(captureBounds = DeviceBounds(0, 0, 400, 600)).visualTapPoint(target))
        assertNull(image.copy(geometry = null).visualTapPoint(target))
    }

    @Test fun aFrameCannotBeReusedAfterWindowRotationExpiryOrAControlsOnlyObservation() {
        val guard = VisualFrameGuard()
        guard.remember(DeviceToolResult(true, "image", screen = screen, screenImage = image), 100)
        assertNotNull(guard.current(screen.screenId, screen.copy(screenId = "new-controls"), 200))
        for (current in listOf(screen.copy(windowId = 8), screen.copy(packageName = "other.app"),
            screen.copy(sensitive = true), screen.copy(geometry = geometry.copy(rotation = 1)),
            screen.copy(geometry = geometry.copy(windowBounds = DeviceBounds(0, 0, 800, 1200))))) {
            assertNull(guard.current(screen.screenId, current, 200))
        }
        assertNull(guard.current("old-image", screen, 200))
        assertNull(guard.current(screen.screenId, screen, 100 + VisualFrameGuard.MaxAgeMillis + 1))
        guard.remember(DeviceToolResult(true, "controls", screen = screen), 200)
        assertNull(guard.current(screen.screenId, screen, 300))
        guard.remember(DeviceToolResult(true, "image", screen = screen, screenImage = image), 300)
        guard.clear()
        assertNull(guard.current(screen.screenId, screen, 400))
    }

    @Test fun visualCoordinatesRequireAnExactTypedImageContract() {
        val valid = """{"screen_id":"image-1","x":500,"y":500,"target_description":"blue button","long_press":false}"""
        assertEquals(target, VisualTapTarget.fromToolArguments(valid))
        for (invalid in listOf(valid.replace("500", "-1"), valid.replace("500", "1001"),
            valid.replace("500", "500.5"), valid.replace("500", "\"500\""),
            valid.replace("false", "\"false\""), valid.replace("blue button", ""),
            valid.replace("\"x\":500", "\"x_px\":500"), valid.replace("image-1", ""), "[]")) {
            assertTrue("Must reject $invalid", runCatching { VisualTapTarget.fromToolArguments(invalid) }.isFailure)
        }
    }

    @Test fun mismatchedOrSensitiveImagesNeverGrantVisualActionAccess() {
        val guard = VisualFrameGuard()
        for (result in listOf(
            DeviceToolResult(true, "sensitive", screen = screen.copy(sensitive = true), screenImage = image),
            DeviceToolResult(true, "wrong image", screen = screen, screenImage = image.copy(screenId = "other")),
            DeviceToolResult(true, "unknown geometry", screen = screen, screenImage = image.copy(geometry = null)))) {
            guard.remember(result, 0)
            assertNull(guard.current(screen.screenId, screen, 1))
        }
    }

    @Test fun aNewTaskCannotReuseAnotherTasksImageEvenWhenTheWindowHasNotChanged() {
        val control = DeviceTaskControl()
        val first = control.begin()
        val guard = VisualFrameGuard()
        guard.remember(DeviceToolResult(true, "image", screen = screen, screenImage = image), 0, first)
        assertNotNull(guard.current(screen.screenId, screen, 1, first))
        val second = control.begin()
        assertNull(guard.current(screen.screenId, screen, 2, second))
        assertNull(guard.current(screen.screenId, screen, 2))
    }
}
