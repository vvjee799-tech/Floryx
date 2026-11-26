package com.flowchat.app.device

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

/** Real gesture dispatch and independent fixture outcomes, not a mocked gateway. */
class AdaptiveScrollAcceptanceTest : ScrollFixtureDiagnostics() {
    private suspend fun screen(scope: DeviceObservationScope? = null) = requireNotNull(
        (scope?.let { gateway.observeScreen(it) } ?: gateway.observeScreen()).screen)
    private fun DeviceScreenSnapshot.list() = elements.single { it.contentDescription == "scroll_list" }

    @Test fun twoPercentNudgeReportsActualTravelBelowTheOldMinimum() = runBlocking {
        start(); val before = screen(); val extent = before.list().bounds.height
        val result = gateway.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Up, 2, 600))
        val measured = control("report")
        report("micro result=${result.asToolContent().lineSequence().take(5).joinToString(";")} metrics=$measured")
        assertTrue(result.asToolContent(), result.success)
        assertTrue(measured.toString(), measured.getInt("listY") in 1 until extent / 5)
        assertTrue(measured.toString(), abs(measured.getInt("fingerPixels")) < extent / 10)
        assertEquals(measured.getInt("listY").toDouble(), result.scrollFeedback!!.motion!!.contentPixels.toDouble(), 5.0)
    }

    @Test fun partlyVisibleTargetsAreRefinedAndOpenedWithoutManualCorrection() = runBlocking {
        repeat(3) { sample ->
            start(); if (sample > 0) control("position", 37 * sample)
            val before = screen()
            val target = before.elements.filter { it.contentDescription?.startsWith("scroll_item_") == true }.maxBy { it.bounds.bottom }
            val started = SystemClock.elapsedRealtime()
            val result = gateway.scrollContainer(DeviceContainerScrollRequest(before.list().index, DeviceScrollDirection.Down,
                before.screenId, target = DeviceUiSelector(description = target.contentDescription)))
            assertTrue(result.asToolContent(), result.success)
            assertEquals("ready", result.scrollFeedback!!.targetState)
            val fresh = requireNotNull(result.screen); val found = fresh.elements.single { it.contentDescription == target.contentDescription }
            val tap = gateway.tapUiElement(found.index, false, fresh.screenId)
            assertTrue(tap.asToolContent(), tap.success)
            val measured = control("report")
            assertEquals(target.contentDescription!!.substringAfterLast('_').toInt(), measured.getInt("selected"))
            report("task=partial sample=$sample completed=true interruptions=0 manual_steps=0 elapsed_ms=${SystemClock.elapsedRealtime() - started} feedback=${result.scrollFeedback} metrics=$measured")
        }
    }

    private suspend fun findAndOpen(mode: String, row: Int, sample: Int) {
        start(mode)
        if (sample > 0) control("position", sample * 110)
        val scope = if (mode == "nested") DeviceObservationScope(DeviceUiSelector(description = "scroll_list"), DeviceObservationScope.Kind.ScrollContainer) else null
        var current = screen(scope)
        val started = SystemClock.elapsedRealtime()
        var movements = 0; var corrections = 0; var invalid = 0
        repeat(22) {
            val result = gateway.scrollContainer(DeviceContainerScrollRequest(current.list().index, DeviceScrollDirection.Down,
                current.screenId, target = DeviceUiSelector(description = "scroll_item_$row")))
            movements += result.scrollFeedback?.actions ?: 0; corrections += result.scrollFeedback?.corrections ?: 0
            assertTrue(result.asToolContent(), result.success)
            current = requireNotNull(result.screen)
            if (result.scrollFeedback?.targetState == "ready") {
                val target = current.elements.single { it.contentDescription == "scroll_item_$row" }
                val tap = gateway.tapUiElement(target.index, false, current.screenId)
                assertTrue(tap.asToolContent(), tap.success)
                val measured = control("report")
                assertEquals(row, measured.getInt("selected"))
                if (mode == "nested") { assertEquals(0, measured.getInt("outerY")); assertEquals(0, measured.getInt("siblingY")) }
                report("task=$mode sample=$sample completed=true interruptions=0 manual_steps=0 actions=$movements corrections=$corrections invalid=$invalid elapsed_ms=${SystemClock.elapsedRealtime() - started} metrics=$measured")
                return
            }
        }
        fail("Target was not found within the bounded traversal")
    }

    @Test fun longListFindsAndOpensAnExactTargetFromThreeStartingPositions() = runBlocking {
        repeat(3) { findAndOpen("list", 65 + it * 4, it) }
    }
    @Test fun nestedSearchLeavesParentAndSiblingStillAcrossThreeTasks() = runBlocking {
        repeat(3) { findAndOpen("nested", 15 + it, it) }
    }

    @Test fun boundaryStopsAfterOneAttempt() = runBlocking {
        start(); screen()
        val result = gateway.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Down, 3, 600))
        assertFalse(result.asToolContent(), result.success)
        assertEquals("ui_scroll_no_progress", result.errorCode)
        assertEquals(1, result.scrollFeedback!!.actions)
        report("boundary=no-progress; gestures=1; metrics=${control("report")}")
    }

    @Test fun sideOverlayDoesNotCauseAnAmplifiedGestureToLoseTheTarget() = runBlocking {
        openAmplifiedTarget(gainTenths = 58, expectReverse = false)
    }

    @Test fun anAppThatAmplifiesTravelIsCorrectedWithASmallerReverseMovement() = runBlocking {
        openAmplifiedTarget(gainTenths = 64, expectReverse = true)
    }

    @Test fun recoversATargetHiddenBeyondTheNavigationBar() = runBlocking {
        val gain = InstrumentationRegistry.getArguments()
            .getString("overshootNoOverlapGain", "67").toInt()
        check(gain in 65..67)
        openAmplifiedTarget(gainTenths = gain, expectReverse = true, expectGone = gain >= 66)
    }

    private suspend fun openAmplifiedTarget(gainTenths: Int, expectReverse: Boolean, expectGone: Boolean = false) {
        val row = 44
        start("gain", targetRow = row, initialY = 5000, gainTenths = gainTenths)
        val initial = control("report")
        assertEquals(5000, initial.getInt("listY"))
        val before = screen()
        val selector = DeviceUiSelector(description = "scroll_item_$row")
        val request = DeviceContainerScrollRequest(before.list().index, DeviceScrollDirection.Up, before.screenId, target = selector)
        report("overshoot_before gain=$gainTenths metrics=$initial ${scrollGeometry(before, request.gesture())}")
        val result = gateway.scrollContainer(request)
        val moved = control("report")
        report("overshoot injected_drag_gain=${gainTenths / 10f} feedback=${result.scrollFeedback} metrics=$moved")
        // The metrics read may refresh the snapshot when OriginOS drops its broadcast.
        val fresh = screen()
        val freshRequest = request.gesture().copy(containerIndex = fresh.list().index)
        report("overshoot_after gain=$gainTenths ${scrollGeometry(fresh, freshRequest)}")
        val area = requireNotNull(DeviceAdaptiveScroll.area(before, request.gesture()))
        val initialTarget = before.elements.single(selector::matches)
        val firstMove = moved.getJSONArray("movements").getJSONObject(0)
        val firstTargetBottom = initialTarget.bounds.bottom + initial.getInt("listY") - firstMove.getInt("to_y")
        if (expectGone) assertTrue(moved.toString(), firstTargetBottom - initial.getInt("target_height") >= area.bounds.bottom)
        assertTrue(result.asToolContent(), result.success)
        val feedback = requireNotNull(result.scrollFeedback)
        assertEquals("ready", feedback.targetState)
        val trace = moved.getJSONArray("fingerTrace")
        if (expectReverse) {
            assertTrue(moved.toString(), firstTargetBottom > area.bounds.bottom)
            assertEquals(1, feedback.corrections)
            assertTrue(feedback.toString(), feedback.actions in 2..3)
            assertTrue(moved.toString(), trace.getInt(0) < 0 && trace.getInt(1) > 0)
            assertTrue(moved.toString(), abs(trace.getInt(1)) < abs(trace.getInt(0)))
        } else {
            assertTrue(moved.toString(), firstTargetBottom <= area.bounds.bottom)
            assertEquals(0, feedback.corrections)
            assertEquals(1, feedback.actions)
            assertEquals(initial.getInt("target_height"), firstMove.getInt("visible_height_at_end"))
            feedback.motion?.let { measured ->
                assertEquals(initial.getInt("listY") - moved.getInt("listY").toDouble(), abs(measured.contentPixels).toDouble(), 5.0)
            }
        }
        val found = fresh.elements.single(selector::matches)
        val opened = gateway.tapUiElement(found.index, false, fresh.screenId)
        assertTrue(opened.asToolContent(), opened.success)
        val openedMetrics = control("report")
        assertEquals(row, openedMetrics.getInt("selected"))
        assertEquals(initial.getInt("target_height"), openedMetrics.getJSONArray("clicks").getJSONObject(0).getInt("visible_height"))
        report("overshoot_opened gain=$gainTenths first_target_bottom=$firstTargetBottom metrics=$openedMetrics")
    }

    private fun scrollGeometry(screen: DeviceScreenSnapshot, request: DeviceScrollRequest): String {
        val area = requireNotNull(DeviceAdaptiveScroll.area(screen, request))
        val context = requireNotNull(screen.interactionContext)
        val target = DeviceAdaptiveScroll.targets(screen, area, request.target).singleOrNull()
        val targetLayer = context.windows.single { it.id == screen.windowId }.layer
        val blockers = context.windows.filter { it.layer > targetLayer }.map { it.kind to it.regions }
        val rows = DeviceAdaptiveScroll.descendants(screen, area.container).mapNotNull { element ->
            element.contentDescription?.takeIf { it.startsWith("scroll_item_") }?.let { "$it=${element.bounds}" }
        }
        return "viewport=${area.container?.bounds} operating=${area.bounds} " +
            "available=${context.availableRegions(screen)} blockers=$blockers cutouts=${context.cutouts} " +
            "target=${target?.bounds} requested_delta=${target?.let { DeviceAdaptiveScroll.targetDelta(area, it, true) }} rows=$rows"
    }

    @Test fun canvasUsesPixelDisplacementAndWebViewUsesItsActualContent() = runBlocking {
        for (mode in listOf("canvas", "web")) {
            start(mode); screen()
            val baseline = if (mode == "canvas") gateway.captureScreen() else null
            val result = gateway.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Up, 5, 600))
            report("mode=$mode success=${result.success} code=${result.errorCode} feedback=${result.scrollFeedback}")
            if (mode == "canvas" && baseline?.screenImage != null && result.screenImage != null) {
                val diagnostics = mutableListOf<String>()
                val pixels = com.flowchat.app.data.device.ScrollImageMotionEstimator.measure(baseline.screenImage!!, result.screenImage!!,
                    requireNotNull(baseline.screen?.geometry).windowBounds, true) { diagnostics += it }
                report("canvas_independent_motion=$pixels diagnostics=$diagnostics metrics=${control("report")}")
                if (!result.success) {
                    val cache = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                    cache.resolve("autonomy-scroll-before.png").writeBytes(java.util.Base64.getDecoder().decode(baseline.screenImage!!.image.base64))
                    cache.resolve("autonomy-scroll-after.png").writeBytes(java.util.Base64.getDecoder().decode(result.screenImage!!.image.base64))
                }
            }
            assertTrue(result.asToolContent(), result.success)
            assertTrue(requireNotNull(result.scrollFeedback?.motion).contentPixels > 0)
            if (mode == "canvas") {
                assertEquals("pixels", result.scrollFeedback?.motion?.source)
                assertEquals(control("report").getInt("listY").toDouble(), result.scrollFeedback!!.motion!!.contentPixels.toDouble(), 5.0)
            }
        }
    }
}
