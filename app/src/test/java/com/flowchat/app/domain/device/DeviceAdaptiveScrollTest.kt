package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DeviceAdaptiveScrollTest {
    private val bounds = DeviceBounds(0, 0, 600, 1000)
    private val root = DeviceUiElement(0, "ScrollView", null, "list", null, bounds, false, false, true, true, nodePath = "0/0")
    private fun scene(offset: Int = 0) = DeviceScreenSnapshot("fixture", "list", listOf(root) + (0..25).map { row ->
        DeviceUiElement(row + 1, "TextView", "Row $row", null, "row", DeviceBounds(0, row * 40 - offset, 600, row * 40 + 40 - offset),
            true, false, false, true, parentIndex = 0, depth = 1)
    }, windowId = 1, geometry = DeviceScreenGeometry(bounds, 600, 1000, 0),
        interactionContext = DeviceInteractionContext(listOf(DeviceWindowRegion(1, DeviceWindowKind.Application, 1, bounds, listOf(bounds), true, true))))

    @Test fun microDistanceSurvivesGeometryAndInvalidParametersCannotBecomeFiftyPercent() {
        val screen = scene()
        val request = DeviceScrollRequest.fromSwipeArguments("""{"direction":"up","distance_percent":2,"duration_ms":600}""")
        val path = screen.interactionContext!!.swipe(screen, request.direction, request.distancePercent!!)!!
        assertEquals(20, path.startY - path.endY)
        for (args in listOf("""{"direction":"up","distance_percent":0}""", """{"direction":"up","distance_percent":"2"}""",
            """{"direction":"up","duration_ms":null}""", """{"direction":"up","container_index":0}""")) {
            assertTrue(args, runCatching { DeviceScrollRequest.fromSwipeArguments(args) }.isFailure)
        }
    }

    @Test fun movementUsesAgreeingContentAnchorsRatherThanFingerTravelOrChangingIndices() {
        val before = scene(); val area = DeviceAdaptiveScroll.area(before, DeviceScrollRequest(DeviceSwipeDirection.Up))!!
        val after = scene(73).copy(elements = scene(73).elements.map { if (it.index == 0) it else it.copy(index = it.index + 100, parentIndex = 0) })
        assertEquals(73, DeviceAdaptiveScroll.measure(before, after, area, true)!!.contentPixels)
        val duplicate = before.copy(elements = before.elements.map { if (it.index == 0) it else it.copy(text = "Repeated") })
        assertNull(DeviceAdaptiveScroll.measure(duplicate, duplicate, area, true))
        assertNull(DeviceAdaptiveScroll.measure(before, after.copy(windowId = 2), area, true))
    }

    @Test fun nestedAndSiblingContainersNeedAnExplicitChoiceAndOuterGesturesAvoidTheChild() {
        val child = root.copy(index = 100, contentDescription = "inner", bounds = DeviceBounds(0, 0, 600, 400), parentIndex = 0, depth = 1, nodePath = "0/0/0")
        val screen = scene().copy(elements = listOf(root, child))
        assertNull(DeviceAdaptiveScroll.area(screen, DeviceScrollRequest(DeviceSwipeDirection.Up)))
        val area = DeviceAdaptiveScroll.area(screen, DeviceScrollRequest(DeviceSwipeDirection.Up, screenId = screen.screenId, containerIndex = 0))!!
        val path = screen.interactionContext!!.swipe(screen, DeviceSwipeDirection.Up, 20, area.bounds, area.excluded)!!
        assertTrue(path.endY >= 400 && path.startY >= 400)
    }

    @Test fun aClippedTargetNeedsRefinementAndSemanticForwardIsNotAControlledPhysicalDirection() {
        val screen = scene(); val request = DeviceScrollRequest(DeviceSwipeDirection.Up)
        val area = DeviceAdaptiveScroll.area(screen, request)!!
        val target = screen.elements.first { it.text == "Row 24" }
        assertFalse(DeviceAdaptiveScroll.targetReady(screen, area, target, true))
        assertTrue(DeviceAdaptiveScroll.targetDelta(area, target, true) in 1..199)
        assertTrue(runCatching { DeviceContainerScrollRequest.fromArguments("""{"screen_id":"fresh","index":0,"direction":"forward","distance_percent":2}""") }.isFailure)
        assertFalse(DeviceContainerScrollRequest.fromArguments("""{"screen_id":"fresh","index":0,"direction":"forward"}""").controlled)
    }

    @Test fun aSideOverlayDoesNotMoveTheScrollEdgePastAClippedTarget() {
        val screen = scene(offset = 18)
        val context = requireNotNull(screen.interactionContext)
        val navigationBar = DeviceBounds(0, 965, 600, 1000)
        val floatingControl = DeviceBounds(500, 365, 570, 440)
        fun window(id: Int, region: DeviceBounds) =
            DeviceWindowRegion(id, DeviceWindowKind.System, id, region, listOf(region), false, false)
        val unobstructed = screen.copy(interactionContext = context.copy(windows = context.windows + window(2, navigationBar)))
        val obstructed = screen.copy(interactionContext = context.copy(windows =
            context.windows + window(2, navigationBar) + window(3, floatingControl)))
        val selector = DeviceUiSelector(text = "Row 0")
        val request = DeviceScrollRequest(DeviceSwipeDirection.Down, target = selector)
        val normalArea = requireNotNull(DeviceAdaptiveScroll.area(unobstructed, request))
        val area = requireNotNull(DeviceAdaptiveScroll.area(obstructed, request))
        val target = DeviceAdaptiveScroll.targets(obstructed, area, selector).single()
            .copy(bounds = DeviceBounds(0, 0, 600, 22))

        assertEquals(DeviceBounds(0, 0, 600, 965), area.bounds)
        val distance = DeviceAdaptiveScroll.targetDelta(area, target, true)
        assertEquals(DeviceAdaptiveScroll.targetDelta(normalArea, target, true), distance)
        val path = requireNotNull(obstructed.interactionContext?.swipe(obstructed, request.direction, 55,
            area.bounds, area.excluded, -distance))
        assertTrue(path.startX < floatingControl.left && path.endX == path.startX && path.endY < navigationBar.top)
    }

    @Test fun singleEdgeClippedAnchorsSupportOvershootMeasurementAndShiftedNodePathResolvesUniqueContainer() {
        val viewport = DeviceBounds(0, 100, 600, 500)
        val container = root.copy(resourceId = "com.example:id/recycler_view", bounds = viewport, nodePath = "0/0/1")
        val before = scene().copy(
            elements = listOf(
                container,
                DeviceUiElement(1, "TextView", "Row 41", null, "row", DeviceBounds(0, 100, 600, 180), true, false, false, true, parentIndex = 0, depth = 1),
                DeviceUiElement(2, "TextView", "Row 42", null, "row", DeviceBounds(0, 180, 600, 280), true, false, false, true, parentIndex = 0, depth = 1),
                DeviceUiElement(3, "TextView", "Row 43", null, "row", DeviceBounds(0, 280, 600, 380), true, false, false, true, parentIndex = 0, depth = 1)
            )
        )
        val shiftedContainer = container.copy(nodePath = "0/0/0")
        val after = before.copy(
            elements = listOf(
                shiftedContainer,
                DeviceUiElement(1, "TextView", "Row 41", null, "row", DeviceBounds(0, 230, 600, 330), true, false, false, true, parentIndex = 0, depth = 1),
                DeviceUiElement(2, "TextView", "Row 42", null, "row", DeviceBounds(0, 330, 600, 430), true, false, false, true, parentIndex = 0, depth = 1),
                DeviceUiElement(3, "TextView", "Row 43", null, "row", DeviceBounds(0, 430, 600, 500), true, false, false, true, parentIndex = 0, depth = 1)
            )
        )
        assertEquals(shiftedContainer, DeviceAdaptiveScroll.resolveContainer(after, container))
        val area = requireNotNull(DeviceAdaptiveScroll.area(before, DeviceScrollRequest(DeviceSwipeDirection.Up)))
        val motion = requireNotNull(DeviceAdaptiveScroll.measure(before, after, area, true))
        assertEquals(-150, motion.contentPixels)
        assertEquals(3, motion.anchors)
    }

    @Test fun orthogonalScrollableAncestorsDoNotMarkVerticalContainersAsSameAxisNested() {
        assertFalse(DeviceAdaptiveScroll.isSameAxisScrollableAncestor("androidx.viewpager.widget.ViewPager", emptyList(), vertical = true))
        assertFalse(DeviceAdaptiveScroll.isSameAxisScrollableAncestor("android.widget.HorizontalScrollView", emptyList(), vertical = true))
        assertFalse(DeviceAdaptiveScroll.isSameAxisScrollableAncestor("androidx.recyclerview.widget.RecyclerView", listOf("scroll_left", "scroll_right"), vertical = true))
        assertTrue(DeviceAdaptiveScroll.isSameAxisScrollableAncestor("android.widget.ScrollView", emptyList(), vertical = true))
        assertTrue(DeviceAdaptiveScroll.isSameAxisScrollableAncestor("androidx.recyclerview.widget.RecyclerView", listOf("scroll_up", "scroll_down"), vertical = true))
        assertFalse(DeviceAdaptiveScroll.isSameAxisScrollableAncestor("android.widget.ScrollView", emptyList(), vertical = false))
    }

    @Test fun siblingChromeOverlappingAFullBleedContainerTrimsOperatingEdgesAndSwipeLanes() {
        val fullContainer = root.copy(index = 1, parentIndex = 0, depth = 1, bounds = bounds, nodePath = "0/0")
        val pagerParent = root.copy(index = 0, parentIndex = null, depth = 0, bounds = bounds, nodePath = "0")
        val topSearch = DeviceUiElement(50, "LinearLayout", "Search", null, "search", DeviceBounds(24, 100, 576, 140),
            true, false, false, true, parentIndex = null, depth = 0)
        val bottomTabs = DeviceUiElement(51, "LinearLayout", null, null, "tabs", DeviceBounds(0, 900, 600, 1000),
            true, false, false, true, parentIndex = null, depth = 0)
        val bottomRow = DeviceUiElement(2, "TextView", "Card", null, "card", DeviceBounds(0, 880, 600, 960),
            true, false, false, true, parentIndex = 1, depth = 2)
        val screen = scene().copy(elements = listOf(pagerParent, fullContainer, bottomRow, topSearch, bottomTabs))
        val request = DeviceScrollRequest(DeviceSwipeDirection.Up, 80, screenId = screen.screenId, containerIndex = 1)
        val area = requireNotNull(DeviceAdaptiveScroll.area(screen, request))
        assertEquals(DeviceBounds(0, 140, 600, 900), area.bounds)
        assertFalse(DeviceAdaptiveScroll.targetReady(screen, area, bottomRow, vertical = true))
        val path = requireNotNull(screen.interactionContext?.swipe(screen, DeviceSwipeDirection.Up, 80, area.bounds, area.excluded))
        assertTrue(path.toString(), path.startY in 140 until 900 && path.endY in 140 until 900)
    }
}
