package com.flowchat.app.presentation.overlay

import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.terminal.CommandSnapshot
import com.flowchat.app.presentation.chat.ChatUiState
import com.flowchat.app.presentation.chat.DeviceActionConfirmationUi
import com.flowchat.app.domain.device.DeviceTaskControl
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FloatingAssistantState(
    val conversationId: String? = null,
    val assistantName: String = "Floryx",
    val assistantAvatarPath: String? = null,
    val modelName: String = "",
    val assistantText: String = "",
    val activeToolName: String? = null,
    val lastOpenedAppName: String? = null,
    val isStreaming: Boolean = false,
    val canChat: Boolean = false,
    val canRecognizeScreen: Boolean = false,
    val photoAnswerEnabled: Boolean = true,
    val errorMessage: String? = null,
    val progress: FloatingTaskProgress? = null,
    val pendingDeviceActionConfirmation: DeviceActionConfirmationUi? = null
) {
    companion object {
        fun from(state: ChatUiState, commands: List<CommandSnapshot> = emptyList()): FloatingAssistantState {
            val conversation = state.currentConversation
            val currentTurn = state.messages.drop(state.messages.indexOfLast { it.role == MessageRole.User }.coerceAtLeast(0))
            val assistantText = currentTurn
                .asReversed()
                .firstOrNull { it.role == MessageRole.Assistant && it.content.isNotBlank() }
                ?.content
                .orEmpty()
            val activeToolMessage = currentTurn
                .asReversed()
                .firstOrNull {
                    it.role == MessageRole.Tool &&
                        it.status == MessageStatus.Streaming
                }
            val activeTool = activeToolMessage?.content
                ?.lineSequence()
                ?.firstOrNull()
                ?.substringBefore(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val latestAssistant = currentTurn.lastOrNull { it.role == MessageRole.Assistant }
            val turnStartedAt = currentTurn.firstOrNull { it.role == MessageRole.User }?.createdAt
            // The caller scopes commands to this conversation. Never show a previous turn's command.
            val runningCommand = commands.firstOrNull {
                it.isRunning && turnStartedAt != null && it.createdAt >= turnStartedAt
            }
            val commandTool = activeTool in setOf("run_command", "read_command_output")
            val phase = when {
                state.deviceTaskPaused -> FloatingTaskPhase.Paused
                state.pendingDeviceActionConfirmation != null || state.usageAccessPermissionRequest != null -> FloatingTaskPhase.WaitingForUser
                state.isStreaming && commandTool && runningCommand != null -> FloatingTaskPhase.RunningCommand
                state.isStreaming && commandTool -> FloatingTaskPhase.PreparingCommand
                state.isStreaming && activeTool != null -> FloatingTaskPhase.UsingTool
                state.isStreaming && latestAssistant?.status == MessageStatus.Streaming && latestAssistant.content.isNotBlank() -> FloatingTaskPhase.Responding
                state.isStreaming -> FloatingTaskPhase.Thinking
                latestAssistant?.status == MessageStatus.Failed -> FloatingTaskPhase.Failed
                latestAssistant?.status in setOf(MessageStatus.Stopped, MessageStatus.Cancelled) -> FloatingTaskPhase.Stopped
                latestAssistant?.status == MessageStatus.Complete -> FloatingTaskPhase.Finished
                else -> FloatingTaskPhase.Ready
            }
            val startedAt = if (phase == FloatingTaskPhase.RunningCommand) runningCommand?.createdAt
                else activeToolMessage?.createdAt ?: latestAssistant?.createdAt ?: turnStartedAt
            val openedApp = currentTurn
                .asReversed()
                .firstOrNull { it.role == MessageRole.Tool && it.content.startsWith("open_app:") }
                ?.content
                ?.lineSequence()
                ?.firstOrNull()
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            return FloatingAssistantState(
                conversationId = conversation?.id,
                assistantName = conversation?.assistantName?.trim().takeUnless { it.isNullOrBlank() }
                    ?: conversation?.title?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Floryx",
                assistantAvatarPath = conversation?.assistantAvatarPath,
                modelName = conversation?.modelName.orEmpty(),
                assistantText = assistantText,
                activeToolName = activeTool.takeIf { state.isStreaming },
                lastOpenedAppName = openedApp,
                isStreaming = state.isStreaming,
                canChat = conversation != null,
                photoAnswerEnabled = state.appSettings.photoAnswerEnabled,
                canRecognizeScreen = state.appSettings.photoAnswerEnabled && (conversation != null || state.providers.any {
                    it.isEnabled && com.flowchat.app.domain.validation.ProviderConfigValidator.validate(it).isEmpty()
                }),
                errorMessage = state.errorMessage,
                progress = FloatingTaskProgress(phase, startedAt.takeIf { phase.isActive }),
                pendingDeviceActionConfirmation = state.pendingDeviceActionConfirmation
            )
        }
    }
}

sealed interface FloatingAssistantCommand {
    data class Send(val text: String) : FloatingAssistantCommand
    data class RespondToConfirmation(val conversationId: String, val requestId: String, val approved: Boolean) : FloatingAssistantCommand
    data object Stop : FloatingAssistantCommand
}

@Singleton
class FloatingAssistantBridge @Inject constructor(
    val deviceTaskControl: DeviceTaskControl
) {
    constructor() : this(DeviceTaskControl())
    private val mutableState = MutableStateFlow(FloatingAssistantState())
    val state: StateFlow<FloatingAssistantState> = mutableState.asStateFlow()

    private val mutableCommands = MutableSharedFlow<FloatingAssistantCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<FloatingAssistantCommand> = mutableCommands.asSharedFlow()

    private val mutableScreenQuestion = MutableStateFlow(ScreenQuestionState())
    val screenQuestion: StateFlow<ScreenQuestionState> = mutableScreenQuestion.asStateFlow()

    private val mutableAnswerBanner = MutableStateFlow<AnswerBannerState?>(null)
    val answerBanner: StateFlow<AnswerBannerState?> = mutableAnswerBanner.asStateFlow()

    internal fun beginAnswerBanner(taskId: String, owner: String) {
        mutableAnswerBanner.value = AnswerBannerState(taskId, owner)
    }

    internal fun updateAnswerBanner(taskId: String, text: String, streaming: Boolean, error: Boolean = false) {
        mutableAnswerBanner.update { current ->
            if (current?.taskId == taskId) current.copy(text = text.take(16_000), isStreaming = streaming, isError = error) else current
        }
    }

    fun dismissAnswerBanner() { mutableAnswerBanner.update { it?.copy(dismissed = true) } }
    fun clearAnswerBanner() { mutableAnswerBanner.value = null }

    internal fun updateScreenQuestion(value: ScreenQuestionState) { mutableScreenQuestion.value = value }

    fun clearScreenQuestion() {
        if (screenQuestion.value.phase != ScreenQuestionPhase.Capturing) mutableScreenQuestion.value = ScreenQuestionState()
    }

    private val mutableUiInteractionCount = MutableStateFlow(0)
    val uiInteractionCount: StateFlow<Int> = mutableUiInteractionCount.asStateFlow()

    private val mutableAppInForeground = MutableStateFlow(false)
    val appInForeground: StateFlow<Boolean> = mutableAppInForeground.asStateFlow()

    private val mutableAssistantPanels = MutableStateFlow(0)
    val assistantPanels: StateFlow<Int> = mutableAssistantPanels.asStateFlow()

    /** Hide duplicate floating output without declaring a user takeover of the full chat. */
    fun showAssistantPanel(): java.io.Closeable {
        mutableAssistantPanels.update { it + 1 }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return java.io.Closeable {
            if (closed.compareAndSet(false, true)) mutableAssistantPanels.update { (it - 1).coerceAtLeast(0) }
        }
    }

    fun publish(state: FloatingAssistantState) {
        mutableState.value = state
    }

    fun send(text: String) {
        clearScreenQuestion()
        text.trim().takeIf { it.isNotEmpty() }?.let { value ->
            mutableCommands.tryEmit(FloatingAssistantCommand.Send(value))
        }
    }

    fun stop() {
        clearScreenQuestion()
        mutableCommands.tryEmit(FloatingAssistantCommand.Stop)
    }

    fun respondToConfirmation(conversationId: String, requestId: String, approved: Boolean) {
        mutableCommands.tryEmit(FloatingAssistantCommand.RespondToConfirmation(conversationId, requestId, approved))
    }

    /** Keep the output visible while touchable controls yield for the entire device operation. */
    suspend fun <T> withExternalUiAction(action: suspend () -> T): T {
        mutableUiInteractionCount.update { it + 1 }
        return try {
            action()
        } finally {
            mutableUiInteractionCount.update { it - 1 }
        }
    }

    private val mutableScreenCaptureCount = MutableStateFlow(0)
    val screenCaptureCount = mutableScreenCaptureCount.asStateFlow()
    private val mutableDisplayCaptureCount = MutableStateFlow(0)
    val displayCaptureCount = mutableDisplayCaptureCount.asStateFlow()

    /** Also needed on Android 14+ when a window surface has unmappable shadow insets. */
    suspend fun <T> withDisplayCapture(action: suspend () -> T): T {
        mutableDisplayCaptureCount.update { it + 1 }
        return try {
            kotlinx.coroutines.delay(120)
            action()
        } finally { mutableDisplayCaptureCount.update { it - 1 } }
    }

    suspend fun <T> withScreenCapture(action: suspend () -> T): T {
        mutableScreenCaptureCount.update { it + 1 }
        return try {
            // Full-display capture on Android 11–13 must not include Floryx's floating output.
            kotlinx.coroutines.delay(120)
            action()
        } finally { mutableScreenCaptureCount.update { it - 1 } }
    }

    fun setAppInForeground(inForeground: Boolean) {
        deviceTaskControl.setAppInForeground(inForeground)
        mutableAppInForeground.value = inForeground
    }
}
