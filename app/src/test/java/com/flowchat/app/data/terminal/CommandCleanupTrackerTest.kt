package com.flowchat.app.data.terminal

import com.flowchat.app.domain.terminal.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CommandCleanupTrackerTest {
    @Test fun commandExitAloneCannotProveItsOwnedGroupIsGone() {
        val tracker = CommandCleanupTracker("a", { true }, { 123 })
        tracker.starting(); tracker.identify(42); tracker.processExited()
        val evidence = tracker.snapshot(true)
        assertEquals(CommandCleanupState.Unverified, evidence.state)
        assertEquals(CommandCleanupReason.GroupPresent, evidence.reason)
        assertTrue(evidence.processExited)
        assertTrue(evidence.processStarted)
        assertTrue(evidence.blocksReuse)
    }

    @Test fun groupAbsenceCannotProveThatTheLauncherExited() {
        val tracker = CommandCleanupTracker("a", { false })
        tracker.starting(); tracker.identify(42)
        assertEquals(CommandCleanupReason.ProcessActive, tracker.snapshot(true).reason)
        tracker.processExited()
        assertEquals(CommandCleanupState.Released, tracker.snapshot(true).state)
    }

    @Test fun missingOrInvalidStartupIdentityRemainsUnknownEvenAfterExit() {
        val tracker = CommandCleanupTracker("a", { false })
        tracker.starting(); tracker.identify(0); tracker.identify(1); tracker.processExited()
        assertEquals(CommandCleanupReason.IdentityUnknown, tracker.snapshot(true).reason)
        assertNull(tracker.snapshot(true).processGroup)
    }

    @Test fun permissionOrProbeErrorsAreNotAbsence() {
        listOf<(Int) -> Boolean?>({ null }, { throw SecurityException() }).forEach { probe ->
            val tracker = CommandCleanupTracker("a", probe)
            tracker.starting(); tracker.identify(42); tracker.processExited()
            assertEquals(CommandCleanupReason.CheckFailed, tracker.snapshot(true).reason)
        }
    }

    @Test fun cancellationBeforeProcessStartNeedsNoInventedGroup() {
        var probes = 0
        val tracker = CommandCleanupTracker("a", { probes++; true })
        assertEquals(CommandCleanupState.InUse, tracker.snapshot(false).state)
        val evidence = tracker.snapshot(true)
        assertEquals(CommandCleanupState.Released, evidence.state)
        assertFalse(evidence.processExited)
        assertFalse(evidence.processStarted)
        assertNull(evidence.processGroup)
        assertEquals(0, probes)
    }

    @Test fun verifiedAbsenceIsLatchedAndCannotTargetAReusedIdentity() {
        var present = true
        var probes = 0
        val tracker = CommandCleanupTracker("a", { probes++; present }, { probes.toLong() })
        tracker.starting(); tracker.identify(42); tracker.processExited()
        assertEquals(CommandCleanupState.Unverified, tracker.snapshot(true).state)
        present = false
        val released = tracker.snapshot(true)
        present = true
        tracker.identify(99)
        assertEquals(released, tracker.snapshot(true))
        assertEquals(42, released.processGroup)
        assertEquals(2, probes)
    }

    @Test fun oldSnapshotsDoNotInheritGreenEvidenceAndNewEvidenceRoundTrips() {
        val old = """{"id":"old","command":"true","environment":"Android","workingDirectory":"/","status":"Exited","exitCode":0,"createdAt":1000}"""
        val decoded = Json.decodeFromString<CommandSnapshot>(old)
        assertNull(decoded.cleanup)
        val pending = decoded.cleanupUnavailable(CommandCleanupReason.EvidenceUnavailable)
        assertEquals(CommandCleanupState.Unverified, pending.cleanup?.state)
        assertEquals(pending, Json.decodeFromString<CommandSnapshot>(Json.encodeToString(CommandSnapshot.serializer(), pending)))
    }

    @Test fun connectionLossPreservesReleasedEvidenceAndCannotMakeUnknownGreen() {
        val original = CommandSnapshot("a", "true", CommandEnvironment.Android, "/",
            cleanup = CommandCleanup("a", CommandCleanupState.Released, 42, true, checkedAtMillis = 1, processStarted = true))
        assertSame(original, original.cleanupUnavailable(CommandCleanupReason.OriginalConnectionUnavailable))
        val unknown = original.copy(cleanup = original.cleanup!!.copy(state = CommandCleanupState.InUse))
            .cleanupUnavailable(CommandCleanupReason.OriginalConnectionUnavailable)
        assertEquals(CommandCleanupState.Unverified, unknown.cleanup?.state)
        assertEquals(42, unknown.cleanup?.processGroup)
    }
}
