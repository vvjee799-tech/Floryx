package com.flowchat.app.domain.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUiModelsTest {
    @Test
    fun formatsCompactIndexedElementsForATextOnlyModel() {
        val snapshot = DeviceScreenSnapshot(
            packageName = "com.android.settings",
            activityName = "Settings",
            elements = listOf(
                DeviceUiElement(
                    index = 3,
                    className = "android.widget.TextView",
                    text = "Battery",
                    contentDescription = null,
                    resourceId = "android:id/title",
                    bounds = DeviceBounds(24, 240, 500, 320),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true
                )
            )
        )

        val content = snapshot.asToolContent()

        assertTrue(content.contains("package=com.android.settings"))
        assertTrue(content.contains("[3] TextView \"Battery\""))
        assertTrue(content.contains("clickable"))
        assertTrue(content.contains("bounds=24,240,500,320"))
    }

    @Test
    fun sensitiveSnapshotsNeverExposeNodeText() {
        val snapshot = DeviceScreenSnapshot(
            packageName = "com.example.pay",
            activityName = "Checkout",
            elements = listOf(
                DeviceUiElement(
                    index = 0,
                    className = "android.widget.EditText",
                    text = "123456",
                    contentDescription = "Payment password",
                    resourceId = null,
                    bounds = DeviceBounds(20, 200, 600, 300),
                    clickable = true,
                    editable = true,
                    scrollable = false,
                    enabled = true
                )
            ),
            sensitive = true,
            sensitivityReason = "password_or_payment"
        )

        val content = snapshot.asToolContent()

        assertTrue(content.contains("sensitive_screen_blocked"))
        assertFalse(content.contains("123456"))
        assertFalse(content.contains("Payment password"))
    }

    @Test
    fun screenEvidenceCallsOutTapCentersCoveredByTheSystemBar() {
        val window = DeviceBounds(0, 0, 400, 800)
        val bar = DeviceBounds(0, 740, 400, 800)
        val full = DeviceUiElement(4, "android.widget.TextView", "A row", null, null,
            DeviceBounds(0, 660, 400, 710), clickable = true, editable = false, scrollable = false, enabled = true)
        val clipped = full.copy(index = 5, text = "The target", bounds = DeviceBounds(0, 725, 400, 780))
        val context = DeviceInteractionContext(listOf(
            DeviceWindowRegion(1, DeviceWindowKind.Application, 0, window, listOf(window), focused = true, active = true),
            DeviceWindowRegion(2, DeviceWindowKind.System, 1, bar, listOf(bar), focused = false, active = false)
        ))
        val screen = DeviceScreenSnapshot("com.example.list", "List", listOf(full, clipped), windowId = 1,
            geometry = DeviceScreenGeometry(window, 400, 800, 0), interactionContext = context)

        val recorded = screen.asToolContent()
        assertTrue(recorded.lineSequence().any { it == "tap_center_blocked_indices=5" })
        assertTrue(recorded.contains("use scroll_container with its scrollable ancestor and the exact target selector"))

        val cleared = screen.copy(elements = listOf(full, clipped.copy(bounds = DeviceBounds(0, 700, 400, 735))))
        assertFalse(cleared.asToolContent().contains("tap_center_blocked_indices"))
        assertFalse(screen.copy(interactionContext = context.copy(complete = false)).asToolContent().contains("tap_center_blocked_indices"))
    }
}
