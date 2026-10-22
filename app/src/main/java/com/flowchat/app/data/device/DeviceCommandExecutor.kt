package com.flowchat.app.data.device

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class DeviceCommandResult(val success: Boolean, val output: String)

internal fun interface DeviceCommandExecutor {
    fun execute(command: List<String>): DeviceCommandResult
}

/** Only accepts commands assembled by FlowChat's typed device operations. */
internal class ProcessDeviceCommandExecutor(private val root: Boolean = false) : DeviceCommandExecutor {
    override fun execute(command: List<String>): DeviceCommandResult {
        require(command.isNotEmpty())
        val processCommand = if (root) {
            listOf("su", "-c", command.joinToString(" ", transform = ::quoteShellArgument))
        } else {
            command
        }
        return runCatching {
            val process = ProcessBuilder(processCommand).redirectErrorStream(true).start()
            process.outputStream.close()
            // Drain output concurrently so a stalled command or a Root prompt cannot block the timeout.
            val output = OutputReaders.submit<String> {
                process.inputStream.bufferedReader().use { reader ->
                    val captured = StringBuilder()
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
            try {
                val timeout = if (root) RootTimeoutSeconds else CommandTimeoutSeconds
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                    DeviceCommandResult(false, "Command timed out.")
                } else {
                    DeviceCommandResult(process.exitValue() == 0, output.get(1, TimeUnit.SECONDS))
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                output.cancel(true)
            }
        }.getOrElse { DeviceCommandResult(false, it.message ?: "Command failed.") }
    }

    companion object {
        private const val CommandTimeoutSeconds = 5L
        private const val RootTimeoutSeconds = 45L
        private const val MaxOutputCharacters = 1_048_576
        private val OutputReaders = Executors.newCachedThreadPool { task ->
            Thread(task, "flowchat-command-output").apply { isDaemon = true }
        }

        internal fun quoteShellArgument(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        fun isRootAvailable(): Boolean {
            val paths = System.getenv("PATH").orEmpty().split(':') +
                listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk")
            return paths.any { path -> File(path, "su").canExecute() }
        }
    }
}
