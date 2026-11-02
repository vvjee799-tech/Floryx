package com.flowchat.app.data.device

import android.content.Context
import android.os.RemoteException
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
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
    private val verificationLifetimeMillis: Long = 30_000
) {
    @Inject constructor(@ApplicationContext context: Context) : this(LibsuRootServiceConnector(context))

    private val mutableState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.RootPermissionRequired))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val operations = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val requestSlots = Semaphore(4)
    private var permissionRequested = false
    private var verifiedAt: Long? = null
    private var generation = 0L
    private var connection: Connection? = null
    private var idleJob: Job? = null
    @Volatile private var connectingJob: Job? = null

    private class Connection(val session: RootServiceSession, val generation: Long, var leases: Int = 0)

    internal class ExecutionDisplayLease(
        val remote: IFlowChatExecutionDisplay,
        private val isAlive: () -> Boolean,
        private val release: suspend () -> Unit
    ) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val alive: Boolean get() = !closed.get() && isAlive()
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
            verifyLocked()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failLocked(error)
        }
    }

    private suspend fun connectLocked(): Connection {
        connection?.takeIf { it.session.alive }?.let { return it }
        val previous = state.value
        detachLocked()
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
            mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot, capabilities)
            verifiedAt = clockMillis()
            return connected
        } finally { scheduleIdleLocked(connected) }
    }

    /** Each RPC owns a cancellable request; failed operations are never retried here. */
    internal suspend fun <T> withService(operation: (IFlowChatShizukuService) -> T): T {
        val connected = acquireConnection()
        try {
            return request(connected, operation)
        } catch (error: Exception) {
            if (error is RemoteException || !connected.session.alive) {
                withContext(NonCancellable) { disconnected(connected.generation) }
            }
            throw error
        } finally { releaseLease(connected) }
    }

    private suspend fun acquireConnection(): Connection = mutex.withLock {
            check(permissionRequested) { "Grant Floryx Root permission before using device operations." }
            val elapsed = verifiedAt?.let { clockMillis() - it }
            val ready = connection?.takeIf { it.session.alive && state.value.status == ShizukuConnectionStatus.ConnectedDirectRoot &&
                elapsed != null && elapsed in 0 until verificationLifetimeMillis }
            val value = try { ready ?: verifyLocked() } catch (error: Exception) {
                if (error !is CancellationException) failLocked(error)
                throw error
            }
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
        val connected = acquireConnection()
        val rpc = operations.async { connected.session.openExecutionDisplay(ownerId) }
        try {
            val remote = rpc.await()
            return ExecutionDisplayLease(remote, { connected.session.alive && remote.asBinder().isBinderAlive }) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { runCatching { connected.session.closeExecutionDisplay(ownerId) } }
                    finally { releaseLease(connected) }
                }
            }
        } catch (error: Throwable) {
            rpc.cancel()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { connected.session.closeExecutionDisplay(ownerId) }
                releaseLease(connected)
            }
            throw error
        }
    }

    private suspend fun <T> request(connected: Connection, operation: (IFlowChatShizukuService) -> T): T = requestSlots.withPermit {
        val id = UUID.randomUUID().toString()
        // Await independent IO so cancellation can use another Binder thread to close the
        // request while the original synchronous RPC is still executing.
        val rpc = operations.async { operation(connected.session.openRequest(id)) }
        var cancelled = false
        try { rpc.await() } catch (error: CancellationException) {
            cancelled = true
            rpc.cancel()
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { connected.session.finishRequest(id, cancelled) } }
        }
    }

    /** Invalidate a probe without killing unrelated requests or replaying the failed operation. */
    suspend fun invalidate() = mutex.withLock {
        verifiedAt = null
        if (state.value.status == ShizukuConnectionStatus.ConnectedDirectRoot) {
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
        detachLocked()
        mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.Error,
            detail = "Root service disconnected. The interrupted operation was not replayed.")
    }

    private suspend fun failLocked(error: Exception) {
        detachLocked()
        val status = (error as? RootConnectionException)?.status ?: ShizukuConnectionStatus.Error
        if (status == ShizukuConnectionStatus.RootPermissionDenied) permissionRequested = false
        mutableState.value = ShizukuConnectionState(status, detail = error.message)
    }

    private suspend fun detachLocked() {
        idleJob?.cancel()
        idleJob = null
        val old = connection
        connection = null
        verifiedAt = null
        if (old != null) withContext(NonCancellable) { old.session.close() }
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
                    if (info.activeCommands > 0 || info.activeDisplays > 0) {
                        scheduleIdleLocked(connected)
                    } else {
                        val capabilities = state.value.capabilities
                        detachLocked()
                        mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.RootReady, capabilities)
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
