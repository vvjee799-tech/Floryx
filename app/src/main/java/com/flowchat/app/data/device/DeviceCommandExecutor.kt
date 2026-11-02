package com.flowchat.app.data.device

import com.flowchat.app.data.terminal.OwnedProcessGroup
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Future

internal data class DeviceCommandResult(val success: Boolean, val output: String, val unavailable: Boolean = false)

internal fun interface DeviceCommandExecutor {
    fun execute(command: List<String>): DeviceCommandResult
}

/** Only accepts commands assembled by FlowChat's typed device operations. */
internal class ProcessDeviceCommandExecutor(
    private val root: Boolean = false,
    private val processGroup: Boolean = false
) : DeviceCommandExecutor, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val processes = ConcurrentHashMap.newKeySet<RunningProcess>()

    private inner class RunningProcess(val process: Process) {
        val group = AtomicInteger(0)
        val stopped = AtomicBoolean(false)
        fun stop() {
            stopped.set(true)
            runCatching { process.outputStream.close() }
            val pid = group.get()
            if (processGroup && pid > 1) {
                val argv = listOf("/system/bin/kill", "-KILL", "--", "-$pid")
                val kill = if (root) listOf("su", "-c", argv.joinToString(" ", transform = ::quoteShellArgument)) else argv
                runCatching { ProcessBuilder(kill).start().let { child ->
                    if (!child.waitFor(1, TimeUnit.SECONDS)) child.destroyForcibly()
                    child.inputStream.close()
                    child.errorStream.close()
                    child.outputStream.close()
                } }
            }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    override fun close() {
        closed.set(true)
        processes.toList().forEach { it.stop() }
    }

    override fun execute(command: List<String>): DeviceCommandResult {
        require(command.isNotEmpty())
        if (closed.get()) return DeviceCommandResult(false, "Request cancelled.")
        val marker = "__FC_DEVICE_${UUID.randomUUID()}:"
        val grouped = if (processGroup) OwnedProcessGroup.argv(command, marker) else command
        val processCommand = if (root) {
            listOf("su", "-c", grouped.joinToString(" ", transform = ::quoteShellArgument))
        } else {
            grouped
        }
        return runCatching {
            val process = try {
                ProcessBuilder(processCommand).redirectErrorStream(true).start()
            } catch (error: IOException) {
                return DeviceCommandResult(false, error.message ?: "Command is unavailable.", unavailable = true)
            }
            val running = RunningProcess(process)
            processes.add(running)
            var output: Future<String>? = null
            try {
                if (closed.get()) running.stop()
                if (!processGroup) process.outputStream.close()
                // Drain output concurrently so a stalled command or a Root prompt cannot block the timeout.
                val reading = OutputReaders.submit<String> {
                    process.inputStream.bufferedReader().use { reader ->
                        val captured = StringBuilder()
                        if (processGroup) {
                            val first = reader.readLine().orEmpty()
                            if (first.startsWith(marker)) {
                                running.group.set(first.removePrefix(marker).trim().toIntOrNull() ?: 0)
                                if (running.stopped.get()) running.stop()
                            } else captured.append(first.take(MaxOutputCharacters)).append('\n')
                        }
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            val remaining = MaxOutputCharacters - captured.length
                            if (remaining > 0) captured.append(buffer, 0, minOf(count, remaining))
                        }
                        captured.toString().trim()
                    }
                }
                output = reading
                val timeout = if (root) RootTimeoutSeconds else CommandTimeoutSeconds
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                    DeviceCommandResult(false, "Command timed out.")
                } else {
                    val succeeded = process.exitValue() == 0
                    // Close the lifetime pipe before waiting for EOF: the watchdog itself is
                    // an owned child and must not keep the command's output pipe alive.
                    running.stop()
                    DeviceCommandResult(succeeded, reading.get(1, TimeUnit.SECONDS))
                }
            } finally {
                running.stop()
                process.inputStream.close()
                output?.cancel(true)
                processes.remove(running)
            }
        }.getOrElse { DeviceCommandResult(false, it.message ?: it.javaClass.simpleName) }
    }

    companion object {
        private const val CommandTimeoutSeconds = 5L
        private const val RootTimeoutSeconds = 45L
        private const val MaxOutputCharacters = 65_536
        private val OutputReaders = Executors.newCachedThreadPool { task ->
            Thread(task, "flowchat-command-output").apply { isDaemon = true }
        }

        internal fun quoteShellArgument(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

    }
}
