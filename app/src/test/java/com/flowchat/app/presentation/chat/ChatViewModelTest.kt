package com.flowchat.app.presentation.chat

import androidx.lifecycle.viewModelScope
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.model.AppUsageSummary
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.RecentAppActivity
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.provider.ProviderTemplates
import com.flowchat.app.domain.repository.AppUsageReader
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.repository.MemoryRepository
import com.flowchat.app.domain.repository.PromptProfileRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val testModels = mutableListOf<ChatViewModel>()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        try {
            runTest(dispatcher) {
                val jobs = testModels.mapNotNull { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job] }
                jobs.forEach { it.cancel() }
                jobs.forEach { it.join() }
            }
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun disabledMemoryNeitherReadsExistingFactsNorGeneratesNewSummaries() = runTest(dispatcher) {
        val memory = FakeMemoryRepository()
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("好的"), ChatDelta.Done)))
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memory = memory, memoryEnabled = false)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("以后默认用中文交流。")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(0, memory.reads)
        assertEquals(1, client.requests.size)
        assertTrue(memory.records.isEmpty())
        collection.cancel()
    }

    @Test
    fun durableFactsAreSummarizedAndUsedAcrossTurnsWithoutStoringTheReply() = runTest(dispatcher) {
        val json = """{"memories":[{"key":"preference.response_language","category":"preference","summary":"用户希望默认用中文交流。"}]}"""
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.Content("这是助手的回答，不应成为记忆"), ChatDelta.Done),
            listOf(ChatDelta.Content(json), ChatDelta.Done),
            listOf(ChatDelta.Content("你好"), ChatDelta.Done)
        ))
        val memory = FakeMemoryRepository()
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memory = memory)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("以后默认用中文交流。")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(2, client.requests.size)
        assertEquals("用户希望默认用中文交流。", memory.records.single().summary)
        assertFalse(client.requests[1].messages.any { it.content.orEmpty().contains("这是助手的回答") })
        viewModel.updateInput("你好")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(3, client.requests.size)
        assertTrue(client.requests.last().messages.any { it.content.orEmpty().contains("用户希望默认用中文交流。") })
        collection.cancel()
    }

    @Test
    fun switchingMemoryOffAndOnWhileExtractionWaitsCannotCommitTheOldResult() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var requests = 0
        val client = object : ChatCompletionClient {
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = kotlinx.coroutines.flow.flow<ChatDelta> {
                requests++
                if (requests == 2) {
                    gate.await()
                    emit(ChatDelta.Content("""{"memories":[{"key":"preference.response_language","category":"preference","summary":"用户希望默认用中文交流。"}]}"""))
                } else emit(ChatDelta.Content("好的"))
                emit(ChatDelta.Done)
            }
        }
        val memory = FakeMemoryRepository()
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memory = memory)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("以后默认用中文交流。")
        viewModel.send()
        testScheduler.runCurrent()
        assertEquals(2, requests)
        viewModel.setMemoryEnabled(false)
        viewModel.setMemoryEnabled(true)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(memory.records.isEmpty())
        collection.cancel()
    }

    @Test
    fun thinkingSettingsReachRequestsAndOpaqueToolContextIsPreserved() = runTest(dispatcher) {
        val opaque = kotlinx.serialization.json.Json.parseToJsonElement("""[{"type":"reasoning","encrypted_content":"opaque-test-context"}]""") as kotlinx.serialization.json.JsonArray
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ProviderData(opaque), ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("Done"), ChatDelta.Done)
        ))
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), model = "deepseek-v4-pro")
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.setThinkingEffort(com.flowchat.app.domain.model.ThinkingEffort.Low)
        viewModel.setThinkingEnabled(false)
        viewModel.updateConversationSettings("Assistant", null, false, "Keep this prompt", 0.7, 1.0, null)
        advanceUntilIdle()
        viewModel.updateInput("Inspect the current page")
        viewModel.send()
        advanceUntilIdle()
        assertFalse(client.requests.first().enableThinking)
        assertEquals("Keep this prompt", viewModel.uiState.value.currentConversation?.systemPrompt)
        assertEquals(com.flowchat.app.domain.model.ThinkingEffort.Low, client.requests.first().thinkingEffort)
        assertEquals(opaque, client.requests.last().messages.first { it.providerData != null }.providerData)
        assertTrue(viewModel.uiState.value.messages.none { it.content.contains("opaque-test-context") || it.reasoningContent.contains("opaque-test-context") })
        collection.cancel()
    }

    @Test
    fun searchIsNotOfferedAndUnsolicitedSearchCallsAreRejected() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("search", AgentToolDefinitions.WebSearchToolName, "{\"query\":\"test\"}")))),
            listOf(ChatDelta.Content("Search unavailable"), ChatDelta.Done)
        ))
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), enabled = false)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Search for something")
        viewModel.send()
        advanceUntilIdle()
        assertTrue(client.requests.all { request -> request.tools.none { it.name == AgentToolDefinitions.WebSearchToolName } })
        assertTrue(client.requests.last().messages.any { it.role == "tool" && it.content.orEmpty().contains("tool_unavailable") })
        collection.cancel()
    }

    @Test
    fun returningToFlowChatPausesPhoneWorkAndOnlyTheContinueButtonResumesIt() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("Resumed safely"), ChatDelta.Done)
        ))
        val gateway = FakeDeviceAssistantGateway().apply { observationGate = kotlinx.coroutines.CompletableDeferred() }
        val viewModel = deviceTestViewModel(client, gateway, bridge = bridge)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Open Douyin and swipe three videos")
        viewModel.send()
        advanceUntilIdle()
        bridge.setAppInForeground(false)
        bridge.setAppInForeground(true)
        bridge.setAppInForeground(false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deviceTaskPaused)
        assertFalse(viewModel.uiState.value.isStreaming)
        assertTrue(viewModel.uiState.value.messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Cancelled })
        gateway.observationGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, client.requests.size)
        viewModel.updateInput("my unsent draft")
        viewModel.resumeDeviceTask()
        advanceUntilIdle()
        assertEquals(2, client.requests.size)
        assertFalse(viewModel.uiState.value.deviceTaskPaused)
        assertEquals("my unsent draft", viewModel.uiState.value.input)
        assertTrue(client.requests.last().messages.last { it.role == "user" }.content.orEmpty().contains("Open Douyin and swipe three videos"))
        collection.cancel()
    }

    @Test
    fun returningForDeviceConfirmationPreservesTheRequestAndLaterReturnsStillPause() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, """{"include_image":false}""")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("stop", AgentToolDefinitions.ForceStopAppToolName, """{"app_name":"Example"}""")))),
            listOf(ChatDelta.Content("Stopped"), ChatDelta.Done)
        )).apply { responseGates[2] = kotlinx.coroutines.CompletableDeferred() }
        val viewModel = deviceTestViewModel(client, gateway, bridge = bridge, memoryEnabled = false)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Inspect and stop Example")
        viewModel.send()
        bridge.setAppInForeground(false)
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingDeviceActionConfirmation
        assertTrue(confirmation != null)
        assertEquals(com.flowchat.app.presentation.overlay.FloatingTaskPhase.WaitingForUser, bridge.state.value.progress?.phase)

        bridge.setAppInForeground(true)
        bridge.setAppInForeground(true) // Activity onStart and onResume.
        advanceUntilIdle()
        assertEquals(confirmation, viewModel.uiState.value.pendingDeviceActionConfirmation)
        assertTrue(viewModel.uiState.value.isStreaming)
        assertFalse(viewModel.uiState.value.deviceTaskPaused)
        assertTrue(gateway.forceStoppedApps.isEmpty())

        viewModel.confirmDeviceAction()
        advanceUntilIdle()
        assertEquals(listOf("Example"), gateway.forceStoppedApps)
        assertEquals(null, viewModel.uiState.value.pendingDeviceActionConfirmation)
        assertTrue(viewModel.uiState.value.isStreaming)
        assertFalse(viewModel.uiState.value.deviceTaskPaused)
        bridge.setAppInForeground(false)
        bridge.setAppInForeground(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deviceTaskPaused)
        assertFalse(viewModel.uiState.value.isStreaming)
        client.responseGates[2]!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Example"), gateway.forceStoppedApps)
        collection.cancel()
    }

    @Test
    fun cancellingAfterReturningForConfirmationDoesNotExecuteTheActionOrPauseTheTask() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("stop", AgentToolDefinitions.ForceStopAppToolName, """{"app_name":"Example"}""")))),
            listOf(ChatDelta.Content("Cancelled"), ChatDelta.Done)
        ))
        val viewModel = deviceTestViewModel(client, gateway, bridge = bridge, memoryEnabled = false)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Stop Example")
        viewModel.send()
        bridge.setAppInForeground(false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingDeviceActionConfirmation != null)
        bridge.setAppInForeground(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingDeviceActionConfirmation != null)

        viewModel.cancelDeviceAction()
        advanceUntilIdle()
        assertTrue(gateway.forceStoppedApps.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingDeviceActionConfirmation)
        assertFalse(viewModel.uiState.value.deviceTaskPaused)
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(2, client.requests.size)
        assertTrue(viewModel.uiState.value.messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Cancelled })
        collection.cancel()
    }

    @Test
    fun returningWhileWaitingForTheNextModelReplyPreventsAQueuedAppRelaunch() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val launcher = FakeAppLauncher()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("reopen", AgentToolDefinitions.OpenAppToolName, """{"app_name":"抖音"}"""))))
        )).apply { responseGates[1] = kotlinx.coroutines.CompletableDeferred() }
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), bridge = bridge, launcher = launcher)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Swipe videos")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(2, client.requests.size)
        bridge.setAppInForeground(false)
        bridge.setAppInForeground(true)
        advanceUntilIdle()
        client.responseGates[1]!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deviceTaskPaused)
        assertTrue(launcher.openedApps.isEmpty())
        viewModel.stop()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.deviceTaskPaused)
        collection.cancel()
    }

    @Test
    fun aLateFirstPhoneActionCannotStealFocusAfterTheUserHasReturned() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val launcher = FakeAppLauncher()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("open", AgentToolDefinitions.OpenAppToolName, """{"app_name":"抖音"}"""))))
        )).apply { responseGates[0] = kotlinx.coroutines.CompletableDeferred() }
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), bridge = bridge, launcher = launcher)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Open Douyin")
        viewModel.send()
        advanceUntilIdle()
        bridge.setAppInForeground(false)
        bridge.setAppInForeground(true)
        client.responseGates[0]!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deviceTaskPaused)
        assertTrue(launcher.openedApps.isEmpty())
        collection.cancel()
    }

    @Test
    fun ordinaryChatKeepsStreamingWhenTheUserReturnsToFlowChat() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val client = FakeChatCompletionClient().apply { responseGates[0] = kotlinx.coroutines.CompletableDeferred() }
        val viewModel = deviceTestViewModel(client, FakeDeviceAssistantGateway(), bridge = bridge)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Hello")
        viewModel.send()
        advanceUntilIdle()
        bridge.setAppInForeground(false)
        bridge.setAppInForeground(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        assertFalse(viewModel.uiState.value.deviceTaskPaused)
        client.responseGates[0]!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.messages.any { it.content == "ok" && it.status == MessageStatus.Complete })
        collection.cancel()
    }

    @Test
    fun alternatingObservationWaitLoopStopsAndRequestsAToolFreeExplanation() = runTest(dispatcher) {
        val responses = (0 until 7).map { index ->
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("$index",
                if (index % 2 == 0) AgentToolDefinitions.ObserveScreenToolName else AgentToolDefinitions.WaitForScreenToolName, """{"include_image":false}"""))))
        } + listOf(listOf(ChatDelta.Content("页面没有变化，已停止重试。"), ChatDelta.Done))
        val client = FakeChatCompletionClient(responses)
        val gateway = FakeDeviceAssistantGateway()
        val viewModel = deviceTestViewModel(client, gateway)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Open Douyin and swipe three videos")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(8, client.requests.size)
        assertTrue(client.requests.last().tools.isEmpty())
        assertTrue(viewModel.uiState.value.messages.any { it.content == "页面没有变化，已停止重试。" && it.status == MessageStatus.Complete })
        assertEquals(1, viewModel.uiState.value.messages.count { it.role == MessageRole.Assistant && it.content == "I’ll inspect the current screen before the next step." })
        collection.cancel()
    }

    @Test
    fun stoppingCancelsPendingDeviceWorkAndItsVisibleToolStatus() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}"))))
        ))
        val gateway = FakeDeviceAssistantGateway().apply { observationGate = kotlinx.coroutines.CompletableDeferred() }
        val bridge = FloatingAssistantBridge()
        val viewModel = deviceTestViewModel(client, gateway, bridge = bridge)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Inspect settings")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(1, gateway.observeCount)
        assertEquals(1, bridge.uiInteractionCount.value)
        viewModel.stop()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(0, bridge.uiInteractionCount.value)
        assertEquals(1, client.requests.size)
        assertTrue(viewModel.uiState.value.messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Cancelled })
        collection.cancel()
    }

    @Test
    fun uiBatchExecutesOnlyOneMutationBeforeRequestingFreshModelDecision() = runTest(dispatcher) {
        val calls = listOf(1, 2).map { ChatToolCall("tap-$it", AgentToolDefinitions.TapUiElementToolName,
            """{"screen_id":"test-screen","index":$it}""") }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(calls)),
            listOf(ChatDelta.Content("Done"), ChatDelta.Done)
        ))
        val gateway = FakeDeviceAssistantGateway()
        val viewModel = deviceTestViewModel(client, gateway)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.updateInput("Open settings and find battery")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(listOf(1), gateway.tappedIndexes)
        assertTrue(client.requests.last().messages.any { it.role == "tool" && it.content.orEmpty().contains("sequential_ui_required") })
        collection.cancel()
    }

    @Test
    fun missingScreenIdAndDisabledDeviceToolsNeverReachTheDevice() = runTest(dispatcher) {
        for (enabled in listOf(true, false)) {
            val client = FakeChatCompletionClient(listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("tap", AgentToolDefinitions.TapUiElementToolName, """{"index":1}""")))),
                listOf(ChatDelta.Content("Stopped"), ChatDelta.Done)
            ))
            val gateway = FakeDeviceAssistantGateway()
            val viewModel = deviceTestViewModel(client, gateway, enabled)
            val collection = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.updateInput("Open settings")
            viewModel.send()
            advanceUntilIdle()
            assertTrue(gateway.tappedIndexes.isEmpty())
            val expected = if (enabled) "screen_id" else "tool_unavailable"
            assertTrue(client.requests.last().messages.any { it.role == "tool" && it.content.orEmpty().contains(expected) })
            collection.cancel()
        }
    }


    @Test fun reasoningOnlyResponseGetsOneConclusionAttemptThenStops() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(List(3) { listOf(ChatDelta.Reasoning("仍在分析"), ChatDelta.Done) })
        val vm = deviceTestViewModel(client, FakeDeviceAssistantGateway(), enabled = false, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("给一个简短答案"); vm.send(); advanceUntilIdle()
        assertEquals(2, client.requests.size)
        assertTrue(client.requests.last().tools.isEmpty())
        assertFalse(vm.uiState.value.isStreaming)
        assertEquals(MessageStatus.Failed, vm.uiState.value.messages.last().status)
        assertTrue(vm.uiState.value.messages.last().content.contains("未能返回完整答复"))
        collection.cancel()
    }

    @Test fun finalizationNeverExecutesAnotherToolEvenWhenTheModelIgnoresTheLimit() = runTest(dispatcher) {
        val calls = List(4) { index -> listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("bad-$index", "unavailable_tool", "{}")))) }
        val client = FakeChatCompletionClient(calls)
        val gateway = FakeDeviceAssistantGateway()
        val vm = deviceTestViewModel(client, gateway, enabled = false, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("完成这项操作"); vm.send(); advanceUntilIdle()
        assertEquals(4, client.requests.size)
        assertTrue(client.requests.last().tools.isEmpty())
        assertEquals(0, gateway.observeCount)
        assertTrue(vm.uiState.value.messages.last().content.contains("未确认完成"))
        assertFalse(vm.uiState.value.isStreaming)
        collection.cancel()
    }

    @Test fun toolRoundLimitProducesAResultSummaryInsteadOfAnAbruptError() = runTest(dispatcher) {
        val calls = List(8) { index -> listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("volume-$index",
            AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":${index + 10}}""")))) }
        val client = FakeChatCompletionClient(calls + listOf(listOf(ChatDelta.Content("已完成音量调整，其他步骤未执行。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("逐步调节音量"); vm.send(); advanceUntilIdle()
        assertEquals(9, client.requests.size)
        assertTrue(client.requests.last().tools.isEmpty())
        assertTrue(client.requests.last().messages.last().content.orEmpty().contains("execution limit"))
        assertEquals("已完成音量调整，其他步骤未执行。", vm.uiState.value.messages.last().content)
        collection.cancel()
    }

    @Test fun commentaryIsPersistedSeparatelyAndNotReplayedAsAnAnswerOnTheNextTurn() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.Content("先检查页面"), ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("当前是测试页面。"), ChatDelta.Done),
            listOf(ChatDelta.Content("无需再打开。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("看看页面"); vm.send(); advanceUntilIdle()
        assertEquals(com.flowchat.app.domain.model.ReplyPhase.Commentary,
            vm.uiState.value.messages.first { it.content == "先检查页面" }.replyPhase)
        vm.updateInput("还需要打开吗"); vm.send(); advanceUntilIdle()
        assertTrue(client.requests.last().messages.any { it.content == "当前是测试页面。" })
        assertFalse(client.requests.last().messages.any { it.content == "先检查页面" })
        collection.cancel()
    }

    @Test fun stoppingThenImmediatelySendingKeepsCompletedToolEvidenceEvenWithMemoryOff() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.Content("调整到 25"), ChatDelta.ToolCalls(listOf(ChatToolCall("brightness",
                AgentToolDefinitions.SetBrightnessToolName, """{"percent":25}""")))),
            listOf(ChatDelta.Content("不会发送的旧结论"), ChatDelta.Done),
            listOf(ChatDelta.Content("上一步已完成，只处理剩余部分。"), ChatDelta.Done)))
        client.responseGates[1] = kotlinx.coroutines.CompletableDeferred()
        val vm = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("调整亮度再继续检查"); vm.send(); advanceUntilIdle()
        assertEquals(2, client.requests.size)
        vm.stop()
        vm.updateInput("继续剩下的，不要重调亮度"); vm.send(); advanceUntilIdle()
        val next = client.requests.last()
        val evidence = next.messages.first { it.content.orEmpty().startsWith(com.flowchat.app.domain.chat.TaskExecutionHistory.ContextPrefix) }.content!!
        assertTrue(evidence.contains("brightness=25"))
        assertTrue(evidence.contains("\"percent\":25"))
        assertTrue(evidence.contains("Stopped"))
        assertEquals("继续剩下的，不要重调亮度", next.messages.last().content)
        assertTrue(next.messages.none { it.role == "tool" })
        assertEquals(3, client.requests.size)
        collection.cancel()
    }

    @Test fun aLongRunningTurnCompactsWithoutRepeatingActionsAndKeepsNativeResultPairs() = runTest(dispatcher) {
        val normalRequests = mutableListOf<ChatRequest>()
        val summaryRequests = mutableListOf<ChatRequest>()
        var round = 0
        val client = object : ChatCompletionClient {
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = kotlinx.coroutines.flow.flow {
                if (request.messages.first().content.orEmpty().contains("# Floryx context checkpoint writer")) {
                    summaryRequests += request
                    val input = kotlinx.serialization.json.Json.parseToJsonElement(request.messages.last().content!!).jsonObject
                    val record = input["new_records"]!!.jsonArray.first().jsonObject
                    val id = record["id"]!!.jsonPrimitive.content
                    emit(ChatDelta.Content("""{"objective":{"text":"完成测试调节任务","sources":["$id"]},"remaining":[{"text":"继续剩余步骤，不要重做已完成操作","sources":["$id"]}]}"""))
                    emit(ChatDelta.Done)
                } else {
                    normalRequests += request
                    round++
                    if (round <= 7) {
                        emit(ChatDelta.Reasoning("PRIVATE_REASONING_" + "r".repeat(10_000)))
                        emit(ChatDelta.ProviderData(kotlinx.serialization.json.Json.parseToJsonElement("""[{"type":"reasoning","id":"opaque-$round"}]""").jsonArray))
                        emit(ChatDelta.ToolCalls(listOf(ChatToolCall("step-$round", AgentToolDefinitions.SetBrightnessToolName, """{"percent":${round + 20}}"""))))
                    } else { emit(ChatDelta.Content("已完成本轮测试。")); emit(ChatDelta.Done) }
                }
            }
        }
        val gateway = FakeDeviceAssistantGateway()
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false, model = "deepseek-v4-pro")
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("按步骤完成测试调节任务"); vm.send(); advanceUntilIdle()
        assertEquals((21..27).toList(), gateway.brightnessValues)
        assertTrue(summaryRequests.isNotEmpty())
        assertTrue(summaryRequests.none { it.messages.last().content.orEmpty().contains("PRIVATE_REASONING_") })
        val compacted = normalRequests.first { request -> request.messages.any { it.content.orEmpty().startsWith("Conversation task checkpoint") } }
        val calls = compacted.messages.flatMap { it.toolCalls }
        assertEquals(2, calls.size)
        assertEquals(calls.map { it.id }.toSet(), compacted.messages.filter { it.role == "tool" }.map { it.toolCallId }.toSet())
        assertEquals(2, compacted.messages.count { it.providerData != null && it.reasoningContent.orEmpty().contains("PRIVATE_REASONING_") })
        assertEquals("已完成本轮测试。", vm.uiState.value.messages.last().content)
        collection.cancel()
    }

    @Test fun stopWhileAnActionIsInFlightPersistsUnconfirmedOutcomeBeforeFollowUp() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { observationGate = kotlinx.coroutines.CompletableDeferred() }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.Content("先读取页面"), ChatDelta.ToolCalls(listOf(ChatToolCall("observe",
                AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("上次读取未完成，需要先确认状态。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("检查页面"); vm.send(); advanceUntilIdle()
        assertEquals(1, gateway.observeCount)
        vm.stop()
        vm.updateInput("继续"); vm.send(); advanceUntilIdle()
        val next = client.requests.last().messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(next.contains("interrupted_unconfirmed"))
        assertTrue(next.contains("Segment status: Stopped"))
        assertTrue(next.contains("may have taken effect"))
        assertEquals(1, gateway.observeCount)
        collection.cancel()
    }

    @Test fun interruptedReplyKeepsItsPartialTextAndMarksItIncomplete() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("已分析附件的第一部分"), ChatDelta.Error("Disconnected"))))
        val vm = deviceTestViewModel(client, FakeDeviceAssistantGateway(), enabled = false, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("分析附件"); vm.send(); advanceUntilIdle()
        val reply = vm.uiState.value.messages.last()
        assertEquals(MessageStatus.Failed, reply.status)
        assertTrue(reply.content.startsWith("已分析附件的第一部分"))
        assertTrue(reply.content.contains("回复中断"))
        assertEquals(1, client.requests.size)
        collection.cancel()
    }

    @Test fun anEmptyConclusionAfterToolsIsRepairedWithoutRepeatingTheAction() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Done), listOf(ChatDelta.Content("已读取当前页面。"), ChatDelta.Done)))
        val gateway = FakeDeviceAssistantGateway()
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("查看页面"); vm.send(); advanceUntilIdle()
        assertEquals(3, client.requests.size)
        assertEquals(1, gateway.observeCount)
        assertTrue(client.requests.last().tools.isEmpty())
        assertEquals("已读取当前页面。", vm.uiState.value.messages.last().content)
        collection.cancel()
    }

    @Test fun interruptedToolConclusionKeepsItsEvidenceAndDoesNotRepeatTheAction() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("当前页面显示测试文字"), ChatDelta.Error("Disconnected"))))
        val gateway = FakeDeviceAssistantGateway()
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("查看页面"); vm.send(); advanceUntilIdle()
        val final = vm.uiState.value.messages.last()
        assertTrue(final.content.startsWith("当前页面显示测试文字"))
        assertTrue(final.content.contains("任务结果尚未确认"))
        assertEquals(MessageStatus.Failed, final.status)
        assertEquals(2, client.requests.size)
        assertEquals(1, gateway.observeCount)
        collection.cancel()
    }

    @Test fun screenshotIsCarriedIntoToolContinuationWithoutPersistingItsBytes() = runTest(dispatcher) {
        val image = com.flowchat.app.domain.model.ChatImage("image/jpeg", "SYNTHETIC_SCREEN_BYTES")
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = image }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("screen", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("看到了画面。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("看看屏幕上的图片"); vm.send(); advanceUntilIdle()
        assertEquals(1, gateway.captureCount)
        val result = client.requests.last().messages.last { it.role == "tool" }
        assertEquals(image, result.images.single())
        assertTrue(result.content!!.contains("image_available=true"))
        assertEquals(1, client.requests.last().messages.count { it.screenImageId != null })
        assertFalse(vm.uiState.value.messages.any { it.content.contains(image.base64) || it.executionContext.orEmpty().contains(image.base64) })
        collection.cancel()
    }

    @Test fun detailRegionReachesTheCaptureAndModelWithoutPersistingPixels() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = com.flowchat.app.domain.model.ChatImage("image/png", "DETAIL_BYTES") }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("detail", AgentToolDefinitions.ObserveScreenToolName,
                """{"image_region":{"left":0,"top":50,"right":100,"bottom":100}}""")))),
            listOf(ChatDelta.Content("已查看局部细节。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("看清底部小字"); vm.send(); advanceUntilIdle()
        assertEquals(listOf(com.flowchat.app.domain.device.ScreenImageRegion(0, 50, 100, 100)), gateway.captureRegions)
        val result = client.requests.last().messages.last { it.role == "tool" }
        assertEquals("image/png", result.images.single().mimeType)
        assertTrue(result.content!!.contains("image_region_percent=0,50,100,100"))
        assertFalse(vm.uiState.value.messages.any { it.content.contains("DETAIL_BYTES") || it.executionContext.orEmpty().contains("DETAIL_BYTES") })
        collection.cancel()
    }

    @Test fun invalidImageRegionDoesNotTakeAScreenshot() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("detail", AgentToolDefinitions.ObserveScreenToolName,
                """{"image_region":{"left":80,"top":0,"right":20,"bottom":100}}""")))),
            listOf(ChatDelta.Content("区域参数无效。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("查看细节"); vm.send(); advanceUntilIdle()
        assertEquals(0, gateway.captureCount)
        assertTrue(client.requests.last().messages.last { it.role == "tool" }.images.isEmpty())
        collection.cancel()
    }

    @Test fun controlsOnlyObservationDoesNotCaptureAnImage() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("screen", AgentToolDefinitions.ObserveScreenToolName, """{"include_image":false}""")))),
            listOf(ChatDelta.Content("已读取控件文字。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("读取控件"); vm.send(); advanceUntilIdle()
        assertEquals(0, gateway.captureCount)
        assertEquals(1, gateway.observeCount)
        assertTrue(client.requests.last().messages.last { it.role == "tool" }.images.isEmpty())
        collection.cancel()
    }

    @Test fun knownTextOnlyModelExplainsVisionLimitationWithoutCapturing() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("screen", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("请切换到支持图片的模型。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, model = "deepseek-v4-pro", memoryEnabled = false,
            providerBaseUrl = "https://api.deepseek.com")
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("看屏幕"); vm.send(); advanceUntilIdle()
        assertEquals(0, gateway.captureCount)
        val result = client.requests.last().messages.last { it.role == "tool" }
        assertTrue(result.images.isEmpty())
        assertTrue(result.content!!.contains("model_vision_unsupported"))
        collection.cancel()
    }

    @Test fun visualTapReturnsAnImageToTheModelAndAllowsOnlyOneUiActionPerResponse() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = com.flowchat.app.domain.model.ChatImage("image/png", "VISUAL_PIXELS") }
        val arguments = """{"screen_id":"capture-1","x":700,"y":700,"target_description":"blue button","long_press":false}"""
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.ToolCalls(listOf(
                ChatToolCall("visual", AgentToolDefinitions.TapVisualTargetToolName, arguments),
                ChatToolCall("extra", AgentToolDefinitions.TapUiElementToolName, """{"index":2,"screen_id":"capture-1","long_press":false}""")))),
            listOf(ChatDelta.Content("已核对点击后的画面。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("点击蓝色按钮并检查结果"); vm.send(); advanceUntilIdle()
        assertEquals(1, gateway.visualTargets.size)
        assertEquals(700, gateway.visualTargets.single().x)
        assertTrue(gateway.tappedIndexes.isEmpty())
        val messages = client.requests.last().messages
        val result = messages.single { it.toolCallId == "visual" }
        assertEquals("VISUAL_PIXELS", result.images.single().base64)
        assertEquals("visual-after", result.screenImageId)
        assertTrue(messages.single { it.toolCallId == "extra" }.content!!.contains("sequential_ui_required"))
        assertFalse(vm.uiState.value.messages.any { it.content.contains("VISUAL_PIXELS") || it.executionContext.orEmpty().contains("VISUAL_PIXELS") })
        collection.cancel()
    }

    @Test fun invalidVisualCoordinatesCannotReachTheDevice() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("visual", AgentToolDefinitions.TapVisualTargetToolName,
                """{"screen_id":"image","x":"500","y":700,"target_description":"blue button","long_press":false}""")))),
            listOf(ChatDelta.Content("位置参数无效。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("点击蓝色按钮"); vm.send(); advanceUntilIdle()
        assertTrue(gateway.visualTargets.isEmpty())
        assertTrue(client.requests.last().messages.last { it.role == "tool" }.content!!.contains("invalid_visual_target"))
        collection.cancel()
    }

    @Test fun knownTextOnlyModelsDoNotGetAVisualTapToolEvenIfTheyRequestIt() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("visual", AgentToolDefinitions.TapVisualTargetToolName, "{}")))),
            listOf(ChatDelta.Content("需要支持图片的模型。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, model = "deepseek-v4-pro", memoryEnabled = false,
            providerBaseUrl = "https://api.deepseek.com")
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("点击画面按钮"); vm.send(); advanceUntilIdle()
        assertTrue(client.requests.first().tools.none { it.name == AgentToolDefinitions.TapVisualTargetToolName })
        assertTrue(gateway.visualTargets.isEmpty())
        assertTrue(client.requests.last().messages.last { it.role == "tool" }.content!!.contains("tool_unavailable"))
        collection.cancel()
    }

    private fun deviceTestViewModel(
        client: ChatCompletionClient,
        gateway: FakeDeviceAssistantGateway,
        enabled: Boolean = true,
        bridge: FloatingAssistantBridge = FloatingAssistantBridge(),
        launcher: FakeAppLauncher = FakeAppLauncher(),
        model: String = "test",
        memory: FakeMemoryRepository = FakeMemoryRepository(),
        memoryEnabled: Boolean = true,
        providerBaseUrl: String = "https://example.test"
    ): ChatViewModel {
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = providerBaseUrl, defaultModel = model)
        val conversation = Conversation(id = "device-test", title = "Device test", providerId = provider.id, modelName = model)
        return ChatViewModel(
            chatRepository = FakeChatRepository(listOf(conversation), conversation),
            providerRepository = FakeProviderRepository(provider), chatClient = client,
            appUsageReader = FakeAppUsageReader(), appLauncher = launcher, deviceAssistantGateway = gateway,
            promptProfileRepository = FakePromptProfileRepository(), memoryRepository = memory,
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = enabled, memoryEnabled = memoryEnabled, installId = "test-install")),
            floatingAssistantBridge = bridge,
            contextDispatcher = dispatcher
        ).also { testModels += it }
    }

    @Test
    fun sendingImmediatelyAfterNewConversationUsesNewConversationIdEvenBeforeListRefresh() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val oldConversation = Conversation(
            id = "old-conversation",
            title = "Old",
            providerId = provider.id,
            modelName = provider.defaultModel,
            updatedAt = 1L
        )
        val newConversation = Conversation(
            id = "new-conversation",
            title = "New",
            providerId = provider.id,
            modelName = provider.defaultModel,
            updatedAt = 2L
        )
        val chatRepository = FakeChatRepository(
            initialConversations = listOf(oldConversation),
            nextConversation = newConversation
        )
        val viewModel = ChatViewModel(
            chatRepository = chatRepository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = FakeChatCompletionClient(),
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.newConversation()
        advanceUntilIdle()
        viewModel.updateInput("hello new chat")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(chatRepository.messagesFor("new-conversation").any { it.role == MessageRole.User && it.content == "hello new chat" })
        assertFalse(chatRepository.messagesFor("old-conversation").any { it.role == MessageRole.User && it.content == "hello new chat" })
        collection.cancel()
    }

    @Test
    fun newConversationUsesSavedValidProviderInsteadOfBlankCustomDraft() = runTest(dispatcher) {
        val customDraft = ProviderTemplates.defaultCustomProvider()
        val deepSeek = ProviderConfig(
            id = "provider-deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash",
            apiKeyAlias = "provider:provider-deepseek"
        )
        val newConversation = Conversation(
            id = "new-conversation",
            title = "New",
            providerId = deepSeek.id,
            modelName = deepSeek.defaultModel,
            updatedAt = 2L
        )
        val chatRepository = FakeChatRepository(
            initialConversations = emptyList(),
            nextConversation = newConversation
        )
        val viewModel = ChatViewModel(
            chatRepository = chatRepository,
            providerRepository = FakeProviderRepository(
                providers = listOf(customDraft, deepSeek),
                apiKeys = mapOf(deepSeek.id to "deepseek-key")
            ),
            chatClient = FakeChatCompletionClient(),
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.newConversation()
        advanceUntilIdle()

        assertEquals("provider-deepseek", chatRepository.lastCreateProviderId)
        assertEquals("deepseek-v4-flash", chatRepository.lastCreateModelName)
        assertEquals("new-conversation", viewModel.uiState.value.currentConversation?.id)
        collection.cancel()
    }

    @Test
    fun sendingAfterModelSwitchUsesSelectedModelInRequestAndSavedMessages() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Model switch",
            providerId = provider.id,
            modelName = "deepseek-v4-pro"
        )
        val chatRepository = FakeChatRepository(
            initialConversations = listOf(conversation),
            nextConversation = conversation
        )
        val chatClient = FakeChatCompletionClient()
        val viewModel = ChatViewModel(
            chatRepository = chatRepository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateConversationModel(provider, "deepseek-v4-flash")
        advanceUntilIdle()
        viewModel.updateInput("use the switched model")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("deepseek-v4-flash", chatClient.requests.single().model)
        assertTrue(chatRepository.messagesFor("conversation-1")
            .filter { it.role == MessageRole.User || it.role == MessageRole.Assistant }
            .all { it.modelName == "deepseek-v4-flash" })
        collection.cancel()
    }

    @Test
    fun toolFollowUpReplaysAssistantContentAndReasoning() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Tool follow-up",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val chatRepository = FakeChatRepository(
            initialConversations = listOf(conversation),
            nextConversation = conversation
        )
        val chatClient = FakeChatCompletionClient(
            scriptedResponses = listOf(
                listOf(
                    ChatDelta.Reasoning("I need the user's usage summary."),
                    ChatDelta.Content("I will check."),
                    ChatDelta.ToolCalls(
                        listOf(
                            ChatToolCall(
                                id = "call_1",
                                name = AgentToolDefinitions.AppUsageSummaryToolName,
                                arguments = """{"range":"today"}"""
                            )
                        )
                    )
                ),
                listOf(ChatDelta.Content("Here is the result."), ChatDelta.Done)
            )
        )
        val viewModel = ChatViewModel(
            chatRepository = chatRepository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("What did I use today?")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(2, chatClient.requests.size)
        val assistantToolMessage = chatClient.requests[1].messages
            .last { it.role == MessageRole.Assistant.apiRole && it.toolCalls.isNotEmpty() }
        assertEquals("I will check.", assistantToolMessage.content)
        assertEquals("I need the user's usage summary.", assistantToolMessage.reasoningContent)
        collection.cancel()
    }

    @Test
    fun openAppToolLaunchesRequestedAppAndReturnsTheResultToTheModel() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Open app",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val chatRepository = FakeChatRepository(
            initialConversations = listOf(conversation),
            nextConversation = conversation
        )
        val chatClient = FakeChatCompletionClient(
            scriptedResponses = listOf(
                listOf(
                    ChatDelta.Content("I will open it."),
                    ChatDelta.ToolCalls(
                        listOf(
                            ChatToolCall(
                                id = "call_open",
                                name = AgentToolDefinitions.OpenAppToolName,
                                arguments = """{"app_name":"Settings"}"""
                            )
                        )
                    )
                ),
                listOf(ChatDelta.Content("Settings is open."), ChatDelta.Done)
            )
        )
        val appLauncher = FakeAppLauncher()
        val viewModel = ChatViewModel(
            chatRepository = chatRepository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = appLauncher,
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("Open Settings")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("Settings"), appLauncher.openedApps)
        val toolResult = chatClient.requests[1].messages.last { it.role == "tool" }
        assertEquals("Opened app: Settings.", toolResult.content)
        assertTrue(
            chatRepository.messagesFor(conversation.id)
                .any { it.role == MessageRole.Tool && it.content == "open_app:Settings" }
        )
        collection.cancel()
    }

    @Test
    fun forceStopToolWaitsForConfirmationBeforeExecuting() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Device assistant",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val chatClient = FakeChatCompletionClient(
            scriptedResponses = listOf(
                listOf(
                    ChatDelta.Content("I can stop it after you confirm."),
                    ChatDelta.ToolCalls(
                        listOf(
                            ChatToolCall(
                                id = "call_force_stop",
                                name = AgentToolDefinitions.ForceStopAppToolName,
                                arguments = """{"app_name":"Example"}"""
                            )
                        )
                    )
                ),
                listOf(ChatDelta.Content("The app has been stopped."), ChatDelta.Done)
            )
        )
        val deviceGateway = FakeDeviceAssistantGateway()
        val viewModel = ChatViewModel(
            chatRepository = FakeChatRepository(listOf(conversation), conversation),
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = deviceGateway,
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(
                AppSettings(
                    powerModeEnabled = true,
                    installId = "test-install"
                )
            ),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("Stop Example")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingDeviceActionConfirmation != null)
        assertTrue(deviceGateway.forceStoppedApps.isEmpty())

        viewModel.confirmDeviceAction()
        advanceUntilIdle()

        assertEquals(listOf("Example"), deviceGateway.forceStoppedApps)
        assertEquals(2, chatClient.requests.size)
        assertTrue(viewModel.uiState.value.pendingDeviceActionConfirmation == null)
        collection.cancel()
    }

    @Test
    fun cancelledForceStopMarksTheVisibleToolMessageCancelled() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Device assistant",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val chatRepository = FakeChatRepository(listOf(conversation), conversation)
        val chatClient = FakeChatCompletionClient(
            scriptedResponses = listOf(
                listOf(
                    ChatDelta.Content("I will stop it after confirmation."),
                    ChatDelta.ToolCalls(
                        listOf(
                            ChatToolCall(
                                id = "call_force_stop",
                                name = AgentToolDefinitions.ForceStopAppToolName,
                                arguments = """{"app_name":"Example"}"""
                            )
                        )
                    )
                ),
                listOf(ChatDelta.Content("The action was cancelled."), ChatDelta.Done)
            )
        )
        val deviceGateway = FakeDeviceAssistantGateway()
        val viewModel = ChatViewModel(
            chatRepository = chatRepository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = deviceGateway,
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(
                AppSettings(
                    powerModeEnabled = true,
                    installId = "test-install"
                )
            ),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("Stop Example")
        viewModel.send()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingDeviceActionConfirmation != null)
        viewModel.cancelDeviceAction()
        advanceUntilIdle()

        val toolMessage = chatRepository.messagesFor(conversation.id)
            .single { it.role == MessageRole.Tool }
        assertEquals(MessageStatus.Cancelled, toolMessage.status)
        assertTrue(deviceGateway.forceStoppedApps.isEmpty())
        assertEquals(2, chatClient.requests.size)
        collection.cancel()
    }

    @Test
    fun onlyOffersLifestyleToolsEnabledByUserSettings() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Private tools",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val chatClient = FakeChatCompletionClient()
        val viewModel = ChatViewModel(
            chatRepository = FakeChatRepository(listOf(conversation), conversation),
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(
                AppSettings(
                    memoryEnabled = false,
                    powerModeEnabled = false,
                    installId = "test-install"
                )
            ),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf(com.flowchat.app.domain.chat.TaskContextTools.Read,
            com.flowchat.app.domain.chat.AttachmentTools.Read), chatClient.requests.single().tools.map { it.name })
        collection.cancel()
    }

    @Test fun appUsagePermissionFailureRemainsInTheToolTimelineForItsConversation() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("usage", AgentToolDefinitions.AppUsageSummaryToolName, "{}")))),
            listOf(ChatDelta.Content("Usage Access is required."), ChatDelta.Done)
        ))
        val vm = deviceTestViewModel(client, FakeDeviceAssistantGateway(), enabled = true, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.updateInput("Check app usage")
        vm.send()
        advanceUntilIdle()

        val tool = vm.uiState.value.messages.single { it.role == MessageRole.Tool }
        assertEquals(MessageStatus.Failed, tool.status)
        assertTrue(tool.content.contains("Usage Access"))
        assertEquals(AgentToolDefinitions.AppUsageSummaryToolName, vm.uiState.value.usageAccessPermissionRequest?.toolName)
        vm.dismissUsageAccessPermissionRequest()
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.usageAccessPermissionRequest)
        collection.cancel()
    }

    @Test
    fun uiAutomationCanObserveAndActAcrossMoreThanTwoToolRounds() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Device task",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val chatClient = FakeChatCompletionClient(
            scriptedResponses = listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("tap", AgentToolDefinitions.TapUiElementToolName, "{\"screen_id\":\"test-screen\",\"index\":2,\"long_press\":false}")))),
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("back", AgentToolDefinitions.PressBackToolName, "{}")))),
                listOf(ChatDelta.Content("已经替你完成。"), ChatDelta.Done)
            )
        )
        val deviceGateway = FakeDeviceAssistantGateway()
        val viewModel = ChatViewModel(
            chatRepository = FakeChatRepository(listOf(conversation), conversation),
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = deviceGateway,
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(
                AppSettings(powerModeEnabled = true, installId = "test-install")
            ),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("打开设置看看，然后返回")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(4, chatClient.requests.size)
        assertEquals(1, deviceGateway.observeCount)
        assertEquals(listOf(2), deviceGateway.tappedIndexes)
        assertEquals(1, deviceGateway.backCount)
        assertTrue(chatClient.requests.first().tools.any { it.name == AgentToolDefinitions.ObserveScreenToolName })
        collection.cancel()
    }

    @Test
    fun uiAutomationAllowsRepeatedObservationAfterInterveningActions() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Repeated observation",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val scriptedResponses = listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe-1", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("tap-1", AgentToolDefinitions.TapUiElementToolName, "{\"screen_id\":\"test-screen\",\"index\":1}")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe-2", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("tap-2", AgentToolDefinitions.TapUiElementToolName, "{\"screen_id\":\"test-screen\",\"index\":2}")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe-3", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("Task complete."), ChatDelta.Done)
        )
        val chatClient = FakeChatCompletionClient(scriptedResponses)
        val deviceGateway = FakeDeviceAssistantGateway()
        val viewModel = ChatViewModel(
            chatRepository = FakeChatRepository(listOf(conversation), conversation),
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = deviceGateway,
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(
                AppSettings(powerModeEnabled = true, installId = "test-install")
            ),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("Complete a multi-screen task")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(3, deviceGateway.observeCount)
        assertEquals(listOf(1, 2), deviceGateway.tappedIndexes)
        assertEquals(6, chatClient.requests.size)
        collection.cancel()
    }

    @Test
    fun uiAutomationSupportsLongTasksBeyondEightToolRounds() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-pro"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Long device task",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val toolResponses = (0 until 10).map { index ->
            listOf(
                ChatDelta.ToolCalls(
                    listOf(
                        ChatToolCall(
                            id = "tap-$index",
                            name = AgentToolDefinitions.TapUiElementToolName,
                            arguments = "{\"screen_id\":\"test-screen\",\"index\":$index}"
                        )
                    )
                )
            )
        }
        val chatClient = FakeChatCompletionClient(
            scriptedResponses = toolResponses + listOf(
                listOf(ChatDelta.Content("Long task complete."), ChatDelta.Done)
            )
        )
        val deviceGateway = FakeDeviceAssistantGateway()
        val viewModel = ChatViewModel(
            chatRepository = FakeChatRepository(listOf(conversation), conversation),
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = deviceGateway,
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(
                AppSettings(powerModeEnabled = true, installId = "test-install")
            ),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("Complete a long sequence of screen actions")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(11, chatClient.requests.size)
        assertEquals((0 until 10).toList(), deviceGateway.tappedIndexes)
        assertFalse(viewModel.uiState.value.errorMessage == "Tool call limit reached.")
        collection.cancel()
    }

    @Test
    fun refusesToSendApiKeysOrMessagesToAnInsecurePublicProvider() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "provider-1",
            displayName = "Unsafe",
            baseUrl = "http://api.example.com/v1",
            defaultModel = "model"
        )
        val conversation = Conversation(
            id = "conversation-1",
            title = "Unsafe",
            providerId = provider.id,
            modelName = provider.defaultModel
        )
        val chatClient = FakeChatCompletionClient()
        val viewModel = ChatViewModel(
            chatRepository = FakeChatRepository(listOf(conversation), conversation),
            providerRepository = FakeProviderRepository(provider),
            chatClient = chatClient,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("do not send this")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(chatClient.requests.isEmpty())
        assertEquals("Provider configuration is invalid.", viewModel.uiState.value.errorMessage)
        collection.cancel()
    }

    @Test fun floatingApprovalStaysWithItsOwnerAndRejectsOldRequests() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        val bridge = FloatingAssistantBridge()
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("first", AgentToolDefinitions.ForceStopAppToolName, """{"app_name":"First"}""")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("second", AgentToolDefinitions.ForceStopAppToolName, """{"app_name":"Second"}""")))),
            listOf(ChatDelta.Content("Finished"), ChatDelta.Done)
        ))
        val vm = ChatViewModel(repository, FakeProviderRepository(provider), client, FakeAppUsageReader(),
            FakeAppLauncher(), gateway, FakePromptProfileRepository(), FakeMemoryRepository(),
            AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false, installId = "test")),
            floatingAssistantBridge = bridge, contextDispatcher = dispatcher).also { testModels += it }
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(a.id)
        vm.updateInput("Stop First and Second")
        vm.send()
        bridge.setAppInForeground(false)
        advanceUntilIdle()
        val first = requireNotNull(bridge.state.value.pendingDeviceActionConfirmation)
        vm.selectConversation(b.id)
        vm.updateInput("unsent draft")
        advanceUntilIdle()
        bridge.respondToConfirmation(b.id, first.requestId, true)
        bridge.respondToConfirmation(a.id, "expired-request", true)
        advanceUntilIdle()
        assertTrue(gateway.forceStoppedApps.isEmpty())
        bridge.respondToConfirmation(a.id, first.requestId, true)
        advanceUntilIdle()
        assertEquals(listOf("First"), gateway.forceStoppedApps)
        val second = requireNotNull(bridge.state.value.pendingDeviceActionConfirmation)
        assertTrue(second.requestId != first.requestId)
        bridge.respondToConfirmation(a.id, first.requestId, true)
        advanceUntilIdle()
        assertEquals(second, bridge.state.value.pendingDeviceActionConfirmation)
        bridge.respondToConfirmation(a.id, second.requestId, false)
        advanceUntilIdle()
        assertEquals(listOf("First"), gateway.forceStoppedApps)
        assertEquals(null, bridge.state.value.pendingDeviceActionConfirmation)
        assertEquals(b.id, vm.uiState.value.currentConversation?.id)
        assertEquals("unsent draft", vm.uiState.value.input)
        assertEquals("Finished", repository.messagesFor(a.id).last().content)
        collection.cancel()
    }

    @Test fun stoppingAFloatingConfirmationMakesLateApprovalInert() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("stop", AgentToolDefinitions.ForceStopAppToolName, """{"app_name":"Example"}"""))))
        ))
        val vm = deviceTestViewModel(client, gateway, bridge = bridge, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("Stop Example")
        vm.send()
        bridge.setAppInForeground(false)
        advanceUntilIdle()
        val owner = requireNotNull(bridge.state.value.conversationId)
        val request = requireNotNull(bridge.state.value.pendingDeviceActionConfirmation)
        bridge.stop()
        advanceUntilIdle()
        assertEquals(null, bridge.state.value.pendingDeviceActionConfirmation)
        bridge.respondToConfirmation(owner, request.requestId, true)
        advanceUntilIdle()
        assertTrue(gateway.forceStoppedApps.isEmpty())
        assertEquals(1, client.requests.size)
        assertFalse(vm.uiState.value.isStreaming)
        collection.cancel()
    }

    @Test fun anotherConversationCanReplyWhileThePhoneOwnerWaitsForConfirmation() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("stop", AgentToolDefinitions.ForceStopAppToolName, """{"app_name":"Example"}""")))),
            listOf(ChatDelta.Content("answer B"), ChatDelta.Done),
            listOf(ChatDelta.Content("answer A"), ChatDelta.Done)
        ))
        val vm = ChatViewModel(repository, FakeProviderRepository(provider), client, FakeAppUsageReader(),
            FakeAppLauncher(), gateway, FakePromptProfileRepository(), FakeMemoryRepository(),
            AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false, installId = "test")), contextDispatcher = dispatcher).also { testModels += it }
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(a.id)
        vm.updateInput("stop Example")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingDeviceActionConfirmation != null)
        vm.selectConversation(b.id)
        advanceUntilIdle()
        assertEquals(a.id, vm.uiState.value.awaitingConfirmationConversationId)
        assertEquals(null, vm.uiState.value.pendingDeviceActionConfirmation)
        vm.updateInput("ordinary question")
        vm.send()
        advanceUntilIdle()
        assertEquals("answer B", repository.messagesFor(b.id).last().content)
        assertTrue(gateway.forceStoppedApps.isEmpty())
        vm.selectConversation(a.id)
        advanceUntilIdle()
        vm.confirmDeviceAction()
        advanceUntilIdle()
        assertEquals(listOf("Example"), gateway.forceStoppedApps)
        assertEquals("answer A", repository.messagesFor(a.id).last().content)
        collection.cancel()
    }

    private fun conversationTestViewModel(repository: FakeChatRepository, client: ChatCompletionClient): ChatViewModel {
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        return ChatViewModel(
            chatRepository = repository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = client,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = FakeDeviceAssistantGateway(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = false, memoryEnabled = false, installId = "test-install")),
            floatingAssistantBridge = FloatingAssistantBridge(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
    }

    private fun twoConversations(): Pair<Conversation, Conversation> =
        Conversation(id = "chat-a", title = "A", providerId = "provider-1", modelName = "test") to
            Conversation(id = "chat-b", title = "B", providerId = "provider-1", modelName = "test")

    @Test fun contextMeterTracksStreamAndCannotReplayAnOldTimerIntoANewRun() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        val firstChunk = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondChunk = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<ChatRequest, ProviderConfig>>()
        val text = "stream-content ".repeat(300)
        val client = object : ChatCompletionClient {
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = kotlinx.coroutines.flow.flow {
                val index = requests.size
                requests += request to provider
                if (index == 0) {
                    firstChunk.await()
                    emit(ChatDelta.Content(text))
                    secondChunk.await()
                    emit(ChatDelta.Content(text))
                }
                finish.await()
                emit(ChatDelta.Done)
            }
        }
        val vm = conversationTestViewModel(repository, client)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(a.id)
        vm.updateInput("FIRST_CONTEXT_REQUEST")
        vm.send()
        runCurrent()
        assertEquals(1, requests.size)
        val (request, provider) = requests.first()
        assertEquals(com.flowchat.app.data.chat.ContextBudget.request(request, provider),
            vm.uiState.value.contextWindow!!.usedTokens)
        firstChunk.complete(Unit)
        runCurrent()
        advanceTimeBy(151)
        runCurrent()
        val withReply = request.copy(messages = request.messages + com.flowchat.app.domain.model.ChatRequestMessage(
            role = "assistant", content = text))
        val firstUsage = vm.uiState.value.contextWindow!!.usedTokens
        assertEquals(com.flowchat.app.data.chat.ContextBudget.request(withReply, provider), firstUsage)
        assertTrue(firstUsage > com.flowchat.app.data.chat.ContextBudget.request(request, provider))

        vm.selectConversation(b.id)
        vm.updateInput("B_CONTEXT_REQUEST")
        vm.send()
        runCurrent()
        val (requestB, providerB) = requests[1]
        assertEquals(com.flowchat.app.data.chat.ContextBudget.request(requestB, providerB),
            vm.uiState.value.contextWindow!!.usedTokens)
        vm.selectConversation(a.id)
        runCurrent()
        assertEquals(firstUsage, vm.uiState.value.contextWindow!!.usedTokens)

        // Queue a trailing stream refresh, stop, then start a distinct request before that timer fires.
        secondChunk.complete(Unit)
        runCurrent()
        vm.stop()
        runCurrent()
        vm.updateInput("RESTART_CONTEXT_REQUEST " + "new-content ".repeat(200))
        vm.send()
        runCurrent()
        assertEquals(3, requests.size)
        val (restarted, restartedProvider) = requests.last()
        val expected = com.flowchat.app.data.chat.ContextBudget.request(restarted, restartedProvider)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(expected, vm.uiState.value.contextWindow!!.usedTokens)
        assertEquals(32_000, vm.uiState.value.contextWindow!!.totalTokens)
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, requests.size)
        collection.cancel()
    }

    @Test fun deepSeekPreviewRecalculatesAfterThinkingOutputAndModelChangesWithoutNetworkRequests() = runTest(dispatcher) {
        val provider = ProviderConfig(id = "provider-1", displayName = "Custom", baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash")
        val chat = Conversation(id = "capacity-chat", providerId = provider.id, modelName = provider.defaultModel)
        val repository = FakeChatRepository(listOf(chat), chat)
        val client = FakeChatCompletionClient()
        val vm = ChatViewModel(repository, FakeProviderRepository(provider), client, FakeAppUsageReader(), FakeAppLauncher(),
            promptProfileRepository = FakePromptProfileRepository(), memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = false, memoryEnabled = false, installId = "test")),
            contextDispatcher = dispatcher).also { testModels += it }
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(chat.id)
        vm.updateInput("Draft " + "x".repeat(120_000))
        advanceUntilIdle()
        assertEquals(1_000_000, vm.uiState.value.contextWindow!!.modelWindowTokens)
        assertEquals(884_464, vm.uiState.value.contextWindow!!.totalTokens)
        assertTrue(vm.uiState.value.contextWindow!!.usedTokens > 40_000)
        vm.setThinkingEffort(com.flowchat.app.domain.model.ThinkingEffort.Max)
        advanceUntilIdle()
        assertEquals(818_928, vm.uiState.value.contextWindow!!.totalTokens)
        vm.setThinkingEnabled(false)
        advanceUntilIdle()
        assertEquals(941_808, vm.uiState.value.contextWindow!!.totalTokens)
        vm.updateConversationSettings("", null, false, "", 0.7, 1.0, 256)
        advanceUntilIdle()
        assertEquals(949_744, vm.uiState.value.contextWindow!!.totalTokens)
        assertEquals(256, vm.uiState.value.contextWindow!!.outputReserveTokens)
        vm.updateConversationModel(provider, "deepseek-chat")
        advanceUntilIdle()
        assertEquals(32_000, vm.uiState.value.contextWindow!!.totalTokens)
        assertEquals(null, vm.uiState.value.contextWindow!!.modelWindowTokens)
        assertTrue(client.requests.isEmpty())
        collection.cancel()
    }

    @Test fun draftsAndAttachmentsStayWithTheirConversation() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        val vm = conversationTestViewModel(repository, FakeChatCompletionClient())
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.selectConversation(a.id)
        vm.updateInput("draft A")
        vm.attachTextFile("a.txt", "A content")
        vm.selectConversation(b.id)
        vm.updateInput("draft B")
        vm.attachTextFile("b.txt", "B content")
        advanceUntilIdle()
        assertEquals("draft B", vm.uiState.value.input)
        assertEquals("b.txt", vm.uiState.value.pendingAttachment?.name)

        vm.selectConversation(a.id)
        advanceUntilIdle()
        assertEquals("draft A", vm.uiState.value.input)
        assertEquals("a.txt", vm.uiState.value.pendingAttachment?.name)
        collection.cancel()
    }

    @Test fun simultaneousRepliesFinishIndependentlyAndStoppingOneDoesNotStopTheOther() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.Content("answer A"), ChatDelta.Done),
            listOf(ChatDelta.Content("answer B"), ChatDelta.Done)
        ))
        client.responseGates[0] = kotlinx.coroutines.CompletableDeferred()
        client.responseGates[1] = kotlinx.coroutines.CompletableDeferred()
        val vm = conversationTestViewModel(repository, client)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.selectConversation(a.id)
        vm.updateInput("work A")
        vm.send()
        advanceUntilIdle()
        assertEquals(1, client.requests.size)
        vm.selectConversation(b.id)
        vm.updateInput("work B")
        vm.send()
        advanceUntilIdle()
        assertEquals(2, client.requests.size)

        vm.selectConversation(a.id)
        vm.stop()
        client.responseGates[1]!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(MessageStatus.Stopped, repository.messagesFor(a.id).last().status)
        assertEquals("answer B", repository.messagesFor(b.id).last().content)
        assertEquals(MessageStatus.Complete, repository.messagesFor(b.id).last().status)
        assertEquals(listOf(a.id, b.id), client.requests.map { it.usageConversationId })
        collection.cancel()
    }

    @Test fun typingAfterSendSurvivesStartupWait() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        val recoveryGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repository.recoveryGate = recoveryGate
        val vm = conversationTestViewModel(repository, FakeChatCompletionClient())
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.updateInput("first request")
        vm.send()
        vm.updateInput("new draft while starting")
        recoveryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("new draft while starting", vm.uiState.value.input)
        assertEquals("first request", repository.messagesFor(a.id).first { it.role == MessageRole.User }.content)
        collection.cancel()
    }

    @Test fun finalContentPreservesTheRecordedReasoningDuration() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        val afterReasoning = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = object : ChatCompletionClient {
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = kotlinx.coroutines.flow.flow {
                emit(ChatDelta.Reasoning("thinking"))
                afterReasoning.await()
                emit(ChatDelta.Content("answer"))
                emit(ChatDelta.Done)
            }
        }
        val vm = conversationTestViewModel(repository, client)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.updateInput("question")
        vm.send()
        advanceUntilIdle()
        val streaming = repository.messagesFor(a.id).last()
        assertEquals(MessageStatus.Streaming, streaming.status)
        assertEquals("thinking", streaming.reasoningContent)
        afterReasoning.complete(Unit)
        advanceUntilIdle()

        val completed = repository.messagesFor(a.id).last()
        assertEquals(MessageStatus.Complete, completed.status)
        assertEquals("answer", completed.content)
        assertEquals("thinking", completed.reasoningContent)
        assertEquals(streaming.reasoningDurationMillis, completed.reasoningDurationMillis)
        collection.cancel()
    }

    @Test fun asynchronousAttachmentLoadReturnsToItsOriginalConversation() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        val vm = conversationTestViewModel(repository, FakeChatCompletionClient())
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val applied = kotlinx.coroutines.CompletableDeferred<Unit>()

        vm.selectConversation(a.id)
        vm.loadAttachment(reader = {
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            com.flowchat.app.data.attachment.ExtractedAttachment("loaded-a.txt", "file A")
        }, errorText = { it.message.orEmpty() })
        testScheduler.runCurrent()
        assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            started.await(5, java.util.concurrent.TimeUnit.SECONDS)
        })
        vm.selectConversation(b.id)
        repository.getConversationHook = { id -> if (id == a.id) applied.complete(Unit) }
        release.countDown()
        applied.await()
        advanceUntilIdle()

        assertEquals(b.id, vm.uiState.value.currentConversation?.id)
        assertEquals(null, vm.uiState.value.pendingAttachment)
        vm.selectConversation(a.id)
        advanceUntilIdle()
        assertEquals("loaded-a.txt", vm.uiState.value.pendingAttachment?.name)
        assertEquals("file A", vm.uiState.value.pendingAttachment?.text)
        collection.cancel()
    }

    @Test fun continueKeepsCapturedConversationWhenSelectionChangesBeforeWorkStarts() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        for (conversation in listOf(a, b)) {
            repository.appendMessage(conversation.id, MessageRole.User, "Request ${conversation.title}", MessageStatus.Sent, "test", null, null, null, null)
            repository.appendMessage(conversation.id, MessageRole.Assistant, "partial", MessageStatus.Stopped, "test", null, null, null, null)
        }
        val vm = conversationTestViewModel(repository, FakeChatCompletionClient())
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.selectConversation(a.id)
        vm.resumeInterruptedTask()
        vm.selectConversation(b.id)
        advanceUntilIdle()

        assertEquals(2, repository.messagesFor(a.id).count { it.role == MessageRole.User })
        assertEquals(1, repository.messagesFor(b.id).count { it.role == MessageRole.User })
        assertEquals(b.id, vm.uiState.value.currentConversation?.id)
        collection.cancel()
    }

    @Test fun endingAnInterruptedTurnExcludesAnImmediateSend() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        repository.appendMessage(a.id, MessageRole.User, "Original task", MessageStatus.Sent, "test", null, null, null, null)
        repository.appendMessage(a.id, MessageRole.Assistant, "partial", MessageStatus.Stopped, "test", null, null, null, null)
        val client = FakeChatCompletionClient()
        val vm = conversationTestViewModel(repository, client)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.updateInput("New request")
        vm.endInterruptedTask()
        vm.send()
        advanceUntilIdle()

        assertEquals(1, repository.messagesFor(a.id).count { it.role == MessageRole.User })
        assertEquals(MessageStatus.Complete, repository.messagesFor(a.id).last().status)
        assertTrue(repository.messagesFor(a.id).last().content.contains("Task ended"))
        assertTrue(client.requests.isEmpty())
        assertEquals("New request", vm.uiState.value.input)
        collection.cancel()
    }

    private class FakeChatRepository(
        initialConversations: List<Conversation>,
        private val nextConversation: Conversation
    ) : ChatRepository {
        private val checkpoints = mutableMapOf<String, com.flowchat.app.domain.chat.TaskCheckpoint>()
        private val conversations = linkedMapOf<String, Conversation>()
        private val messages = linkedMapOf<String, MutableList<Message>>()
        private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        private val conversationsFlow = MutableStateFlow(initialConversations)
        var recoveryGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var getConversationHook: ((String) -> Unit)? = null
        var lastCreateProviderId: String? = null
            private set
        var lastCreateModelName: String? = null
            private set

        init {
            initialConversations.forEach { conversation ->
                conversations[conversation.id] = conversation
                messages[conversation.id] = mutableListOf()
                messageFlows[conversation.id] = MutableStateFlow(emptyList())
            }
        }

        override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow

        override fun observeMessages(conversationId: String): Flow<List<Message>> =
            messageFlows.getOrPut(conversationId) { MutableStateFlow(messagesFor(conversationId)) }

        override suspend fun recoverInterruptedTasks(): Set<String> {
            recoveryGate?.await()
            return emptySet()
        }

        override suspend fun getConversation(id: String): Conversation? = conversations[id].also { getConversationHook?.invoke(id) }

        override suspend fun getMessages(conversationId: String): List<Message> =
            messagesFor(conversationId)

        override suspend fun updateExecutionContext(id: String, context: String) {
            messages.forEach { (conversationId, rows) ->
                val index = rows.indexOfFirst { it.id == id }
                if (index >= 0) {
                    rows[index] = rows[index].copy(executionContext = context)
                    publishMessages(conversationId)
                }
            }
        }

        override suspend fun getContextCheckpoint(conversationId: String) = checkpoints[conversationId]

        override suspend fun saveContextCheckpoint(checkpoint: com.flowchat.app.domain.chat.TaskCheckpoint,
            expected: com.flowchat.app.domain.chat.TaskCheckpoint?): Boolean {
            if (checkpoints[checkpoint.conversationId] != expected || !checkpoint.matches(messagesFor(checkpoint.conversationId))) return false
            checkpoints[checkpoint.conversationId] = checkpoint
            return true
        }

        override suspend fun createConversation(providerId: String, modelName: String): Conversation {
            lastCreateProviderId = providerId
            lastCreateModelName = modelName
            val created = nextConversation.copy(providerId = providerId, modelName = modelName)
            conversations[created.id] = created
            messages[nextConversation.id] = mutableListOf()
            messageFlows[nextConversation.id] = MutableStateFlow(emptyList())
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
            return created
        }

        override suspend fun updateConversationSettings(
            id: String,
            assistantName: String,
            assistantAvatarPath: String?,
            showAvatars: Boolean,
            enableThinking: Boolean,
            systemPrompt: String,
            temperature: Double,
            topP: Double,
            maxTokens: Int?,
            thinkingEffort: com.flowchat.app.domain.model.ThinkingEffort
        ) {
            val current = conversations[id] ?: return
            conversations[id] = current.copy(assistantName = assistantName, assistantAvatarPath = assistantAvatarPath,
                showAvatars = showAvatars, enableThinking = enableThinking, systemPrompt = systemPrompt,
                temperature = temperature, topP = topP, maxTokens = maxTokens, thinkingEffort = thinkingEffort)
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateThinkingSettings(id: String, enabled: Boolean, effort: com.flowchat.app.domain.model.ThinkingEffort) {
            val current = conversations[id] ?: return
            conversations[id] = current.copy(enableThinking = enabled, thinkingEffort = effort)
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateConversationModel(id: String, providerId: String, modelName: String) {
            val current = conversations[id] ?: return
            val updated = current.copy(providerId = providerId, modelName = modelName)
            conversations[id] = updated
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateConversationTitle(id: String, title: String) {
            val current = conversations[id] ?: return
            conversations[id] = current.copy(title = title)
        }

        override suspend fun appendMessage(
            conversationId: String,
            role: MessageRole,
            content: String,
            status: MessageStatus,
            modelName: String?,
            attachmentName: String?,
            attachmentText: String?,
            attachmentImagePath: String?,
            attachmentMimeType: String?
        ): Message {
            val message = Message(
                conversationId = conversationId,
                role = role,
                content = content,
                attachmentName = attachmentName,
                attachmentText = attachmentText,
                attachmentImagePath = attachmentImagePath,
                attachmentMimeType = attachmentMimeType,
                status = status,
                modelName = modelName
            )
            messages.getOrPut(conversationId) { mutableListOf() }.add(message)
            publishMessages(conversationId)
            return message
        }

        override suspend fun updateMessage(
            id: String,
            content: String,
            status: MessageStatus,
            reasoningContent: String,
            reasoningDurationMillis: Long?,
            replyPhase: com.flowchat.app.domain.model.ReplyPhase?
        ) {
            messages.forEach { (conversationId, conversationMessages) ->
                val index = conversationMessages.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val current = conversationMessages[index]
                    conversationMessages[index] = current.copy(
                        content = content,
                        status = status,
                        reasoningContent = reasoningContent,
                        reasoningDurationMillis = reasoningDurationMillis
                            ?: current.reasoningDurationMillis,
                        replyPhase = replyPhase ?: current.replyPhase,
                        updatedAt = System.currentTimeMillis()
                    )
                    publishMessages(conversationId)
                    return
                }
            }
        }

        override suspend fun markMessageFailed(id: String, error: String) = Unit

        override suspend fun deleteConversation(id: String) {
            conversations.remove(id)
            messages.remove(id)
            messageFlows[id]?.value = emptyList()
        }

        fun messagesFor(conversationId: String): List<Message> =
            messages[conversationId].orEmpty().toList()

        private fun publishMessages(conversationId: String) {
            messageFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.value = messagesFor(conversationId)
        }
    }

    private class FakeProviderRepository(
        private val providers: List<ProviderConfig>,
        private val apiKeys: Map<String, String> = providers.associate { it.id to "api-key" }
    ) : ProviderRepository {
        constructor(provider: ProviderConfig) : this(listOf(provider))

        override fun observeProviders(): Flow<List<ProviderConfig>> = flowOf(providers)
        override suspend fun getProvider(id: String): ProviderConfig? = providers.firstOrNull { it.id == id }
        override suspend fun getProvidersOnce(): List<ProviderConfig> = providers
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = Unit
        override suspend fun deleteProvider(id: String) = Unit
        override suspend fun ensureTemplates() = Unit
        override suspend fun getApiKey(config: ProviderConfig): String? = apiKeys[config.id]
    }

    private class FakeChatCompletionClient(
        private val scriptedResponses: List<List<ChatDelta>> = emptyList()
    ) : ChatCompletionClient {
        val requests = mutableListOf<ChatRequest>()
        val responseGates = mutableMapOf<Int, kotlinx.coroutines.CompletableDeferred<Unit>>()

        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> {
            val responseIndex = requests.size
            requests += request
            val response = scriptedResponses.getOrNull(responseIndex)
                ?: listOf(ChatDelta.Content("ok"), ChatDelta.Done)
            return kotlinx.coroutines.flow.flow {
                responseGates[responseIndex]?.await()
                response.forEach { emit(it) }
            }
        }
    }

    private class FakeAppUsageReader : AppUsageReader {
        override fun hasUsageAccess(): Boolean = false
        override suspend fun getUsageSummary(range: String): AppUsageSummary =
            AppUsageSummary(
                range = range,
                startTimeMillis = 0L,
                endTimeMillis = 0L,
                totalForegroundTimeMillis = 0L,
                items = emptyList()
            )
        override suspend fun getRecentActivity(hours: Int): RecentAppActivity =
            RecentAppActivity(hours = hours, events = emptyList())
    }

    private class FakeAppLauncher : AppLauncher {
        val openedApps = mutableListOf<String>()

        override suspend fun openApp(appName: String): String {
            openedApps += appName
            return appName
        }
    }

    private class FakeDeviceAssistantGateway : DeviceAssistantGateway {
        override val connectionState: StateFlow<ShizukuConnectionState> = MutableStateFlow(
            ShizukuConnectionState(
                status = ShizukuConnectionStatus.ConnectedAdb,
                capabilities = DeviceCapability.entries.toSet()
            )
        )
        override val accessibilityState: StateFlow<AccessibilityConnectionState> = MutableStateFlow(
            AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
        )
        val forceStoppedApps = mutableListOf<String>()
        val brightnessValues = mutableListOf<Int>()
        var observeCount = 0
        var captureCount = 0
        val captureRegions = mutableListOf<com.flowchat.app.domain.device.ScreenImageRegion?>()
        var screenshot: com.flowchat.app.domain.model.ChatImage? = null
        var observationGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val tappedIndexes = mutableListOf<Int>()
        val visualTargets = mutableListOf<com.flowchat.app.domain.device.VisualTapTarget>()
        var backCount = 0

        override fun refreshConnection() = Unit
        override fun requestPermission() = Unit
        override suspend fun getDeviceStatus() = DeviceToolResult(true, "device ok")
        override suspend fun getForegroundApp() = DeviceToolResult(true, "foreground_package=test")
        override suspend fun setScreenBrightness(percent: Int): DeviceToolResult { brightnessValues += percent; return DeviceToolResult(true, "brightness=$percent") }
        override suspend fun setMediaVolume(percent: Int) = DeviceToolResult(true, "volume=$percent")
        override suspend fun enablePowerMode() = DeviceToolResult(true, "power mode")
        override suspend fun forceStopApp(appName: String): DeviceToolResult {
            forceStoppedApps += appName
            return DeviceToolResult(true, "stopped $appName")
        }
        override suspend fun observeScreen(): DeviceToolResult {
            observeCount += 1
            observationGate?.await()
            return DeviceToolResult(true, "screen", screen = testScreen())
        }
        override suspend fun captureScreen(region: com.flowchat.app.domain.device.ScreenImageRegion?): DeviceToolResult {
            captureCount++
            captureRegions += region
            val observed = observeScreen()
            val captured = observed.screen!!.copy(screenId = "capture-$captureCount")
            return observed.copy(screen = captured, screenImage = screenshot?.let {
                com.flowchat.app.domain.device.DeviceScreenImage(it, captured.screenId, 400, 800, region = region)
            }, imageErrorCode = if (screenshot == null) "screen_capture_unavailable" else null)
        }
        override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult {
            tappedIndexes += index
            return DeviceToolResult(true, "tapped", screen = testScreen())
        }
        override suspend fun tapVisualTarget(target: com.flowchat.app.domain.device.VisualTapTarget): DeviceToolResult {
            visualTargets += target
            val screen = testScreen().copy(screenId = "visual-after")
            return DeviceToolResult(true, "visual gesture completed; verify the resulting image", screen = screen,
                screenImage = screenshot?.let { com.flowchat.app.domain.device.DeviceScreenImage(it, screen.screenId, 400, 800) })
        }
        override suspend fun inputText(index: Int, text: String, clearExisting: Boolean, screenId: String?) =
            DeviceToolResult(true, "typed", screen = testScreen())
        override suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int) =
            DeviceToolResult(true, "swiped", screen = testScreen())
        override suspend fun pressBack(): DeviceToolResult {
            backCount += 1
            return DeviceToolResult(true, "back", screen = testScreen())
        }
        override suspend fun pressHome() = DeviceToolResult(true, "home", screen = testScreen())

        private fun testScreen() = DeviceScreenSnapshot(
            packageName = "com.android.settings",
            activityName = "Settings",
            elements = listOf(
                DeviceUiElement(
                    index = 2,
                    className = "android.widget.TextView",
                    text = "Display page ${tappedIndexes.size}",
                    contentDescription = null,
                    resourceId = null,
                    bounds = DeviceBounds(0, 100, 500, 200),
                    clickable = true,
                    editable = false,
                    scrollable = false,
                    enabled = true
                )
            )
        )
    }

    private class FakePromptProfileRepository : PromptProfileRepository {
        override suspend fun getActiveProfile(): PromptProfileConfig = PromptProfileConfig()
        override suspend fun getActiveThinkingFormat(): String? = null
    }

    private class FakeMemoryRepository : MemoryRepository {
        val records = mutableListOf<MemoryRecord>()
        var revision = 0L
        var reads = 0
        override suspend fun retrieve(userMessage: String, topN: Int): List<MemoryRecord> { reads++; return records.take(topN) }
        override suspend fun snapshot() = com.flowchat.app.domain.model.MemorySnapshot(records.toList(), revision)
        override suspend fun merge(drafts: List<com.flowchat.app.domain.model.MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean): Boolean {
            if (revision != expectedRevision || !canWrite() || drafts.isEmpty()) return false
            drafts.forEach { draft ->
                records.removeAll { it.key == draft.key }
                records += MemoryRecord(draft.key, draft.category, draft.summary, "summary", 1, draft.key, draft.category, 2)
            }
            revision++
            return true
        }
        override suspend fun update(id: String, summary: String) { revision++ }
        override suspend fun getAll(): List<MemoryRecord> = records.toList()
        override suspend fun delete(id: String) { records.removeAll { it.id == id }; revision++ }
        override suspend fun clear() { records.clear(); revision++ }
    }
}
