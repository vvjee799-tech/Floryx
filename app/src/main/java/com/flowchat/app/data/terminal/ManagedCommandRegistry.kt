package com.flowchat.app.data.terminal

import com.flowchat.app.domain.terminal.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class CommandProcessSpec(
    val argv: List<String>,
    val directory: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val root: Boolean = false,
    val processGroup: Boolean = true
)

/** Bounded output and job count. Each Android process gets a separate group for cancellation. */
internal class ManagedCommandRegistry(private val prefix: String = "local-") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = linkedMapOf<String, Entry>()
    private val mutableState = MutableStateFlow<List<CommandSnapshot>>(emptyList())
    val state: StateFlow<List<CommandSnapshot>> = mutableState

    private class Entry(var snapshot: CommandSnapshot, val spec: CommandProcessSpec) {
        var job: Job? = null
        var cancelRequested = false
        val processGroup = AtomicInteger(0)
    }

    @Synchronized
    fun start(command: String, environment: CommandEnvironment, cwd: String, timeoutSeconds: Int, spec: CommandProcessSpec): CommandSnapshot {
        CommandValidation.validate(command, cwd, timeoutSeconds)
        check(entries.values.count { it.snapshot.isRunning } < 4) { "Four commands are already running. Stop one or wait for it to finish." }
        val id = prefix + UUID.randomUUID()
        val entry = Entry(CommandSnapshot(id, command, environment, cwd), spec)
        entries[id] = entry
        while (entries.size > 24) {
            val old = entries.entries.firstOrNull { !it.value.snapshot.isRunning } ?: break
            entries.remove(old.key)
        }
        publish()
        entry.job = scope.launch {
            try {
                val exit = withTimeout(timeoutSeconds * 1000L) { runProcess(entry) }
                update(entry) { it.copy(status = if (entry.cancelRequested) CommandStatus.Cancelled else CommandStatus.Exited,
                    exitCode = exit, finishedAt = System.currentTimeMillis()) }
            } catch (error: TimeoutCancellationException) {
                update(entry) { it.copy(status = CommandStatus.TimedOut, error = "Command timed out.", finishedAt = System.currentTimeMillis()) }
            } catch (error: CancellationException) {
                update(entry) { it.copy(status = CommandStatus.Cancelled, finishedAt = System.currentTimeMillis()) }
            } catch (error: Exception) {
                update(entry) { it.copy(status = CommandStatus.Failed, error = error.message ?: "Command failed.", finishedAt = System.currentTimeMillis()) }
            }
        }
        entry.job?.invokeOnCompletion {
            if (entry.snapshot.isRunning) update(entry) { snapshot -> snapshot.copy(status = CommandStatus.Cancelled, finishedAt = System.currentTimeMillis()) }
        }
        return entry.snapshot
    }

    @Synchronized fun read(id: String): CommandSnapshot = requireNotNull(entries[id]) { "Command not found." }.snapshot
    @Synchronized fun cancel(id: String): CommandSnapshot {
        val entry = requireNotNull(entries[id]) { "Command not found." }
        if (entry.snapshot.isRunning) { entry.cancelRequested = true; entry.job?.cancel() }
        return entry.snapshot
    }

    private suspend fun runProcess(entry: Entry): Int = coroutineScope {
        ensureActive()
        if (entry.cancelRequested) throw CancellationException()
        val marker = "__FC_PID_${UUID.randomUUID().toString().replace("-", "")}:"
        val groupArgv = if (entry.spec.processGroup) listOf("/system/bin/setsid", "/system/bin/sh", "-c",
            "printf '%s%s\\n' '$marker' \"\$\$\"; exec \"\$@\"", "flowchat") + entry.spec.argv else entry.spec.argv
        val argv = if (entry.spec.root) listOf("su", "-c", groupArgv.joinToString(" ", transform = ::shellQuote)) else groupArgv
        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        entry.spec.directory?.let(builder::directory)
        builder.environment().putAll(entry.spec.environment)
        val process = builder.start()
        process.outputStream.close()
        val reader = launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { input ->
                if (entry.spec.processGroup) {
                    val first = input.readLine().orEmpty()
                    if (first.startsWith(marker)) entry.processGroup.set(first.removePrefix(marker).trim().toIntOrNull() ?: 0)
                    else appendOutput(entry, first + "\n")
                }
                val buffer = CharArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    appendOutput(entry, String(buffer, 0, count))
                }
            }
        }
        try {
            val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
            // A detached child must not hold the parent's output pipe open indefinitely.
            withTimeoutOrNull(2_000) { reader.join() }
            code
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val group = entry.processGroup.get()
                if (entry.spec.processGroup && group > 1) {
                    val kill = listOf("/system/bin/kill", "-KILL", "--", "-$group")
                    val stop = if (entry.spec.root) listOf("su", "-c", kill.joinToString(" ", transform = ::shellQuote)) else kill
                    runCatching { ProcessBuilder(stop).redirectErrorStream(true).start().let { killer ->
                        if (!killer.waitFor(2, TimeUnit.SECONDS)) killer.destroyForcibly()
                        killer.inputStream.close()
                    } }
                }
                if (process.isAlive) process.destroyForcibly()
                runCatching { process.inputStream.close() }
                reader.cancel()
            }
        }
    }

    private fun appendOutput(entry: Entry, text: String) = update(entry) {
        val combined = it.output + text
        val next = it.nextOffset + text.length
        val tail = combined.takeLast(MaxOutputCharacters)
        it.copy(output = tail, outputOffset = next - tail.length, nextOffset = next)
    }
    @Synchronized private fun update(entry: Entry, block: (CommandSnapshot) -> CommandSnapshot) { entry.snapshot = block(entry.snapshot); publish() }
    private fun publish() { mutableState.value = entries.values.map { it.snapshot }.reversed() }

    companion object {
        const val MaxOutputCharacters = 65_536
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
