package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.chat.TaskServiceState
import com.flowchat.app.domain.device.ExecutionDisplayState
import com.flowchat.app.domain.device.RootRequestState
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.terminal.CommandSnapshot

/** A projection of existing task evidence. It never owns or changes execution state. */
enum class TaskDisplayPhase {
    Ready, Thinking, Responding, PreparingCommand, RunningCommand, UsingTool,
    WaitingForUser, WaitingForDevice, Paused, RecoveryRequired, Interrupted,
    UserStopped, ModelTimedOut, Failed, Finished
}

data class TaskPresentation(
    val phase: TaskDisplayPhase,
    val activeTool: String? = null,
    val startedAt: Long? = null,
    val resourceIssue: Boolean = false,
    val commandTimedOut: Boolean = false
) {
    val showStatus: Boolean get() = phase !in setOf(TaskDisplayPhase.Ready, TaskDisplayPhase.Finished) || resourceIssue

    val showChatStatus: Boolean get() = false

    companion object {
        fun from(state: ChatUiState, commands: List<CommandSnapshot> = emptyList()): TaskPresentation {
            val messages = state.messages.filter { state.currentConversation == null || it.conversationId == state.currentConversation.id }
            val turn = messages.drop(messages.indexOfLast { it.role == MessageRole.User }.coerceAtLeast(0))
            val assistant = turn.lastOrNull { it.role == MessageRole.Assistant }
            val tool = turn.lastOrNull { it.role == MessageRole.Tool && it.status == MessageStatus.Streaming }
            val activeTool = tool?.content?.lineSequence()?.firstOrNull()?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }
            val start = turn.firstOrNull { it.role == MessageRole.User }?.createdAt
            val command = commands.firstOrNull { it.isRunning && start != null && it.createdAt >= start }
            val owner = state.currentConversation?.id
            val power = state.taskPower?.takeIf { p -> (p.owners + p.recentOwners).any {
                it.conversationId == owner && (state.taskRunId == null || it.runId == state.taskRunId)
            } }
            val ownResourceIssue = power?.let { it.failure != null || it.leaseHeld == null ||
                (it.serviceState == TaskServiceState.Destroyed && it.leaseHeld == true) } == true ||
                state.executionDisplayCleanup?.state == ExecutionDisplayState.Unverified ||
                state.rootRequestCleanup.any { it.state == RootRequestState.Unverified }
            // Old resources only interrupt a task that is actually trying to use the affected device path.
            val usingDevice = state.isStreaming && (state.deviceTaskProgress?.phase == DeviceTaskPhase.WaitingForCleanup ||
                (state.deviceTaskProgress?.target?.backgroundExecution == true && activeTool in setOf(
                    "open_app", "observe_screen", "tap_ui_element", "tap_ui_target", "swipe_screen", "input_text", "paste_focused_text")))
            val resourceIssue = ownResourceIssue || usingDevice &&
                (state.otherExecutionDisplayCleanupPending || state.otherRootRequestCleanupPending)
            val phase = when {
                state.isStreaming && (state.pendingDeviceActionConfirmation != null || state.usageAccessPermissionRequest != null) -> TaskDisplayPhase.WaitingForUser
                state.isStreaming && state.deviceTaskProgress?.phase == DeviceTaskPhase.WaitingForDevice -> TaskDisplayPhase.WaitingForDevice
                state.isStreaming && activeTool in setOf("run_command", "read_command_output") ->
                    if (command != null) TaskDisplayPhase.RunningCommand else TaskDisplayPhase.PreparingCommand
                state.isStreaming && activeTool != null -> TaskDisplayPhase.UsingTool
                state.isStreaming && assistant?.status == MessageStatus.Streaming && assistant.content.isNotBlank() -> TaskDisplayPhase.Responding
                state.isStreaming -> TaskDisplayPhase.Thinking
                state.taskStopReason == "user_stopped" || state.taskStopReason == "ended_by_user" -> TaskDisplayPhase.UserStopped
                state.taskStopReason == "model_timeout" -> TaskDisplayPhase.ModelTimedOut
                state.taskRecovery != null -> TaskDisplayPhase.RecoveryRequired
                state.deviceTaskPaused || state.taskBudgetPaused -> TaskDisplayPhase.Paused
                state.errorMessage != null || assistant?.status == MessageStatus.Failed ||
                    state.taskStopReason in setOf("model_failed", "execution_failed") -> TaskDisplayPhase.Failed
                state.interruptedTask || state.taskRunState == "Interrupted" ||
                    assistant?.status in setOf(MessageStatus.Stopped, MessageStatus.Cancelled) -> TaskDisplayPhase.Interrupted
                assistant?.status == MessageStatus.Complete -> TaskDisplayPhase.Finished
                else -> TaskDisplayPhase.Ready
            }
            return TaskPresentation(phase, activeTool.takeIf { state.isStreaming },
                (if (phase == TaskDisplayPhase.RunningCommand) command?.createdAt else tool?.createdAt ?: assistant?.createdAt ?: start)
                    .takeIf { state.isStreaming }, resourceIssue, state.taskStopReason == "command_timeout")
        }
    }
}

fun modelFailureReason(message: String): String = if (
    message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true) || message.contains("超时")
) "model_timeout" else "model_failed"

internal fun stoppedTaskReply(content: String, chinese: Boolean, userStopped: Boolean): String {
    val ending = if (userStopped) {
        if (chinese) "已按你的要求停止。已有内容已保留，后续步骤未继续执行。"
        else "Stopped at your request. Existing results were kept; remaining steps were not continued."
    } else {
        if (chinese) "处理已中断。已有内容已保留，任务尚未完成。"
        else "Processing was interrupted. Existing results were kept; the task is unfinished."
    }
    return if (content.endsWith(ending)) content else listOf(content, ending).filter { it.isNotBlank() }.joinToString("\n\n")
}
