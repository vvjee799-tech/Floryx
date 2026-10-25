package com.flowchat.app.domain.device

import com.flowchat.app.domain.model.ChatImage
import org.junit.Assert.*
import org.junit.Test

class ScreenCapturePolicyTest {
    private val target = DeviceUiElement(0, "Button", "Play", null, "play", DeviceBounds(0, 0, 100, 80),
        clickable = true, editable = false, scrollable = false, enabled = true)
    private val before = DeviceScreenSnapshot("video.app", "Video", listOf(target), screenId = "before", windowId = 5)
    private val image = DeviceScreenImage(ChatImage("image/jpeg", "PIXELS"), before.screenId, 400, 800, 123)

    @Test fun animatedControlsKeepPixelsAndBindActionsToRefreshedTargets() {
        val after = before.copy(screenId = "after", capturedAt = 130, elements = listOf(
            target.copy(text = "00:02", clickable = false),
            target.copy(index = 1, bounds = DeviceBounds(100, 20, 200, 100))))
        assertFalse(before.hasSameUi(after))
        val result = ScreenCapturePolicy.complete(before, after, image)
        assertTrue(result.asToolContent(), result.success)
        assertEquals(image.image, result.screenImage!!.image)
        assertEquals(123L, result.screenImage.capturedAt)
        assertEquals(after.screenId, result.screenImage.screenId)
        assertEquals(after, result.screen)
        val guard = DeviceSnapshotGuard().apply { remember(result.screen) }
        assertFalse(guard.matches(before.screenId, after, 0))
        assertTrue(guard.matches(after.screenId, after, 1))
        assertFalse(guard.matches(after.screenId, after.copy(elements = listOf(target)), 1))
        assertTrue(result.asToolContent().contains("ui_captured_at=130"))
    }

    @Test fun aCustomDrawnWindowNeedsNoReadableControlsOrStableTitle() {
        val empty = before.copy(elements = emptyList())
        val after = empty.copy(screenId = "after", activityName = "Playing 00:02")
        val result = ScreenCapturePolicy.complete(empty, after, image)
        assertTrue(result.asToolContent(), result.success)
        assertTrue(result.screen!!.elements.isEmpty())
        assertNotNull(result.screenImage)
    }

    @Test fun switchedAppsWindowsAndMissingRootsDiscardPixels() {
        for (after in listOf(null, before.copy(packageName = "other.app"), before.copy(windowId = 6))) {
            val result = ScreenCapturePolicy.complete(before, after, image)
            assertFalse(result.success)
            assertNull(result.screenImage)
            assertEquals("screen_changed_during_capture", result.imageErrorCode)
        }
    }

    @Test fun sensitivePagesBeforeOrAfterCaptureNeverExposePixels() {
        for ((first, last) in listOf(before.copy(sensitive = true) to before, before to before.copy(sensitive = true))) {
            val result = ScreenCapturePolicy.complete(first, last, image)
            assertFalse(result.success)
            assertNull(result.screenImage)
            assertEquals("sensitive_screen_blocked", result.errorCode)
            assertEquals(result.errorCode, result.imageErrorCode)
        }
    }

    @Test fun unidentifiableWindowsAndUnrelatedFramesAreRejected() {
        for (windowId in listOf(null, -1)) {
            val snapshot = before.copy(windowId = windowId)
            assertNull(ScreenCapturePolicy.complete(snapshot, snapshot, image).screenImage)
        }
        assertNull(ScreenCapturePolicy.complete(before, before, image.copy(screenId = "unrelated")).screenImage)
    }
}
