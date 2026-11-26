package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TaskPresentationTest {
    private val conversation = Conversation(id = "current", providerId = "p", modelName = "m")
    private fun ended() = ChatUiState(currentConversation = conversation, messages = listOf(
        Message(conversationId = conversation.id, role = MessageRole.User, content = "Check the file"),
        Message(conversationId = conversation.id, role = MessageRole.Assistant, content = "File information", status = MessageStatus.Complete)))

    @Test fun releasedPowerAndNormalCleanupLeaveTheAnswerWithoutAStatusCard() {
        val owner = TaskPowerOwner(conversation.id, "r", TaskPowerWork.Finalizing)
        val state = ended().copy(taskPower = TaskPowerState("s", TaskServiceState.Destroyed,
            recentOwners = setOf(owner), leaseHeld = false),
            executionDisplayCleanup = ExecutionDisplayCleanup("r", ExecutionDisplayState.Released),
            rootRequestCleanup = listOf(RootRequestCleanup("q", "r", RootRequestState.Released)))
        assertFalse(TaskPresentation.from(state).showStatus)
        assertEquals(TaskDisplayPhase.Finished, TaskPresentation.from(state).phase)
    }

    @Test fun uncertainCommandAndReleasedPowerProduceOneRecoveryProjection() {
        val state = ended().copy(taskRecovery = TaskRecoveryUi("r", 1, false, listOf("run_command")),
            taskStopReason = "command_timeout", taskRunState = "Stopped")
        val result = TaskPresentation.from(state)
        assertEquals(TaskDisplayPhase.RecoveryRequired, result.phase)
        assertTrue(result.commandTimedOut)
        assertTrue(result.showStatus)
    }

    @Test fun explicitUserStopOverridesPendingEffectsAndDoesNotClaimTheyWereVerified() {
        val state = ended().copy(taskStopReason = "user_stopped", interruptedTask = true,
            taskRecovery = TaskRecoveryUi("r", 2, false), verifiedTaskStepCount = 1)
        assertEquals(TaskDisplayPhase.UserStopped, TaskPresentation.from(state).phase)
        assertEquals(2, state.taskRecovery?.pendingCount)
    }

    @Test fun legacyStoppedRecordNeverBecomesAUserStopWithoutProvenance() {
        val state = ended().copy(taskRunState = "Stopped", taskStopReason = "Stopped", interruptedTask = true)
        assertEquals(TaskDisplayPhase.Interrupted, TaskPresentation.from(state).phase)
        assertEquals(TaskDisplayPhase.ModelTimedOut, TaskPresentation.from(state.copy(taskStopReason = "model_timeout")).phase)
    }

    @Test fun oldResourceProblemsStayOutOfAnUnrelatedConversation() {
        val old = TaskPowerOwner("old", "r", TaskPowerWork.Finalizing)
        val state = ended().copy(taskPower = TaskPowerState("s", TaskServiceState.Destroyed, recentOwners = setOf(old),
            leaseHeld = null, failure = TaskPowerFailure.ReadFailed, otherUnverifiedLeases = 1),
            otherRootRequestCleanupPending = true, otherExecutionDisplayCleanupPending = true)
        assertFalse(TaskPresentation.from(state).showStatus)
        val own = state.copy(taskPower = state.taskPower?.copy(recentOwners = setOf(old.copy(conversationId = conversation.id))))
        assertTrue(TaskPresentation.from(own).resourceIssue)
    }

    @Test fun recoveredToolFailureDoesNotReplaceASuccessfulFinalReply() {
        val state = ended().copy(messages = ended().messages.toMutableList().apply {
            add(1, Message(conversationId = conversation.id, role = MessageRole.Tool, content = "failed", status = MessageStatus.Failed))
        })
        assertEquals(TaskDisplayPhase.Finished, TaskPresentation.from(state).phase)
        assertFalse(TaskPresentation.from(state).showStatus)
    }

    @Test fun aPreviousRunsUnknownPowerDoesNotInterruptANewerTaskInTheSameConversation() {
        val state = ended().copy(taskRunId = "new-run", taskPower = TaskPowerState("s", TaskServiceState.Destroyed,
            leaseHeld = null, recentOwners = setOf(TaskPowerOwner(conversation.id, "old-run", TaskPowerWork.Finalizing))))
        assertFalse(TaskPresentation.from(state).showStatus)
    }

    @Test fun newRunIgnoresThePreviousRunsStopReason() {
        assertEquals(TaskDisplayPhase.Thinking, TaskPresentation.from(ended().copy(isStreaming = true, taskStopReason = "user_stopped")).phase)
    }

    @Test fun stoppedPartialReplyRetainsItsContentAndAddsTheCauseOnce() {
        val text = stoppedTaskReply("已读取文件头。", true, true)
        assertTrue(text.startsWith("已读取文件头。"))
        assertTrue(text.contains("按你的要求停止"))
        assertEquals(text, stoppedTaskReply(text, true, true))
        assertFalse(stoppedTaskReply("", true, false).contains("按你的要求"))
    }

    @Test fun runningPhasesHideChatStatusCardWhileKeepingOverlayStatus() {
        val runningStates = listOf(
            ended().copy(isStreaming = true),
            ended().copy(
                isStreaming = true,
                messages = ended().messages + Message(
                    conversationId = conversation.id,
                    role = MessageRole.Assistant,
                    content = "正在回答",
                    status = MessageStatus.Streaming
                )
            ),
            ended().copy(
                isStreaming = true,
                messages = ended().messages + Message(
                    conversationId = conversation.id,
                    role = MessageRole.Tool,
                    content = "run_command",
                    status = MessageStatus.Streaming
                )
            ),
            ended().copy(
                isStreaming = true,
                messages = ended().messages + Message(
                    conversationId = conversation.id,
                    role = MessageRole.Tool,
                    content = "workspace_read",
                    status = MessageStatus.Streaming
                )
            ),
            ended().copy(
                isStreaming = true,
                pendingDeviceActionConfirmation = DeviceActionConfirmationUi("run_command", "cmd", "msg")
            )
        )
        for (state in runningStates) {
            val presentation = TaskPresentation.from(state)
            assertTrue("Expected showStatus=true for ${presentation.phase}", presentation.showStatus)
            assertFalse("Expected showChatStatus=false for ${presentation.phase}", presentation.showChatStatus)
        }
    }

    @Test fun pausedStoppedFailedAndWaitingForDeviceHideChatStatusCardWhileKeepingStatusProjection() {
        val target = DeviceExecutionTarget("dev", conversation.id, true, PowerConnectionMode.Root, false, 1L)
        val states = listOf(
            ended().copy(isStreaming = true, deviceTaskProgress = DeviceTaskProgress(conversation.id, "r", target, DeviceTaskPhase.WaitingForDevice)),
            ended().copy(deviceTaskPaused = true),
            ended().copy(taskRecovery = TaskRecoveryUi("r", 1, false)),
            ended().copy(interruptedTask = true),
            ended().copy(taskStopReason = "user_stopped"),
            ended().copy(taskStopReason = "model_timeout"),
            ended().copy(errorMessage = "failed"),
            ended().copy(
                taskPower = TaskPowerState(
                    "s",
                    TaskServiceState.Destroyed,
                    recentOwners = setOf(TaskPowerOwner(conversation.id, "r", TaskPowerWork.Finalizing)),
                    leaseHeld = null
                )
            )
        )
        for (state in states) {
            val presentation = TaskPresentation.from(state)
            assertTrue("Expected showStatus=true for ${presentation.phase}", presentation.showStatus)
            assertFalse("Expected showChatStatus=false for ${presentation.phase}", presentation.showChatStatus)
        }
    }
}
