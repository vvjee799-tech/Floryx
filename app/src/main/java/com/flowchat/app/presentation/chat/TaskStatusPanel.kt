package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R

internal data class TaskStatusActions(
    val stop: () -> Unit = {}, val continueTask: () -> Unit = {}, val end: () -> Unit = {},
    val resumeDevice: () -> Unit = {}, val powerRecheck: () -> Unit = {},
    val displayRecheck: () -> Unit = {}, val rootRecheck: () -> Unit = {},
    val selectConversation: (String) -> Unit = {}, val cancelQueued: (String, String) -> Unit = { _, _ -> },
    val continueRemaining: (String) -> Unit = {}, val extendBudget: () -> Unit = {}
)

@Composable
internal fun taskStatusText(status: TaskPresentation): String = stringResource(status.titleResource())

internal fun TaskPresentation.titleResource(): Int = taskStatusResource(this)

private fun taskStatusResource(status: TaskPresentation): Int = when {
    status.commandTimedOut && status.phase == TaskDisplayPhase.RecoveryRequired -> R.string.task_status_command_timeout
    status.resourceIssue && status.phase in setOf(TaskDisplayPhase.Ready, TaskDisplayPhase.Finished) -> R.string.task_status_resource_issue
    else -> when (status.phase) {
        TaskDisplayPhase.Ready -> R.string.assistant_ready
        TaskDisplayPhase.Thinking -> R.string.task_status_thinking
        TaskDisplayPhase.Responding -> R.string.task_status_responding
        TaskDisplayPhase.PreparingCommand -> R.string.task_status_command
        TaskDisplayPhase.RunningCommand -> R.string.task_status_command
        TaskDisplayPhase.UsingTool -> when (status.activeTool) {
            "workspace_read", "workspace_stat", "workspace_search", "workspace_inspect_execution", "read_attachment" -> R.string.task_status_reading
            "workspace_list", "workspace_status" -> R.string.task_status_files
            else -> R.string.task_status_operating
        }
        TaskDisplayPhase.WaitingForUser -> R.string.task_status_waiting_user
        TaskDisplayPhase.WaitingForDevice -> R.string.task_status_waiting_device
        TaskDisplayPhase.Paused -> R.string.task_status_paused
        TaskDisplayPhase.RecoveryRequired -> R.string.task_status_uncertain
        TaskDisplayPhase.Interrupted -> R.string.task_status_interrupted
        TaskDisplayPhase.UserStopped -> R.string.task_status_user_stopped
        TaskDisplayPhase.ModelTimedOut -> R.string.task_status_model_timeout
        TaskDisplayPhase.Failed -> R.string.task_status_failed
        TaskDisplayPhase.Finished -> R.string.task_status_reply_finished
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TaskStatusPanel(state: ChatUiState, actions: TaskStatusActions) {
    val status = TaskPresentation.from(state)
    if (!status.showChatStatus) return
    val ownerId = state.currentConversation?.id
    val recovery = state.taskRecovery
    val idle = !state.isStreaming && state.taskStopReason !in setOf("user_stopped", "ended_by_user")
    val budgetExhausted = state.taskBudgetPaused && state.taskBudget?.isExhausted == true
    val canResume = idle && (state.deviceTaskPaused || recovery != null || (state.interruptedTask && !budgetExhausted))
    val canExtendBudget = idle && budgetExhausted && recovery == null && !state.deviceTaskPaused
    val canEnd = idle && (state.interruptedTask || recovery != null || state.deviceTaskPaused || state.taskBudgetPaused || state.taskEffectsRetained)
    val queued = state.deviceQueue.firstOrNull { it.ownerConversationId == ownerId }
    val showStop = state.isStreaming
    val showRecheck = status.resourceIssue && !state.isStreaming
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("task-status")) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            val statusText = if (status.phase == TaskDisplayPhase.Paused && budgetExhausted) {
                stringResource(R.string.task_status_budget_exhausted)
            } else {
                taskStatusText(status)
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            if (!state.isStreaming) {
                state.errorMessage?.let {
                    Text(localizedChatError(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            // This area scrolls with the answer, including at large font sizes and with the IME open.
            if (showStop || canResume || canExtendBudget || canEnd || showRecheck || queued != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showStop) TextButton(onClick = actions.stop, modifier = Modifier.testTag("task-status-stop")) {
                        Text(stringResource(R.string.stop))
                    }
                    if (canResume) TextButton(onClick = {
                        if (recovery?.canAcknowledge == true) actions.continueRemaining(recovery.runId)
                        else if (state.deviceTaskPaused) actions.resumeDevice() else actions.continueTask()
                    }, modifier = Modifier.testTag("task-status-resume")) {
                        Text(stringResource(when {
                            recovery?.canAcknowledge == true -> R.string.task_status_skip_uncertain
                            recovery != null -> R.string.task_status_check_again
                            else -> R.string.device_task_resume
                        }))
                    }
                    if (canExtendBudget) TextButton(onClick = actions.extendBudget, modifier = Modifier.testTag("task-status-extend-budget")) {
                        Text(stringResource(R.string.task_status_extend_budget))
                    }
                    if (canEnd) TextButton(onClick = actions.end, modifier = Modifier.testTag("task-status-end")) {
                        Text(stringResource(R.string.device_task_end))
                    }
                    if (showRecheck) TextButton(onClick = {
                        actions.powerRecheck(); actions.displayRecheck(); actions.rootRecheck()
                    }, modifier = Modifier.testTag("task-status-recheck")) {
                        Text(stringResource(R.string.task_power_recheck))
                    }
                    if (queued != null) TextButton(onClick = {
                        actions.cancelQueued(queued.ownerConversationId, queued.runId)
                    }, modifier = Modifier.testTag("task-status-cancel-queued")) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
