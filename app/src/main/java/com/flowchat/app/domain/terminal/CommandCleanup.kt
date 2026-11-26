package com.flowchat.app.domain.terminal

import kotlinx.serialization.Serializable

@Serializable
enum class CommandCleanupState { InUse, Releasing, Unverified, Released }

@Serializable
enum class CommandCleanupReason { ProcessActive, IdentityUnknown, GroupPresent, CheckFailed, OriginalConnectionUnavailable, EvidenceUnavailable, StartResponsePending }

/** Evidence for this retained command only, not its RPC, service, display or power lease. */
@Serializable
data class CommandCleanup(
    val commandId: String,
    val state: CommandCleanupState = CommandCleanupState.InUse,
    val processGroup: Int? = null,
    val processExited: Boolean = false,
    val reason: CommandCleanupReason? = null,
    val checkedAtMillis: Long? = null,
    val processStarted: Boolean = false
) {
    val blocksReuse: Boolean get() = state == CommandCleanupState.Unverified || state == CommandCleanupState.Releasing
}

/** Losing the original process is uncertainty, never evidence of resource release. */
fun CommandSnapshot.cleanupUnavailable(reason: CommandCleanupReason): CommandSnapshot =
    if (cleanup?.state == CommandCleanupState.Released) this else copy(
        cleanup = (cleanup ?: CommandCleanup(id)).copy(state = CommandCleanupState.Unverified,
            reason = reason, checkedAtMillis = System.currentTimeMillis()))
