package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.model.ChatToolCall
import org.junit.Assert.*
import org.junit.Test

class ToolCallLoopGuardTest {

    @Test fun repeatedNonUiToolsAndConsecutiveFailuresAlsoStop() {
        val repeated = ToolCallLoopGuard(4)
        repeat(4) { assertTrue(repeated.allow(ChatToolCall("$it", "terminal_status", "{}"))) }
        assertFalse(repeated.allow(ChatToolCall("5", "terminal_status", "{}")))
        assertTrue(repeated.shouldStopAutomation)
        val failed = ToolCallLoopGuard(4)
        failed.recordOutcome(false); failed.recordOutcome(false)
        assertFalse(failed.shouldStopAutomation)
        failed.recordOutcome(false)
        assertTrue(failed.shouldStopAutomation)
        val recovered = ToolCallLoopGuard(4)
        repeat(5) { recovered.recordOutcome(false); recovered.recordOutcome(true) }
        assertFalse(recovered.shouldStopAutomation)
    }

    @Test fun alternatingOpenObserveWaitCycleStopsWithoutProgress() {
        val guard = ToolCallLoopGuard(4)
        repeat(9) { index ->
            val name = listOf("open_app", "observe_screen", "wait_for_screen")[index % 3]
            guard.allow(ChatToolCall("$index", name, "{}"))
            guard.recordProgress("same screen", name)
        }
        assertTrue(guard.shouldStopAutomation)
    }

    @Test fun repeatedFailedTargetsStopEvenWhenFreshScreenTextChanges() {
        val guard = ToolCallLoopGuard(4)
        repeat(6) { index ->
            guard.allow(ChatToolCall("$index", "tap_ui_element", "{\"index\":$index}"))
            guard.recordProgress("dynamic-screen-$index", "tap_ui_element", success = false)
        }
        assertTrue(guard.shouldStopAutomation)
    }

    @Test fun successfulNavigationAndVideoChangesDoNotTriggerTheCycleGuard() {
        val guard = ToolCallLoopGuard(4)
        repeat(12) { index ->
            assertTrue(guard.allow(ChatToolCall("$index", "swipe_screen", "{\"direction\":\"up\"}")))
            guard.recordProgress("video-$index", "swipe_screen")
        }
        assertFalse(guard.shouldStopAutomation)
    }
    @Test fun repeatedScrollingContinuesWhileScreenMakesProgress() {
        val guard = ToolCallLoopGuard(4)
        val call = ChatToolCall("scroll", "swipe_screen", "{\"direction\":\"up\"}")
        repeat(12) {
            assertTrue(guard.allow(call))
            guard.recordProgress("page-$it")
        }
    }
    @Test fun newSnapshotIdsDoNotHideAStuckActionLoop() {
        val guard = ToolCallLoopGuard(4)
        repeat(4) {
            assertTrue(guard.allow(ChatToolCall("$it", "tap_ui_element", "{\"screen_id\":\"$it\",\"index\":1}")))
            guard.recordProgress("same page")
        }
        assertFalse(guard.allow(ChatToolCall("5", "tap_ui_element", "{\"index\":1,\"screen_id\":\"5\"}")))
    }
}
