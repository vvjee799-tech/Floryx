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
    private val registry = ManagedCommandRegistry("linux-")
    private data class Ownership(val owner: String, val runId: String?)
    private val owners = ConcurrentHashMap<String, Ownership>()
    private val remote = MutableStateFlow<List<CommandSnapshot>>(emptyList())
    val state: StateFlow<TerminalUiState> = combine(linux.state, registry.state, remote) { runtime, local, system ->
        val all = (local + system).sortedByDescending { it.createdAt }
        val running = all.filter { it.isRunning }
        TerminalUiState(runtime, (running + all.filterNot { it.isRunning }.take((24 - running.size).coerceAtLeast(0))))
    }.stateIn(scope, SharingStarted.Eagerly, TerminalUiState())

    fun prepare() { scope.launch { linux.prepare() } }
    fun owns(owner: String, id: String): Boolean = owners[id]?.owner == owner

    suspend fun start(owner: String, environment: CommandEnvironment, command: String, cwd: String, timeoutSeconds: Int, runId: String? = null): CommandSnapshot {
        CommandValidation.validate(command, cwd, timeoutSeconds)
        ensureForegroundService()
        val snapshot = when (environment) {
            CommandEnvironment.Linux -> {
                linux.prepare()
                registry.start(command, environment, cwd, timeoutSeconds, linux.processSpec(owner, command, cwd))
            }
            CommandEnvironment.Android -> device.startShellCommand(command, cwd, timeoutSeconds)
        }
        owners[snapshot.id] = Ownership(owner, runId)
        if (owners.size > 128) {
            val retained = (registry.state.value + remote.value).map { it.id }.toSet() + snapshot.id
            owners.keys.retainAll(retained)
        }
        if (environment == CommandEnvironment.Android) {
            storeRemote(snapshot)
            scope.launch {
                var current = snapshot
                while (current.isRunning) {
                    delay(350)
                    current = runCatching { device.readShellCommand(snapshot.id) }.getOrElse { error ->
                        current.copy(status = CommandStatus.Failed, error = error.message ?: "Power connection lost.", finishedAt = System.currentTimeMillis())
                    }
                    storeRemote(current)
                }
            }
        } else scope.launch {
            registry.state.first { list -> list.firstOrNull { it.id == snapshot.id }?.isRunning == false }
            linux.refreshState()
        }
        return snapshot
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
        return if (id.startsWith("linux-")) registry.cancel(id) else device.cancelShellCommand(id).also(::storeRemote)
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
        owners.entries.filter { it.value.runId == runId }.forEach { (id, ownership) ->
            scope.launch { runCatching { cancel(ownership.owner, id) } }
        }
    }
    fun cancelAgentCommands() {
        owners.entries.filter { it.value.runId != null }.forEach { (id, ownership) -> scope.launch { runCatching { cancel(ownership.owner, id) } } }
    }
    fun cancelAll() { owners.forEach { (id, ownership) -> scope.launch { runCatching { cancel(ownership.owner, id) } } } }
    private fun storeRemote(snapshot: CommandSnapshot) { remote.update { list ->
        val all = list.filterNot { it.id == snapshot.id } + snapshot
        val running = all.filter { it.isRunning }
        running + all.filterNot { it.isRunning }.takeLast((24 - running.size).coerceAtLeast(0))
    } }
    private fun ensureForegroundService() {
        ContextCompat.startForegroundService(context, Intent(context, CommandExecutionService::class.java))
    }
}
