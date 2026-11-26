package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RootRequestCleanupTrackerTest {
    private fun evidence(id: String = "request") = JSONObject().apply {
        put("requestId", id); put("finishReceived", true); put("activeCalls", 0); put("activeCommands", 0)
        put("processGroups", org.json.JSONArray(listOf(123))); put("remainingGroups", org.json.JSONArray())
        put("identityUnknown", false); put("retainedShellCommands", 0)
    }

    @Test fun finishedDeferredAndCloseRequestAreInsufficientUntilActualRpcReturns() {
        val tracker = RootRequestCleanupTracker { 42 }
        tracker.started("request", "owner"); tracker.releasing("request")
        var reads = 0
        assertFalse(tracker.verify("request", false) { reads++; evidence().toString() })
        assertEquals(0, reads)
        assertEquals(RootRequestCleanupReason.RpcPending, tracker.state.value["request"]?.reason)
        assertThrows(IllegalStateException::class.java) { tracker.requireReusable() }
        assertTrue(tracker.verify("request", true) { evidence().toString() })
        tracker.requireReusable()
        assertEquals(listOf(123), tracker.state.value["request"]?.processGroups)
        assertEquals(42L, tracker.state.value["request"]?.checkedAtMillis)
    }

    @Test fun unknownIdentityActiveCallsAndRemainingGroupsEachKeepTheGateClosed() {
        val cases = listOf(
            evidence().put("finishReceived", false), evidence().put("activeCalls", 1),
            evidence().put("activeCommands", 1), evidence().put("remainingGroups", org.json.JSONArray(listOf(123))),
            evidence().put("identityUnknown", true), evidence().put("retainedShellCommands", 1),
            evidence("someone-elses-request")
        )
        cases.forEach { value ->
            val tracker = RootRequestCleanupTracker()
            tracker.started("request", "owner"); tracker.releasing("request")
            assertFalse(value.toString(), tracker.verify("request", true) { value.toString() })
            assertThrows(IllegalStateException::class.java) { tracker.requireReusable() }
        }
    }

    @Test fun disconnectedOriginalConnectionCannotBecomeSuccessOrAffectAnotherOwner() {
        val tracker = RootRequestCleanupTracker()
        tracker.started("request", "A"); tracker.started("B-request", "B")
        tracker.releasing("B-request")
        assertTrue(tracker.verify("B-request", true) { evidence("B-request").toString() })
        val completedB = tracker.state.value["B-request"]
        tracker.releasing("request")
        assertFalse(tracker.verify("request", true) { error("Original Binder unavailable") })
        assertEquals(RootRequestCleanupReason.CheckFailed, tracker.state.value["request"]?.reason)
        assertEquals(completedB, tracker.state.value["B-request"])
        tracker.unverified("B-request", RootRequestCleanupReason.FinishPending)
        assertEquals(completedB, tracker.state.value["B-request"])
    }

    @Test fun completedHistoryIsBoundedWithoutDiscardingUnresolvedOwners() {
        val tracker = RootRequestCleanupTracker()
        tracker.started("pending", "A"); tracker.releasing("pending")
        repeat(150) { index ->
            val id = "id-$index"
            tracker.started(id, "B"); tracker.releasing(id)
            assertTrue(tracker.verify(id, true) { evidence(id).toString() })
        }
        assertTrue(tracker.state.value.size <= 129)
        assertTrue(tracker.state.value["pending"]!!.blocksReuse)
    }

    @Test fun lostOriginalConnectionCannotBeClearedByWatchdogsOrLateGreenEvidence() {
        val tracker = RootRequestCleanupTracker()
        tracker.started("request", "A"); tracker.releasing("request")
        assertFalse(tracker.verify("request", true) {
            // A read was already in flight when the transport detached.
            tracker.unverified("request", RootRequestCleanupReason.OriginalConnectionUnavailable)
            evidence().toString()
        })
        val lost = tracker.state.value["request"]
        tracker.releasing("request")
        tracker.unverified("request", RootRequestCleanupReason.FinishPending)
        var reads = 0
        assertFalse(tracker.verify("request", true) { reads++; evidence().toString() })
        assertEquals(0, reads)
        assertEquals(lost, tracker.state.value["request"])
        assertEquals(RootRequestCleanupReason.OriginalConnectionUnavailable, lost?.reason)
        assertThrows(IllegalStateException::class.java) { tracker.requireReusable() }
    }
}
