package com.flowchat.app.domain.device

enum class RootRequestState { InUse, Releasing, Released, Unverified }
enum class RootRequestCleanupReason { RpcPending, FinishPending, CheckFailed, RequestActive, CommandsPresent, IdentityUnknown, OriginalConnectionUnavailable }

/** Process-local evidence, scoped to one request in one pinned Root connection. */
data class RootRequestCleanup(
    val requestId: String,
    val ownerId: String,
    val state: RootRequestState = RootRequestState.InUse,
    val reason: RootRequestCleanupReason? = null,
    val processGroups: List<Int> = emptyList(),
    val checkedAtMillis: Long? = null
) {
    val blocksReuse: Boolean get() = state == RootRequestState.Releasing || state == RootRequestState.Unverified
}
