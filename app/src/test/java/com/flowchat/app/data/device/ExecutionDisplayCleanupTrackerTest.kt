package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionDisplayCleanupTrackerTest {
    @Test fun closeDoesNotProveRemovalAndOnlyTheOwnedSystemDisplayCanReleaseTheGate() {
        val present = mutableSetOf(0, 7, 8)
        val tracker = ExecutionDisplayCleanupTracker({ it in present }, { 123L })
        tracker.creating("a"); tracker.identify("a", 7); tracker.releasing("a")
        assertFalse(tracker.verify("a", creationFinished = true))
        assertEquals(ExecutionDisplayCleanupReason.DisplayPresent, tracker.state.value["a"]!!.reason)
        assertTrue(runCatching { tracker.requireReusable() }.isFailure)
        present.remove(8)
        assertFalse(tracker.verify("a", creationFinished = true))
        present.remove(7)
        assertTrue(tracker.verify("a", creationFinished = true))
        tracker.requireReusable()
        assertEquals(123L, tracker.state.value["a"]!!.checkedAtMillis)
        assertTrue(0 in present)
    }

    @Test fun unknownIdentityMissingSystemServiceAndQueryFailureCannotBecomeSuccess() {
        val tracker = ExecutionDisplayCleanupTracker({ null })
        tracker.creating("a"); tracker.releasing("a")
        assertFalse(tracker.verify("a", true))
        assertEquals(ExecutionDisplayCleanupReason.IdentityUnknown, tracker.state.value["a"]!!.reason)
        tracker.identify("a", 7)
        assertFalse(tracker.verify("a", true))
        val failed = ExecutionDisplayCleanupTracker({ error("unavailable") })
        failed.creating("b"); failed.identify("b", 8); failed.releasing("b")
        assertFalse(failed.verify("b", true))
        assertEquals(ExecutionDisplayCleanupReason.CheckFailed, failed.state.value["b"]!!.reason)
    }

    @Test fun lateCreationCannotClearItsGateUntilItHasReturnedAndItsDisplayHasGone() {
        var present = false
        val tracker = ExecutionDisplayCleanupTracker({ present })
        tracker.creating("a"); tracker.releasing("a")
        assertFalse(tracker.verify("a", false))
        present = true
        tracker.identify("a", 7)
        assertEquals(ExecutionDisplayState.Unverified, tracker.state.value["a"]!!.state)
        assertFalse(tracker.verify("a", true))
        present = false
        assertTrue(tracker.verify("a", true))
    }

    @Test fun cancelledLazyCreationCanBeReleasedWithoutInventingADisplayId() {
        val tracker = ExecutionDisplayCleanupTracker({ error("No display should be queried") })
        tracker.creating("a"); tracker.releasing("a")
        assertTrue(tracker.verify("a", true, neverStarted = true))
        assertNull(tracker.state.value["a"]!!.displayId)
    }

    @Test fun completedHistoryIsBoundedAndOldWatchdogsCannotInvalidateReleaseOrClearOtherOwners() {
        val tracker = ExecutionDisplayCleanupTracker({ false })
        tracker.creating("pending"); tracker.releasing("pending"); tracker.verify("pending", false)
        repeat(100) { n ->
            val owner = "done-$n"
            tracker.creating(owner); tracker.identify(owner, n + 1); tracker.releasing(owner); tracker.verify(owner, true)
        }
        assertEquals(33, tracker.state.value.size)
        assertEquals(ExecutionDisplayState.Unverified, tracker.state.value["pending"]!!.state)
        tracker.unverified("done-99", ExecutionDisplayCleanupReason.ClosePending)
        assertEquals(ExecutionDisplayState.Released, tracker.state.value["done-99"]!!.state)
        assertTrue(runCatching { tracker.requireReusable() }.isFailure)
    }

    @Test fun physicalDisplayIdentityIsRejected() {
        val tracker = ExecutionDisplayCleanupTracker({ false })
        tracker.creating("a")
        assertTrue(runCatching { tracker.identify("a", 0) }.isFailure)
    }
}
