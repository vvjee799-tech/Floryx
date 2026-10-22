package com.flowchat.app.domain.terminal

import kotlinx.serialization.Serializable

@Serializable
enum class CommandEnvironment { Linux, Android }

@Serializable
enum class CommandStatus { Running, Exited, Failed, TimedOut, Cancelled }

@Serializable
data class CommandSnapshot(
    val id: String,
    val command: String,
    val environment: CommandEnvironment,
    val workingDirectory: String,
    val status: CommandStatus = CommandStatus.Running,
    val output: String = "",
    val outputOffset: Long = 0,
    val nextOffset: Long = 0,
    val exitCode: Int? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
) {
    val isRunning: Boolean get() = status == CommandStatus.Running

    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long =
        ((finishedAt ?: now) - createdAt).coerceAtLeast(0L)

    fun outputSince(offset: Long, limit: Int = 12_000): CommandSnapshot {
        val start = (offset - outputOffset).coerceIn(0, output.length.toLong()).toInt()
        val end = minOf(output.length, start + limit)
        return copy(output = output.substring(start, end), outputOffset = outputOffset + start, nextOffset = outputOffset + end)
    }
}

data class LinuxEnvironmentState(
    val ready: Boolean = false,
    val preparing: Boolean = false,
    val supported: Boolean = true,
    val version: String = "",
    val architecture: String = "",
    val developerToolsReady: Boolean = false,
    val error: String? = null
)

data class TerminalUiState(
    val linux: LinuxEnvironmentState = LinuxEnvironmentState(),
    val commands: List<CommandSnapshot> = emptyList()
)

object CommandValidation {
    fun validate(command: String, workingDirectory: String, timeoutSeconds: Int) {
        require(command.isNotBlank() && command.length <= 16_000 && '\u0000' !in command) { "Command must contain 1–16000 characters without NUL." }
        require(workingDirectory.startsWith('/') && workingDirectory.length <= 2048 && '\u0000' !in workingDirectory) { "An absolute working directory is required." }
        require(timeoutSeconds in 1..600) { "Command timeout must be between 1 and 600 seconds." }
    }
}
