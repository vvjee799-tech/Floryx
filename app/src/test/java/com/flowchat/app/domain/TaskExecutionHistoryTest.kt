package com.flowchat.app.domain

import com.flowchat.app.domain.chat.ChatRequestFactory
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TaskExecutionHistoryTest {
    private val conversation = Conversation(id = "c", providerId = "p", modelName = "test")
    private fun message(role: MessageRole, content: String, status: MessageStatus = MessageStatus.Complete) =
        Message(conversationId = "c", role = role, content = content, status = status)

    @Test fun stoppedTaskKeepsConfirmedFindingsAndToolEvidenceInNextRequest() {
        val call = ChatToolCall("old-call", "run_command", """{"command":"check installed component"}""")
        val evidence = TaskExecutionHistory.completed(TaskExecutionHistory.pending(call), "exit=0; component already installed")
        val history = listOf(
            message(MessageRole.User, "检查网络设置"),
            message(MessageRole.Assistant, "IPv6 已关闭，无需再改。")
                .copy(replyPhase = ReplyPhase.Commentary, executionContext = TaskExecutionHistory.encode(listOf(evidence)),
                    reasoningContent = "PRIVATE_REASONING_DO_NOT_REPLAY"),
            message(MessageRole.Assistant, "已停止本轮处理。", MessageStatus.Stopped),
            message(MessageRole.User, "不要再重复查组件，继续剩余部分")
        )
        val request = ChatRequestFactory.create(conversation, history)
        val context = request.messages.single { it.content.orEmpty().startsWith(TaskExecutionHistory.ContextPrefix) }.content!!
        assertTrue(context.contains("IPv6 已关闭"))
        assertTrue(context.contains("component already installed"))
        assertTrue(context.contains("check installed component"))
        assertTrue(context.contains("Stopped"))
        assertFalse(context.contains("PRIVATE_REASONING"))
        assertTrue(request.messages.none { it.role == "tool" || it.toolCalls.isNotEmpty() })
        assertEquals("不要再重复查组件，继续剩余部分", request.messages.last().content)
    }

    @Test fun interruptedActionIsUnconfirmedRatherThanFalselyCompleted() {
        val call = ChatToolCall("tap", "tap_ui_element", """{"index":2,"screen_id":"old"}""")
        val context = TaskExecutionHistory.context(listOf(message(MessageRole.Assistant, "", MessageStatus.Stopped)
            .copy(executionContext = TaskExecutionHistory.encode(listOf(TaskExecutionHistory.interrupted(TaskExecutionHistory.pending(call)))))))!!
        assertTrue(context.contains("interrupted_unconfirmed"))
        assertTrue(context.contains("may have taken effect"))
        assertFalse(context.contains("outcome: returned"))
    }

    @Test fun legacyStoppedCommentaryIsRecoveredWithoutInventingLostResults() {
        val context = TaskExecutionHistory.context(listOf(
            message(MessageRole.Assistant, "已确认组件存在，不需要重新安装。").copy(replyPhase = ReplyPhase.Commentary),
            message(MessageRole.Tool, "run_command", MessageStatus.Complete),
            message(MessageRole.Assistant, "下一步核对其他配置", MessageStatus.Stopped).copy(replyPhase = ReplyPhase.Commentary)
        ))!!
        assertTrue(context.contains("已确认组件存在"))
        assertTrue(context.contains("full arguments/result were not stored"))
        assertTrue(context.contains("下一步核对其他配置"))
        assertTrue(context.contains("Stopped"))
    }

    @Test fun failedResultsAndExplicitCorrectionsSurviveASecondInterruption() {
        val call = ChatToolCall("tap", "tap_ui_element", "{}")
        val failed = TaskExecutionHistory.completed(TaskExecutionHistory.pending(call), "failed: stale_screen")
        val history = listOf(message(MessageRole.User, "检查设置"),
            message(MessageRole.Assistant, "", MessageStatus.Stopped).copy(executionContext = TaskExecutionHistory.encode(listOf(failed))),
            message(MessageRole.User, "不要再改 IPv6，刚才已经关了"),
            message(MessageRole.Assistant, "已停止", MessageStatus.Stopped), message(MessageRole.User, "继续"))
        val text = ChatRequestFactory.create(conversation, history).messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(text.contains("failed: stale_screen"))
        assertTrue(text.contains("不要再改 IPv6，刚才已经关了"))
        assertEquals(2, Regex("Segment status: Stopped").findAll(text).count())
    }

    @Test fun factoryPreservesUnsummarizedHistorySoCompactionFailureCannotEraseTheTask() {
        val history = (1..20).flatMap { n -> listOf(message(MessageRole.User, "Task $n " + "x".repeat(10_000)),
            message(MessageRole.Assistant, "Answer $n " + "y".repeat(10_000))) } +
            listOf(message(MessageRole.User, "Latest task"), message(MessageRole.Assistant, "Stopped marker", MessageStatus.Stopped))
        val request = ChatRequestFactory.create(conversation, history)
        assertTrue(request.messages.drop(1).sumOf { it.content.orEmpty().length } > 120_000)
        assertEquals("user", request.messages[1].role)
        assertTrue(request.messages.any { it.content == "Latest task" })
        assertTrue(request.messages.last().content.orEmpty().contains("Stopped marker"))
        assertTrue(request.messages.any { it.content.orEmpty().startsWith("Task 1 ") })
    }

    @Test fun longOutputsKeepEdgesAndDeclareOmissionsAndOtherConversationsStayPrivate() {
        val result = "FIRST_FINDING " + "x".repeat(30_000) + " LAST_FINDING"
        val step = TaskExecutionHistory.completed(TaskExecutionHistory.pending(ChatToolCall("x", "run_command", "{}")), result)
        assertTrue(step.result.length <= 12_000)
        assertTrue(step.result.contains("FIRST_FINDING") && step.result.contains("LAST_FINDING"))
        assertTrue(step.result.contains("omitted"))
        val request = ChatRequestFactory.create(conversation, listOf(
            message(MessageRole.User, "Other chat secret").copy(conversationId = "other"), message(MessageRole.User, "Here")))
        assertFalse(request.messages.any { it.content.orEmpty().contains("Other chat secret") })
    }

    @Test fun previousCompletedAnswersKeepTheirTextWithoutReplayingThinkingTranscripts() {
        val request = ChatRequestFactory.create(conversation, listOf(message(MessageRole.User, "Old task"),
            message(MessageRole.Assistant, "Verified answer").copy(reasoningContent = "PRIVATE_DELIBERATION"),
            message(MessageRole.User, "Follow up")))
        assertTrue(request.messages.any { it.content == "Verified answer" })
        assertTrue(request.messages.all { it.reasoningContent == null })
    }
}
