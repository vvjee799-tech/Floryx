package com.flowchat.app.presentation.overlay

import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.presentation.chat.ChatUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingAssistantBridgeTest {

    @Test fun aNewTurnDoesNotShowThePreviousAnswerOrFinishedToolAsActive() {
        val state = ChatUiState(isStreaming = true, messages = listOf(
            Message(conversationId = "c", role = MessageRole.Tool, content = "observe_screen"),
            Message(conversationId = "c", role = MessageRole.Assistant, content = "Previous answer"),
            Message(conversationId = "c", role = MessageRole.User, content = "New task"),
            Message(conversationId = "c", role = MessageRole.Assistant, content = "", status = MessageStatus.Streaming)))
        val output = FloatingAssistantState.from(state)
        assertEquals("", output.assistantText)
        assertEquals(null, output.activeToolName)
    }

    @Test
    fun mapsTheActiveConversationAndLatestAssistantSegment() {
        val conversation = Conversation(
            id = "conversation-1",
            providerId = "provider-1",
            modelName = "deepseek-chat",
            assistantName = "Anny"
        )
        val state = ChatUiState(
            currentConversation = conversation,
            messages = listOf(
                Message(
                    id = "user-1",
                    conversationId = conversation.id,
                    role = MessageRole.User,
                    content = "刷会儿抖音"
                ),
                Message(
                    id = "assistant-1",
                    conversationId = conversation.id,
                    role = MessageRole.Assistant,
                    content = "我先看看你最近在刷什么。"
                ),
                Message(
                    id = "tool-1",
                    conversationId = conversation.id,
                    role = MessageRole.Tool,
                    content = "observe_screen",
                    status = MessageStatus.Streaming
                )
            ),
            isStreaming = true
        )

        val overlay = FloatingAssistantState.from(state)

        assertEquals("Anny", overlay.assistantName)
        assertEquals("deepseek-chat", overlay.modelName)
        assertEquals("我先看看你最近在刷什么。", overlay.assistantText)
        assertEquals("observe_screen", overlay.activeToolName)
        assertTrue(overlay.isStreaming)
        assertTrue(overlay.canChat)
    }

    @Test
    fun emitsSendAndStopCommands() = runTest {
        val bridge = FloatingAssistantBridge()
        val send = async(start = CoroutineStart.UNDISPATCHED) { bridge.commands.first() }
        bridge.send("继续看看")
        assertEquals(FloatingAssistantCommand.Send("继续看看"), send.await())

        val stop = async(start = CoroutineStart.UNDISPATCHED) { bridge.commands.first() }
        bridge.stop()
        assertEquals(FloatingAssistantCommand.Stop, stop.await())

    }

    @Test
    fun overlappingDeviceActionsKeepControlsYieldingUntilBothHaveFinishedOrCancelled() = runTest {
        val bridge = FloatingAssistantBridge()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            bridge.withExternalUiAction { firstGate.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            bridge.withExternalUiAction { secondGate.await() }
        }
        assertEquals(2, bridge.uiInteractionCount.value)
        bridge.publish(FloatingAssistantState(assistantText = "Still reporting progress", isStreaming = true))
        assertEquals("Still reporting progress", bridge.state.value.assistantText)
        firstGate.complete(Unit)
        first.await()
        assertEquals(1, bridge.uiInteractionCount.value)
        second.cancelAndJoin()
        assertEquals(0, bridge.uiInteractionCount.value)
    }

    @Test
    fun aFailedDeviceActionRestoresControls() = runTest {
        val bridge = FloatingAssistantBridge()
        val result = runCatching {
            bridge.withExternalUiAction {
                assertEquals(1, bridge.uiInteractionCount.value)
                error("Device disconnected")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(0, bridge.uiInteractionCount.value)
    }
}
