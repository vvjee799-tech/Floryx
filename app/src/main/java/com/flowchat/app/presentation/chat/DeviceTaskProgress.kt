package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.device.DeviceExecutionTarget

enum class DeviceTaskPhase {
    Planning, WaitingForModel, WaitingForDevice, Operating, WaitingForUser, WaitingForCleanup, Paused, CleaningUp, Ended
}

data class DeviceTaskProgress(
    val ownerConversationId: String,
    val runId: String,
    val target: DeviceExecutionTarget,
    val phase: DeviceTaskPhase,
    val queueOrder: Long? = null,
    val ownsDevice: Boolean = false,
    val executionDisplayOwnerId: String? = null,
    val canCorrectCalendarTime: Boolean = false
)
