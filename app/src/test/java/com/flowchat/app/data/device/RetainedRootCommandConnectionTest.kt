package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.shizuku.IFlowChatShizukuService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetainedRootCommandConnectionTest {
    private class Session : RootServiceSession {
        override var alive = true
        var starts = 0
        var reads = 0
        var cancels = 0
        var opens = 0
        var snapshot = CommandSnapshot("root-00000000-0000-0000-0000-000000000001", "sleep 30", CommandEnvironment.Android, "/",
            cleanup = CommandCleanup("root-00000000-0000-0000-0000-000000000001", processGroup = 42, processStarted = true))
        val service = object : FlowChatShizukuService(DeviceCommandExecutor { DeviceCommandResult(true, "") }) {
            override fun probeCapabilities() = DeviceCapability.ShellCommands.name
            override fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): String {
                starts++; return Json.encodeToString(CommandSnapshot.serializer(), snapshot)
            }
            override fun readShellCommand(commandId: String): String { reads++; return Json.encodeToString(CommandSnapshot.serializer(), snapshot) }
            override fun cancelShellCommand(commandId: String): String { cancels++; return Json.encodeToString(CommandSnapshot.serializer(), snapshot) }
        }
        override fun info() = RootServiceInfo(0, 42, RootServiceInfo.Protocol, 0,
            if (snapshot.isRunning || snapshot.cleanup?.blocksReuse == true) 1 else 0)
        override fun openRequest(id: String): IFlowChatShizukuService { opens++; return service }
        override fun finishRequest(id: String, cancelled: Boolean) = Unit
        override suspend fun close() { alive = false }
        fun released() { snapshot = snapshot.copy(status = CommandStatus.Exited, exitCode = 0,
            cleanup = snapshot.cleanup!!.copy(state = CommandCleanupState.Released, processExited = true, reason = null, checkedAtMillis = 123)) }
    }
    private suspend fun start(root: RootDeviceConnection) = root.withShellCommand { it.startShellCommand("sleep 30", "/", 60) }
    private suspend fun read(root: RootDeviceConnection, id: String) = root.withShellCommand(id) { it.readShellCommand(id) }

    @Test fun releasedHistoryCanBeReadAfterIdleWithoutConnectingOrReplaying() = runTest {
        val session = Session()
        var connections = 0
        val root = RootDeviceConnection({ connections++; session }, backgroundScope)
        root.refresh(requestPermission = true)
        val initial = start(root)
        session.released()
        val released = read(root, initial.id)
        advanceTimeBy(30_001); runCurrent()
        assertFalse(session.alive)
        repeat(3) { assertEquals(released, read(root, initial.id)) }
        assertEquals(1, connections); assertEquals(1, session.starts); assertEquals(1, session.reads)
        root.release()
    }

    @Test fun aLostOriginalConnectionCannotBeReplacedToReadOrCancelItsCommand() = runTest {
        val session = Session()
        var connections = 0
        val root = RootDeviceConnection({ connections++; session }, backgroundScope)
        root.refresh(requestPermission = true)
        val initial = start(root)
        root.release()
        assertTrue(runCatching { read(root, initial.id) }.isFailure)
        assertTrue(runCatching { root.withShellCommand(initial.id) { it.cancelShellCommand(initial.id) } }.isFailure)
        assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
        assertEquals(1, connections); assertEquals(0, session.reads); assertEquals(0, session.cancels)
    }

    @Test fun unverifiedCleanupGatesNewWorkButAllowsReadOnlyRecheckAndCancellation() = runTest {
        val session = Session()
        val root = RootDeviceConnection({ session }, backgroundScope)
        root.refresh(requestPermission = true)
        val initial = start(root)
        session.snapshot = session.snapshot.copy(status = CommandStatus.Cancelled,
            cleanup = initial.cleanup!!.copy(state = CommandCleanupState.Unverified, reason = CommandCleanupReason.GroupPresent))
        read(root, initial.id)
        assertTrue(runCatching { start(root) }.isFailure)
        assertTrue(runCatching { root.withService { it.getDeviceStatus() } }.isFailure)
        root.withShellCommand(initial.id) { it.cancelShellCommand(initial.id) }
        session.released()
        assertEquals(CommandCleanupState.Released, read(root, initial.id).cleanup?.state)
        root.requireRequestCleanupVerified()
        assertEquals(1, session.starts); assertEquals(1, session.cancels)
        root.release()
    }

    @Test fun anotherCommandOrAnotherEvidenceIdentityIsRejected() = runTest {
        val session = Session()
        val root = RootDeviceConnection({ session }, backgroundScope)
        root.refresh(requestPermission = true)
        val initial = start(root)
        session.snapshot = session.snapshot.copy(cleanup = CommandCleanup("another-command", CommandCleanupState.Released))
        assertTrue(runCatching { read(root, initial.id) }.isFailure)
        assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
        session.snapshot = session.snapshot.copy(id = "root-another-command")
        assertTrue(runCatching { read(root, initial.id) }.isFailure)
        assertEquals(1, session.starts)
        root.release()
    }

    @Test fun missingLegacyEvidenceFailsClosedAndRecheckDoesNotStartAnotherCommand() = runTest {
        val session = Session().apply { snapshot = snapshot.copy(cleanup = null) }
        val root = RootDeviceConnection({ session }, backgroundScope)
        root.refresh(requestPermission = true)
        val initial = start(root)
        assertEquals(CommandCleanupReason.EvidenceUnavailable, initial.cleanup?.reason)
        assertTrue(runCatching { start(root) }.isFailure)
        read(root, initial.id)
        assertEquals(1, session.starts); assertEquals(1, session.reads)
        root.release()
    }

    @Test fun aLateRunningResponseCannotOverwriteAlreadyVerifiedCleanup() = runBlocking {
        val session = Session()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection({ session }, scope)
        root.refresh(requestPermission = true)
        val initial = start(root)
        val staleJson = Json.encodeToString(CommandSnapshot.serializer(), initial)
        val entered = CompletableDeferred<Unit>()
        val resume = java.util.concurrent.CountDownLatch(1)
        val oldRead = async(Dispatchers.IO) {
            root.withShellCommand(initial.id) {
                entered.complete(Unit)
                check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
                staleJson
            }
        }
        try {
            entered.await()
            session.released()
            val released = read(root, initial.id)
            resume.countDown()
            assertEquals(released, oldRead.await())
            assertEquals(released, read(root, initial.id))
            root.requireRequestCleanupVerified()
        } finally { resume.countDown(); root.release(); scope.cancel() }
    }

    @Test fun aGreenLabelWithNoProcessExitProofIsRejected() = runTest {
        val session = Session()
        val root = RootDeviceConnection({ session }, backgroundScope)
        root.refresh(requestPermission = true)
        val initial = start(root)
        session.snapshot = session.snapshot.copy(status = CommandStatus.Exited,
            cleanup = initial.cleanup!!.copy(state = CommandCleanupState.Released, checkedAtMillis = 1))
        assertTrue(runCatching { read(root, initial.id) }.isFailure)
        assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
        root.release()
    }
}
