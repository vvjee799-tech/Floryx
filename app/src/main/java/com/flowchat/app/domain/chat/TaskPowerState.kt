package com.flowchat.app.domain.chat

enum class TaskPowerWork { Execution, Model, Finalizing, WaitingForUser, WaitingForDevice, WaitingForCleanup }

data class TaskPowerOwner(val conversationId: String, val runId: String, val work: TaskPowerWork) {
    val needsPower: Boolean get() = work in setOf(TaskPowerWork.Execution, TaskPowerWork.Model, TaskPowerWork.Finalizing)
}

enum class TaskServiceState { Running, Stopping, Destroyed }
enum class TaskPowerFailure { AcquireFailed, ReleaseFailed, ReadFailed }

/** In-process evidence for this service generation and its exact WakeLock, not other task resources. */
data class TaskPowerState(
    val serviceId: String,
    val serviceState: TaskServiceState,
    val owners: Set<TaskPowerOwner> = emptySet(),
    val recentOwners: Set<TaskPowerOwner> = emptySet(),
    val leaseHeld: Boolean? = null,
    val otherUnverifiedLeases: Int = 0,
    val failure: TaskPowerFailure? = null,
    val checkedAtMillis: Long? = null
)
