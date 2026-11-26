package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DeviceInteractionContextTest {
    private fun window(id: Int, bounds: DeviceBounds, layer: Int = id, kind: DeviceWindowKind = DeviceWindowKind.Application,
        regions: List<DeviceBounds> = listOf(bounds)) = DeviceWindowRegion(id, kind, layer, bounds, regions, true, true)
    private val app = window(1, DeviceBounds(500, 100, 1000, 900))
    private fun screen(vararg extra: DeviceWindowRegion, complete: Boolean = true) = DeviceScreenSnapshot("fixture", "", emptyList(),
        windowId = 1, geometry = DeviceScreenGeometry(app.bounds, 1200, 1000, 0),
        interactionContext = DeviceInteractionContext(listOf(app) + extra, complete = complete))

    @Test fun offsetSplitWindowSwipeNeverUsesTheOtherHalfOrSystemBars() {
        val screen = screen()
        for (direction in DeviceSwipeDirection.entries) {
            val path = screen.interactionContext!!.swipe(screen, direction, 80)!!
            assertTrue(app.bounds.contains(path.startX, path.startY))
            assertTrue(app.bounds.contains(path.endX, path.endY))
            assertTrue(path.startX >= 500 && path.endX >= 500)
        }
        val rotating = screen.copy(geometry = screen.geometry!!.copy(windowBounds = DeviceBounds(-63, -303, 1263, 1303)))
        assertTrue(rotating.interactionContext!!.availableRegions(rotating).isEmpty())
    }

    @Test fun keyboardAndFloatingOverlayAreExcludedAlongTheWholeSwipe() {
        val keyboard = window(2, DeviceBounds(0, 600, 1200, 1000), kind = DeviceWindowKind.InputMethod)
        val overlay = window(3, DeviceBounds(700, 200, 800, 500), kind = DeviceWindowKind.Overlay)
        val screen = screen(keyboard, overlay)
        val context = screen.interactionContext!!
        assertTrue(context.keyboardVisible)
        assertFalse(context.allowsPoint(screen, 750, 250))
        assertFalse(context.allowsPoint(screen, 900, 700))
        for (direction in DeviceSwipeDirection.entries) {
            val path = context.swipe(screen, direction, 80)!!
            repeat(101) { i ->
                val x = path.startX + (path.endX - path.startX) * i / 100
                val y = path.startY + (path.endY - path.startY) * i / 100
                assertTrue("$direction at $x,$y", context.allowsPoint(screen, x, y))
            }
        }
    }

    @Test fun preciseTouchableRegionsPreserveHolesAndRejectHiddenParts() {
        val overlay = window(2, app.bounds, regions = listOf(DeviceBounds(500, 100, 550, 900)))
        val screen = screen(overlay)
        assertTrue(screen.interactionContext!!.allowsPoint(screen, 700, 400))
        assertFalse(screen.interactionContext!!.allowsPoint(screen, 525, 400))
        val covered = screen(window(2, app.bounds))
        assertNull(covered.interactionContext!!.swipe(covered, DeviceSwipeDirection.Up, 50))
        assertTrue(screen(complete = false).interactionContext!!.availableRegions(screen(complete = false)).isEmpty())
    }

    @Test fun cutoutsRotationAndFocusChangesInvalidateOldIndexedTargets() {
        val old = screen().copy(elements = listOf(DeviceUiElement(0, "Button", "Tap", null, "button", DeviceBounds(600, 200, 700, 300), true, false, false, true)))
        val guard = DeviceSnapshotGuard().apply { remember(old) }
        val cutout = old.copy(interactionContext = old.interactionContext!!.copy(cutouts = listOf(DeviceBounds(600, 100, 700, 400))))
        assertFalse(cutout.interactionContext!!.allowsPoint(cutout, 650, 250))
        assertFalse(guard.matches(old.screenId, cutout, 0))
        assertFalse(guard.matches(old.screenId, old.copy(geometry = old.geometry!!.copy(rotation = 1)), 0))
        assertFalse(guard.matches(old.screenId, old.copy(interactionContext = old.interactionContext!!.copy(inputFocusIndex = 0)), 0))
    }

    @Test fun sideCutoutDoesNotMoveAHorizontalSwipeOntoTheTopButtonRowOrShortenAVerticalLane() {
        val bounds = DeviceBounds(0, 0, 2800, 1260)
        val context = DeviceInteractionContext(listOf(window(1, bounds)), cutouts = listOf(DeviceBounds(0, 593, 104, 667)))
        val screen = DeviceScreenSnapshot("fixture", "", emptyList(), windowId = 1,
            geometry = DeviceScreenGeometry(bounds, 2800, 1260, 1), interactionContext = context)
        val horizontal = context.swipe(screen, DeviceSwipeDirection.Right, 40)!!
        assertEquals(630, horizontal.startY)
        assertEquals(630, horizontal.endY)
        assertTrue(horizontal.startX > 104)
        val vertical = context.swipe(screen, DeviceSwipeDirection.Up, 80)!!
        assertEquals(1400, vertical.startX)
        assertTrue(vertical.startY - vertical.endY >= 1000)
        repeat(101) { i -> assertTrue(context.allowsPoint(screen, vertical.startX, vertical.startY + (vertical.endY - vertical.startY) * i / 100)) }
    }
}
