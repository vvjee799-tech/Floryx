package com.flowchat.app.presentation.chat

import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionState

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val currentConversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val providers: List<ProviderConfig> = emptyList(),
    val input: String = "",
    val pendingAttachment: PendingAttachment? = null,
    val isAttachmentLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val deviceTaskPaused: Boolean = false,
    val appSettings: AppSettings = AppSettings(),
    val memories: List<MemoryRecord> = emptyList(),
    val usageAccessPermissionRequest: UsageAccessPermissionRequestUi? = null,
    val shizukuState: ShizukuConnectionState = ShizukuConnectionState(),
    val accessibilityState: AccessibilityConnectionState = AccessibilityConnectionState(),
    val pendingDeviceActionConfirmation: DeviceActionConfirmationUi? = null,
    val errorMessage: String? = null
)

data class PendingAttachment(
    val name: String,
    val text: String = "",
    val imagePath: String? = null,
    val mimeType: String? = null
)

data class UsageAccessPermissionRequestUi(
    val toolName: String
)

data class DeviceActionConfirmationUi(
    val toolName: String,
    val title: String,
    val message: String
)
