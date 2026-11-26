package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveScrollControllerTest {
    private class ListScene(var offset: Int = 1000, val multiplier: Double = 1.0, private val rightInset: Int = 0) {
        val gestures = mutableListOf<Int>()
        var cancelled = false
        var viewportTop = 0
        private val bounds = DeviceBounds(0, 0, 600, 1000)
        fun screen(offset: Int = this.offset): DeviceScreenSnapshot {
            val viewport = DeviceBounds(0, viewportTop, 600, 1000)
            val root = DeviceUiElement(0, "ScrollView", null, "list", null, viewport, false, false, true, true, nodePath = "0")
            val rows = (0..199).mapNotNull { row ->
                val top = row * 25 - offset + viewportTop
                if (top >= 1000 || top + 25 <= viewportTop) null else DeviceUiElement(row + 1, "Text", "Row $row", "row_$row", null,
                    DeviceBounds(0, top.coerceAtLeast(viewportTop), 600, (top + 25).coerceAtMost(1000)), true, false, false, true, parentIndex = 0, depth = 1)
            }
            return DeviceScreenSnapshot("fixture", "list", listOf(root) + rows, capturedAt = 0, windowId = 1,
                geometry = DeviceScreenGeometry(bounds, 600, 1000, 0), interactionContext = DeviceInteractionContext(
                    listOf(DeviceWindowRegion(1, DeviceWindowKind.Application, 1, bounds,
                        listOf(bounds.copy(right = bounds.right - rightInset)), true, true))))
        }
        fun dispatch(path: DeviceSwipePath): AdaptiveScrollDispatch {
            val delta = path.startY - path.endY; gestures += delta
            offset = (offset + (delta * multiplier).toInt()).coerceIn(0, 4000); return AdaptiveScrollDispatch(true)
        }
    }

    @Test fun highTravelGainReversesAndShrinksWithoutUserCorrection() = runTest {
        val scene = ListScene(multiplier = 10.2, rightInset = 48)
        val controller = AdaptiveScrollController { testScheduler.currentTime }
        val target = DeviceUiSelector(description = "row_43")
        var leftViewport = false
        val result = controller.scroll(scene.screen(), DeviceScrollRequest(DeviceSwipeDirection.Up, target = target),
            "task", 16, read = { scene.screen() }, capture = { DeviceToolResult(false, "No image") },
            dispatch = { _, path, _ ->
                scene.dispatch(path).also {
                    if (scene.gestures.size == 1) leftViewport = scene.screen().elements.none(target::matches)
                }
            }, check = {})
        assertTrue(result.asToolContent(), result.success)
        assertEquals("ready", result.scrollFeedback!!.targetState)
        assertTrue(leftViewport)
        assertTrue(scene.gestures.toString(), scene.gestures.size >= 2)
        assertTrue(scene.gestures[0] < 0 && scene.gestures[1] > 0)
        assertTrue(kotlin.math.abs(scene.gestures[1]) < kotlin.math.abs(scene.gestures[0]))
        assertTrue(scene.gestures.size <= 3)
    }

    @Test fun aTargetLostAtReleaseRecoversWithTwoComparablePairsOfAnchors() = runTest {
        for (comparable in listOf(false, true)) {
            val scene = ListScene(offset = 2007, multiplier = 6.25)
            val controller = AdaptiveScrollController { testScheduler.currentTime }
            val target = DeviceUiSelector(description = "row_80")
            val request = DeviceScrollRequest(DeviceSwipeDirection.Down, target = target)
            val result = controller.scroll(scene.screen(), request, "task", 16,
                read = { scene.screen() }, capture = { DeviceToolResult(false, "No image") },
                dispatch = { before, path, _ ->
                    val held = scene.screen(scene.offset + path.startY - path.endY)
                    val movement = scene.dispatch(path)
                    if (scene.gestures.size == 1) {
                        val after = scene.screen()
                        val area = requireNotNull(DeviceAdaptiveScroll.area(before, request))
                        assertNull(DeviceAdaptiveScroll.measure(before, after, area, true))
                        assertTrue(after.elements.none(target::matches))
                    }
                    movement.copy(beforeRelease = if (comparable) held else held.copy(windowId = 99))
                }, check = {})
            if (comparable) {
                assertEquals("ready", result.scrollFeedback?.targetState)
                assertEquals(2, scene.gestures.size)
                assertEquals(1, result.scrollFeedback?.corrections)
                assertTrue(scene.gestures[1] in 1 until -scene.gestures[0])
            } else {
                assertEquals("lost_unverified", result.scrollFeedback?.targetState)
                assertNull(result.scrollFeedback?.motion)
                assertEquals(1, scene.gestures.size)
            }
        }
    }

    @Test fun fullyRevealedRowNearTheEdgeDoesNotTriggerAnotherGesture() = runTest {
        val scene = ListScene(offset = 11, multiplier = .57)
        val controller = AdaptiveScrollController { testScheduler.currentTime }
        val result = controller.scroll(scene.screen(),
            DeviceScrollRequest(DeviceSwipeDirection.Up, target = DeviceUiSelector(description = "row_40")),
            "task", 16, read = { scene.screen() }, capture = { DeviceToolResult(false, "No image") },
            dispatch = { _, path, _ -> scene.dispatch(path) }, check = {})

        assertEquals("ready", result.scrollFeedback?.targetState)
        assertEquals(scene.gestures.toString(), 1, scene.gestures.size)
        val target = result.screen?.elements?.single { it.contentDescription == "row_40" }
        assertEquals(25, target?.bounds?.height)
        assertTrue(requireNotNull(target).bounds.bottom in 881..999)
    }

    @Test fun noProgressStopsOnceAndTakeoverNeverDispatches() = runTest {
        val scene = ListScene(offset = 0)
        val controller = AdaptiveScrollController { testScheduler.currentTime }
        val result = controller.scroll(scene.screen(), DeviceScrollRequest(DeviceSwipeDirection.Down, 5), "task", 16,
            read = { scene.screen() }, capture = { DeviceToolResult(false, "No image") }, dispatch = { _, path, _ -> scene.dispatch(path) }, check = {})
        assertFalse(result.success); assertEquals("ui_scroll_no_progress", result.errorCode); assertEquals(1, scene.gestures.size)
        try {
            controller.scroll(scene.screen(), DeviceScrollRequest(DeviceSwipeDirection.Up), "task", 16,
                read = { scene.screen() }, capture = { DeviceToolResult(false, "No image") }, dispatch = { _, path, _ -> scene.dispatch(path) },
                check = { throw CancellationException("user takeover") })
            fail("Takeover must cancel")
        } catch (_: CancellationException) { assertEquals(1, scene.gestures.size) }
    }

    @Test fun collapsingHeaderMayExpandTheSameViewportUpward() = runTest {
        val scene = ListScene(offset = 0)
        scene.viewportTop = 100
        val controller = AdaptiveScrollController { testScheduler.currentTime }
        var first = true
        val target = DeviceUiSelector(description = "row_40")
        val result = controller.scroll(scene.screen(), DeviceScrollRequest(DeviceSwipeDirection.Up, target = target),
            "task", 16, read = { scene.screen() }, capture = { DeviceToolResult(false, "No image") },
            dispatch = { _, path, _ ->
                scene.dispatch(path).also {
                    if (first) { first = false; scene.viewportTop = 0 }
                }
            }, check = {})
        assertTrue(result.asToolContent(), result.success)
        assertEquals("ready", result.scrollFeedback?.targetState)
        assertNotEquals("ui_scroll_container_moved", result.errorCode)
        assertEquals(1, scene.gestures.size)
    }

    @Test fun collapsingSiblingHeaderDoesNotAbortScrollWhenContainerBoundsStayFixed() = runTest {
        val scene = ListScene(offset = 0, multiplier = 1.0)
        var headerBottom = 140
        fun screenWithHeader(): DeviceScreenSnapshot {
            val base = scene.screen()
            val header = DeviceUiElement(500, "LinearLayout", "Search", null, "search", DeviceBounds(0, 60, 600, headerBottom),
                true, false, false, true, parentIndex = null, depth = 0)
            return base.copy(elements = base.elements + header)
        }
        val controller = AdaptiveScrollController { testScheduler.currentTime }
        val result = controller.scroll(screenWithHeader(), DeviceScrollRequest(DeviceSwipeDirection.Up, 20), "task", 16,
            read = { screenWithHeader() }, capture = { DeviceToolResult(false, "No image") },
            dispatch = { _, path, _ ->
                scene.dispatch(path).also { headerBottom = 100 }
            }, check = {})
        assertTrue(result.asToolContent(), result.success)
        assertNotEquals("ui_scroll_container_moved", result.errorCode)
    }
}
