package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DeviceSnapshotGuardTest {
    private val element = DeviceUiElement(0, "Switch", "Wi-Fi", null, "wifi", DeviceBounds(0, 0, 200, 80),
        clickable = true, editable = false, scrollable = false, enabled = true, checked = false)
    private val screen = DeviceScreenSnapshot("settings", "Settings", listOf(element), screenId = "first")

    @Test fun unrelatedLiveTextDoesNotInvalidateAnUnchangedTarget() {
        val clock = element.copy(index = 1, text = "00:01", clickable = false)
        val guard = DeviceSnapshotGuard().apply { remember(screen.copy(elements = listOf(element, clock))) }
        val current = screen.copy(elements = listOf(element, clock.copy(text = "00:02")))
        assertTrue(guard.matches("first", current, targetIndex = 0))
        assertFalse(guard.matches("first", current.copy(elements = listOf(element.copy(text = "Delete"), clock)), targetIndex = 0))
        assertFalse(guard.matches("first", current.copy(packageName = "other.app"), targetIndex = 0))
        assertFalse(guard.matches("first", current.copy(windowId = 7), targetIndex = 0))
    }

    @Test fun progressIgnoresPlaybackClocksCountersAndBoundsButDetectsNewContent() {
        val first = screen.copy(elements = listOf(element.copy(text = "00:01"), element.copy(index = 1, text = "120")))
        val next = first.copy(elements = listOf(element.copy(text = "00:02", bounds = DeviceBounds(1, 1, 201, 81)), element.copy(index = 1, text = "121")))
        assertEquals(first.progressKey(), next.progressKey())
        assertNotEquals(first.progressKey(), next.copy(elements = listOf(element.copy(text = "New video caption"))).progressKey())
    }

    @Test fun acceptsFreshIndexOnlyWithMatchingScreenIdentity() {
        val guard = DeviceSnapshotGuard()
        assertFalse(guard.matches("first", screen))
        guard.remember(screen)
        assertTrue(guard.matches("first", screen.copy(screenId = "fresh", capturedAt = 99)))
        assertFalse(guard.matches(null, screen))
        assertFalse(guard.matches("old", screen))
    }

    @Test fun blocksReorderedMovedOrChangedControlsAndOtherPackages() {
        val guard = DeviceSnapshotGuard().apply { remember(screen) }
        assertFalse(guard.matches("first", screen.copy(elements = listOf(element.copy(index = 1)))))
        assertFalse(guard.matches("first", screen.copy(elements = listOf(element.copy(checked = true)))))
        assertFalse(guard.matches("first", screen.copy(elements = listOf(element.copy(bounds = DeviceBounds(0, 80, 200, 160))))))
        assertFalse(guard.matches("first", screen.copy(packageName = "another.app")))
        assertFalse(guard.matches("first", screen.copy(sensitive = true)))
    }

    @Test fun sensitiveAndMissingScreensInvalidateEarlierTargets() {
        val guard = DeviceSnapshotGuard().apply { remember(screen) }
        guard.remember(screen.copy(sensitive = true))
        assertFalse(guard.matches("first", screen))
        guard.remember(screen)
        guard.remember(null)
        assertFalse(guard.matches("first", screen))
    }

    @Test fun toolResultPreservesErrorAlongsideRecoveryScreenAndState() {
        val result = DeviceToolResult(false, "changed", errorCode = "stale_screen", screen = screen).asToolContent()
        assertTrue(result.contains("error_code: stale_screen"))
        assertTrue(result.contains("screen_id=first"))
        assertTrue(result.contains("checked=false"))
        assertTrue(result.contains("id=wifi"))
        assertTrue(element.copy(focused = true, selected = true, hint = "Search").asModelLine().contains("focused"))
    }
}
