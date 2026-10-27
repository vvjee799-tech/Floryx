package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceScreenSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReadinessWaiterTest {
    private val canvas = DeviceScreenSnapshot("fixture", "Canvas", emptyList(), windowId = 1)

    @Test fun emptyCanvasReturnsWithoutWaitingForNonexistentControls() = runTest {
        val waiter = ScreenReadinessWaiter({ canvas }, MutableStateFlow(0L)) { testScheduler.currentTime }
        assertSame(canvas, waiter.observe())
        assertEquals(120L, testScheduler.currentTime)
    }

    @Test fun eventsWakeAnExplicitWaitAndTheNewWindowMustSettle() = runTest {
        var current = canvas
        val changes = MutableStateFlow(0L)
        val changed = canvas.copy(windowId = 2)
        launch { delay(50); current = changed; changes.value++ }
        val result = ScreenReadinessWaiter({ current }, changes) { testScheduler.currentTime }.observe(canvas)
        assertSame(changed, result)
        assertEquals(170L, testScheduler.currentTime)
    }

    @Test fun pollingFindsChangesWhenAnAppOmitsEvents() = runTest {
        var current = canvas
        val changed = canvas.copy(windowId = 2)
        launch { delay(200); current = changed }
        val result = ScreenReadinessWaiter({ current }, MutableStateFlow(0L)) { testScheduler.currentTime }.observe(canvas)
        assertSame(changed, result)
        assertEquals(360L, testScheduler.currentTime)
    }

    @Test fun explicitWaitDoesNotReturnAnUnchangedPageImmediately() = runTest {
        assertSame(canvas, ScreenReadinessWaiter({ canvas }, MutableStateFlow(0L)) { testScheduler.currentTime }.observe(canvas))
        assertEquals(700L, testScheduler.currentTime)
    }

    @Test fun changeAlreadyPresentDoesNotPayTheLoadingTimeout() = runTest {
        val changed = canvas.copy(windowId = 2)
        assertSame(changed, ScreenReadinessWaiter({ changed }, MutableStateFlow(0L)) { testScheduler.currentTime }.observe(canvas))
        assertEquals(120L, testScheduler.currentTime)
    }

    @Test fun continuousChangesRemainBoundedAndCancellationStopsReading() = runTest {
        var reads = 0
        val changes = MutableStateFlow(0L)
        val producer = launch { repeat(100) { delay(20); changes.value++ } }
        val waiter = ScreenReadinessWaiter({ canvas.copy(windowId = reads++) }, changes) { testScheduler.currentTime }
        assertNotNull(waiter.observe())
        assertEquals(1_200L, testScheduler.currentTime)
        assertTrue("Event storms must not cause unbounded tree reads", reads <= 32)
        producer.cancel()
        val job = launch { waiter.observe() }
        delay(50); job.cancel(); job.join()
        val stoppedAt = reads
        delay(200)
        assertEquals(stoppedAt, reads)
    }

    @Test fun sensitiveScreensReturnImmediatelyAndMissingWindowsAreBounded() = runTest {
        val sensitive = canvas.copy(sensitive = true)
        assertSame(sensitive, ScreenReadinessWaiter({ sensitive }, MutableStateFlow(0L)) { testScheduler.currentTime }.observe())
        assertEquals(0L, testScheduler.currentTime)
        assertNull(ScreenReadinessWaiter({ null }, MutableStateFlow(0L)) { testScheduler.currentTime }.observe())
        assertEquals(1_200L, testScheduler.currentTime)
    }

    @Test fun leavingASensitiveBaselineWaitsForTheAcceptedNavigation() = runTest {
        val sensitive = canvas.copy(sensitive = true)
        var current = sensitive
        val changes = MutableStateFlow(0L)
        launch { delay(50); current = canvas; changes.value++ }
        assertSame(canvas, ScreenReadinessWaiter({ current }, changes) { testScheduler.currentTime }.observe(sensitive))
        assertEquals(170L, testScheduler.currentTime)
    }
}
