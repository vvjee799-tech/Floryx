package com.flowchat.app.domain.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DeviceSafetyBoundaryTest {
    @Test fun recoveringTheScreenDoesNotReviveTheOldRevisionOrClearItsReason() {
        for ((locked, interactive, reason) in listOf(
            Triple(true, true, DeviceSafetyReason.Locked),
            Triple(false, false, DeviceSafetyReason.ScreenOff)
        )) {
            val boundary = DeviceSafetyBoundary()
            val captured = boundary.updateScreen(false, true, 10).revision
            boundary.requireCurrent(captured)
            boundary.requireScreenAvailable()

            boundary.updateScreen(locked, interactive, 20)
            assertThrows(CancellationException::class.java) { boundary.requireScreenAvailable() }
            val recovered = boundary.updateScreen(false, true, 30)

            assertEquals(captured + 1, recovered.revision)
            assertEquals(reason, recovered.reason)
            assertEquals(30L, recovered.checkedAtMillis)
            boundary.requireScreenAvailable()
            boundary.requireCurrent(recovered.revision)
            val cancelled = assertThrows(CancellationException::class.java) { boundary.requireCurrent(captured) }
            assertEquals("Phone control boundary changed; start or resume to observe again.", cancelled.message)
        }
    }

    @Test fun repeatedUnsafeReadingsUpdateTheCheckTimeWithoutCreatingAnotherRevision() {
        val boundary = DeviceSafetyBoundary()
        val first = boundary.updateScreen(true, false, 10)
        val repeated = boundary.updateScreen(true, false, 20)

        assertEquals(1L, first.revision)
        assertEquals(first.revision, repeated.revision)
        assertEquals(DeviceSafetyReason.Locked, repeated.reason)
        assertEquals(20L, repeated.checkedAtMillis)
        assertEquals(true, repeated.screenLocked)
        assertEquals(false, repeated.screenInteractive)
    }

    @Test fun simultaneousLockAndScreenOffCreateOneBoundaryWithLockPriority() {
        val boundary = DeviceSafetyBoundary()
        boundary.updateScreen(false, true, 10)

        val unsafe = boundary.updateScreen(true, false, 20)

        assertEquals(1L, unsafe.revision)
        assertEquals(DeviceSafetyReason.Locked, unsafe.reason)
    }

    @Test fun eachFactorCanCreateANewBoundaryWhileTheOtherFactorRemainsUnsafe() {
        val boundary = DeviceSafetyBoundary()
        assertEquals(1L, boundary.updateScreen(true, true, 10).revision)
        val screenOff = boundary.updateScreen(true, false, 20)
        assertEquals(2L, screenOff.revision)
        assertEquals(DeviceSafetyReason.ScreenOff, screenOff.reason)
        assertEquals(2L, boundary.updateScreen(false, false, 30).revision)
        val relocked = boundary.updateScreen(true, false, 40)
        assertEquals(3L, relocked.revision)
        assertEquals(DeviceSafetyReason.Locked, relocked.reason)
    }

    @Test fun unknownScreenStateIsPreservedAndUnavailableWithoutCreatingAnEpoch() {
        val boundary = DeviceSafetyBoundary()
        assertEquals(DeviceSafetyState(), boundary.state.value)
        assertThrows(CancellationException::class.java) { boundary.requireScreenAvailable() }

        for ((locked, interactive) in listOf(null to null, false to null, null to true)) {
            val unknown = boundary.updateScreen(locked, interactive, 10)
            assertEquals(0L, unknown.revision)
            assertEquals(locked, unknown.screenLocked)
            assertEquals(interactive, unknown.screenInteractive)
            assertNull(unknown.reason)
            val cancelled = assertThrows(CancellationException::class.java) { boundary.requireScreenAvailable() }
            assertTrue(cancelled.message.orEmpty().contains("unverified"))
        }
        boundary.updateScreen(false, true, 20)
        boundary.requireScreenAvailable()
        boundary.updateScreen(null, true, 30)
        assertThrows(CancellationException::class.java) { boundary.requireScreenAvailable() }
        assertEquals(0L, boundary.state.value.revision)
    }

    @Test fun firstKnownUnsafeValuesInvalidateEvenWhenTheOtherValueIsUnknown() {
        val locked = DeviceSafetyBoundary()
        val lockedState = locked.updateScreen(true, null, 10)
        assertEquals(1L, lockedState.revision)
        assertEquals(DeviceSafetyReason.Locked, lockedState.reason)
        assertTrue(assertThrows(CancellationException::class.java) {
            locked.requireScreenAvailable()
        }.message.orEmpty().contains("locked"))

        val screenOff = DeviceSafetyBoundary()
        val offState = screenOff.updateScreen(null, false, 10)
        assertEquals(1L, offState.revision)
        assertEquals(DeviceSafetyReason.ScreenOff, offState.reason)
        assertTrue(assertThrows(CancellationException::class.java) {
            screenOff.requireScreenAvailable()
        }.message.orEmpty().contains("screen is off"))
    }

    @Test fun serviceAndPermissionEventsPreserveScreenEvidenceAndEachInvalidate() {
        val boundary = DeviceSafetyBoundary()
        val screen = boundary.updateScreen(false, true, 10)

        for ((index, reason) in listOf(
            DeviceSafetyReason.AccessibilityLost,
            DeviceSafetyReason.PowerConnectionLost,
            DeviceSafetyReason.PowerConnectionLost
        ).withIndex()) {
            boundary.invalidate(reason)
            val current = boundary.state.value
            assertEquals((index + 1).toLong(), current.revision)
            assertEquals(reason, current.reason)
            assertEquals(screen.screenLocked, current.screenLocked)
            assertEquals(screen.screenInteractive, current.screenInteractive)
            assertEquals(screen.checkedAtMillis, current.checkedAtMillis)
            boundary.requireScreenAvailable()
            assertThrows(CancellationException::class.java) { boundary.requireCurrent(screen.revision) }
        }
        val refreshed = boundary.updateScreen(false, true, 20)
        assertEquals(3L, refreshed.revision)
        assertEquals(DeviceSafetyReason.PowerConnectionLost, refreshed.reason)
    }

    @Test fun concurrentInvalidationCountsEveryEventWithoutLostUpdates() {
        val boundary = DeviceSafetyBoundary()
        val workers = 4
        val eventsPerWorker = 100
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        try {
            val tasks = List(workers) {
                executor.submit {
                    start.await()
                    repeat(eventsPerWorker) { boundary.invalidate(DeviceSafetyReason.AccessibilityLost) }
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals((workers * eventsPerWorker).toLong(), boundary.state.value.revision)
            assertEquals(DeviceSafetyReason.AccessibilityLost, boundary.state.value.reason)
            assertNull(boundary.state.value.screenLocked)
            assertNull(boundary.state.value.screenInteractive)
            assertNull(boundary.state.value.checkedAtMillis)
        } finally {
            executor.shutdownNow()
        }
    }
}
