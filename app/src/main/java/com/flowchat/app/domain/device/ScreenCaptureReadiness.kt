package com.flowchat.app.domain.device

/** A readable frame from a sliding window may still lack a safe image-to-display mapping. */
object ScreenCaptureReadiness {
    fun shouldRetry(result: DeviceToolResult): Boolean {
        if (result.screen?.sensitive == true || result.errorCode == "sensitive_screen_blocked" ||
            result.imageErrorCode == "secure_window_blocked" || result.imageErrorCode == "visual_target_obscured") return false
        val image = result.screenImage
        if (image != null) return image.geometry == null && result.screen?.geometry != null
        return (result.imageErrorCode ?: result.errorCode) in setOf("screen_unavailable", "screen_window_unavailable",
            "screen_changed_during_capture", "screen_capture_rate_limited")
    }
}
