package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatMessageGroupingTest {
    private fun assistant(content: String = "", reasoning: String = "", status: MessageStatus = MessageStatus.Complete, duration: Long = 100) =
        Message(conversationId = "chat", role = MessageRole.Assistant, content = content, reasoningContent = reasoning, status = status, reasoningDurationMillis = duration)
    private fun user(content: String) = Message(conversationId = "chat", role = MessageRole.User, content = content)

    @Test fun waitingThoughtsShareOnePanelAndLeaveStoredMessagesUntouched() {
        val first = assistant("开始安装", "检查环境")
        val tool = Message(conversationId = "chat", role = MessageRole.Tool, content = "run_command")
        val waiting = (1..16).map { assistant(reasoning = "等待 $it") }
        val final = assistant("安装完成", "核对退出码")
        val original = listOf(user("安装"), first, tool) + waiting + listOf(assistant(), final)
        val grouped = groupChatMessages(original)
        assertEquals(listOf(original.first().id, first.id, final.id), grouped.map { it.message.id })
        assertTrue(grouped[1].activity.any { it.id == tool.id })
        val reasoning = grouped.mapNotNull { it.reasoning }.single()
        assertEquals(18, reasoning.reasoningContent.split("\n\n").size)
        assertEquals(1800, reasoning.reasoningDurationMillis)
        assertEquals("检查环境", first.reasoningContent)
        assertTrue(waiting.all { it.reasoningContent.isNotBlank() })
        assertEquals("核对退出码", final.reasoningContent)
    }

    @Test fun aNewUserTurnOrConversationStartsANewReasoningPanel() {
        val first = assistant(reasoning = "第一轮")
        val second = assistant(reasoning = "第二轮")
        val third = assistant(reasoning = "其他会话").copy(conversationId = "other")
        val grouped = groupChatMessages(listOf(user("一"), first, user("二"), second, third))
        assertEquals(listOf("第一轮", "第二轮", "其他会话"), grouped.mapNotNull { it.reasoning?.reasoningContent })
    }

    @Test fun aRecoveredReasoningOnlyReplyDoesNotCreateAnEmptyActivityPanel() {
        val thought = assistant(reasoning = "准备回答").copy(replyPhase = ReplyPhase.Commentary)
        val answer = assistant("这是结论")
        val grouped = groupChatMessages(listOf(user("提问"), thought, answer))
        assertTrue(grouped.all { it.activity.isEmpty() })
        assertEquals("这是结论", grouped.last().message.content)
        assertEquals(1, grouped.mapNotNull { it.reasoning }.size)
    }

    @Test fun streamingReasoningUpdatesTheExistingPanelWithoutAnEmptyContinuation() {
        val first = assistant("执行前说明", "第一步")
        val active = assistant(reasoning = "接着检查", status = MessageStatus.Streaming, duration = 400)
        val grouped = groupChatMessages(listOf(user("执行"), first, active))
        assertEquals(2, grouped.size)
        val reasoning = grouped.last().reasoning!!
        assertEquals(first.id, reasoning.id)
        assertEquals(MessageStatus.Streaming, reasoning.status)
        assertTrue(reasoning.content.isEmpty())
        assertEquals(500, reasoning.reasoningDurationMillis)
        assertEquals(MessageStatus.Complete, grouped.last().message.status)
        assertEquals("执行前说明", grouped.last().message.content)
    }

    @Test fun pendingFirstResponseAndErrorsRemainVisibleAndStopUpdatesThePanel() {
        assertEquals(1, groupChatMessages(listOf(assistant(status = MessageStatus.Streaming))).size)
        val first = assistant(reasoning = "准备")
        val stopped = assistant(status = MessageStatus.Stopped)
        val grouped = groupChatMessages(listOf(first, stopped))
        assertEquals(MessageStatus.Stopped, grouped.first().reasoning!!.status)
        assertEquals(stopped.id, grouped.last().message.id)
        assertEquals(1, groupChatMessages(listOf(assistant(status = MessageStatus.Failed))).size)
    }
}
