package com.flowchat.app.presentation.overlay

import org.junit.Assert.*
import org.junit.Test

class AnswerBannerStateTest {
    @Test fun onlyTheCurrentAnswerTaskCanUpdateItsBanner() {
        val bridge = FloatingAssistantBridge()
        bridge.beginAnswerBanner("first", "owner")
        assertFalse(bridge.answerBanner.value!!.hasContent)
        bridge.updateAnswerBanner("first", "1. 56", streaming = true)
        assertTrue(bridge.answerBanner.value!!.hasContent)
        assertEquals("owner", bridge.answerBanner.value!!.conversationId)
        bridge.beginAnswerBanner("second", "other")
        bridge.updateAnswerBanner("first", "Stale result", streaming = false)
        assertEquals("", bridge.answerBanner.value!!.text)
        assertEquals("other", bridge.answerBanner.value!!.conversationId)
    }

    @Test fun dismissingAnAnswerDoesNotLetLaterTokensReopenIt() {
        val bridge = FloatingAssistantBridge()
        bridge.beginAnswerBanner("one", "owner")
        bridge.updateAnswerBanner("one", "Partial", streaming = true)
        bridge.dismissAnswerBanner()
        bridge.updateAnswerBanner("one", "Complete", streaming = false)
        assertFalse(bridge.answerBanner.value!!.hasContent)
        bridge.beginAnswerBanner("two", "owner")
        bridge.updateAnswerBanner("two", "New answer", streaming = true)
        assertTrue(bridge.answerBanner.value!!.hasContent)
    }

    @Test fun clearingTheModePreventsAnyLateResponseFromRestoringItsContent() {
        val bridge = FloatingAssistantBridge()
        bridge.beginAnswerBanner("one", "owner")
        bridge.clearAnswerBanner()
        bridge.updateAnswerBanner("one", "Late final answer", streaming = false)
        assertNull(bridge.answerBanner.value)
    }
}
