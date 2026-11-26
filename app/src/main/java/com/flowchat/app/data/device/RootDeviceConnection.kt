package com.flowchat.app.data.device

import android.content.Context
import android.os.RemoteException
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.device.DeviceSafetyBoundary
import com.flowchat.app.domain.device.DeviceSafetyReason
import com.flowchat.app.domain.device.ExecutionDisplayCleanupReason
import com.flowchat.app.domain.device.RootRequestCleanupReason
import com.flowchat.app.domain.terminal.*
import kotlinx.serialization.json.Json
import android.hardware.display.DisplayManager
import org.json.JSONObject
import com.flowchat.app.shizuku.IFlowChatShizukuService
import com.flowchat.app.shizuku.IFlowChatExecutionDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class RootDeviceConnection internal constructor(
    private val connector: RootServiceConnector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idleTimeoutMillis: Long = 30_000,
    private val verificationLifetimeMillis: Long = 30_000,
    private val safetyBoundary: DeviceSafetyBoundary = DeviceSafetyBoundary(),
    displayPresent: (Int) -> Boolean? = { null }
) {
    private class CleanupPendingException(message: String) : IllegalStateException(message)

    @Inject constructor(@ApplicationContext context: Context, safetyBoundary: DeviceSafetyBoundary = DeviceSafetyBoundary()) :
        this(LibsuRootServiceConnector(context), safetyBoundary = safetyBoundary,
            displayPresent = { id -> context.getSystemService(DisplayManager::class.java)?.let { it.getDisplay(id) != null } })

    private val displayCleanup = ExecutionDisplayCleanupTracker(displayPresent)
    private val requestCleanup = RootRequestCleanupTracker()
    val rootRequestCleanup get() = requestCleanup.state
    internal fun requireRequestCleanupVerified() {
        // Death callbacks may be queued. Do not let another transport use that delivery gap.
        connection?.takeIf { !it.session.alive }?.let(::markConnectionUnavailable)
        try {
            displayCleanup.requireReusable()
            requestCleanup.requireReusable()
            check(commandStarts.values.none { it.abandoned && !it.settled }) {
                "A Root command start is unresolved. Check its original connection before starting new phone operations."
            }
            check(retainedCommands.values.none { it.snapshot.cleanup?.blocksReuse == true }) {
                "Previous Root command cleanup is unverified. Check its original connection before starting new phone operations."
            }
        } catch (pending: IllegalStateException) {
            throw CleanupPendingException(requireNotNull(pending.message))
        }
    }
    private val requestCleanupChecks = java.util.concurrent.ConcurrentHashMap<String, () -> Boolean>()

    /** Observe the original connection only; never reconnect, finish again or replay a command. */
    suspend fun recheckRootRequestCleanup() = withContext(Dispatchers.IO) {
        requestCleanupChecks.entries.forEach { (id, check) -> if (check()) requestCleanupChecks.remove(id, check) }
    }
    val executionDisplayCleanup get() = displayCleanup.state
    private val displayCleanupChecks = java.util.concurrent.ConcurrentHashMap<String, () -> Boolean>()

    /** Observation only: never retries a close, creates a connection or replays an operation. */
    fun recheckExecutionDisplayCleanup() {
        displayCleanupChecks.entries.forEach { (owner, check) -> if (check()) displayCleanupChecks.remove(owner, check) }
    }

    private val mutableState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.RootPermissionRequired))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val operations = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val requestSlots = Semaphore(4)
    private var permissionRequested = false
    private var verifiedAt: Long? = null
    private var generation = 0L
    @Volatile private var connection: Connection? = null
    private var idleJob: Job? = null
    @Volatile private var connectingJob: Job? = null

    private class RetainedCommand(val connection: Connection, @Volatile var snapshot: CommandSnapshot) {
        @Synchronized fun accept(next: CommandSnapshot): CommandSnapshot {
            if (snapshot.cleanup?.state == CommandCleanupState.Released || (!snapshot.isRunning && next.isRunning)) return snapshot
            snapshot = next
            return snapshot
        }
        @Synchronized fun unavailable(reason: CommandCleanupReason) { snapshot = snapshot.cleanupUnavailable(reason) }
    }
    private val retainedCommands = java.util.concurrent.ConcurrentHashMap<String, RetainedCommand>()
    private val commandJson = Json { ignoreUnknownKeys = true }
    private class ShellStart(val connection: Connection, val observer: CommandStartObserver?) {
        val id = UUID.randomUUID().toString()
        @Volatile var rpcFinished = false
        @Volatile var finishReturned = false
        @Volatile var abandoned = false
        @Volatile var settled = false
        @Volatile var commandId: String? = null
        val dispatchState = java.util.concurrent.atomic.AtomicInteger(0)
    }
    private val commandStarts = java.util.concurrent.ConcurrentHashMap<String, ShellStart>()

    private fun acceptCommand(connected: Connection, received: CommandSnapshot, commandId: String? = null): CommandSnapshot = synchronized(connected) {
        check(!connected.detached && connected.session.alive) { "Original command connection is unavailable; a late response cannot verify cleanup." }
        check(received.id.startsWith("root-") && (commandId == null || received.id == commandId)) { "Command identity changed." }
        check(received.cleanup == null || received.cleanup.commandId == received.id) { "Cleanup evidence belongs to another command." }
        received.cleanup?.takeIf { it.state == CommandCleanupState.Released }?.let {
            check(!received.isRunning && it.reason == null && it.checkedAtMillis != null &&
                (if (it.processStarted) it.processExited && (it.processGroup ?: 0) > 1
                else !it.processExited && it.processGroup == null)) { "Incomplete command cleanup evidence." }
        }
        val next = if (received.cleanup == null) received.cleanupUnavailable(CommandCleanupReason.EvidenceUnavailable) else received
        val retained = retainedCommands.putIfAbsent(next.id, RetainedCommand(connected, next))
        check(retained == null || retained.connection === connected) { "Command belongs to another Root connection." }
        val snapshot = retained?.accept(next) ?: next
        if (snapshot.cleanup?.state == CommandCleanupState.Released) {
            commandStarts.values.filter { it.commandId == snapshot.id && it.rpcFinished && it.finishReturned }.forEach {
                it.settled = true; commandStarts.remove(it.id, it)
            }
        }
        retainedCommands.values.filter { it.snapshot.cleanup?.state == CommandCleanupState.Released }
            .sortedByDescending { it.snapshot.createdAt }.drop(128)
            .forEach { retainedCommands.remove(it.snapshot.id, it) }
        return snapshot
    }

    /** Observe one finished start request on its original connection, never replay or cancel again. */
    private suspend fun recheckCommandStart(start: ShellStart) = withContext(Dispatchers.IO) {
        if (start.settled) return@withContext
        var reason = CommandCleanupReason.StartResponsePending
        try {
            check(!start.connection.detached && start.connection.session.alive) {
                reason = CommandCleanupReason.OriginalConnectionUnavailable
                "Original connection unavailable"
            }
            if (!start.rpcFinished || !start.finishReturned) {
                start.observer?.update?.invoke(CommandStartUpdate(start.id, reason = reason))
                return@withContext
            }
            reason = CommandCleanupReason.EvidenceUnavailable
            val evidence = JSONObject(start.connection.session.requestCleanup(start.id))
            check(evidence.getString("requestId") == start.id && evidence.getInt("shellCommandEvidenceVersion") == 1)
            check(evidence.getBoolean("finishReceived") && evidence.getInt("activeCalls") == 0 &&
                !evidence.getBoolean("identityUnknown") && evidence.getInt("activeCommands") == 0 &&
                evidence.getJSONArray("remainingGroups").length() == 0)
            check(evidence.getBoolean("shellCommandCancellationRequested"))
            val commands = evidence.getJSONArray("shellCommands")
            check(commands.length() == evidence.getInt("retainedShellCommands") && commands.length() in 0..1)
            synchronized(start.connection) {
                check(!start.connection.detached && start.connection.session.alive) {
                    reason = CommandCleanupReason.OriginalConnectionUnavailable
                    "Original connection was lost during the evidence read"
                }
                if (commands.length() == 0) {
                    check(start.commandId == null)
                    start.observer?.update?.invoke(CommandStartUpdate(start.id, notStarted = true))
                    start.settled = true
                } else {
                    val received = commandJson.decodeFromString<CommandSnapshot>(commands.getJSONObject(0).toString())
                        .copy(startRequestId = start.id)
                    val snapshot = acceptCommand(start.connection, received, start.commandId)
                    start.commandId = snapshot.id
                    start.observer?.update?.invoke(CommandStartUpdate(start.id, snapshot = snapshot))
                    start.settled = snapshot.cleanup?.state == CommandCleanupState.Released
                }
                if (start.settled) commandStarts.remove(start.id, start)
            }
        } catch (_: Exception) {
            start.observer?.update?.invoke(CommandStartUpdate(start.id, reason = reason))
        }
    }

    /** Retained commands outlive a short RPC; reads/cancellation must never reconnect elsewhere. */
    internal suspend fun withShellCommand(commandId: String? = null,
        operation: (IFlowChatShizukuService) -> String): CommandSnapshot {
        val retained = commandId?.let { requireNotNull(retainedCommands[it]) { "Original command connection is unavailable." } }
        if (retained?.snapshot?.cleanup?.state == CommandCleanupState.Released) return retained.snapshot
        if (commandId == null) requireRequestCleanupVerified()
        val connected = if (retained == null) acquireConnection() else mutex.withLock {
            check(!retained.connection.detached && retained.connection.session.alive) {
                retained.unavailable(CommandCleanupReason.OriginalConnectionUnavailable)
                "Original command connection is unavailable; cleanup remains unverified."
            }
            idleJob?.cancel()
            retained.connection.also { it.leases++ }
        }
        val start = if (commandId == null) ShellStart(connected, currentCoroutineContext()[CommandStartObserver]) else null
        try {
            if (commandId == null) requireRequestCleanupVerified()
            return request(connected, checkCleanup = commandId == null, shellStart = start) { service ->
                val received = commandJson.decodeFromString<CommandSnapshot>(operation(service))
                val snapshot = acceptCommand(connected, received.copy(startRequestId = start?.id ?: retained?.snapshot?.startRequestId), commandId)
                if (start != null) {
                    start.commandId = snapshot.id
                    start.observer?.update?.invoke(CommandStartUpdate(start.id, snapshot = snapshot))
                }
                snapshot
            }
        } catch (error: Exception) {
            retained?.unavailable(if (!connected.session.alive || connected.detached)
                CommandCleanupReason.OriginalConnectionUnavailable else CommandCleanupReason.CheckFailed)
            if (error is RemoteException || !connected.session.alive) withContext(NonCancellable) { disconnected(connected.generation) }
            throw error
        } finally { releaseLease(connected) }
    }

    private class Connection(val session: RootServiceSession, val generation: Long, var leases: Int = 0) {
        @Volatile var detached = false
        val displays = linkedMapOf<String, DisplayAttempt>()
        val ownedRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }

    private class DisplayAttempt(val ownerId: String) {
        lateinit var rpc: Deferred<IFlowChatExecutionDisplay>
        val finished = CompletableDeferred<Unit>()
        @Volatile var started = false
    }

    internal interface ExecutionDisplayCommands {
        suspend fun <T> execute(operation: (IFlowChatExecutionDisplay) -> T): T
    }

    internal class ExecutionDisplayLease(
        val remote: IFlowChatExecutionDisplay,
        private val isAlive: () -> Boolean,
        private val commands: ExecutionDisplayCommands,
        private val release: suspend () -> Unit
    ) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val alive: Boolean get() = !closed.get() && isAlive()
        suspend fun <T> command(operation: (IFlowChatExecutionDisplay) -> T): T {
            check(alive) { "Execution display disconnected. Start a new task." }
            return commands.execute { scoped ->
                check(alive) { "Execution display closed before dispatch." }
                operation(scoped)
            }
        }
        suspend fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    suspend fun refresh(requestPermission: Boolean = false, force: Boolean = false) = mutex.withLock {
        if (requestPermission) permissionRequested = true
        if (!permissionRequested) return@withLock
        // Authorized and ready does not mean keeping a Root process alive while idle.
        if (!force && !requestPermission && state.value.status == ShizukuConnectionStatus.RootReady) return@withLock
        val elapsed = verifiedAt?.let { clockMillis() - it }
        if (!force && !requestPermission && connection?.session?.alive == true &&
            state.value.status == ShizukuConnectionStatus.ConnectedDirectRoot &&
            elapsed != null && elapsed in 0 until verificationLifetimeMillis) return@withLock
        try {
            requireRequestCleanupVerified()
            verifyLocked()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is CleanupPendingException) {
                // A cleanup gate is not a transport failure. Keep a live original for read-only checks.
                if (connection?.let { !it.detached && it.session.alive } != true)
                    mutableState.value = state.value.copy(status = ShizukuConnectionStatus.Error, detail = error.message)
                return@withLock
            }
            failLocked(error)
        }
    }

    private suspend fun connectLocked(): Connection {
        connection?.takeIf { it.session.alive }?.let { requireRequestCleanupVerified(); return it }
        // The death callback may still be queued behind this mutex. Latch the loss before
        // replacing its generation, otherwise the late callback cannot invalidate old plans.
        if (connection != null) safetyBoundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
        val previous = state.value
        detachLocked()
        // Changing transport or probing again is not proof that old resources have gone away.
        requireRequestCleanupVerified()
        val nextGeneration = ++generation
        mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.Connecting)
        var opened: RootServiceSession? = null
        val session = try {
            coroutineScope {
                connectingJob = currentCoroutineContext()[Job]
                try {
                    connector.connect { scope.launch { disconnected(nextGeneration) } }.also { opened = it }
                } finally { connectingJob = null }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { opened?.close() }
            mutableState.value = if (previous.status == ShizukuConnectionStatus.RootReady) previous
                else ShizukuConnectionState(ShizukuConnectionStatus.Error, detail = "Root connection was cancelled.")
            throw error
        }
        return Connection(session, nextGeneration).also { connection = it }
    }

    private suspend fun verifyLocked(): Connection {
        val connected = connectLocked()
        idleJob?.cancel()
        try {
            val identity = withContext(Dispatchers.IO) { connected.session.info() }
            check(identity.uid == 0 && identity.pid > 0) { "Root service identity is invalid." }
            check(identity.protocol == RootServiceInfo.Protocol) { "Root service protocol is incompatible." }
            val capabilities = request(connected) { it.probeCapabilities() }.split(',').mapNotNull { name ->
                DeviceCapability.entries.firstOrNull { it.name == name }
            }.toSet()
            check(capabilities.isNotEmpty()) { "Root device commands are unavailable." }
            mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot, capabilities,
                lastVerifiedAtMillis = System.currentTimeMillis())
            verifiedAt = clockMillis()
            return connected
        } finally { scheduleIdleLocked(connected) }
    }

    /** Each RPC owns a cancellable request; failed operations are never retried here. */
    internal suspend fun <T> withService(ownerId: String? = null, cleanupOnly: Boolean = false,
        operation: (IFlowChatShizukuService) -> T): T {
        if (!cleanupOnly) requireRequestCleanupVerified()
        val connected = acquireConnection()
        try {
            if (!cleanupOnly) requireRequestCleanupVerified()
            return request(connected, ownerId, checkCleanup = !cleanupOnly, operation = operation)
        } catch (error: Exception) {
            if (error is RemoteException || !connected.session.alive) {
                withContext(NonCancellable) { disconnected(connected.generation) }
            }
            throw error
        } finally { releaseLease(connected) }
    }

    private suspend fun acquireConnection(onAcquired: (Connection) -> Unit = {}): Connection = mutex.withLock {
            check(permissionRequested) { "Grant Floryx Root permission before using device operations." }
            val elapsed = verifiedAt?.let { clockMillis() - it }
            val ready = connection?.takeIf { it.session.alive && state.value.status == ShizukuConnectionStatus.ConnectedDirectRoot &&
                elapsed != null && elapsed in 0 until verificationLifetimeMillis }
            val value = try { ready ?: verifyLocked() } catch (error: Exception) {
                if (error !is CancellationException && error !is CleanupPendingException) failLocked(error)
                throw error
            }
            onAcquired(value)
            idleJob?.cancel()
            value.leases++
            value
    }

    private suspend fun releaseLease(connected: Connection) = withContext(NonCancellable) {
        mutex.withLock {
            connected.leases--
            if (connection === connected) scheduleIdleLocked(connected)
        }
    }

    /** The lease pins the exact Binder process. A lost display is never recreated in this task. */
    internal suspend fun openExecutionDisplay(ownerId: String): ExecutionDisplayLease {
        requireRequestCleanupVerified()
        recheckExecutionDisplayCleanup()
        displayCleanup.requireReusable()
        lateinit var attempt: DisplayAttempt
        val connected = acquireConnection { selected ->
            displayCleanup.requireReusable()
            check(ownerId !in selected.displays) { "An execution display already belongs to this task." }
            displayCleanup.creating(ownerId)
            attempt = DisplayAttempt(ownerId)
            attempt.rpc = operations.async(start = CoroutineStart.LAZY) {
                attempt.started = true
                try {
                    selected.session.openExecutionDisplay(ownerId).also { remote ->
                        // Read the identity in the independent RPC even if its original waiter was cancelled.
                        val id = runCatching { JSONObject(remote.info).optInt("displayId") }.getOrNull()
                        if (id != null && id > 0) displayCleanup.identify(ownerId, id)
                    }
                } finally { attempt.finished.complete(Unit) }
            }
            selected.displays[ownerId] = attempt
        }
        try {
            val remote = attempt.rpc.await()
            mutex.withLock {
                check(connection === connected && !connected.detached && connected.displays[ownerId] === attempt) {
                    "Execution display connection closed. Start a new task."
                }
            }
            val commands = object : ExecutionDisplayCommands {
                override suspend fun <T> execute(operation: (IFlowChatExecutionDisplay) -> T): T {
                    requireRequestCleanupVerified()
                    mutex.withLock {
                        check(connection === connected && !connected.detached && connected.session.alive &&
                            connected.displays[ownerId] === attempt) { "Original execution display connection is unavailable." }
                        idleJob?.cancel()
                        connected.leases++
                    }
                    try {
                        return request(connected, ownerId, checkCleanup = true) { service ->
                            operation(service.getRequestExecutionDisplay(ownerId))
                        }
                    } catch (error: Exception) {
                        if (error is RemoteException || !connected.session.alive) {
                            withContext(NonCancellable) { disconnected(connected.generation) }
                        }
                        throw error
                    } finally { releaseLease(connected) }
                }
            }
            return ExecutionDisplayLease(remote, { !connected.detached && connected.session.alive && remote.asBinder().isBinderAlive }, commands) {
                closeDisplay(connected, attempt)
            }
        } catch (error: Throwable) {
            closeDisplay(connected, attempt)
            throw error
        }
    }

    private suspend fun closeDisplay(connected: Connection, attempt: DisplayAttempt) = withContext(NonCancellable) {
        mutex.withLock { closeDisplayLocked(connected, attempt) }
    }

    /** The map removal owns both cleanup and the display's connection lease, exactly once. */
    private suspend fun closeDisplayLocked(connected: Connection, attempt: DisplayAttempt) {
        if (connected.displays[attempt.ownerId] !== attempt) return
        connected.displays.remove(attempt.ownerId)
        displayCleanup.releasing(attempt.ownerId)
        attempt.rpc.cancel()
        connected.leases--
        val watchdog = scope.launch {
            delay(3_000)
            displayCleanup.unverified(attempt.ownerId, ExecutionDisplayCleanupReason.ClosePending)
        }
        try { withContext(NonCancellable + Dispatchers.IO) {
            // Closing even a not-yet-opened owner installs the server's cancellation tombstone.
            runCatching { connected.session.closeExecutionDisplay(attempt.ownerId) }
        } } finally { watchdog.cancel() }
        // Do not wait for a blocking Binder while holding the connection mutex. Its late return
        // may still identify a display, but cannot recreate a task or overwrite another owner.
        val check = { displayCleanup.verify(attempt.ownerId,
            !attempt.started || attempt.finished.isCompleted, neverStarted = !attempt.started) }
        displayCleanupChecks[attempt.ownerId] = check
        if (check()) displayCleanupChecks.remove(attempt.ownerId, check)
        else scope.launch {
            if (attempt.started) withTimeoutOrNull(3_000) { attempt.finished.await() }
            repeat(21) {
                if (check()) { displayCleanupChecks.remove(attempt.ownerId, check); return@launch }
                delay(100)
            }
        }
        if (connection === connected) scheduleIdleLocked(connected)
    }

    private suspend fun <T> request(connected: Connection, ownerId: String? = null, checkCleanup: Boolean = false,
        shellStart: ShellStart? = null,
        operation: (IFlowChatShizukuService) -> T): T = requestSlots.withPermit {
        if (checkCleanup) requireRequestCleanupVerified()
        val id = shellStart?.id ?: UUID.randomUUID().toString()
        synchronized(connected) {
            check(!connected.detached && connected.session.alive) { "Original Root connection is unavailable." }
            if (shellStart != null) {
                commandStarts[id] = shellStart
                shellStart.observer?.prepared?.invoke(id) { recheckCommandStart(shellStart) }
            }
            if (ownerId != null) {
                requestCleanup.requireReusable()
                connected.ownedRequests.add(id)
                requestCleanup.started(id, ownerId)
            }
        }
        val finished = CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        // Await independent IO so cancellation can use another Binder thread to close the
        // request while the original synchronous RPC is still executing.
        val rpc = operations.async(start = CoroutineStart.LAZY) {
            if (shellStart != null && !shellStart.dispatchState.compareAndSet(0, 1)) {
                throw CancellationException("Command start was cancelled before dispatch.")
            }
            started.set(true)
            try {
                if (checkCleanup) requireRequestCleanupVerified()
                check(!connected.detached && connected.session.alive) { "Original Root connection is unavailable." }
                val result = operation(connected.session.openRequest(id))
                check(!connected.detached && connected.session.alive) { "Original Root connection was lost; the late result is unverified." }
                result
            } finally {
                finished.complete(Unit)
                shellStart?.rpcFinished = true
                if (shellStart?.abandoned == true) scope.launch { recheckCommandStart(shellStart) }
            }
        }
        var cancelled = false
        try { rpc.await() } catch (error: CancellationException) {
            cancelled = true
            shellStart?.abandoned = true
            rpc.cancel()
            throw error
        } catch (error: Exception) {
            if (shellStart != null) { cancelled = true; shellStart.abandoned = true }
            throw error
        } finally {
            withContext(NonCancellable) {
                if (ownerId != null) requestCleanup.releasing(id)
                val watchdog = if (ownerId == null) null else scope.launch {
                    delay(3_000)
                    requestCleanup.unverified(id, RootRequestCleanupReason.FinishPending)
                }
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val result = runCatching { connected.session.finishRequest(id, cancelled) }
                        shellStart?.finishReturned = result.isSuccess
                    }
                } finally { watchdog?.cancel() }
                if (shellStart != null) {
                    if (shellStart.dispatchState.compareAndSet(0, 2)) shellStart.rpcFinished = true
                    if (!shellStart.abandoned && shellStart.commandId != null && shellStart.finishReturned) {
                        shellStart.settled = true; commandStarts.remove(id, shellStart)
                    } else {
                        shellStart.abandoned = true
                        recheckCommandStart(shellStart)
                        scope.launch { repeat(21) {
                            if (shellStart.settled) return@launch
                            delay(100); recheckCommandStart(shellStart)
                        } }
                    }
                }
                if (ownerId != null) {
                    val check = {
                        if (connected.detached || !connected.session.alive) {
                            requestCleanup.unverified(id, RootRequestCleanupReason.OriginalConnectionUnavailable)
                            false
                        } else requestCleanup.verify(id, !started.get() || finished.isCompleted) {
                            connected.session.requestCleanup(id)
                        }.also { if (it) connected.ownedRequests.remove(id) }
                    }
                    requestCleanupChecks[id] = check
                    if (check()) requestCleanupChecks.remove(id, check)
                    else scope.launch {
                        // Deferred cancellation is not proof that the synchronous Binder returned.
                        if (started.get()) withTimeoutOrNull(3_000) { finished.await() }
                        repeat(21) {
                            if (check()) { requestCleanupChecks.remove(id, check); return@launch }
                            delay(100)
                        }
                    }
                }
            }
        }
    }

    /** Invalidate a probe without killing unrelated requests or replaying the failed operation. */
    suspend fun invalidate() = mutex.withLock {
        verifiedAt = null
        if (state.value.status == ShizukuConnectionStatus.ConnectedDirectRoot) {
            safetyBoundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
            mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.Error, detail = "Root connection needs verification.")
        }
    }

    /** Power-mode disable or a transport switch cancels work through service teardown. */
    suspend fun release() {
        connectingJob?.cancel()
        mutex.withLock {
            permissionRequested = false
            detachLocked()
            mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.RootPermissionRequired)
        }
    }

    private suspend fun disconnected(expectedGeneration: Long) = mutex.withLock {
        if (connection?.generation != expectedGeneration) return@withLock
        safetyBoundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
        detachLocked()
        mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.Error,
            detail = "Root service disconnected. The interrupted operation was not replayed.")
    }

    private suspend fun failLocked(error: Exception) {
        safetyBoundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
        detachLocked()
        val status = (error as? RootConnectionException)?.status ?: ShizukuConnectionStatus.Error
        if (status == ShizukuConnectionStatus.RootPermissionDenied) permissionRequested = false
        mutableState.value = ShizukuConnectionState(status, detail = error.message)
    }

    private suspend fun detachLocked() {
        idleJob?.cancel()
        idleJob = null
        val old = connection
        if (old != null) markConnectionUnavailable(old)
        connection = null
        verifiedAt = null
        if (old != null) withContext(NonCancellable) {
            try {
                old.displays.values.toList().forEach { closeDisplayLocked(old, it) }
            } finally { old.session.close() }
        }
    }

    /** Serialized with request registration and command evidence publication, without doing any IPC. */
    private fun markConnectionUnavailable(connected: Connection) = synchronized(connected) {
        if (connected.detached) return@synchronized
        connected.detached = true
        connected.ownedRequests.forEach { requestCleanup.unverified(it, RootRequestCleanupReason.OriginalConnectionUnavailable) }
        commandStarts.values.filter { it.connection === connected && !it.settled }.forEach {
            it.abandoned = true
            it.observer?.update?.invoke(CommandStartUpdate(it.id, reason = CommandCleanupReason.OriginalConnectionUnavailable))
        }
        retainedCommands.values.filter { it.connection === connected }.forEach {
            it.unavailable(CommandCleanupReason.OriginalConnectionUnavailable)
        }
    }

    private fun scheduleIdleLocked(connected: Connection) {
        idleJob?.cancel()
        if (connected.leases > 0) return
        idleJob = scope.launch {
            delay(idleTimeoutMillis)
            mutex.withLock {
                if (connection !== connected || connected.leases > 0) return@withLock
                idleJob = null
                try {
                    val info = connected.session.info()
                    if (info.activeRequests > 0 || info.activeCommands > 0 || info.activeDisplays > 0) {
                        scheduleIdleLocked(connected)
                    } else {
                        val capabilities = state.value.capabilities
                        val lastVerifiedAt = state.value.lastVerifiedAtMillis
                        detachLocked()
                        mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.RootReady, capabilities,
                            lastVerifiedAtMillis = lastVerifiedAt)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failLocked(error)
                }
            }
        }
    }

    internal suspend fun serviceInfo(): RootServiceInfo? = mutex.withLock {
        connection?.session?.takeIf { it.alive }?.info()
    }
}
