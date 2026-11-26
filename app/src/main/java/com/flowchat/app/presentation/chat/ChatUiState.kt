package com.flowchat.app.presentation.chat

import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ContextWindowUsage
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.DeviceSafetyState

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val currentConversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val providers: List<ProviderConfig> = emptyList(),
    val input: String = "",
    val pendingAttachment: PendingAttachment? = null,
    val isAttachmentLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val contextWindow: ContextWindowUsage? = null,
    val deviceTaskPaused: Boolean = false,
    val deviceTaskProgress: DeviceTaskProgress? = null,
    val deviceQueue: List<DeviceTaskProgress> = emptyList(),
    val deviceOwner: DeviceTaskProgress? = null,
    val taskPower: com.flowchat.app.domain.chat.TaskPowerState? = null,
    val executionDisplayCleanup: com.flowchat.app.domain.device.ExecutionDisplayCleanup? = null,
    val otherExecutionDisplayCleanupPending: Boolean = false,
    val rootRequestCleanup: List<com.flowchat.app.domain.device.RootRequestCleanup> = emptyList(),
    val otherRootRequestCleanupPending: Boolean = false,
    val interruptedTask: Boolean = false,
    val taskRecovery: TaskRecoveryUi? = null,
    val taskRunId: String? = null,
    val taskRunState: String? = null,
    val taskStopReason: String? = null,
    val taskGoalStatus: com.flowchat.app.domain.chat.TaskGoalStatus? = null,
    val taskRecoveryReviewed: Boolean = false,
    val verifiedTaskStepCount: Int = 0,
    val taskBudget: com.flowchat.app.domain.chat.TaskExecutionBudgetSnapshot? = null,
    val taskBudgetPaused: Boolean = false,
    val verifiedPostconditionCount: Int = 0,
    val taskEffectsRetained: Boolean = false,
    val appSettings: AppSettings = AppSettings(),
    val memories: List<MemoryRecord> = emptyList(),
    val usageAccessPermissionRequest: UsageAccessPermissionRequestUi? = null,
    val shizukuState: ShizukuConnectionState = ShizukuConnectionState(),
    val accessibilityState: AccessibilityConnectionState = AccessibilityConnectionState(),
    val deviceSafetyState: DeviceSafetyState = DeviceSafetyState(),
    val pendingDeviceActionConfirmation: DeviceActionConfirmationUi? = null,
    val awaitingConfirmationConversationId: String? = null,
    val errorMessage: String? = null
)

data class TaskRecoveryUi(val runId: String, val pendingCount: Int, val canAcknowledge: Boolean,
    val pendingTools: List<String> = emptyList())

data class PendingAttachment(
    val name: String,
    val text: String = "",
    val imagePath: String? = null,
    val mimeType: String? = null,
    val sharedTextDestination: com.flowchat.app.domain.sharing.SharedTextDestination? = null
)

data class UsageAccessPermissionRequestUi(
    val toolName: String
)

data class DeviceActionConfirmationUi(
    val toolName: String,
    val title: String,
    val message: String,
    val requestId: String = java.util.UUID.randomUUID().toString()
)
