package com.flowchat.app.data.device

import com.flowchat.app.data.terminal.LinuxRuntime
import com.flowchat.app.data.terminal.TerminalManager
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.shizuku.IFlowChatShizukuService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RootCommandStartTest {
    private enum class Failure { BeforeStart, AfterStart, LostReply, None }
    private class Record {
        @Volatile var finished = false
        @Volatile var cancelled = false
        @Volatile var snapshot: CommandSnapshot? = null
    }
    private class Session(val failure: Failure) : RootServiceSession {
        override var alive = true
        val entered = CountDownLatch(1)
        val reply = CountDownLatch(1)
        val records = ConcurrentHashMap<String, Record>()
        var starts = 0
        var readable = true
        var legacy = false
        var wrongRequest = false
        var afterEvidence: () -> Unit = {}
        override fun info() = RootServiceInfo(0, 42, RootServiceInfo.Protocol, 0, 0)
        override fun openRequest(id: String): IFlowChatShizukuService {
            val record = records.getOrPut(id) { Record() }
            check(!record.finished)
            return object : FlowChatShizukuService(DeviceCommandExecutor { DeviceCommandResult(true, "") }) {
                override fun probeCapabilities() = DeviceCapability.ShellCommands.name
                override fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): String {
                    val affected = command.contains("DELAY")
                    if (affected && failure == Failure.BeforeStart) {
                        entered.countDown(); check(reply.await(5, TimeUnit.SECONDS))
                    }
                    synchronized(record) {
                        check(!record.finished) { "Request already finished" }
                        starts++
                        val id = "root-" + UUID.randomUUID()
                        record.snapshot = CommandSnapshot(id, command, CommandEnvironment.Android, workingDirectory,
                            cleanup = CommandCleanup(id, processGroup = 42 + starts, processStarted = true))
                    }
                    if (affected && failure == Failure.AfterStart) {
                        entered.countDown(); check(reply.await(5, TimeUnit.SECONDS))
                    }
                    if (affected && failure == Failure.LostReply) error("Injected lost reply after command creation")
                    return Json.encodeToString(CommandSnapshot.serializer(), requireNotNull(record.snapshot))
                }
                override fun readShellCommand(commandId: String) = Json.encodeToString(CommandSnapshot.serializer(), find(commandId))
                override fun cancelShellCommand(commandId: String): String {
                    val target = records.values.single { it.snapshot?.id == commandId }
                    release(target)
                    return Json.encodeToString(CommandSnapshot.serializer(), requireNotNull(target.snapshot))
                }
            }
        }
        fun find(id: String) = requireNotNull(records.values.single { it.snapshot?.id == id }.snapshot)
        private fun release(record: Record) { synchronized(record) {
            record.snapshot = record.snapshot?.let { it.copy(status = CommandStatus.Cancelled, finishedAt = 1,
                cleanup = it.cleanup!!.copy(state = CommandCleanupState.Released, processExited = true, checkedAtMillis = 1)) }
        } }
        override fun finishRequest(id: String, cancelled: Boolean) {
            val record = records.getOrPut(id) { Record() }
            synchronized(record) { record.finished = true; record.cancelled = cancelled }
            if (cancelled) release(record)
        }
        override fun requestCleanup(id: String): String {
            check(readable)
            val record = requireNotNull(records[id])
            return JSONObject().apply {
                put("requestId", if (wrongRequest) UUID.randomUUID().toString() else id)
                put("finishReceived", record.finished); put("activeCalls", 0)
                put("activeCommands", 0); put("identityUnknown", false)
                put("processGroups", JSONArray()); put("remainingGroups", JSONArray())
                put("retainedShellCommands", if (record.snapshot == null) 0 else 1)
                if (!legacy) {
                    put("shellCommandEvidenceVersion", 1)
                    put("shellCommandCancellationRequested", record.cancelled)
                    put("shellCommands", JSONArray(listOfNotNull(record.snapshot?.let {
                        JSONObject(Json.encodeToString(CommandSnapshot.serializer(), it))
                    })))
                }
            }.toString().also { afterEvidence() }
        }
        override suspend fun close() { alive = false }
    }
    private class Observations {
        val updates = CopyOnWriteArrayList<CommandStartUpdate>()
        @Volatile var check: (suspend () -> Unit)? = null
        val context = CommandStartObserver({ _, recheck -> check = recheck }, updates::add)
    }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }
    private suspend fun fixture(failure: Failure, block: suspend (Session, RootDeviceConnection, CoroutineScope, () -> Int) -> Unit) {
        val session = Session(failure)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var connects = 0
        val root = RootDeviceConnection({ connects++; session }, scope)
        try { root.refresh(requestPermission = true); block(session, root, scope) { connects } }
        finally { session.reply.countDown(); root.release(); scope.cancel() }
    }
    private suspend fun start(root: RootDeviceConnection, observations: Observations) = withContext(observations.context) {
        root.withShellCommand { it.startShellCommand("DELAY sleep 30", "/", 60) }
    }

    @Test fun cancelledBeforeCreationCannotReviveAndCanBeVerifiedAsNotStarted() = runBlocking {
        fixture(Failure.BeforeStart) { session, root, scope, connects ->
            val observations = Observations()
            val task = scope.async { start(root, observations) }
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            task.cancelAndJoin()
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            assertFalse(observations.updates.any { it.notStarted })
            session.reply.countDown()
            until { observations.updates.any { it.notStarted } }
            root.requireRequestCleanupVerified()
            repeat(3) { observations.check!!.invoke() }
            assertEquals(0, session.starts); assertEquals(1, connects())
        }
    }

    @Test fun cancelledWaiterStillReceivesLateCommandIdentityAndVerifiedCleanup() = runBlocking {
        fixture(Failure.AfterStart) { session, root, scope, connects ->
            val observations = Observations()
            val task = scope.async { start(root, observations) }
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            task.cancelAndJoin()
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            session.reply.countDown()
            until { observations.updates.any { it.snapshot?.cleanup?.state == CommandCleanupState.Released } }
            val resolved = observations.updates.last { it.snapshot != null }
            assertEquals(resolved.requestId, resolved.snapshot!!.startRequestId)
            until { runCatching { root.requireRequestCleanupVerified() }.isSuccess }
            assertEquals(1, session.starts); assertEquals(1, connects())
        }
    }

    @Test fun lostReplyRecoversExactCommandWithoutStartingItAgain() = runBlocking {
        fixture(Failure.LostReply) { session, root, _, connects ->
            val observations = Observations()
            assertTrue(runCatching { start(root, observations) }.isFailure)
            until { observations.updates.any { it.snapshot?.cleanup?.state == CommandCleanupState.Released } }
            repeat(3) { observations.check!!.invoke() }
            root.requireRequestCleanupVerified()
            assertEquals(1, session.starts); assertEquals(1, connects())
        }
    }

    @Test fun lostOriginalConnectionStaysUnknownAndCannotBeReplaced() = runBlocking {
        fixture(Failure.AfterStart) { session, root, scope, connects ->
            val observations = Observations()
            val task = scope.async { start(root, observations) }
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            task.cancelAndJoin()
            root.release()
            session.reply.countDown()
            until { observations.updates.any { it.reason == CommandCleanupReason.OriginalConnectionUnavailable } }
            repeat(3) { observations.check!!.invoke() }
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            assertEquals(1, connects()); assertEquals(1, session.starts)
        }
    }

    @Test fun aNotStartedReplyCannotClearConnectionLossDuringTheEvidenceRead() = runBlocking {
        fixture(Failure.BeforeStart) { session, root, scope, connects ->
            val observations = Observations()
            val task = scope.async { start(root, observations) }
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            task.cancelAndJoin()
            session.afterEvidence = { runBlocking { root.release() } }
            session.reply.countDown()
            until { observations.updates.any { it.reason == CommandCleanupReason.OriginalConnectionUnavailable } }
            repeat(3) { observations.check!!.invoke() }
            assertFalse(observations.updates.any { it.notStarted || it.snapshot != null })
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            assertEquals(0, session.starts); assertEquals(1, connects())
        }
    }

    @Test fun legacyCleanupCannotProveWhatHappenedToALostStartReply() = runBlocking {
        fixture(Failure.LostReply) { session, root, _, _ ->
            session.legacy = true
            val observations = Observations()
            assertTrue(runCatching { start(root, observations) }.isFailure)
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            assertFalse(observations.updates.any { it.notStarted || it.snapshot != null })
            session.legacy = false
            observations.check!!.invoke()
            root.requireRequestCleanupVerified()
            assertEquals(1, session.starts)
        }
    }

    @Test fun anotherRequestCannotSupplyTheMissingStartEvidence() = runBlocking {
        fixture(Failure.LostReply) { session, root, _, _ ->
            session.wrongRequest = true
            val observations = Observations()
            assertTrue(runCatching { start(root, observations) }.isFailure)
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            assertFalse(observations.updates.any { it.notStarted || it.snapshot != null })
            session.wrongRequest = false
            observations.check!!.invoke()
            root.requireRequestCleanupVerified()
        }
    }

    @Test fun terminalRunCancellationOwnsLateCommandAndDoesNotCancelManualCommand() = runBlocking {
        fixture(Failure.AfterStart) { session, root, scope, _ ->
            val context = RuntimeEnvironment.getApplication()
            context.applicationInfo.nativeLibraryDir = context.filesDir.absolutePath
            val gateway = object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
                override suspend fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int) =
                    root.withShellCommand { it.startShellCommand(command, workingDirectory, timeoutSeconds) }
                override suspend fun readShellCommand(commandId: String) = root.withShellCommand(commandId) { it.readShellCommand(commandId) }
                override suspend fun cancelShellCommand(commandId: String) = root.withShellCommand(commandId) { it.cancelShellCommand(commandId) }
            }
            val terminal = TerminalManager(context, LinuxRuntime(context), gateway)
            val manual = terminal.start("owner-b", CommandEnvironment.Android, "sleep 30", "/", 60)
            val task = scope.async { terminal.start("owner-a", CommandEnvironment.Android, "DELAY sleep 30", "/", 60, "run-a") }
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            until { terminal.state.value.commands.any { it.id.startsWith("start-") } }
            val intent = terminal.state.value.commands.single { it.id.startsWith("start-") }
            assertTrue(terminal.owns("owner-a", intent.id))
            assertTrue(runCatching { terminal.recheckCleanup("owner-b", intent.id) }.isFailure)
            terminal.cancelRun("run-a")
            until { task.isCancelled }
            session.reply.countDown()
            until { terminal.state.value.commands.any { it.startIntentId == intent.id && it.id.startsWith("root-") && it.cleanup?.state == CommandCleanupState.Released } }
            val resolved = terminal.state.value.commands.single { it.startIntentId == intent.id }
            assertTrue(terminal.owns("owner-a", resolved.id)); assertFalse(terminal.owns("owner-b", resolved.id))
            assertNotNull(resolved.startRequestId)
            assertTrue(terminal.read("owner-b", manual.id).isRunning)
            terminal.cancel("owner-b", manual.id)
            assertEquals(2, session.starts)
        }
    }

    @Test fun cancellationAfterRpcReturnButBeforeIdDeliveryStillCancelsTheExactCommand() = runBlocking {
        fixture(Failure.None) { session, root, scope, _ ->
            val context = RuntimeEnvironment.getApplication()
            context.applicationInfo.nativeLibraryDir = context.filesDir.absolutePath
            val returned = CompletableDeferred<CommandSnapshot>()
            val gateway = object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
                override suspend fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): CommandSnapshot {
                    val snapshot = root.withShellCommand { it.startShellCommand(command, workingDirectory, timeoutSeconds) }
                    returned.complete(snapshot)
                    awaitCancellation()
                }
                override suspend fun readShellCommand(commandId: String) = root.withShellCommand(commandId) { it.readShellCommand(commandId) }
                override suspend fun cancelShellCommand(commandId: String) = root.withShellCommand(commandId) { it.cancelShellCommand(commandId) }
            }
            val terminal = TerminalManager(context, LinuxRuntime(context), gateway)
            val task = scope.async { terminal.start("owner-a", CommandEnvironment.Android, "sleep 30", "/", 60, "run-a") }
            val snapshot = withTimeout(5_000) { returned.await() }
            assertTrue(session.find(snapshot.id).isRunning)
            task.cancelAndJoin()
            until { !session.find(snapshot.id).isRunning }
            assertEquals(CommandStatus.Cancelled, session.find(snapshot.id).status)
            assertTrue(terminal.owns("owner-a", snapshot.id))
            assertEquals(1, session.starts)
        }
    }

    @Test fun aNewStartChecksUnresolvedCleanupAgainAtActualDispatch() = runBlocking {
        fixture(Failure.AfterStart) { session, root, scope, _ ->
            val first = scope.async { start(root, Observations()) }
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            val secondObserver = CommandStartObserver(prepared = { _, _ ->
                runBlocking { first.cancelAndJoin() }
            }, update = {})
            val second = runCatching { withContext(secondObserver) {
                root.withShellCommand { it.startShellCommand("second command", "/", 60) }
            } }
            assertTrue("A start admitted earlier must be rejected if prior cleanup becomes unknown before dispatch", second.isFailure)
            assertEquals(1, session.starts)
            session.reply.countDown()
        }
    }
}
