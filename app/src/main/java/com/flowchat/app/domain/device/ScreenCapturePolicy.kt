package com.flowchat.app.domain.device

/** Pixels and controls are sampled separately; animations do not invalidate a frame from the same window. */
object ScreenCapturePolicy {
    fun complete(before: DeviceScreenSnapshot, after: DeviceScreenSnapshot?, image: DeviceScreenImage): DeviceToolResult {
        val error = when {
            before.sensitive || after?.sensitive == true -> "sensitive_screen_blocked"
            after == null || before.packageName != after.packageName || before.windowId == null ||
                before.windowId < 0 || before.windowId != after.windowId || before.geometry != after.geometry ||
                image.screenId != before.screenId ->
                "screen_changed_during_capture"
            else -> null
        }
        if (error != null) return DeviceToolResult(
            false,
            if (error == "sensitive_screen_blocked") "A sensitive screen was detected. The image was discarded."
            else "The app window changed during capture. The image was discarded; observe again.",
            errorCode = error, screen = after, imageErrorCode = error
        )
        val current = requireNotNull(after)
        return DeviceToolResult(
            true,
            "Captured one frame from the current app window and refreshed ${current.elements.size} UI controls. " +
                "Controls were sampled after the image; moving content may differ. Use the refreshed screen_id and indices for actions.",
            screen = current, screenImage = image.copy(screenId = current.screenId)
        )
    }
}
