package com.flowchat.app.domain.device

import com.flowchat.app.domain.model.ChatImage
import org.junit.Assert.*
import org.junit.Test

class ScreenCaptureReadinessTest {
    private val geometry = DeviceScreenGeometry(DeviceBounds(1305, 296, 2124, 2115), 1260, 2800, 0)
    private val sliding = DeviceScreenSnapshot("test.app", "Test", emptyList(), windowId = 9, geometry = geometry)
    private val image = DeviceScreenImage(ChatImage("image/png", "synthetic"), sliding.screenId, 1043, 2043)

    @Test fun aFrameFromThePhonesOffScreenEntranceAnimationNeedsAFreshMapping() {
        val result = DeviceToolResult(true, "frame", screen = sliding, screenImage = image)
        assertTrue(ScreenCaptureReadiness.shouldRetry(result))
        val settled = geometry.copy(windowBounds = DeviceBounds(110, 296, 929, 2115))
        assertFalse(ScreenCaptureReadiness.shouldRetry(result.copy(screen = sliding.copy(geometry = settled),
            screenImage = image.copy(geometry = settled, captureBounds = settled.windowBounds))))
    }

    @Test fun aTemporaryWindowChangeCanRetryButProtectedOrCoveredScreensCannot() {
        assertTrue(ScreenCaptureReadiness.shouldRetry(DeviceToolResult(false, "transition", imageErrorCode = "screen_changed_during_capture")))
        for (error in listOf("secure_window_blocked", "visual_target_obscured", "screen_capture_permission_required"))
            assertFalse(ScreenCaptureReadiness.shouldRetry(DeviceToolResult(false, "protected", imageErrorCode = error)))
        assertFalse(ScreenCaptureReadiness.shouldRetry(DeviceToolResult(false, "sensitive", screen = sliding.copy(sensitive = true), screenImage = image)))
    }
}
