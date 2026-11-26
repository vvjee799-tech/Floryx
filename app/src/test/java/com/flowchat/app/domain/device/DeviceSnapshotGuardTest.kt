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

    @Test fun identicalIndicesCannotCrossScopesRootsOrUnresolvedReads() {
        val scope = DeviceObservationScope(DeviceUiSelector(description = "list"))
        val scoped = screen.copy(observationScope = scope, observationRootPath = "0/3")
        val guard = DeviceSnapshotGuard().apply { remember(scoped) }
        assertTrue(guard.matches("first", scoped, 0))
        assertFalse(guard.matches("first", screen, 0))
        assertFalse(guard.matches("first", scoped.copy(observationRootPath = "0/4"), 0))
        assertFalse(guard.matches("first", scoped.copy(observationScope = scope.copy(root = DeviceUiSelector(description = "other"))), 0))
        guard.remember(scoped.copy(observationErrorCode = "observation_root_ambiguous"))
        assertNull(guard.latest())
    }

    @Test fun progressIgnoresPlaybackClocksCountersAndBoundsButDetectsNewContent() {
        val first = screen.copy(elements = listOf(element.copy(text = "00:01"), element.copy(index = 1, text = "120")))
        val next = first.copy(elements = listOf(element.copy(text = "00:02", bounds = DeviceBounds(1, 1, 201, 81)), element.copy(index = 1, text = "121")))
        assertEquals(first.progressKey(), next.progressKey())
        assertNotEquals(first.progressKey(), next.copy(elements = listOf(element.copy(text = "New video caption"))).progressKey())
    }

    @Test fun editableSelectionProgressDetectsBlankCustomInputsWhileNonEditableRemainsStable() {
        val formula = element.copy(index = 0, className = "EditText", text = "", resourceId = "calc:id/formula", editable = true, textSelectionStart = 6, textSelectionEnd = 6)
        val first = screen.copy(elements = listOf(formula))
        val typed = screen.copy(elements = listOf(formula.copy(textSelectionStart = 7, textSelectionEnd = 7)))
        val sameClock = screen.copy(elements = listOf(formula, element.copy(index = 1, text = "00:05", editable = false)))
        val nextClock = screen.copy(elements = listOf(formula, element.copy(index = 1, text = "00:06", editable = false)))
        assertNotEquals(first.progressKey(), typed.progressKey())
        assertEquals(sameClock.progressKey(), nextClock.progressKey())
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

    @Test fun rotatingBannerDoesNotInvalidateUnchangedScrollContainerWhileTargetChangesReject() {
        val list = DeviceUiElement(0, "RecyclerView", null, null, "list", DeviceBounds(0, 0, 600, 1000),
            clickable = false, editable = false, scrollable = true, enabled = true, nodePath = "0/0")
        val banner = DeviceUiElement(1, "ImageView", null, "Slide 1", "banner", DeviceBounds(0, 100, 600, 300),
            clickable = false, editable = false, scrollable = false, enabled = true, parentIndex = 0)
        val row = DeviceUiElement(2, "TextView", "Row 5", null, "row", DeviceBounds(0, 800, 600, 900),
            clickable = true, editable = false, scrollable = false, enabled = true, parentIndex = 0)
        val initial = DeviceScreenSnapshot("tips", "Main", listOf(list, banner, row), screenId = "first")
        val guard = DeviceSnapshotGuard().apply { remember(initial) }
        val requestMissingTarget = DeviceScrollRequest(DeviceSwipeDirection.Up, screenId = "first", containerIndex = 0,
            target = DeviceUiSelector(text = "Target"))
        val bannerRotated = initial.copy(elements = listOf(list, banner.copy(contentDescription = "Slide 2"), row))
        assertTrue(guard.matchesScroll("first", bannerRotated, requestMissingTarget))
        val requestVisibleTarget = DeviceScrollRequest(DeviceSwipeDirection.Up, screenId = "first", containerIndex = 0,
            target = DeviceUiSelector(text = "Row 5"))
        assertTrue(guard.matchesScroll("first", bannerRotated, requestVisibleTarget))
        val targetMoved = initial.copy(elements = listOf(list, banner, row.copy(bounds = DeviceBounds(0, 700, 600, 800))))
        assertFalse(guard.matchesScroll("first", targetMoved, requestVisibleTarget))
        val containerChanged = initial.copy(elements = listOf(list.copy(bounds = DeviceBounds(0, 50, 600, 1000)), banner, row))
        assertFalse(guard.matchesScroll("first", containerChanged, requestMissingTarget))
    }

    @Test fun editorActionAllowsLiveTelemetryImmediatelyAndBlocksFirstTimeResultArrival() {
        val input = DeviceUiElement(
            index = 1, className = "android.widget.EditText", text = "锁屏时钟通透效果",
            contentDescription = "search", resourceId = "search_edit", bounds = DeviceBounds(0, 100, 600, 180),
            clickable = true, editable = true, scrollable = false, enabled = true, focused = true,
            supportedActions = listOf("set_text", "ime_enter"), editorActionLabel = "搜索", nodePath = "0/1"
        )
        val clock = element.copy(
            index = 0, className = "android.widget.TextView", text = "18:00:01",
            resourceId = "clock", bounds = DeviceBounds(0, 0, 200, 60), clickable = false, checked = null, nodePath = "0/0"
        )
        val row = element.copy(
            index = 2, className = "android.widget.TextView", text = "简易模式",
            resourceId = "row_title", bounds = DeviceBounds(0, 200, 600, 280), clickable = true, checked = null, nodePath = "0/2"
        )
        val initial = DeviceScreenSnapshot("tips", "Search", listOf(clock, input, row), screenId = "s1")
        val guard = DeviceSnapshotGuard().apply { remember(initial) }

        // Live HH:MM:SS clock + bounds animation do not block explicit submit on Call 1.
        val clockTicked = initial.copy(
            elements = listOf(clock.copy(text = "18:00:02", bounds = DeviceBounds(4, 0, 204, 60)), input, row)
        )
        assertTrue(guard.matchesEditorAction("s1", clockTicked, 1))
        assertEquals(initial.progressKey(), clockTicked.progressKey())

        // Stationary async search result arrival (e.g. Vivo Tips) must still return stale_screen on Call 1.
        val resultsLoaded = initial.copy(
            elements = listOf(clock, input, row.copy(text = "锁屏时钟通透效果"))
        )
        assertFalse(guard.matchesEditorAction("s1", resultsLoaded, 1))

        // Draft/focus/label changes on the target input always reject.
        assertFalse(guard.matchesEditorAction("s1", initial.copy(elements = listOf(clock, input.copy(text = "changed"), row)), 1))
        assertFalse(guard.matchesEditorAction("s1", initial.copy(elements = listOf(clock, input.copy(focused = false), row)), 1))
        assertFalse(guard.matchesEditorAction("s1", initial.copy(elements = listOf(clock, input.copy(editorActionLabel = "发送"), row)), 1))
    }

    @Test fun editorActionLearnsVolatileWidgetsAndSubtreesWithoutAllowingUnexpectedControlsOrReplay() {
        val input = DeviceUiElement(
            index = 1, className = "android.widget.EditText", text = "地铁公告",
            contentDescription = "input", resourceId = "input", bounds = DeviceBounds(0, 100, 600, 180),
            clickable = true, editable = true, scrollable = false, enabled = true, focused = true,
            supportedActions = listOf("set_text", "ime_enter"), editorActionLabel = "搜索", nodePath = "0/1"
        )
        val ticker = element.copy(
            index = 0, className = "android.widget.TextView", text = "实时动态：话题 A",
            resourceId = "ticker", bounds = DeviceBounds(0, 0, 600, 80), clickable = false, checked = null, nodePath = "0/0"
        )
        val feedContainer = element.copy(
            index = 2, className = "android.widget.LinearLayout", text = null,
            resourceId = "feed", bounds = DeviceBounds(0, 200, 600, 500), clickable = false, checked = null, nodePath = "0/2"
        )
        val feedItem1 = element.copy(
            index = 3, className = "android.widget.TextView", text = "直播流 #1",
            resourceId = null, bounds = DeviceBounds(0, 200, 600, 280), clickable = false, checked = null, parentIndex = 2, nodePath = "0/2/0"
        )
        val feedItem2 = feedItem1.copy(index = 4, text = "直播流 #2", bounds = DeviceBounds(0, 280, 600, 360), nodePath = "0/2/1")
        val status = element.copy(
            index = 5, className = "android.widget.TextView", text = "等待提交",
            resourceId = "status", bounds = DeviceBounds(0, 520, 600, 580), clickable = false, checked = null, nodePath = "0/3"
        )

        val s1 = DeviceScreenSnapshot("dyn", "Main", listOf(ticker, input, feedContainer, feedItem1, feedItem2, status), screenId = "s1")
        val guard = DeviceSnapshotGuard().apply { remember(s1) }

        val s2 = s1.copy(
            screenId = "s2",
            elements = listOf(
                ticker.copy(text = "实时动态：话题 B"),
                input,
                feedContainer.copy(bounds = DeviceBounds(0, 200, 600, 580)),
                feedItem1.copy(text = "直播流 #2"),
                feedItem2.copy(text = "直播流 #3"),
                feedItem2.copy(index = 5, text = "直播流 #4", bounds = DeviceBounds(0, 360, 600, 440), nodePath = "0/2/2"),
                status.copy(index = 6)
            )
        )
        // Call 1 sees unconfirmed non-numeric changes -> refreshes once.
        assertFalse(guard.matchesEditorAction("s1", s2, 1))
        guard.remember(s2)

        // Call 2 acts on s2 while ticker + feed container continue mutating -> dispatches cleanly.
        val s3 = s1.copy(
            screenId = "s3",
            elements = listOf(
                ticker.copy(text = "实时动态：话题 C"),
                input,
                feedContainer.copy(bounds = DeviceBounds(0, 200, 600, 420)),
                feedItem1.copy(text = "直播流 #3"),
                status.copy(index = 4)
            )
        )
        assertTrue(guard.matchesEditorAction("s2", s3, 1))

        // Even if S1 -> S2 kept the same child count inside the feed container before changing count on S2 -> S3,
        // the multi-child container is still isolated safely while static controls remain protected.
        val sameSizeGuard = DeviceSnapshotGuard().apply { remember(s1) }
        val s2SameCount = s1.copy(
            screenId = "s2b",
            elements = listOf(
                ticker.copy(text = "实时动态：话题 B"),
                input,
                feedContainer,
                feedItem1.copy(text = "直播流 #3"),
                feedItem2.copy(text = "直播流 #4"),
                status
            )
        )
        assertFalse(sameSizeGuard.matchesEditorAction("s1", s2SameCount, 1))
        sameSizeGuard.remember(s2SameCount)
        assertTrue(sameSizeGuard.matchesEditorAction("s2b", s3, 1))

        // Unexpected popup/banner outside volatile regions still blocks submission.
        val popup = status.copy(index = 5, text = "确认弹窗", resourceId = "popup", nodePath = "0/4")
        val s3WithPopup = s3.copy(elements = s3.elements + popup)
        assertFalse(guard.matchesEditorAction("s2", s3WithPopup, 1))

        // An unrelated status change outside volatile slots also blocks submission.
        val s3WithStatusFlip = s3.copy(
            elements = s3.elements.dropLast(1) + status.copy(index = 4, text = "已禁用")
        )
        assertFalse(guard.matchesEditorAction("s2", s3WithStatusFlip, 1))

        // Clearing guard on dispatch prevents replaying s2.
        val filter = guard.captureVolatileFilter(s3, 1)
        assertTrue(filter.matchesOnlyTelemetryOrVolatile(s3, s3.copy(elements = listOf(
            ticker.copy(text = "实时动态：话题 D"),
            input,
            feedContainer.copy(bounds = DeviceBounds(0, 200, 600, 500)),
            feedItem1.copy(text = "直播流 #4"),
            feedItem2.copy(index = 4, text = "直播流 #5"),
            status.copy(index = 5)
        ))))
        assertFalse(filter.matchesOnlyTelemetryOrVolatile(s3, s3.copy(elements = s3.elements.dropLast(1) + status.copy(index = 4, text = "已提交"))))

        guard.remember(null)
        guard.remember(s3.copy(screenId = "s4", elements = s3.elements.dropLast(1) + status.copy(index = 4, text = "已提交")))
        assertFalse(guard.matchesEditorAction("s2", s3, 1))
    }
}
