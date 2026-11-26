package com.flowchat.app.domain.device

/** This evidence covers the task's execution display, not all task or process resources. */
enum class ExecutionDisplayState { Creating, InUse, Releasing, Released, Unverified }

enum class ExecutionDisplayCleanupReason { DisplayPresent, IdentityUnknown, CreationPending, CheckFailed, ClosePending }

data class ExecutionDisplayCleanup(
    val ownerId: String,
    val state: ExecutionDisplayState,
    val displayId: Int? = null,
    val reason: ExecutionDisplayCleanupReason? = null,
    val checkedAtMillis: Long? = null
) {
    val blocksReuse: Boolean get() = state in setOf(ExecutionDisplayState.Releasing, ExecutionDisplayState.Unverified)
}
