package com.flowchat.app.domain.chat

import org.junit.Assert.*
import org.junit.Test

class ReplyPolicyTest {
    @Test fun insufficientBalanceHasAnActionableMessageWithoutRawProviderJson() {
        val raw = "HTTP 402: {\"error\":{\"message\":\"Insufficient Balance (request_id: test-id)\"}}"
        for (chinese in listOf(true, false)) {
            val error = ReplyPolicy.errorMessage(raw, chinese)
            assertTrue(error.contains("402"))
            assertFalse(error.contains("request_id"))
            assertTrue(error.contains(if (chinese) "充值" else "Add credits"))
            val reply = ReplyPolicy.interruptedReply("", chinese, 14, false, error)
            assertTrue(reply.contains("14"))
        }
    }

    @Test fun aToolFailureKeepsItsActualCauseInTheSavedAnswer() {
        val reason = "上下文整理失败，摘要请求超时。"
        val reply = ReplyPolicy.interruptedReply("", true, 3, false, reason)
        assertTrue(reply.startsWith(reason))
        assertTrue(reply.contains("3 次操作记录"))
        assertTrue(reply.contains("任务结果尚未确认"))
    }

    @Test fun partialAnswersKeepBothTheirContentAndFailureReason() {
        val reply = ReplyPolicy.interruptedReply("已读取第一部分", true, 1, false, "请求超时。")
        assertTrue(reply.startsWith("已读取第一部分"))
        assertTrue(reply.contains("请求超时"))
        assertTrue(reply.contains("回复中断"))
    }

    @Test fun reasoningProtocolErrorsAreReadableWithoutDumpingRawJson() {
        val message = ReplyPolicy.errorMessage("HTTP 400: {\"error\":{\"message\":\"reasoning_content must be passed back\"}}", true)
        assertTrue(message.contains("思考上下文不完整"))
        assertFalse(message.contains("{\"error\""))
        assertEquals("request failed", ReplyPolicy.interruptedReply("", false, 0, false, "request failed"))
    }
}
