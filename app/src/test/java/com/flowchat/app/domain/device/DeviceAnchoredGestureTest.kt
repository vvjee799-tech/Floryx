package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DeviceAnchoredGestureTest {
    private val a = DeviceGesturePoint(20f, 20f)
    private val b = DeviceGesturePoint(180f, 180f)
    @Test fun aPathCannotCrossAnOccluderEvenWhenBothAnchorsAreAvailable() {
        assertTrue(DeviceAnchoredGesturePolicy.coversLine(listOf(DeviceBounds(0, 0, 200, 200)), a, b))
        val regions = listOf(DeviceBounds(0, 0, 200, 99), DeviceBounds(0, 100, 200, 200))
        assertFalse(DeviceAnchoredGesturePolicy.coversLine(regions, a, b))
        assertTrue(DeviceAnchoredGesturePolicy.coversLine(listOf(DeviceBounds(0, 0, 200, 100), DeviceBounds(0, 100, 200, 200)), a, b))
        assertFalse(DeviceAnchoredGesturePolicy.coversLine(listOf(DeviceBounds(0, 0, 20, 20)), a, a))
    }
    @Test fun dragSegmentsRemainContinuousBoundedAndKeepOnlyOnePointer() {
        val request = DeviceAnchoredGesture.Drag(0, 1, 600, 700)
        val legs = DeviceAnchoredGesturePolicy.legs(request, a, b)
        assertEquals(1300L, legs.sumOf { it.durationMillis })
        assertTrue(legs.all { it.durationMillis in 1..50 && it.keepDown })
        assertEquals(a, legs.first().from); assertEquals(b, legs.last().to)
        assertTrue(legs.zipWithNext().all { (x, y) -> x.to == y.from })
    }
    @Test fun malformedOrUnboundedArgumentsNeverBecomeDefaultGestures() {
        for (args in listOf("""{"screen_id":"s","index":0,"target_index":0}""",
            """{"screen_id":"s","index":0,"target_index":1,"duration_ms":9999}""",
            """{"screen_id":"s","index":"0","target_index":1}""",
            """{"screen_id":"s","index":0,"target_index":1,"x":200}"""))
            assertTrue(args, runCatching { DeviceAnchoredGesture.fromToolArguments(args, true) }.isFailure)
        assertTrue(runCatching { DeviceRangeAdjustment.fromToolArguments("""{"screen_id":"s","index":0,"value":16777217}""") }.isFailure)
        assertTrue(runCatching { DeviceRangeAdjustment.fromToolArguments("""{"screen_id":"s","index":0,"value":"5"}""") }.isFailure)
    }
    @Test fun rangeWritesNeedAnAdvertisedActionAndRespectTypeAndLimits() {
        val target = DeviceUiElement(0, "SeekBar", null, "value", "id/value", DeviceBounds(0, 0, 100, 100), true, false, false, true,
            supportedActions = listOf("set_progress"), range = DeviceRangeInfo(0, 0f, 10f, 3f))
        assertTrue(DeviceRangeAdjustment(0, 7f).validate(target))
        assertFalse(DeviceRangeAdjustment(0, 7.5f).validate(target))
        assertFalse(DeviceRangeAdjustment(0, 11f).validate(target))
        assertFalse(DeviceRangeAdjustment(0, 7f).validate(target.copy(supportedActions = emptyList())))
        val before = DeviceScreenSnapshot("fixture", "fixture", listOf(target))
        fun result(value: Float, screen: DeviceScreenSnapshot = before) = DeviceActionOutcomePolicy.complete(before,
            DeviceToolResult(true, "observed", screen = screen.copy(elements = listOf(target.copy(range = target.range!!.copy(current = value))))),
            "dispatched", rangeAdjustment = DeviceRangeAdjustment(0, 7f))
        assertEquals(DeviceActionOutcome.TargetVerified, result(7f).actionOutcome)
        assertEquals("ui_range_unverified", result(6f).errorCode)
        assertFalse(result(7f, before.copy(windowId = 2)).success)
    }
}
