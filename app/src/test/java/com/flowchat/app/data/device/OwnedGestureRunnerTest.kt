package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedGestureRunnerTest {
    private val point = DeviceGesturePoint(10f, 10f)
    private val held = DeviceGestureLeg(point, point, 50, true)
    @Test fun cancellationWaitsForTheIssuedSegmentThenReleasesOnlyItsContinuation() = runTest {
        val calls = mutableListOf<Pair<DeviceGestureLeg, Int?>>()
        val completion = CompletableDeferred<Boolean>()
        val runner = OwnedGestureRunner<Int>({ leg, previous ->
            calls += leg to previous
            OwnedGestureRunner.Pending(calls.size, if (calls.size == 1) completion else CompletableDeferred(true))
        }, {})
        val job = launch { runner.run(listOf(held, held)) }
        runCurrent(); assertEquals(1, calls.size)
        job.cancel(); runCurrent(); assertFalse(job.isCompleted)
        completion.complete(true); job.join()
        assertEquals(2, calls.size); assertEquals(1, calls.last().second)
        assertFalse(calls.last().first.keepDown); assertEquals(point, calls.last().first.to); assertFalse(runner.holdingTouch)
    }
    @Test fun aRejectedContinuationStillReleasesThePreviouslyHeldStroke() = runTest {
        val previousTokens = mutableListOf<Int?>()
        val runner = OwnedGestureRunner<Int>({ _, previous ->
            previousTokens += previous
            OwnedGestureRunner.Pending(previousTokens.size, CompletableDeferred(true), accepted = previousTokens.size != 2)
        }, {})
        assertFalse(runner.run(listOf(held, held.copy(to = DeviceGesturePoint(20f, 20f)))))
        assertEquals(listOf(null, 1, 1), previousTokens); assertFalse(runner.holdingTouch)
    }
    @Test fun systemCancellationDoesNotInjectAnotherTouchOrReleaseIntoTheUsersGesture() = runTest {
        var count = 0
        val runner = OwnedGestureRunner<Int>({ _, _ -> count++; OwnedGestureRunner.Pending(count, CompletableDeferred(false)) }, {})
        assertFalse(runner.run(listOf(held, held))); assertEquals(1, count); assertFalse(runner.holdingTouch)
    }
    @Test fun cancellationBetweenTapsPreventsTheSecondDown() = runTest {
        var count = 0
        val runner = OwnedGestureRunner<Int>({ _, _ -> count++; OwnedGestureRunner.Pending(count, CompletableDeferred(true)) }, {})
        val tap = held.copy(keepDown = false)
        val job = launch { runner.run(listOf(tap, tap)) }
        runCurrent(); assertEquals(1, count)
        job.cancelAndJoin(); assertEquals(1, count); assertFalse(runner.holdingTouch)
    }
    @Test fun anUnconfirmedReleaseIsNotReportedAsSafe() = runTest {
        var count = 0
        val runner = OwnedGestureRunner<Int>({ _, _ -> count++; OwnedGestureRunner.Pending(count,
            if (count == 1) CompletableDeferred(true) else CompletableDeferred()) }, {})
        val result = runCatching { runner.run(listOf(held)) }
        assertTrue(result.isFailure); assertEquals("ui_touch_release_unverified", result.exceptionOrNull()?.message)
        assertTrue(runner.holdingTouch)
    }
    @Test fun stationaryHoldTimeUsesTheOriginalPointerWithoutEmptyContinuations() = runTest {
        val calls = mutableListOf<Pair<DeviceGestureLeg, Int?>>()
        val runner = OwnedGestureRunner<Int>({ leg, previous ->
            calls += leg to previous; OwnedGestureRunner.Pending(calls.size, CompletableDeferred(true))
        }, {}, { testScheduler.currentTime })
        val moving = held.copy(to = DeviceGesturePoint(20f, 20f))
        assertTrue(runner.run(listOf(held, held, held, moving)))
        assertEquals(200L, testScheduler.currentTime)
        assertEquals(3, calls.size)
        assertEquals(listOf(null, 1, 2), calls.map { it.second })
        assertFalse(calls.last().first.keepDown); assertFalse(runner.holdingTouch)
    }
}
