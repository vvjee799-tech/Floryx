package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.ChatImage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRegionWaiterTest {
    private val geometry = DeviceScreenGeometry(DeviceBounds(0, 0, 100, 100), 100, 100, 0)
    private val screen = DeviceScreenSnapshot("fixture", "", emptyList(), screenId = "crop", windowId = 1, geometry = geometry)
    private val image = DeviceScreenImage(ChatImage("image/png", ""), "crop", 100, 20,
        region = ScreenImageRegion(0, 70, 100, 90), geometry = geometry, captureBounds = geometry.windowBounds)
    private val frame = VisualFrameGuard.Frame(screen, image, 0, null)
    private val request = DeviceScreenWaitRequest(DeviceScreenCondition(regionChangedFrom = "crop"), 2200)
    private fun result() = DeviceToolResult(true, "Frame", screen = screen, screenImage = image)

    @Test fun delayedPixelResultIsOnlyProgressAndUsesBoundedSampling() = runTest {
        var reads = 0
        val result = ScreenRegionWaiter({ reads++; result() }, { testScheduler.currentTime }, { _, _ ->
            if (testScheduler.currentTime >= 1600) .05 else 0.0
        }).observe(request, frame)
        assertTrue(result.success); assertEquals(true, result.conditionMatched)
        assertEquals(DeviceActionOutcome.ProgressObserved, result.actionOutcome)
        assertEquals(4, reads)
    }

    @Test fun unavailableBaselineChangedWindowAndSecureCaptureNeverMatch() = runTest {
        var reads = 0
        val waiter = ScreenRegionWaiter({ reads++; result() }, { testScheduler.currentTime }, { _, _ -> .1 })
        assertEquals("screen_region_baseline_unavailable", waiter.observe(request, null).errorCode)
        assertEquals("screen_region_baseline_unavailable", waiter.observe(request, frame.copy(image = image.copy(region = null))).errorCode)
        assertEquals("screen_region_baseline_unavailable", waiter.observe(request.copy(imageRegion = ScreenImageRegion(0, 0, 100, 20)), frame).errorCode)
        assertEquals(0, reads)
        val changed = ScreenRegionWaiter({ result().copy(screen = screen.copy(windowId = 2)) }, { testScheduler.currentTime }, { _, _ -> .1 })
            .observe(request, frame)
        assertEquals("screen_region_target_changed", changed.errorCode); assertEquals(false, changed.conditionMatched)
        val blocked = ScreenRegionWaiter({ DeviceToolResult(false, "Blocked", errorCode = "sensitive_screen_blocked") }, { testScheduler.currentTime })
            .observe(request, frame)
        assertEquals("sensitive_screen_blocked", blocked.errorCode)
    }

    @Test fun cancellationAndSlowCapturesRespectTheTotalBudget() = runTest {
        var reads = 0
        val waiter = ScreenRegionWaiter({ reads++; delay(5000); result() }, { testScheduler.currentTime }, { _, _ -> .1 })
        val timed = waiter.observe(request, frame)
        assertEquals("screen_condition_timeout", timed.errorCode)
        assertEquals(2200L, testScheduler.currentTime)
        val job = launch { waiter.observe(request, frame) }
        delay(100); job.cancelAndJoin()
        val stoppedAt = reads; delay(1000); assertEquals(stoppedAt, reads)
    }

    @Test fun unrelatedPixelsAndCombinedUnmetBusinessPredicateTimeOut() = runTest {
        val unchanged = ScreenRegionWaiter({ result() }, { testScheduler.currentTime }, { _, _ -> .009 }).observe(request, frame)
        assertEquals("screen_condition_timeout", unchanged.errorCode)
        val combined = request.copy(condition = request.condition!!.copy(packageName = "success"))
        val changed = ScreenRegionWaiter({ result() }, { testScheduler.currentTime }, { _, _ -> .1 }).observe(combined, frame)
        assertEquals("screen_condition_timeout", changed.errorCode)
    }
}
