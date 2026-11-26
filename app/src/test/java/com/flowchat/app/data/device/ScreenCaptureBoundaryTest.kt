package com.flowchat.app.data.device

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenCaptureBoundaryTest {
    @Test fun aMissingCallbackDoesNotCancelTheCallingTaskOrRestartCapture() = runTest {
        var calls = 0
        val outcome = runCatching { captureWithinBoundary(40) { calls++; delay(1000); "late frame" } }
        assertEquals("screen_capture_timeout", (outcome.exceptionOrNull() as ScreenCaptureFailure).code)
        assertTrue(currentCoroutineContext().isActive); assertEquals(1, calls)
    }
    @Test fun externalCancellationRemainsCancellationAndStopsTheCapture() = runTest {
        val entered = CompletableDeferred<Unit>()
        var finished = false
        val request = async { captureWithinBoundary(3000) { entered.complete(Unit); delay(1000); finished = true; "frame" } }
        entered.await(); request.cancelAndJoin(); runCurrent()
        assertTrue(request.isCancelled); assertFalse(finished)
    }
    @Test fun anOnTimeFrameIsReturnedWithoutReplacingTheEvidence() = runTest {
        val frame = Any()
        assertSame(frame, captureWithinBoundary(40) { delay(10); frame })
    }
}
