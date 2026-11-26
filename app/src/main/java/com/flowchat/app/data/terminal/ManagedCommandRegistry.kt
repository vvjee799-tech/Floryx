package com.flowchat.app.data.terminal

import com.flowchat.app.domain.terminal.*
import com.flowchat.app.data.device.processGroupPresent
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
internal class ManagedCommandRegistry(
    private val prefix: String = "local-",
    private val trackCleanup: Boolean = false,
    private val groupPresent: (Int) -> Boolean? = ::processGroupPresent
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = linkedMapOf<String, Entry>()
    private val mutableState = MutableStateFlow<List<CommandSnapshot>>(emptyList())
    val state: StateFlow<List<CommandSnapshot>> = mutableState
    private var closed = false

    private class Entry(var snapshot: CommandSnapshot, val spec: CommandProcessSpec, val cleanup: CommandCleanupTracker?) {
        val startedNanos = System.nanoTime()
        var job: Job? = null
        @Volatile var cancelRequested = false
        @Volatile var process: Process? = null
        val processGroup = AtomicInteger(0)
    }

    @Synchronized
    fun start(command: String, environment: CommandEnvironment, cwd: String, timeoutSeconds: Int, spec: CommandProcessSpec,
        preparationMillis: Long? = null): CommandSnapshot {
        CommandValidation.validate(command, cwd, timeoutSeconds)
        check(!closed) { "Command service has closed." }
        check(entries.values.count { it.snapshot.isRunning || it.snapshot.cleanup?.blocksReuse == true } < 4) { "Four commands are running or awaiting cleanup verification. Stop or check one first." }
        check(entries.values.none { !it.snapshot.isRunning && it.snapshot.cleanup?.blocksReuse == true }) {
            "Previous command cleanup is unverified. Check its original connection before starting another command."
        }
        val id = prefix + UUID.randomUUID()
        val cleanup = if (trackCleanup) CommandCleanupTracker(id, groupPresent) else null
        val entry = Entry(CommandSnapshot(id, command, environment, cwd, cleanup = cleanup?.snapshot(false),
            preparationMillis = preparationMillis), spec, cleanup)
        entries[id] = entry
        while (entries.size > 24) {
            val old = entries.entries.firstOrNull { !it.value.snapshot.isRunning && it.value.snapshot.cleanup?.blocksReuse != true } ?: break
            entries.remove(old.key)
        }
        publish()
        entry.job = scope.launch {
            try {
                val exit = withTimeout(timeoutSeconds * 1000L) { runProcess(entry) }
                update(entry) { it.copy(status = if (entry.cancelRequested) CommandStatus.Cancelled else CommandStatus.Exited,
                    exitCode = exit, finishedAt = System.currentTimeMillis()) }
            } catch (error: TimeoutCancellationException) {
                event(entry, "timeout")
                update(entry) { it.copy(status = CommandStatus.TimedOut, error = "Command timed out.", finishedAt = System.currentTimeMillis()) }
            } catch (error: CancellationException) {
                update(entry) { it.copy(status = CommandStatus.Cancelled, finishedAt = System.currentTimeMillis()) }
            } catch (error: Exception) {
                update(entry) { it.copy(status = CommandStatus.Failed, error = error.message ?: "Command failed.", finishedAt = System.currentTimeMillis()) }
            }
        }
        entry.job?.invokeOnCompletion {
            if (entry.snapshot.isRunning) update(entry) { snapshot -> snapshot.copy(status = CommandStatus.Cancelled, finishedAt = System.currentTimeMillis()) }
            refreshCleanup(entry)
        }
        return entry.snapshot
    }

    @Synchronized fun read(id: String): CommandSnapshot = requireNotNull(entries[id]) { "Command not found." }.let {
        refreshCleanup(it)
        it.snapshot
    }
    /** Keep exact evidence for a bounded request history even after terminal history is trimmed. */
    @Synchronized fun observer(id: String): () -> CommandSnapshot {
        val entry = requireNotNull(entries[id]) { "Command not found." }
        return { synchronized(this) { refreshCleanup(entry); entry.snapshot } }
    }
    @Synchronized fun activeResourceCount(): Int {
        entries.values.forEach(::refreshCleanup)
        return entries.values.count { it.snapshot.isRunning || it.snapshot.cleanup?.blocksReuse == true }
    }
    @Synchronized fun cancel(id: String): CommandSnapshot {
        val entry = requireNotNull(entries[id]) { "Command not found." }
        if (entry.snapshot.isRunning) {
            entry.cancelRequested = true
            event(entry, "cancel_requested")
            update(entry) { it.copy(cleanup = it.cleanup?.copy(state = CommandCleanupState.Releasing)) }
            entry.job?.cancel()
        }
        return entry.snapshot
    }

    /** Request owned-group cleanup before host exit; unresolved evidence stays unverified. */
    override fun close() {
        synchronized(this) { closed = true }
        runBlocking {
            withTimeoutOrNull(3_000) { scope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runProcess(entry: Entry): Int = coroutineScope {
        ensureActive()
        if (entry.cancelRequested) throw CancellationException()
        val marker = "__FC_PID_${UUID.randomUUID().toString().replace("-", "")}:"
        val groupArgv = if (entry.spec.processGroup) OwnedProcessGroup.argv(entry.spec.argv, marker, reportExit = true) else entry.spec.argv
        val argv = if (entry.spec.root) listOf("su", "-c", groupArgv.joinToString(" ", transform = ::shellQuote)) else groupArgv
        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        entry.spec.directory?.let(builder::directory)
        builder.environment().putAll(entry.spec.environment)
        val process = builder.start()
        event(entry, "process_started")
        if (entry.cleanup != null) entry.process = process
        entry.cleanup?.starting()
        if (!entry.spec.processGroup) process.outputStream.close()
        // A cancellation immediately after ProcessBuilder.start must still consume the
        // startup identity. Otherwise cleanup remains unknown even after the watchdog exits.
        val reader = launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val frames = OwnedProcessOutput(marker, { appendOutput(entry, it) }, { event(entry, "target_exited", it) })
            try {
                process.inputStream.bufferedReader().use { input ->
                if (entry.spec.processGroup) {
                    val first = input.readLine().orEmpty()
                    if (first.startsWith(marker)) {
                        val group = first.removePrefix(marker).trim().toIntOrNull() ?: 0
                        entry.processGroup.set(group)
                        entry.cleanup?.identify(group)
                        event(entry, "group_identified", group)
                    }
                    else appendOutput(entry, first + "\n")
                }
                val buffer = CharArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) { event(entry, "output_eof"); break }
                    frames.accept(String(buffer, 0, count))
                }
            }
            } finally { frames.finish(); event(entry, "reader_finished") }
        }
        try {
            val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
            event(entry, "wrapper_exited", code)
            // Also releases the lifetime watchdog before waiting for the output EOF.
            process.outputStream.close()
            event(entry, "lifetime_closed")
            // A detached child must not hold the parent's output pipe open indefinitely.
            if (withTimeoutOrNull(2_000) { reader.join(); true } != true) event(entry, "output_drain_timeout")
            code
        } catch (error: CancellationException) {
            event(entry, if (error is TimeoutCancellationException) "execution_deadline" else "execution_cancelled")
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { process.outputStream.close() }
                event(entry, "lifetime_closed")
                // Give the exact startup marker a chance to arrive after an early cancellation.
                // This wait is bounded; no marker remains unknown rather than claiming release.
                if (entry.cleanup != null && entry.processGroup.get() <= 1) withTimeoutOrNull(500) { reader.join() }
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
                if (entry.cleanup != null && runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
                    entry.cleanup.processExited()
                    event(entry, "process_reaped", process.exitValue())
                }
                runCatching { process.inputStream.close() }
                reader.cancel()
                event(entry, "cleanup_finished")
            }
        }
    }

    private fun appendOutput(entry: Entry, text: String) = update(entry) {
        val combined = it.output + text
        val next = it.nextOffset + text.length
        val tail = combined.takeLast(MaxOutputCharacters)
        it.copy(output = tail, outputOffset = next - tail.length, nextOffset = next,
            lifecycle = if (it.lifecycle.any { event -> event.phase == "first_output" }) it.lifecycle else
                it.lifecycle + CommandLifecycleEvent("first_output", (System.nanoTime() - entry.startedNanos) / 1_000_000))
    }
    private fun event(entry: Entry, phase: String, value: Int? = null) = update(entry) {
        if (it.lifecycle.any { event -> event.phase == phase }) it else it.copy(lifecycle =
            (it.lifecycle + CommandLifecycleEvent(phase, (System.nanoTime() - entry.startedNanos) / 1_000_000, value)).takeLast(24))
    }
    @Synchronized private fun refreshCleanup(entry: Entry) {
        // A process that outlived the bounded teardown wait can still be verified later,
        // using its original handle rather than a PID that may have been reused.
        if (entry.cleanup != null && entry.process?.isAlive == false) entry.cleanup.processExited()
        val observed = entry.cleanup?.snapshot(!entry.snapshot.isRunning) ?: return
        val cleanup = if (entry.snapshot.isRunning && entry.cancelRequested) observed.copy(state = CommandCleanupState.Releasing) else observed
        if (cleanup.state == CommandCleanupState.Released) entry.process = null
        if (cleanup != entry.snapshot.cleanup) update(entry) { it.copy(cleanup = cleanup) }
    }
    @Synchronized private fun update(entry: Entry, block: (CommandSnapshot) -> CommandSnapshot) { entry.snapshot = block(entry.snapshot); publish() }
    private fun publish() { mutableState.value = entries.values.map { it.snapshot }.reversed() }

    companion object {
        const val MaxOutputCharacters = 65_536
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
