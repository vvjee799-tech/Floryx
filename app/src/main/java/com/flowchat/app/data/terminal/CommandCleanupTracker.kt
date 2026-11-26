package com.flowchat.app.data.terminal

import com.flowchat.app.domain.terminal.*

/** Serialized per command. Absence is latched so a reused group ID cannot rewrite history. */
internal class CommandCleanupTracker(
    private val commandId: String,
    private val groupPresent: (Int) -> Boolean?,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var started = false
    private var exited = false
    private var group: Int? = null
    private var released: CommandCleanup? = null

    @Synchronized fun starting() { started = true }
    @Synchronized fun identify(value: Int) { if (value > 1 && group == null) group = value }
    @Synchronized fun processExited() { exited = true }

    @Synchronized fun snapshot(finishing: Boolean): CommandCleanup {
        released?.let { return it }
        val reason = when {
            !finishing -> null
            !started -> null // Cancellation before dispatch or a failed ProcessBuilder.start.
            !exited -> CommandCleanupReason.ProcessActive
            group == null -> CommandCleanupReason.IdentityUnknown
            else -> when (runCatching { groupPresent(requireNotNull(group)) }.getOrNull()) {
                false -> null
                true -> CommandCleanupReason.GroupPresent
                null -> CommandCleanupReason.CheckFailed
            }
        }
        val state = when {
            !finishing -> CommandCleanupState.InUse
            reason != null -> CommandCleanupState.Unverified
            else -> CommandCleanupState.Released
        }
        return CommandCleanup(commandId, state, group, exited, reason,
            now().takeIf { finishing }, started).also { if (state == CommandCleanupState.Released) released = it }
    }
}
