package com.flowchat.app.data.terminal

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.terminal.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Singleton
class TerminalManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linux: LinuxRuntime,
    private val device: DeviceAssistantGateway
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = ManagedCommandRegistry("linux-", trackCleanup = true)
    private data class Ownership(val owner: String, val runId: String?)
    private val owners = ConcurrentHashMap<String, Ownership>()
    private class PendingStart(val snapshot: CommandSnapshot, val ownership: Ownership, val job: Job) {
        @Volatile var tracked = false
        @Volatile var recheck: (suspend () -> Unit)? = null
        @Volatile var commandId: String? = null
        val cancelIdentified = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private val pendingStarts = ConcurrentHashMap<String, PendingStart>()
    private val polling = ConcurrentHashMap.newKeySet<String>()
    private val remote = MutableStateFlow<List<CommandSnapshot>>(emptyList())
    val state: StateFlow<TerminalUiState> = combine(linux.state, registry.state, remote) { runtime, local, system ->
        val all = (local + system).sortedByDescending { it.createdAt }
        val active = all.filter { it.isRunning || it.cleanup?.blocksReuse == true }
        TerminalUiState(runtime, (active + all.filterNot { it in active }.take((24 - active.size).coerceAtLeast(0))))
    }.stateIn(scope, SharingStarted.Eagerly, TerminalUiState())

    fun prepare() { scope.launch { linux.prepare() } }
    fun owns(owner: String, id: String): Boolean = owners[id]?.owner == owner || pendingStarts[id]?.ownership?.owner == owner

    suspend fun start(owner: String, environment: CommandEnvironment, command: String, cwd: String, timeoutSeconds: Int, runId: String? = null): CommandSnapshot {
        CommandValidation.validate(command, cwd, timeoutSeconds)
        val preparingAt = android.os.SystemClock.elapsedRealtime()
        require(environment != CommandEnvironment.Android || runId == null || !com.flowchat.app.domain.terminal.AndroidUiCommandPolicy.requiresDeviceTools(command)) {
            com.flowchat.app.domain.terminal.AndroidUiCommandPolicy.Guidance
        }
        ensureForegroundService()
        val snapshot = when (environment) {
            CommandEnvironment.Linux -> {
                linux.prepare()
                registry.start(command, environment, cwd, timeoutSeconds, linux.processSpec(owner, command, cwd),
                    android.os.SystemClock.elapsedRealtime() - preparingAt)
            }
            CommandEnvironment.Android -> startAndroid(Ownership(owner, runId), command, cwd, timeoutSeconds)
        }
        owners[snapshot.id] = Ownership(owner, runId)
        if (owners.size > 128) {
            val retained = (registry.state.value + remote.value).map { it.id }.toSet() + snapshot.id
            owners.keys.retainAll(retained)
        }
        if (environment == CommandEnvironment.Android) {
            storeRemote(snapshot)
            watchRemote(snapshot)
        } else scope.launch {
            registry.state.first { list -> list.firstOrNull { it.id == snapshot.id }?.isRunning == false }
            linux.refreshState()
        }
        return snapshot
    }

    private suspend fun startAndroid(ownership: Ownership, command: String, cwd: String, timeoutSeconds: Int): CommandSnapshot = coroutineScope {
        val id = "start-" + java.util.UUID.randomUUID()
        val initial = CommandSnapshot(id, command, CommandEnvironment.Android, cwd, startIntentId = id)
        val pending = PendingStart(initial, ownership, currentCoroutineContext().job)
        // Register before dispatch so cancelRun/cancelAll also cover callers awaiting an ID.
        owners[id] = ownership
        pendingStarts[id] = pending
        storeRemote(initial)
        val observer = CommandStartObserver(prepared = { requestId, recheck ->
            pending.tracked = true
            pending.recheck = recheck
            storeRemote(initial.copy(startRequestId = requestId, cleanup = CommandCleanup(id)))
        }, update = { update ->
            synchronized(pending) {
                val received = update.snapshot
                when {
                    received != null -> {
                        check(pending.commandId == null || pending.commandId == received.id) { "Start resolved to another command." }
                        pending.commandId = received.id
                        owners[received.id] = ownership
                        val snapshot = received.copy(startIntentId = id, startRequestId = update.requestId)
                        // Publish final ownership even if the original start waiter has gone away.
                        remote.update { list -> list.filterNot { it.id == id } }
                        if (pending.job.isCompleted) pendingStarts.remove(id, pending)
                        owners.remove(id)
                        storeRemote(snapshot)
                        watchRemote(snapshot)
                        cancelIdentifiedStart(pending)
                    }
                    pending.commandId == null -> {
                        val previous = remote.value.firstOrNull { it.id == id } ?: initial
                        val next = previous.copy(startRequestId = update.requestId,
                            status = if (pending.job.isCancelled) CommandStatus.Cancelled else CommandStatus.Failed,
                            finishedAt = previous.finishedAt ?: System.currentTimeMillis(),
                            cleanup = if (update.notStarted) CommandCleanup(id, CommandCleanupState.Released,
                                checkedAtMillis = System.currentTimeMillis()) else
                                CommandCleanup(id, CommandCleanupState.Unverified, reason = update.reason ?: CommandCleanupReason.StartResponsePending))
                        storeRemote(next)
                        if (update.notStarted) pendingStarts.remove(id, pending)
                    }
                }
            }
        })
        try {
            val snapshot = withContext(observer) { device.startShellCommand(command, cwd, timeoutSeconds) }
            snapshot.copy(startIntentId = id)
        } catch (error: Exception) {
            synchronized(pending) {
                if (pending.tracked && pending.commandId == null && pendingStarts[id] === pending) {
                    val previous = remote.value.firstOrNull { it.id == id } ?: initial
                    storeRemote(previous.copy(status = if (error is CancellationException) CommandStatus.Cancelled else CommandStatus.Failed,
                        error = error.message, finishedAt = previous.finishedAt ?: System.currentTimeMillis(),
                        cleanup = previous.cleanup?.takeIf { it.state == CommandCleanupState.Released } ?:
                            (previous.cleanup ?: CommandCleanup(id)).copy(state = CommandCleanupState.Unverified,
                                reason = previous.cleanup?.reason ?: CommandCleanupReason.StartResponsePending)))
                }
            }
            throw error
        } finally {
            cancelIdentifiedStart(pending)
            if (pending.commandId != null) pendingStarts.remove(id, pending)
            if (!pending.tracked) {
                pendingStarts.remove(id, pending)
                owners.remove(id)
                remote.update { list -> list.filterNot { it.id == id } }
            }
        }
    }

    private fun cancelPendingStart(pending: PendingStart) {
        pending.job.cancel()
        cancelIdentifiedStart(pending)
    }

    private fun cancelIdentifiedStart(pending: PendingStart) {
        val id = pending.commandId ?: return
        if (pending.job.isCancelled && pending.cancelIdentified.compareAndSet(false, true)) scope.launch {
            // Covers cancellation after the RPC completed but before its caller received the ID.
            runCatching { device.cancelShellCommand(id).also(::storeRemote) }
        }
    }

    private fun watchRemote(snapshot: CommandSnapshot) {
        if (!snapshot.isRunning || !polling.add(snapshot.id)) return
        scope.launch {
            var current = snapshot
            try {
                while (current.isRunning) {
                    delay(350)
                    current = runCatching { device.readShellCommand(snapshot.id) }.getOrElse { error ->
                        current.copy(status = CommandStatus.Failed, error = error.message ?: "Power connection lost.", finishedAt = System.currentTimeMillis())
                            .let { if (it.id.startsWith("root-")) it.cleanupUnavailable(CommandCleanupReason.OriginalConnectionUnavailable) else it }
                    }
                    storeRemote(current)
                }
            } finally { polling.remove(snapshot.id) }
        }
    }

    suspend fun read(owner: String, id: String, offset: Long = 0, waitSeconds: Int = 0): CommandSnapshot {
        check(owns(owner, id)) { "Command does not belong to this conversation." }
        require(offset >= 0 && waitSeconds in 0..15) { "Invalid output offset or wait duration." }
        val current: () -> CommandSnapshot = {
            if (id.startsWith("linux-")) registry.read(id) else requireNotNull(remote.value.firstOrNull { it.id == id }) { "Command not found." }
        }
        var snapshot = current()
        val deadline = android.os.SystemClock.elapsedRealtime() + waitSeconds * 1000
        while (snapshot.isRunning && snapshot.nextOffset <= offset && android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(100)
            snapshot = current()
        }
        return snapshot
    }

    suspend fun cancel(owner: String, id: String): CommandSnapshot {
        check(owns(owner, id)) { "Command does not belong to this conversation." }
        pendingStarts[id]?.let { pending ->
            cancelPendingStart(pending)
            val previous = remote.value.firstOrNull { it.id == (pending.commandId ?: id) } ?: pending.snapshot
            if (pending.commandId != null) return previous
            return previous.copy(status = CommandStatus.Cancelled, finishedAt = System.currentTimeMillis(),
                cleanup = previous.cleanup?.copy(state = CommandCleanupState.Unverified, reason = CommandCleanupReason.StartResponsePending))
                .also(::storeRemote)
        }
        remote.value.firstOrNull { it.id == id && it.startIntentId == id }?.let { return it }
        return if (id.startsWith("linux-")) registry.cancel(id) else device.cancelShellCommand(id).also(::storeRemote)
    }

    /** Read only: no restart, new connection, second cancellation or model request. */
    suspend fun recheckCleanup(owner: String, id: String): CommandSnapshot {
        check(owns(owner, id)) { "Command does not belong to this conversation." }
        pendingStarts[id]?.let { pending ->
            pending.recheck?.invoke()
            return requireNotNull(remote.value.firstOrNull { it.id == (pending.commandId ?: id) })
        }
        remote.value.firstOrNull { it.id == id && it.startIntentId == id && it.cleanup?.state == CommandCleanupState.Released }?.let { return it }
        if (id.startsWith("linux-")) return registry.read(id)
        require(id.startsWith("root-")) { "Cleanup verification requires the original Root or Linux command." }
        val current = requireNotNull(remote.value.firstOrNull { it.id == id }) { "Command not found." }
        if (current.cleanup?.state == CommandCleanupState.Released) return current
        return try { device.readShellCommand(id).also(::storeRemote) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            current.cleanupUnavailable(CommandCleanupReason.OriginalConnectionUnavailable).also(::storeRemote)
        }
    }

    /** Wait locally, independently of output offsets or the model's polling choices. */
    suspend fun awaitCompletion(
        owner: String,
        id: String,
        onProgress: suspend (CommandSnapshot) -> Unit = {}
    ): CommandSnapshot {
        var lastProgress = Long.MIN_VALUE
        while (true) {
            val snapshot = read(owner, id)
            val now = android.os.SystemClock.elapsedRealtime()
            if (!snapshot.isRunning || lastProgress == Long.MIN_VALUE || now - lastProgress >= 1_000) {
                onProgress(snapshot)
                lastProgress = now
            }
            if (!snapshot.isRunning) return snapshot
            delay(100)
        }
    }

    fun cancelRun(runId: String) {
        pendingStarts.values.filter { it.ownership.runId == runId }.forEach(::cancelPendingStart)
        owners.entries.filter { it.value.runId == runId }.forEach { (id, ownership) ->
            scope.launch { runCatching { cancel(ownership.owner, id) } }
        }
    }
    fun cancelAgentCommands() {
        pendingStarts.values.filter { it.ownership.runId != null }.forEach(::cancelPendingStart)
        owners.entries.filter { it.value.runId != null }.forEach { (id, ownership) -> scope.launch { runCatching { cancel(ownership.owner, id) } } }
    }
    fun cancelAll() {
        pendingStarts.values.forEach(::cancelPendingStart)
        owners.forEach { (id, ownership) -> scope.launch { runCatching { cancel(ownership.owner, id) } } }
    }
    private fun storeRemote(snapshot: CommandSnapshot) { remote.update { list ->
        if (list.any { it.id == snapshot.id && it.cleanup?.state == CommandCleanupState.Released }) return@update list
        val previous = list.firstOrNull { it.id == snapshot.id }
        if (previous != null && !previous.isRunning && snapshot.isRunning) return@update list
        val next = snapshot.copy(startIntentId = snapshot.startIntentId ?: previous?.startIntentId,
            startRequestId = snapshot.startRequestId ?: previous?.startRequestId)
        val all = list.filterNot { it.id == snapshot.id } + next
        val active = all.filter { it.isRunning || it.cleanup?.blocksReuse == true }
        active + all.filterNot { it in active }.takeLast((24 - active.size).coerceAtLeast(0))
    } }
    private fun ensureForegroundService() {
        ContextCompat.startForegroundService(context, Intent(context, CommandExecutionService::class.java))
    }
}
