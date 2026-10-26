package com.flowchat.app.domain

import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.chat.TaskRecovery
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ReplyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecoveryTest {
    private fun message(role: MessageRole, content: String, status: MessageStatus = MessageStatus.Complete) =
        Message(conversationId = "conversation", role = role, content = content, status = status)

    @Test fun recoversPartialReplyAndPendingEvidenceWithoutRepeatingMutation() {
        val evidence = TaskExecutionHistory.encode(listOf(
            ExecutionStep("run_command", "check", "returned", "exit=0"),
            ExecutionStep("tap_ui_element", "button=2", "unconfirmed", "No result recorded")
        ))
        val original = message(MessageRole.Assistant, "已检查配置", MessageStatus.Streaming)
            .copy(reasoningContent = "private reasoning", executionContext = evidence)

        val recovered = TaskRecovery.recover(original, "继续检查设置", 123L)

        assertEquals(MessageStatus.Stopped, recovered.status)
        assertTrue(recovered.content.startsWith("已检查配置"))
        assertTrue(recovered.content.contains("执行结果未经确认"))
        assertTrue(recovered.executionContext!!.contains("interrupted_unconfirmed"))
        assertTrue(recovered.executionContext!!.contains("exit=0"))
        assertEquals("private reasoning", recovered.reasoningContent)
        assertEquals(123L, recovered.updatedAt)
        assertNotEquals(CheckpointCodec.fingerprint(listOf(original)), CheckpointCodec.fingerprint(listOf(recovered)))
        assertEquals(recovered, TaskRecovery.recover(recovered, "继续检查设置", 456L))
    }

    @Test fun completeCommentaryWithPendingActionIsRecoveredButFinishedAnswerIsNot() {
        val user = message(MessageRole.User, "Check Wi-Fi")
        val pending = message(MessageRole.Assistant, "Checking network", MessageStatus.Complete)
            .copy(replyPhase = ReplyPhase.Commentary, executionContext =
                TaskExecutionHistory.encode(listOf(ExecutionStep("tap", "{}", "unconfirmed", "No result"))))
        val recovered = TaskRecovery.recover(pending, user.content, 12L)
        val interrupted = listOf(user, recovered)

        assertEquals(MessageStatus.Stopped, recovered.status)
        assertTrue(TaskRecovery.canResume(interrupted))
        assertTrue(TaskRecovery.isRecoveredInterruption(interrupted))
        assertTrue(TaskRecovery.continueInstruction(interrupted)!!.contains("Verify whether any action"))
        assertFalse(TaskRecovery.canResume(interrupted + message(MessageRole.Assistant, "Task ended")))
        assertFalse(TaskRecovery.isRecoveredInterruption(interrupted + message(MessageRole.Assistant, "Task ended")))
    }

    @Test fun deliberatelyStoppedTurnRemainsContinuableWithoutRecoveredMarker() {
        val history = listOf(message(MessageRole.User, "检查文件"),
            message(MessageRole.Assistant, "部分结果", MessageStatus.Stopped))

        assertTrue(TaskRecovery.canResume(history))
        assertFalse(TaskRecovery.isRecoveredInterruption(history))
        assertTrue(TaskRecovery.continueInstruction(history)!!.contains("不要重复已完成的步骤"))
        assertEquals("检查文件", TaskRecovery.latestUserInstruction(history))
    }
}
