package com.flowchat.app.presentation.chat

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
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.device.DeviceSafetyReason
import com.flowchat.app.domain.device.DeviceSafetyState
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.device.PowerConnectionMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTaskRuntimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val testModels = mutableListOf<ChatTaskRuntime>()

    @Test fun modelTimeoutIsPersistedAndSharedWithTheAssistantWithoutAnotherRequest() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Error("Request timeout has expired"))))
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        var assistant: ChatUiState? = null
        backgroundScope.launch { fixture.runtime.observeFromAssistant(fixture.a.id).collect { assistant = it } }
        advanceUntilIdle()
        fixture.runtime.updateInput("检查测试文件")
        fixture.runtime.send(); advanceUntilIdle()
        assertEquals("model_timeout", fixture.runtime.uiState.value.taskStopReason)
        assertEquals("model_timeout", assistant?.taskStopReason)
        assertEquals(TaskDisplayPhase.ModelTimedOut, TaskPresentation.from(requireNotNull(assistant)).phase)
        assertEquals(1, client.requests.size)
        assertEquals(MessageStatus.Failed, fixture.repository.getMessages(fixture.a.id).last().status)
    }

    @Test fun explicitStopPersistsItsSourceAndTheAssistantSeesTheSameStoppedTask() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("已有部分内容。"),
            ChatDelta.ToolCalls(listOf(ChatToolCall("read", AgentToolDefinitions.ObserveScreenToolName, "{}"))))))
        val fixture = deviceQueueFixture(client)
        fixture.gateway.observationGate = kotlinx.coroutines.CompletableDeferred()
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        var assistant: ChatUiState? = null
        backgroundScope.launch { fixture.runtime.observeFromAssistant(fixture.a.id).collect { assistant = it } }
        advanceUntilIdle()
        fixture.runtime.updateInput("查看当前页面")
        fixture.runtime.send(); advanceUntilIdle()
        fixture.runtime.stop(); advanceUntilIdle()
        assertEquals("user_stopped", fixture.runtime.uiState.value.taskStopReason)
        assertEquals(TaskDisplayPhase.UserStopped, TaskPresentation.from(requireNotNull(assistant)).phase)
        assertEquals(1, client.requests.size)
        assertTrue(fixture.repository.getMessages(fixture.a.id).last { it.role == MessageRole.Assistant }.content.contains("按你的要求停止"))
        fixture.gateway.observationGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, client.requests.size)
        assertFalse(fixture.runtime.uiState.value.isStreaming)
    }

    @Test fun newTextConversationUsesTheExplicitAssistantModelAndThinkingChoice() = runTest(dispatcher) {
        val fixture = deviceQueueFixture(FakeChatCompletionClient())
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        fixture.settings.setAssistantModelSelection(com.flowchat.app.domain.assistant.AssistantModelSelection(
            fixture.a.providerId, "explicit-model", false, com.flowchat.app.domain.model.ThinkingEffort.Max))
        fixture.runtime.newConversation(); advanceUntilIdle()
        val created = requireNotNull(fixture.runtime.uiState.value.currentConversation)
        assertEquals("explicit-model", created.modelName)
        assertFalse(created.enableThinking)
        assertEquals(com.flowchat.app.domain.model.ThinkingEffort.Max, created.thinkingEffort)
    }

    @Test fun assistantConfigurationChangeDuringPreparationDoesNotDispatchARequest() = runTest(dispatcher) {
        val preparation = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client, preparationGate = preparation)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        runCurrent()
        val provider = fixture.runtime.uiState.value.providers.single()
        val destination = com.flowchat.app.domain.assistant.AssistantTaskDestination(fixture.a.id,
            com.flowchat.app.domain.assistant.AssistantModelSelection.from(fixture.a), provider)
        assertTrue(fixture.runtime.sendFromAssistant(fixture.a.id, "synthetic assistant request", destination = destination))
        runCurrent()
        fixture.repository.updateConversationModel(fixture.a.id, provider.id, "changed-model")
        preparation.complete(Unit); advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertTrue(fixture.runtime.uiState.value.errorMessage.orEmpty().contains("Assistant configuration changed"))
    }

    @Test fun requestCleanupStaysWithItsStoppedTaskAndDoesNotMoveToAnotherConversation() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("unused"), ChatDelta.Done)))
            .apply { responseGates[0] = kotlinx.coroutines.CompletableDeferred() }
        val fixture = deviceQueueFixture(client)
        val runtime = fixture.runtime
        backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        runtime.selectConversation(fixture.a.id); runtime.updateInput("Synthetic phone task"); runtime.send()
        advanceUntilIdle()
        val owner = requireNotNull(runtime.taskProgress.value[fixture.a.id]?.executionDisplayOwnerId)
        val pending = com.flowchat.app.domain.device.RootRequestCleanup("request-A", owner,
            com.flowchat.app.domain.device.RootRequestState.Unverified,
            com.flowchat.app.domain.device.RootRequestCleanupReason.CommandsPresent)
        fixture.gateway.rootRequestCleanup.value = mapOf(pending.requestId to pending)
        runtime.stop(); advanceUntilIdle()
        assertEquals(DeviceTaskPhase.Ended, runtime.uiState.value.deviceTaskProgress?.phase)
        assertEquals(listOf(pending), runtime.uiState.value.rootRequestCleanup)
        runtime.selectConversation(fixture.b.id); advanceUntilIdle()
        assertTrue(runtime.uiState.value.rootRequestCleanup.isEmpty())
        assertTrue(runtime.uiState.value.otherRootRequestCleanupPending)
        runtime.recheckRootRequestCleanup()
        assertEquals(1, fixture.gateway.requestCleanupRechecks)
        val released = pending.copy(state = com.flowchat.app.domain.device.RootRequestState.Released, reason = null)
        fixture.gateway.rootRequestCleanup.value = mapOf(pending.requestId to released)
        advanceUntilIdle()
        assertFalse(runtime.uiState.value.otherRootRequestCleanupPending)
        runtime.selectConversation(fixture.a.id); advanceUntilIdle()
        assertEquals(listOf(released), runtime.uiState.value.rootRequestCleanup)
        assertEquals(1, client.requests.size)
    }

    @Test fun stoppingATaskKeepsItsDisplayCleanupSeparateFromTurnCompletionAndConversationSelection() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("unused"), ChatDelta.Done)))
            .apply { responseGates[0] = kotlinx.coroutines.CompletableDeferred() }
        val fixture = deviceQueueFixture(client, background = true)
        val runtime = fixture.runtime
        backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        runtime.selectConversation(fixture.a.id); runtime.updateInput("Synthetic background task"); runtime.send()
        advanceUntilIdle()
        val owner = requireNotNull(runtime.taskProgress.value[fixture.a.id]?.executionDisplayOwnerId)
        val releasing = com.flowchat.app.domain.device.ExecutionDisplayCleanup(owner,
            com.flowchat.app.domain.device.ExecutionDisplayState.Releasing, 7)
        fixture.gateway.executionDisplayCleanup.value = mapOf(owner to releasing)
        runtime.stop(); advanceUntilIdle()
        assertEquals(DeviceTaskPhase.Ended, runtime.uiState.value.deviceTaskProgress?.phase)
        assertFalse(runtime.uiState.value.isStreaming)
        assertEquals(releasing, runtime.uiState.value.executionDisplayCleanup)
        runtime.selectConversation(fixture.b.id); advanceUntilIdle()
        assertNull(runtime.uiState.value.executionDisplayCleanup)
        assertTrue(runtime.uiState.value.otherExecutionDisplayCleanupPending)
        assertTrue(client.requests.size == 1)
        runtime.recheckExecutionDisplayCleanup()
        assertEquals(1, fixture.gateway.cleanupRechecks)
        val released = releasing.copy(state = com.flowchat.app.domain.device.ExecutionDisplayState.Released)
        fixture.gateway.executionDisplayCleanup.value = mapOf(owner to released)
        advanceUntilIdle()
        assertFalse(runtime.uiState.value.otherExecutionDisplayCleanupPending)
        runtime.selectConversation(fixture.a.id); advanceUntilIdle()
        assertEquals(released, runtime.uiState.value.executionDisplayCleanup)
        assertEquals(1, client.requests.size)
    }

    private fun shareDestination(fixture: DeviceQueueFixture, owner: Conversation = fixture.b) =
        com.flowchat.app.domain.sharing.SharedTextDestination(owner.id, owner.modelName,
            fixture.runtime.uiState.value.providers.single())

    @Test fun sharedTextStagesOnlyToExplicitOwnerAndWaitsForUserSend() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        fixture.runtime.selectConversation(fixture.b.id)
        fixture.runtime.updateInput("B_REQUEST_KEEP")
        fixture.runtime.selectConversation(fixture.a.id)
        fixture.runtime.updateInput("A_DRAFT_KEEP")
        fixture.runtime.attachTextFile("A.txt", "A_ATTACHMENT_PRIVATE")
        val payload = com.flowchat.app.domain.sharing.SharedText("共享链接 https://r6.invalid/ 😀", null, 0)
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.Added,
            fixture.runtime.stageSharedText(payload, shareDestination(fixture)) { 1 })
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertTrue(fixture.repository.getMessages(fixture.b.id).isEmpty())
        assertEquals(fixture.b.id, fixture.runtime.uiState.value.currentConversation?.id)
        assertEquals("B_REQUEST_KEEP", fixture.runtime.uiState.value.input)
        assertTrue(fixture.runtime.uiState.value.pendingAttachment!!.text.contains(payload.text))
        fixture.runtime.selectConversation(fixture.a.id)
        advanceUntilIdle()
        assertEquals("A_DRAFT_KEEP", fixture.runtime.uiState.value.input)
        assertEquals("A.txt", fixture.runtime.uiState.value.pendingAttachment?.name)
        fixture.runtime.selectConversation(fixture.b.id)
        advanceUntilIdle()
        fixture.runtime.send()
        advanceUntilIdle()
        assertEquals(1, client.requests.size)
        assertEquals(fixture.b.id, client.requests.single().usageConversationId)
        val body = client.requests.single().messages.joinToString { it.content.orEmpty() }
        assertTrue(body.contains(payload.text))
        assertTrue(body.contains("untrusted reference content"))
        assertFalse(body.contains("A_ATTACHMENT_PRIVATE"))
        assertTrue(fixture.repository.getMessages(fixture.a.id).isEmpty())
    }

    @Test fun sharedTextPreservesExistingAttachmentAndRejectsRepeatedConfirmation() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        val destination = shareDestination(fixture)
        val payload = com.flowchat.app.domain.sharing.SharedText("shared", null, 0)
        fixture.runtime.selectConversation(fixture.b.id)
        fixture.runtime.attachTextFile("keep.txt", "KEEP")
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.AttachmentExists,
            fixture.runtime.stageSharedText(payload, destination) { 1 })
        fixture.runtime.clearAttachment()
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.Added,
            fixture.runtime.stageSharedText(payload, destination) { 1 })
        fixture.runtime.clearAttachment()
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.AlreadyAdded,
            fixture.runtime.stageSharedText(payload, destination) { 1 })
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertNull(fixture.runtime.uiState.value.pendingAttachment)
    }

    @Test fun sharedTextRejectsExpiredDeletedOrChangedDestinationWithoutFallback() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        val destination = shareDestination(fixture)
        val payload = com.flowchat.app.domain.sharing.SharedText("shared", null, 0)
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.Expired,
            fixture.runtime.stageSharedText(payload, destination) { com.flowchat.app.domain.sharing.SharedText.MaxAgeMillis })
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.DestinationChanged,
            fixture.runtime.stageSharedText(payload, destination.copy(provider = destination.provider.copy(baseUrl = "https://other.invalid"))) { 1 })
        fixture.repository.updateConversationModel(fixture.b.id, fixture.b.providerId, "changed")
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.DestinationChanged,
            fixture.runtime.stageSharedText(payload, destination) { 1 })
        fixture.repository.deleteConversation(fixture.b.id)
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.DestinationChanged,
            fixture.runtime.stageSharedText(payload, destination) { 1 })
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertEquals(fixture.a.id, fixture.runtime.uiState.value.currentConversation?.id)
        assertNull(fixture.runtime.uiState.value.pendingAttachment)
    }

    @Test fun sharedTextSendRejectsModelChangeAndRestoresTheUnsentDraft() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        val payload = com.flowchat.app.domain.sharing.SharedText("MUST_NOT_SEND", null, 0)
        fixture.runtime.stageSharedText(payload, shareDestination(fixture)) { 1 }
        fixture.runtime.updateInput("KEEP_REQUEST")
        fixture.repository.updateConversationModel(fixture.b.id, fixture.b.providerId, "new-model")
        advanceUntilIdle()
        fixture.runtime.send()
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertTrue(fixture.repository.getMessages(fixture.b.id).isEmpty())
        assertEquals("KEEP_REQUEST", fixture.runtime.uiState.value.input)
        assertTrue(fixture.runtime.uiState.value.pendingAttachment!!.text.contains("MUST_NOT_SEND"))
        assertTrue(fixture.runtime.uiState.value.errorMessage!!.contains("destination changed"))
    }

    @Test fun sharedTextDoesNotChangeOrEnterARunningConversation() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        client.responseGates[0] = kotlinx.coroutines.CompletableDeferred()
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        val destination = shareDestination(fixture)
        fixture.runtime.selectConversation(fixture.b.id)
        fixture.runtime.updateInput("RUNNING")
        fixture.runtime.send()
        runCurrent()
        fixture.runtime.selectConversation(fixture.a.id)
        assertEquals(com.flowchat.app.domain.sharing.SharedTextStageResult.Busy,
            fixture.runtime.stageSharedText(com.flowchat.app.domain.sharing.SharedText("shared", null, 0), destination) { 1 })
        advanceUntilIdle()
        assertEquals(fixture.a.id, fixture.runtime.uiState.value.currentConversation?.id)
        assertEquals(1, client.requests.size)
    }

    @Test fun publicEditorHandoffStopsBatchAndDoesNotAskModelToSubmit() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.ToolCalls(listOf(
            ChatToolCall("prefill", com.flowchat.app.domain.device.PublicAppActions.Dialer, """{"phone_number":"01000000000"}"""),
            ChatToolCall("bad-followup", AgentToolDefinitions.TapUiElementToolName, """{"index":1,"screen_id":"old"}"""))))))
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        fixture.runtime.selectConversation(fixture.a.id)
        fixture.runtime.updateInput("只预填测试号码，交给我，不拨号")
        fixture.runtime.send()
        advanceUntilIdle()
        assertEquals(1, fixture.gateway.publicAppCalls)
        assertTrue(fixture.gateway.tappedIndexes.isEmpty())
        assertEquals("No finalization model request after handoff", 1, client.requests.size)
        val messages = fixture.repository.getMessages(fixture.a.id)
        assertTrue(messages.last().content.contains("未执行拨号"))
        assertEquals(MessageStatus.Stopped, messages.last().status)
        assertFalse(fixture.runtime.uiState.value.isStreaming)
    }

    @Test fun publicEditorToolsAreAbsentInBackgroundAndShizukuRequests() = runTest(dispatcher) {
        for ((background, mode) in listOf(true to PowerConnectionMode.Root, false to PowerConnectionMode.Shizuku)) {
            val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.ToolCalls(listOf(
                ChatToolCall("forged", com.flowchat.app.domain.device.PublicAppActions.Dialer, """{"phone_number":"01000000000"}""")))),
                listOf(ChatDelta.Content("Unsupported"), ChatDelta.Done)))
            val fixture = deviceQueueFixture(client, background = background, connection = mode)
            backgroundScope.launch { fixture.runtime.uiState.collect {} }
            advanceUntilIdle()
            fixture.runtime.selectConversation(fixture.a.id)
            fixture.runtime.updateInput("Open dialer")
            fixture.runtime.send()
            advanceUntilIdle()
            assertEquals(0, fixture.gateway.publicAppCalls)
            assertTrue(client.requests.first().tools.none { it.name in com.flowchat.app.domain.device.PublicAppActions.Names })
        }
    }

    @Test fun unknownPublicEditorResultReportsUncertaintyWithoutLockingSubsequentUserTurns() = runTest(dispatcher) {
        val call = ChatToolCall("prefill", com.flowchat.app.domain.device.PublicAppActions.Dialer, """{"phone_number":"01000000000"}""")
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.ToolCalls(listOf(call))),
            listOf(ChatDelta.ToolCalls(listOf(call.copy(id = "followup")))), listOf(ChatDelta.Content("Done"), ChatDelta.Done)))
        val fixture = deviceQueueFixture(client)
        fixture.gateway.publicAppResult = DeviceToolResult(false, "Unknown fields", errorCode = "public_app_prefill_unverified",
            actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.Unverified)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        fixture.runtime.selectConversation(fixture.a.id)
        fixture.runtime.updateInput("只打开预填界面，不拨号")
        fixture.runtime.send()
        advanceUntilIdle()
        assertEquals(1, client.requests.size)
        assertTrue(fixture.repository.getMessages(fixture.a.id).last().content.contains("尚未核实"))
        assertNull(fixture.runtime.uiState.value.taskRecovery)

        fixture.runtime.updateInput("再帮我打开一次")
        fixture.runtime.send()
        advanceUntilIdle()
        assertEquals(2, fixture.gateway.publicAppCalls)
        assertNull(fixture.runtime.uiState.value.taskRecovery)
        assertTrue(fixture.repository.getMessages(fixture.a.id).none { it.executionContext.orEmpty().contains("recovery_verification_required") })
    }

    @Test fun unverifiedToolCallMidTurnDoesNotBlockSubsequentToolCalls() = runTest(dispatcher) {
        val tapCall = ChatToolCall("tap-1", AgentToolDefinitions.TapUiElementToolName, """{"index":2,"screen_id":"s1"}""")
        val brightnessCall = ChatToolCall("bright-1", AgentToolDefinitions.SetBrightnessToolName, """{"percent":75}""")
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(tapCall))),
            listOf(ChatDelta.ToolCalls(listOf(brightnessCall))),
            listOf(ChatDelta.Content("已继续调整亮度为 75%。"), ChatDelta.Done)
        ))
        val fixture = deviceQueueFixture(client)
        fixture.gateway.tapUiElementResult = DeviceToolResult(
            success = false,
            summary = "Unchanged UI",
            errorCode = "ui_action_failed",
            actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.Unverified
        )
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()
        fixture.runtime.selectConversation(fixture.a.id)
        fixture.runtime.updateInput("点一下控件并把亮度调到 75")
        fixture.runtime.send()
        advanceUntilIdle()

        assertEquals(listOf(2), fixture.gateway.tappedIndexes)
        assertEquals(listOf(75), fixture.gateway.brightnessValues)
        assertNull(fixture.runtime.uiState.value.taskRecovery)
        assertEquals("已继续调整亮度为 75%。", fixture.repository.getMessages(fixture.a.id).last().content)
        assertTrue(fixture.repository.getMessages(fixture.a.id).none { it.executionContext.orEmpty().contains("recovery_verification_required") })
    }

    @Test fun screenQuestionRejectsKnownTextOnlyModelWithoutSendingOrConsumingTheDraft() = runTest(dispatcher) {
        val provider = ProviderConfig(id = "text-only", displayName = "Text", baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-chat")
        val owner = Conversation(id = "screen-owner", providerId = provider.id, modelName = provider.defaultModel)
        val chats = FakeChatRepository(listOf(owner), owner)
        val client = FakeChatCompletionClient()
        val bridge = FloatingAssistantBridge()
        val runtime = ChatTaskRuntime(chats, FakeProviderRepository(provider), client, FakeAppUsageReader(),
            FakeAppLauncher(), FakeDeviceAssistantGateway(), FakePromptProfileRepository(), FakeMemoryRepository(),
            AppSettingsStore(AppSettings(powerModeEnabled = false, memoryEnabled = false)), floatingAssistantBridge = bridge,
            contextDispatcher = dispatcher).also { testModels += it }
        backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        runtime.selectConversation(owner.id)
        runtime.updateInput("Keep my draft")
        runtime.attachTextFile("keep.txt", "Keep my attachment")
        assertTrue(runtime.recognizeScreen(owner.id))
        assertFalse(runtime.recognizeScreen(owner.id))
        advanceUntilIdle()
        assertEquals("vision_unsupported", bridge.screenQuestion.value.errorCode)
        assertTrue(client.requests.isEmpty())
        assertTrue(chats.getMessages(owner.id).isEmpty())
        assertEquals("Keep my draft", runtime.uiState.value.input)
        assertEquals("keep.txt", runtime.uiState.value.pendingAttachment?.name)
        runtime.setPhotoAnswerEnabled(false)
        bridge.clearScreenQuestion()
        assertFalse(runtime.recognizeScreen(owner.id))
        advanceUntilIdle()
        assertEquals(com.flowchat.app.presentation.overlay.ScreenQuestionPhase.Idle, bridge.screenQuestion.value.phase)
        assertTrue(chats.getMessages(owner.id).isEmpty())
    }

    @Test fun assistantEntryReconnectsAccessibilityBeforeSendingFirstModelRequest() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client)
        fixture.gateway.accessibilityState.value = AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable)
        fixture.gateway.connectionState.value = ShizukuConnectionState(
            status = ShizukuConnectionStatus.ConnectedRoot,
            capabilities = emptySet()
        )
        fixture.gateway.onReconnect = {
            fixture.gateway.accessibilityState.value = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
            fixture.gateway.connectionState.value = ShizukuConnectionState(
                status = ShizukuConnectionStatus.ConnectedRoot,
                capabilities = setOf(DeviceCapability.ScreenObservation, DeviceCapability.UiElementAction)
            )
        }
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(fixture.runtime.sendFromAssistant(fixture.b.id, "看看当前屏幕"))
        advanceUntilIdle()

        assertEquals(1, fixture.gateway.reconnectCalls)
        assertEquals(1, client.requests.size)
        val toolNames = client.requests.single().tools.map { it.name }
        assertTrue(AgentToolDefinitions.ObserveScreenToolName in toolNames)
    }

    @Test fun sharedSummaryDisabledPowerOrAlreadyConnectedSkipsAccessibilityReconnect() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client)
        fixture.gateway.accessibilityState.value = AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()

        val destination = com.flowchat.app.domain.assistant.AssistantTaskDestination(
            fixture.b.id,
            com.flowchat.app.domain.assistant.AssistantModelSelection(fixture.b.providerId, fixture.b.modelName),
            requireNotNull(fixture.runtime.uiState.value.providers.firstOrNull { it.id == fixture.b.providerId })
        )
        val payload = com.flowchat.app.domain.sharing.SharedText("hello", null, 0L)
        assertTrue(fixture.runtime.sendSharedTextSummary(destination, "总结", payload, "share.txt"))
        advanceUntilIdle()
        assertEquals(0, fixture.gateway.reconnectCalls)

        fixture.settings.setPowerModeEnabled(false)
        assertTrue(fixture.runtime.sendFromAssistant(fixture.b.id, "关闭强力模式发消息"))
        advanceUntilIdle()
        assertEquals(0, fixture.gateway.reconnectCalls)

        fixture.settings.setPowerModeEnabled(true)
        fixture.gateway.accessibilityState.value = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
        assertTrue(fixture.runtime.sendFromAssistant(fixture.b.id, "已连接时发消息"))
        advanceUntilIdle()
        assertEquals(0, fixture.gateway.reconnectCalls)
    }

    @Test fun stoppingAssistantWhileAccessibilityReconnectSuspendsCancelsWithoutModelRequest() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        val fixture = deviceQueueFixture(client)
        fixture.gateway.accessibilityState.value = AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.gateway.reconnectGate = gate
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(fixture.runtime.sendFromAssistant(fixture.b.id, "打开设置"))
        runCurrent()
        assertEquals(1, fixture.gateway.reconnectCalls)
        assertEquals(0, client.requests.size)

        fixture.runtime.stopFromAssistant(fixture.b.id)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, client.requests.size)
        assertEquals("user_stopped", fixture.runtime.uiState.value.taskStopReason.takeIf { fixture.runtime.uiState.value.currentConversation?.id == fixture.b.id } ?: fixture.runtime.observeFromAssistant(fixture.b.id).let {
            var state: ChatUiState? = null
            val job = backgroundScope.launch { it.collect { s -> state = s } }
            runCurrent()
            job.cancel()
            state?.taskStopReason
        })
    }

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        try {
            runTest(dispatcher) {
                val jobs = testModels.mapNotNull { it.scope.coroutineContext[kotlinx.coroutines.Job] }
                jobs.forEach { it.cancel() }
                jobs.forEach { it.join() }
            }
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun assistantEntryKeepsTheEditorSelectionAndDraftAndStopsOnlyItsOwnConversation() = runTest(dispatcher) {
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        val editor = Conversation(id = "editor", providerId = provider.id, modelName = "test")
        val assistant = editor.copy(id = "assistant")
        val chats = FakeChatRepository(listOf(editor, assistant), assistant)
        val client = FakeChatCompletionClient()
        client.responseGates[0] = kotlinx.coroutines.CompletableDeferred()
        val runtime = ChatTaskRuntime(chats, FakeProviderRepository(provider), client, FakeAppUsageReader(),
            FakeAppLauncher(), FakeDeviceAssistantGateway(), FakePromptProfileRepository(), FakeMemoryRepository(),
            AppSettingsStore(AppSettings(powerModeEnabled = false, memoryEnabled = false)), contextDispatcher = dispatcher)
            .also { testModels += it }
        val editorCollection = backgroundScope.launch { runtime.uiState.collect {} }
        val snapshots = mutableListOf<ChatUiState>()
        val assistantCollection = backgroundScope.launch { runtime.observeFromAssistant(assistant.id).collect { snapshots += it } }
        advanceUntilIdle()
        runtime.selectConversation(editor.id)
        runtime.updateInput("KEEP_EDITOR_DRAFT")
        runtime.attachTextFile("editor.txt", "KEEP_EDITOR_ATTACHMENT")
        assertTrue(runtime.sendFromAssistant(assistant.id, "ONLY_ASSISTANT_REQUEST", "PAGE_CONTEXT_OPTED_IN"))
        runCurrent()
        assertFalse(runtime.sendFromAssistant(assistant.id, "DUPLICATE"))
        assertEquals(editor.id, runtime.uiState.value.currentConversation?.id)
        assertEquals("KEEP_EDITOR_DRAFT", runtime.uiState.value.input)
        assertEquals("editor.txt", runtime.uiState.value.pendingAttachment?.name)
        assertTrue(chats.getMessages(editor.id).isEmpty())
        val user = chats.getMessages(assistant.id).first { it.role == MessageRole.User }
        assertEquals("ONLY_ASSISTANT_REQUEST", user.content)
        assertEquals("PAGE_CONTEXT_OPTED_IN", user.attachmentText)
        assertTrue(snapshots.last().isStreaming)
        runtime.stopFromAssistant(assistant.id)
        advanceUntilIdle()
        assertTrue(chats.getMessages(assistant.id).any { it.status == MessageStatus.Stopped })
        assertFalse(snapshots.last().isStreaming)
        assertEquals("KEEP_EDITOR_DRAFT", runtime.uiState.value.input)
        assertEquals(1, client.requests.size)
        editorCollection.cancel(); assistantCollection.cancel()
    }

    @Test
    fun assistantEntryDoesNotInheritAnEditorAttachmentWhenPageContextIsOmitted() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("DONE"), ChatDelta.Done)))
        val runtime = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memoryEnabled = false)
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        runtime.updateInput("KEEP_DRAFT")
        runtime.attachTextFile("private-editor.txt", "PRIVATE_EDITOR_TEXT")
        assertTrue(runtime.sendFromAssistant("device-test", "Assistant request"))
        advanceUntilIdle()
        assertFalse(client.requests.single().messages.any { it.content.orEmpty().contains("PRIVATE_EDITOR_TEXT") })
        assertEquals("KEEP_DRAFT", runtime.uiState.value.input)
        assertEquals("private-editor.txt", runtime.uiState.value.pendingAttachment?.name)
        collection.cancel()
    }

    @Test
    fun clearingTheUiViewModelDoesNotCancelTheSharedTask() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("FINISHED_AFTER_UI_CLEAR"), ChatDelta.Done)))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        client.responseGates[0] = gate
        val runtime = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memoryEnabled = false)
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        val store = androidx.lifecycle.ViewModelStore()
        val original = ChatViewModel(runtime)
        store.put("chat", original)
        original.updateInput("Complete this synthetic task")
        original.send()
        runCurrent()
        assertEquals(1, client.requests.size)
        store.clear()
        runCurrent()
        assertTrue(runtime.uiState.value.isStreaming)
        val replacement = ChatViewModel(runtime)
        assertTrue(replacement.uiState === original.uiState)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(replacement.uiState.value.isStreaming)
        assertTrue(replacement.uiState.value.messages.any { it.content == "FINISHED_AFTER_UI_CLEAR" && it.status == MessageStatus.Complete })
        assertEquals(1, client.requests.size)
        collection.cancel()
    }

    @Test
    fun aFreshUiViewModelCanStopATaskStartedByItsClearedPredecessor() = runTest(dispatcher) {
        val client = FakeChatCompletionClient()
        client.responseGates[0] = kotlinx.coroutines.CompletableDeferred()
        val runtime = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memoryEnabled = false)
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        val store = androidx.lifecycle.ViewModelStore()
        val original = ChatViewModel(runtime)
        store.put("chat", original)
        original.updateInput("Synthetic task awaiting a response")
        original.send()
        runCurrent()
        store.clear()
        ChatViewModel(runtime).stop()
        advanceUntilIdle()
        assertTrue(runtime.activeConversations.value.isEmpty())
        assertTrue(runtime.uiState.value.messages.any { it.role == MessageRole.Assistant && it.status == MessageStatus.Stopped })
        assertEquals(1, client.requests.size)
        collection.cancel()
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
        assertEquals("Paused", viewModel.uiState.value.taskStopReason)
        assertFalse(viewModel.uiState.value.messages.last { it.role == MessageRole.Assistant }.content.contains("at your request"))
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
    fun outsideTouchWhileWaitingForTheNextModelReplyPausesBeforeAQueuedAppRelaunch() = runTest(dispatcher) {
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
        assertTrue(bridge.onOutsideTouch(Long.MAX_VALUE))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deviceTaskPaused)
        assertFalse(viewModel.uiState.value.isStreaming)
        client.responseGates[1]!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(launcher.openedApps.isEmpty())
        viewModel.stop()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.deviceTaskPaused)
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

    @Test fun waitingUsesTheGatewayReadinessCheckWithOneOverlayPreparation() = runTest(dispatcher) {
        com.flowchat.app.domain.device.AutomationTimings.clear()
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("wait", AgentToolDefinitions.WaitForScreenToolName, """{"include_image":false}""")))),
            listOf(ChatDelta.Content("ready"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Wait for the page"); vm.send(); advanceUntilIdle()
        assertEquals(1, gateway.waitCount)
        assertEquals(1, gateway.observeCount)
        assertEquals(0, gateway.captureCount)
        assertEquals(1, com.flowchat.app.domain.device.AutomationTimings.snapshot().count { it.stage == "overlay.prepare" })
        collection.cancel()
    }

    @Test fun textOnlyModelsKeepLocalSwipeVerificationWithoutReceivingImages() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = com.flowchat.app.domain.model.ChatImage("image/png", "PIXELS") }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("swipe", AgentToolDefinitions.SwipeScreenToolName,
                """{"direction":"up","distance_percent":50}""")))), listOf(ChatDelta.Content("done"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, model = "deepseek-v4-pro", memoryEnabled = false,
            providerBaseUrl = "https://api.deepseek.com")
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Swipe up"); vm.send(); advanceUntilIdle()
        val result = client.requests.last().messages.last { it.role == "tool" }
        assertTrue(result.images.isEmpty())
        assertTrue(result.content.orEmpty().contains("model_vision_unsupported"))
        collection.cancel()
    }

    @Test fun conditionalWaitArgumentsAndEvidenceReachTheModelWithoutWriting() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("wait", AgentToolDefinitions.WaitForScreenToolName,
                """{"include_image":false,"timeout_ms":2200,"condition":{"package_name":"finished"}}""")))),
            listOf(ChatDelta.Content("verified"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Wait for the result"); vm.send(); advanceUntilIdle()
        assertEquals(2200L, gateway.conditionalWaitRequests.single().timeoutMillis)
        val evidence = client.requests.last().messages.last { it.role == "tool" }.content.orEmpty()
        assertTrue(evidence, evidence.contains("condition_matched=true"))
        assertTrue(evidence.contains("action_outcome=TargetVerified"))
        assertTrue(gateway.tappedIndexes.isEmpty())
        assertEquals(0, gateway.captureCount)
        collection.cancel()
    }

    @Test fun controlledInteractionsUseTheirTypedArgumentsAndCapturedTargetAndKeepTextOnlyEvidence() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = com.flowchat.app.domain.model.ChatImage("image/png", "LOCAL_ONLY_PIXELS") }
        val tools = listOf(
            AgentToolDefinitions.InputTextToolName to """{"screen_id":"draft","index":2,"text":"中","mode":"replace_selection"}""",
            AgentToolDefinitions.SetTextSelectionToolName to """{"screen_id":"cursor","index":2,"start":1,"end":3}""",
            AgentToolDefinitions.PerformEditorActionToolName to """{"screen_id":"editor","index":2,"expected_label":"搜索"}""",
            AgentToolDefinitions.ScrollContainerToolName to """{"screen_id":"list","index":3,"direction":"down"}""")
        val client = FakeChatCompletionClient(tools.mapIndexed { index, (name, args) ->
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("controlled-$index", name, args))))
        } + listOf(listOf(ChatDelta.Content("inspected"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, model = "deepseek-v4-pro", memoryEnabled = false, providerBaseUrl = "https://api.deepseek.com")
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Edit and inspect the selected phone controls"); vm.send(); advanceUntilIdle()
        assertEquals(listOf("edit", "selection", "editor", "container"), gateway.calls.map { it.first })
        val target = requireNotNull(gateway.calls.first().second)
        assertTrue(gateway.calls.all { it.second == target })
        assertEquals(com.flowchat.app.domain.device.DeviceTextEditMode.ReplaceSelection, gateway.textEdits.single().first.mode)
        assertEquals("draft", gateway.textEdits.single().second)
        assertEquals(com.flowchat.app.domain.device.DeviceTextSelection(2, 1, 3), gateway.selections.single().first)
        assertEquals("搜索", gateway.editorLabels.single())
        assertEquals(com.flowchat.app.domain.device.DeviceScrollDirection.Down, gateway.containerDirections.single())
        val evidence = client.requests.last().messages.filter { it.role == "tool" }
        assertEquals(4, evidence.size)
        assertTrue(evidence.all { it.images.isEmpty() && it.content.orEmpty().contains("image_available=false") })
        assertFalse(vm.uiState.value.messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Failed })
        collection.cancel()
    }

    @Test fun malformedControlledInteractionsNeverReachTheGateway() = runTest(dispatcher) {
        val tools = listOf(
            AgentToolDefinitions.InputTextToolName to """{"screen_id":"s","index":2,"text":"x","clear_existing":true,"mode":"append"}""",
            AgentToolDefinitions.SetTextSelectionToolName to """{"screen_id":"s","index":"2","start":1,"end":3}""",
            AgentToolDefinitions.PerformEditorActionToolName to """{"screen_id":"s","index":2,"expected_label":"搜索","fallback":true}""",
            AgentToolDefinitions.ScrollContainerToolName to """{"screen_id":"s","index":3,"direction":"anywhere"}""",
            AgentToolDefinitions.DragUiElementToolName to """{"screen_id":"s","index":2,"target_index":3,"x":100}""",
            AgentToolDefinitions.DoubleTapUiElementToolName to """{"screen_id":"s","index":"2"}""",
            AgentToolDefinitions.AdjustRangeToolName to """{"screen_id":"s","index":2,"value":"NaN"}""")
        for ((name, args) in tools) {
            val gateway = FakeDeviceAssistantGateway()
            val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("invalid", name, args)))),
                listOf(ChatDelta.Content("stopped"), ChatDelta.Done)))
            val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
            val collection = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle(); vm.updateInput("Inspect without guessing parameters"); vm.send(); advanceUntilIdle()
            assertTrue(name, gateway.calls.isEmpty())
            val evidence = client.requests.last().messages.last { it.role == "tool" }.content.orEmpty()
            assertTrue(evidence, evidence.contains("Invalid $name arguments"))
            assertTrue(gateway.textEdits.isEmpty()); assertTrue(gateway.selections.isEmpty()); assertTrue(gateway.editorLabels.isEmpty()); assertTrue(gateway.containerDirections.isEmpty())
            collection.cancel()
        }
    }

    @Test fun lateControlledCallsCannotReviveAnOldTargetAfterModeChanges() = runTest(dispatcher) {
        for (background in listOf(false, true)) for ((name, args) in listOf(
            AgentToolDefinitions.InputTextToolName to """{"screen_id":"s","index":2,"text":"x","mode":"replace"}""",
            AgentToolDefinitions.SetTextSelectionToolName to """{"screen_id":"s","index":2,"start":1,"end":3}""",
            AgentToolDefinitions.PerformEditorActionToolName to """{"screen_id":"s","index":2,"expected_label":"搜索"}""",
            AgentToolDefinitions.ScrollContainerToolName to """{"screen_id":"s","index":3,"direction":"down"}""",
            AgentToolDefinitions.DragUiElementToolName to """{"screen_id":"s","index":2,"target_index":3}""",
            AgentToolDefinitions.DoubleTapUiElementToolName to """{"screen_id":"s","index":2}""",
            AgentToolDefinitions.AdjustRangeToolName to """{"screen_id":"s","index":2,"value":7}""")) {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("late", name, args))))))
                .apply { responseGates[0] = gate }
            val fixture = deviceQueueFixture(client, background)
            val vm = fixture.runtime
            val collection = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle(); vm.updateInput("Use the selected phone controls"); vm.send(); advanceUntilIdle()
            vm.setBackgroundExecutionEnabled(!background); gate.complete(Unit); advanceUntilIdle()
            assertTrue(name, fixture.gateway.calls.isEmpty())
            assertFalse(fixture.a.id in vm.activeConversations.value)
            collection.cancel()
        }
    }

    @Test fun anchoredGesturesAndRangeValuesKeepTheirTargetAndTextOnlyEvidence() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = com.flowchat.app.domain.model.ChatImage("image/png", "LOCAL_GESTURE_EVIDENCE") }
        val tools = listOf(
            AgentToolDefinitions.DragUiElementToolName to """{"screen_id":"s1","index":2,"target_index":3,"hold_ms":700,"duration_ms":900}""",
            AgentToolDefinitions.DoubleTapUiElementToolName to """{"screen_id":"s2","index":2}""",
            AgentToolDefinitions.AdjustRangeToolName to """{"screen_id":"s3","index":4,"value":7}""")
        val client = FakeChatCompletionClient(tools.mapIndexed { index, (name, args) ->
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("gesture-$index", name, args))))
        } + listOf(listOf(ChatDelta.Content("verified scope"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, model = "deepseek-v4-pro", memoryEnabled = false, providerBaseUrl = "https://api.deepseek.com")
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Use the selected controls"); vm.send(); advanceUntilIdle()
        assertEquals(listOf("drag", "double", "range"), gateway.calls.map { it.first })
        val target = requireNotNull(gateway.calls.first().second)
        assertTrue(gateway.calls.all { it.second == target })
        assertEquals(com.flowchat.app.domain.device.DeviceAnchoredGesture.Drag(2, 3, 700, 900), gateway.anchoredGestures.first())
        assertEquals(com.flowchat.app.domain.device.DeviceRangeAdjustment(4, 7f), gateway.rangeAdjustments.single())
        val evidence = client.requests.last().messages.filter { it.role == "tool" }
        assertEquals(3, evidence.size); assertTrue(evidence.all { it.images.isEmpty() })
        collection.cancel()
    }

    @Test fun directedControlsReachTheGatewayAndMalformedScopesNeverReadTheWholePage() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("scoped", AgentToolDefinitions.ObserveScreenToolName,
                """{"include_image":false,"observation_scope":{"root":{"description":"items"},"kind":"scroll_container"}}""")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("invalid", AgentToolDefinitions.ObserveScreenToolName,
                """{"include_image":false,"observation_scope":{"root":{"index":3}}}""")))),
            listOf(ChatDelta.Content("done"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Read the items"); vm.send(); advanceUntilIdle()
        assertEquals("items", gateway.observationScopes.single().root.description)
        assertEquals(1, gateway.observeCount)
        assertEquals(0, gateway.captureCount)
        assertTrue(client.requests.last().messages.last { it.role == "tool" }.content.orEmpty().contains("Invalid observe_screen arguments"))
        collection.cancel()
    }

    @Test fun regionWaitUsesItsVerifiedFrameWithoutReplacingTheEvidence() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = com.flowchat.app.domain.model.ChatImage("image/png", "VERIFIED_CROP") }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("pixels", AgentToolDefinitions.WaitForScreenToolName,
                """{"condition":{"region_changed":{"screen_id":"crop"}}}""")))),
            listOf(ChatDelta.Content("visual progress"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, model = "gpt-4.1-mini", memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Wait for the cropped area to change"); vm.send(); advanceUntilIdle()
        assertEquals(0, gateway.captureCount)
        assertEquals("crop", gateway.conditionalWaitRequests.single().condition!!.regionChangedFrom)
        val evidence = client.requests.last().messages.last { it.role == "tool" }
        assertEquals("VERIFIED_CROP", evidence.images.single().base64)
        assertTrue(evidence.content.orEmpty().contains("action_outcome=ProgressObserved"))
        collection.cancel()
    }

    @Test fun newImageObservationsChooseTheirScopeAndCanReturnToTheWholeWindow() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway().apply { screenshot = com.flowchat.app.domain.model.ChatImage("image/png", "PIXELS") }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("scoped-image", AgentToolDefinitions.ObserveScreenToolName,
                """{"observation_scope":{"root":{"description":"items"}}}""")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("full-image", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("done"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, model = "gpt-4.1-mini", memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Read the items, then the whole window"); vm.send(); advanceUntilIdle()
        assertEquals(2, gateway.imageObservationScopes.size)
        assertEquals("items", gateway.imageObservationScopes.first()!!.root.description)
        assertEquals(null, gateway.imageObservationScopes.last())
        collection.cancel()
    }

    @Test fun malformedConditionDoesNotFallBackToAnUnconditionalWait() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("wait", AgentToolDefinitions.WaitForScreenToolName,
                """{"include_image":false,"condition":{}}""")))),
            listOf(ChatDelta.Content("invalid condition"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Wait for the result"); vm.send(); advanceUntilIdle()
        assertEquals(0, gateway.waitCount)
        assertTrue(gateway.conditionalWaitRequests.isEmpty())
        assertTrue(client.requests.last().messages.last { it.role == "tool" }.content.orEmpty().contains("Invalid wait_for_screen arguments"))
        collection.cancel()
    }

    @Test fun conditionalWaitsShareOneBoundedBudgetForTheWholeTurn() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        val client = FakeChatCompletionClient((1..4).map { index ->
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("wait-$index", AgentToolDefinitions.WaitForScreenToolName,
                """{"include_image":false,"timeout_ms":10000,"condition":{"package_name":"stage-$index"}}"""))))
        } + listOf(listOf(ChatDelta.Content("budget reached"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, gateway, memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle(); vm.updateInput("Wait through the stages"); vm.send(); advanceUntilIdle()
        assertEquals(3, gateway.conditionalWaitRequests.size)
        assertTrue(client.requests.last().messages.last { it.role == "tool" }.content.orEmpty().contains("screen_wait_budget_exhausted"))
        assertTrue(gateway.tappedIndexes.isEmpty())
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

    @Test fun unverifiedRepeatedWorkPausesWithSavedBudgetAndALocalConclusion() = runTest(dispatcher) {
        val calls = List(8) { index -> listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("volume-$index",
            AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":${index + 10}}""")))) }
        val client = FakeChatCompletionClient(calls + listOf(listOf(ChatDelta.Content("已完成音量调整，其他步骤未执行。"), ChatDelta.Done)))
        val vm = deviceTestViewModel(client, FakeDeviceAssistantGateway(), memoryEnabled = false)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.updateInput("逐步调节音量"); vm.send(); advanceUntilIdle()
        assertEquals(8, client.requests.size)
        assertEquals(com.flowchat.app.domain.chat.TaskBudgetStopReason.NoProgress, vm.uiState.value.taskBudget?.stoppedReason)
        assertEquals(MessageStatus.Stopped, vm.uiState.value.messages.last().status)
        assertTrue(vm.uiState.value.messages.last().content.contains("未获得新的可验证信息"))
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
                        // Known third-party DeepSeek now has a 1M window; cross its compaction threshold.
                        emit(ChatDelta.Reasoning("PRIVATE_REASONING_" + "r".repeat(400_000)))
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

    @Test
    fun coldStartAndPassiveRecoveryNeverRestorePowerPermissions() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.ConnectedDirectRoot)
        val client = FakeChatCompletionClient()
        val runtime = deviceTestViewModel(client, gateway)
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)
        runtime.refreshShizukuConnection()
        runtime.requestShizukuPermission()
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.RootReady)
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)
        assertTrue(runtime.uiState.value.shizukuState.isConnected)
        assertTrue(runtime.uiState.value.shizukuState.capabilities.isNotEmpty())
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.Connecting)
        advanceUntilIdle()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.ConnectedDirectRoot)
        advanceUntilIdle()
        gateway.safetyState.value = gateway.safetyState.value.copy(revision = 1, screenLocked = true, reason = DeviceSafetyReason.Locked)
        advanceUntilIdle()
        gateway.safetyState.value = gateway.safetyState.value.copy(screenLocked = false)
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)
        assertTrue(client.requests.isEmpty())
        collection.cancel()
    }

    @Test
    fun explicitPowerEnableConsumesOneSetupAndAnotherToggleCreatesANewRequest() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.ConnectedDirectRoot)
        val client = FakeChatCompletionClient()
        val runtime = deviceTestViewModel(client, gateway, enabled = false)
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)

        runtime.setPowerModeEnabled(true)
        advanceUntilIdle()
        assertEquals(1, gateway.powerSetupCalls)
        assertTrue(gateway.powerSetupTargets.single() != null)
        assertEquals(0L, gateway.powerSetupTargets.single()?.safetyRevision)
        runtime.refreshShizukuConnection()
        runtime.requestShizukuPermission()
        for (status in listOf(ShizukuConnectionStatus.RootReady, ShizukuConnectionStatus.Connecting,
                ShizukuConnectionStatus.ConnectedDirectRoot)) {
            gateway.connectionState.value = gateway.connectionState.value.copy(status = status)
            advanceUntilIdle()
        }
        gateway.safetyState.value = gateway.safetyState.value.copy(revision = 1, screenLocked = true, reason = DeviceSafetyReason.Locked)
        advanceUntilIdle()
        gateway.safetyState.value = gateway.safetyState.value.copy(screenLocked = false)
        advanceUntilIdle()
        assertEquals(1, gateway.powerSetupCalls)

        runtime.setPowerModeEnabled(false)
        runtime.setPowerModeEnabled(true)
        advanceUntilIdle()
        assertEquals(2, gateway.powerSetupCalls)
        assertEquals(1L, gateway.powerSetupTargets.last()?.safetyRevision)
        assertTrue(client.requests.isEmpty())
        collection.cancel()
    }

    @Test
    fun explicitPowerSetupWaitsForAReadyConnectionAndRunsOnlyOnce() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.Connecting)
        val runtime = deviceTestViewModel(FakeChatCompletionClient(), gateway, enabled = false)
        advanceUntilIdle()
        runtime.setPowerModeEnabled(true)
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)

        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.ConnectedAdb)
        advanceUntilIdle()
        assertEquals(1, gateway.powerSetupCalls)
        assertEquals(0L, gateway.powerSetupTargets.single()?.safetyRevision)
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.Connecting)
        advanceUntilIdle()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.ConnectedAdb)
        advanceUntilIdle()
        assertEquals(1, gateway.powerSetupCalls)
    }

    @Test
    fun safetyChangeDiscardsPowerSetupThatWasWaitingForConnection() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.Connecting)
        val client = FakeChatCompletionClient()
        val runtime = deviceTestViewModel(client, gateway, enabled = false)
        advanceUntilIdle()
        runtime.setPowerModeEnabled(true)
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)

        gateway.safetyState.value = gateway.safetyState.value.copy(revision = 1, reason = DeviceSafetyReason.AccessibilityLost)
        advanceUntilIdle()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.ConnectedAdb)
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun connectionModeChangeDiscardsPowerSetupEvenWhenTheOriginalModeReturns() = runTest(dispatcher) {
        val gateway = FakeDeviceAssistantGateway()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.Connecting)
        val client = FakeChatCompletionClient()
        val runtime = deviceTestViewModel(client, gateway, enabled = false)
        advanceUntilIdle()
        runtime.setPowerModeEnabled(true)
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)

        runtime.setPowerConnectionMode(PowerConnectionMode.Root)
        runtime.setPowerConnectionMode(PowerConnectionMode.Shizuku)
        advanceUntilIdle()
        gateway.connectionState.value = gateway.connectionState.value.copy(status = ShizukuConnectionStatus.ConnectedAdb)
        advanceUntilIdle()
        assertEquals(0, gateway.powerSetupCalls)
        assertTrue(client.requests.isEmpty())
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
    ): ChatTaskRuntime {
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = providerBaseUrl, defaultModel = model)
        val conversation = Conversation(id = "device-test", title = "Device test", providerId = provider.id, modelName = model)
        return ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        assertFalse(viewModel.backgroundWorkActive.value)

        viewModel.confirmDeviceAction()
        advanceUntilIdle()

        assertEquals(listOf("Example"), deviceGateway.forceStoppedApps)
        assertEquals(2, chatClient.requests.size)
        assertTrue(viewModel.uiState.value.pendingDeviceActionConfirmation == null)
        assertFalse(viewModel.backgroundWorkActive.value)
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val viewModel = ChatTaskRuntime(
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
        val vm = ChatTaskRuntime(repository, FakeProviderRepository(provider), client, FakeAppUsageReader(),
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
        val vm = ChatTaskRuntime(repository, FakeProviderRepository(provider), client, FakeAppUsageReader(),
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

    @Test fun switchingTargetsDuringPreparationRejectsTheOldPlanInBothDirections() = runTest(dispatcher) {
        for (background in listOf(false, true)) {
            val preparation = kotlinx.coroutines.CompletableDeferred<Unit>()
            val client = FakeChatCompletionClient(listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("old-back", AgentToolDefinitions.PressBackToolName, "{}")))),
                listOf(ChatDelta.Content("Stopped old phone plan"), ChatDelta.Done)))
            val fixture = deviceQueueFixture(client, background, preparation)
            val vm = fixture.runtime
            val collection = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            vm.selectConversation(fixture.a.id)
            vm.updateInput("Operate the originally selected phone target")
            vm.send()
            advanceUntilIdle()
            val captured = requireNotNull(vm.taskProgress.value[fixture.a.id]).target
            assertEquals(background, captured.backgroundExecution)
            assertEquals(fixture.a.id, captured.ownerConversationId)
            assertTrue(client.requests.isEmpty())

            vm.setBackgroundExecutionEnabled(!background)
            preparation.complete(Unit)
            advanceUntilIdle()

            assertTrue(fixture.gateway.calls.isEmpty())
            assertEquals(0, fixture.gateway.backCount)
            assertFalse(fixture.a.id in vm.activeConversations.value)
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("settings changed"))
            assertEquals(background, captured.backgroundExecution)
            collection.cancel()
        }
    }

    @Test fun aLateFirstModelPhoneCallCannotFollowAModeSwitchInEitherDirection() = runTest(dispatcher) {
        for (background in listOf(false, true)) {
            val response = kotlinx.coroutines.CompletableDeferred<Unit>()
            val client = FakeChatCompletionClient(listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("old-back", AgentToolDefinitions.PressBackToolName, "{}"))))
            )).apply { responseGates[0] = response }
            val fixture = deviceQueueFixture(client, background)
            val vm = fixture.runtime
            val collection = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            vm.selectConversation(fixture.a.id)
            vm.updateInput("Use the selected phone target after planning")
            vm.send()
            advanceUntilIdle()
            assertEquals(DeviceTaskPhase.WaitingForModel, vm.taskProgress.value[fixture.a.id]?.phase)
            assertEquals(1, client.requests.size)

            vm.setBackgroundExecutionEnabled(!background)
            assertTrue(fixture.a.id in vm.activeConversations.value)
            response.complete(Unit)
            advanceUntilIdle()

            assertTrue(fixture.gateway.calls.isEmpty())
            assertFalse(fixture.a.id in vm.activeConversations.value)
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("settings changed"))
            collection.cancel()
        }
    }

    @Test fun aModeSwitchInvalidatesThePhoneOwnerAndItsQueueInBothDirections() = runTest(dispatcher) {
        for (background in listOf(false, true)) {
            val client = FakeChatCompletionClient(listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("owner-observe", AgentToolDefinitions.ObserveScreenToolName,
                    """{"include_image":false}""")))),
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("queued-back", AgentToolDefinitions.PressBackToolName, "{}"))))))
            val fixture = deviceQueueFixture(client, background)
            fixture.gateway.observationGate = kotlinx.coroutines.CompletableDeferred()
            val vm = fixture.runtime
            val collection = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            vm.selectConversation(fixture.a.id)
            vm.updateInput("A owns the phone")
            vm.send()
            advanceUntilIdle()
            assertEquals(DeviceTaskPhase.Operating, vm.taskProgress.value[fixture.a.id]?.phase)
            vm.selectConversation(fixture.b.id)
            vm.updateInput("B must wait for the phone")
            vm.send()
            advanceUntilIdle()
            assertEquals(DeviceTaskPhase.WaitingForDevice, vm.taskProgress.value[fixture.b.id]?.phase)
            assertEquals(listOf("observe"), fixture.gateway.calls.map { it.first })
            val ownerTarget = requireNotNull(fixture.gateway.calls.single().second)
            assertEquals(fixture.a.id, ownerTarget.ownerConversationId)
            assertEquals(background, ownerTarget.backgroundExecution)

            vm.setBackgroundExecutionEnabled(!background)
            fixture.gateway.observationGate!!.complete(Unit)
            advanceUntilIdle()

            assertTrue(vm.activeConversations.value.isEmpty())
            assertEquals(listOf("observe"), fixture.gateway.calls.map { it.first })
            assertEquals(0, fixture.gateway.backCount)
            assertFalse(vm.backgroundWorkActive.value)
            assertFalse(vm.taskProgress.value.values.any { it.phase == DeviceTaskPhase.WaitingForDevice })
            collection.cancel()
        }
    }

    @Test fun switchingDuringAnActiveActionPreventsDispatchAndContinuationInBothDirections() = runTest(dispatcher) {
        for (background in listOf(false, true)) {
            val client = FakeChatCompletionClient(listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("active-back", AgentToolDefinitions.PressBackToolName, "{}")))),
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("late-back", AgentToolDefinitions.PressBackToolName, "{}"))))))
            val fixture = deviceQueueFixture(client, background)
            fixture.gateway.actionGate = kotlinx.coroutines.CompletableDeferred()
            val vm = fixture.runtime
            val collection = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            vm.selectConversation(fixture.a.id)
            vm.updateInput("Act once on the selected phone target")
            vm.send()
            advanceUntilIdle()
            assertEquals(DeviceTaskPhase.Operating, vm.taskProgress.value[fixture.a.id]?.phase)
            assertEquals(listOf("back"), fixture.gateway.calls.map { it.first })
            assertEquals(background, fixture.gateway.calls.single().second?.backgroundExecution)
            assertEquals(0, fixture.gateway.backCount)

            vm.setBackgroundExecutionEnabled(!background)
            fixture.gateway.actionGate!!.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, fixture.gateway.backCount)
            assertEquals(1, client.requests.size)
            assertFalse(fixture.a.id in vm.activeConversations.value)
            assertFalse(vm.backgroundWorkActive.value)
            collection.cancel()
        }
    }

    @Test fun stoppedRunWaitingForAnUncancellableResponseDoesNotContinuouslyRenewPower() = runTest(dispatcher) {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("Late"), ChatDelta.Done))).apply {
            responseGates[0] = release
            nonCancellableGates += 0
        }
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(fixture.a.id); vm.updateInput("Synthetic blocked response"); vm.send()
        advanceUntilIdle()
        val runId = vm.taskPowerOwners.value.single().runId
        vm.stop()
        runCurrent()
        assertFalse(fixture.a.id in vm.activeConversations.value)
        assertEquals(runId, vm.taskPowerOwners.value.single().runId)
        assertEquals(com.flowchat.app.domain.chat.TaskPowerWork.WaitingForCleanup, vm.taskPowerOwners.value.single().work)
        assertFalse(vm.backgroundWorkActive.value)
        advanceTimeBy(180_000); runCurrent()
        assertFalse(vm.backgroundWorkActive.value)
        assertEquals(1, client.requests.size)
        vm.updateInput("New synthetic run waits for prior cleanup"); vm.send()
        runCurrent()
        assertEquals(DeviceTaskPhase.WaitingForCleanup, vm.taskProgress.value[fixture.a.id]?.phase)
        assertEquals(2, vm.taskPowerOwners.value.size)
        assertTrue(vm.taskPowerOwners.value.none { it.needsPower })
        assertFalse(vm.backgroundWorkActive.value)
        assertEquals(1, client.requests.size)
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.taskPowerOwners.value.isEmpty())
        assertEquals(2, client.requests.size)
    }

    @Test fun stoppedRunRetainsPowerOwnershipUntilItsStoppedMessageHasActuallyBeenSaved() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("Waiting"), ChatDelta.Done)))
            .apply { responseGates[0] = kotlinx.coroutines.CompletableDeferred() }
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(fixture.a.id); vm.updateInput("Synthetic request"); vm.send()
        advanceUntilIdle()
        val run = vm.taskPowerOwners.value.single().runId
        val saved = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.repository.beforeUpdateMessage = { status -> if (status == MessageStatus.Stopped) saved.await() }
        vm.stop()
        runCurrent()
        assertFalse(fixture.a.id in vm.activeConversations.value)
        assertEquals(run, vm.taskPowerOwners.value.single().runId)
        assertEquals(com.flowchat.app.domain.chat.TaskPowerWork.Finalizing, vm.taskPowerOwners.value.single().work)
        assertTrue(vm.backgroundWorkActive.value)
        saved.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.taskPowerOwners.value.isEmpty())
        assertFalse(vm.backgroundWorkActive.value)
    }

    @Test fun waitingForConfirmationAndThePhoneDoesNotRenewPowerOrInterleaveTasks() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("owner-stop", AgentToolDefinitions.ForceStopAppToolName,
                """{"app_name":"Synthetic app"}""")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("queued-back", AgentToolDefinitions.PressBackToolName, "{}")))),
            listOf(ChatDelta.Content("Owner finished"), ChatDelta.Done),
            listOf(ChatDelta.Content("Queued task finished"), ChatDelta.Done)))
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(fixture.a.id)
        vm.updateInput("Stop the synthetic app after confirmation")
        vm.send()
        advanceUntilIdle()
        assertEquals(DeviceTaskPhase.WaitingForUser, vm.taskProgress.value[fixture.a.id]?.phase)
        assertFalse(vm.backgroundWorkActive.value)
        vm.selectConversation(fixture.b.id)
        vm.updateInput("Wait for the phone and go back")
        vm.send()
        advanceUntilIdle()

        val queued = requireNotNull(vm.taskProgress.value[fixture.b.id])
        assertEquals(DeviceTaskPhase.WaitingForDevice, queued.phase)
        assertEquals(fixture.b.id, queued.ownerConversationId)
        assertTrue(queued.runId.isNotBlank())
        assertTrue(queued.queueOrder != null)
        assertTrue(fixture.gateway.calls.isEmpty())
        assertFalse(vm.backgroundWorkActive.value)

        assertEquals(setOf(fixture.a.id, fixture.b.id), vm.taskPowerOwners.value.map { it.conversationId }.toSet())
        assertTrue(vm.taskPowerOwners.value.none { it.needsPower })
        vm.selectConversation(fixture.a.id)
        vm.confirmDeviceAction()
        advanceUntilIdle()

        assertEquals(listOf("Synthetic app"), fixture.gateway.forceStoppedApps)
        assertEquals(1, fixture.gateway.backCount)
        assertEquals(listOf("force_stop", "back"), fixture.gateway.calls.map { it.first })
        assertEquals(listOf(fixture.a.id, fixture.b.id), fixture.gateway.calls.map { it.second?.ownerConversationId })
        assertEquals("Owner finished", fixture.repository.messagesFor(fixture.a.id).last().content)
        assertEquals("Queued task finished", fixture.repository.messagesFor(fixture.b.id).last().content)
        assertFalse(vm.backgroundWorkActive.value)
        collection.cancel()
    }

    @Test fun cancellingAQueuedPhoneTaskCannotReviveItWhenTheOwnerFinishes() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("owner-observe", AgentToolDefinitions.ObserveScreenToolName,
                """{"include_image":false}""")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("queued-back", AgentToolDefinitions.PressBackToolName, "{}")))),
            listOf(ChatDelta.Content("Owner finished"), ChatDelta.Done)))
        val fixture = deviceQueueFixture(client, background = true)
        fixture.gateway.observationGate = kotlinx.coroutines.CompletableDeferred()
        val vm = fixture.runtime
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(fixture.a.id)
        vm.updateInput("A observes the phone")
        vm.send()
        advanceUntilIdle()
        vm.selectConversation(fixture.b.id)
        vm.updateInput("B waits for the phone")
        vm.send()
        advanceUntilIdle()
        assertEquals(DeviceTaskPhase.WaitingForDevice, vm.taskProgress.value[fixture.b.id]?.phase)

        vm.stop()
        advanceUntilIdle()
        assertFalse(fixture.b.id in vm.activeConversations.value)
        assertTrue(fixture.a.id in vm.activeConversations.value)
        assertTrue(vm.backgroundWorkActive.value)
        fixture.gateway.observationGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("observe"), fixture.gateway.calls.map { it.first })
        assertEquals(0, fixture.gateway.backCount)
        assertEquals(MessageStatus.Stopped, fixture.repository.messagesFor(fixture.b.id).last { it.role == MessageRole.Assistant }.status)
        assertEquals(MessageStatus.Cancelled, fixture.repository.messagesFor(fixture.b.id).last { it.role == MessageRole.Tool }.status)
        assertEquals("Owner finished", fixture.repository.messagesFor(fixture.a.id).last().content)
        assertFalse(vm.backgroundWorkActive.value)
        collection.cancel()
    }

    @Test fun switchingConnectionAwayAndBackStillInvalidatesALatePhonePlan() = runTest(dispatcher) {
        for (connection in PowerConnectionMode.entries) {
            val response = kotlinx.coroutines.CompletableDeferred<Unit>()
            val client = FakeChatCompletionClient(listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("old-back", AgentToolDefinitions.PressBackToolName, "{}"))))
            )).apply { responseGates[0] = response }
            val fixture = deviceQueueFixture(client, connection = connection)
            val vm = fixture.runtime
            val collection = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            vm.selectConversation(fixture.a.id)
            vm.updateInput("Plan an action using the original connection")
            vm.send()
            advanceUntilIdle()
            val target = requireNotNull(vm.taskProgress.value[fixture.a.id]).target
            val other = PowerConnectionMode.entries.single { it != connection }

            vm.setPowerConnectionMode(other)
            vm.setPowerConnectionMode(connection)
            assertEquals(target.configurationVersion + 2, fixture.settings.state.value.deviceConfigurationVersion)
            response.complete(Unit)
            advanceUntilIdle()

            assertTrue(fixture.gateway.calls.isEmpty())
            assertFalse(fixture.a.id in vm.activeConversations.value)
            assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("settings changed"))
            collection.cancel()
        }
    }

    @Test fun ordinaryChatSurvivesModeConnectionAndPowerChangesWhileThePhoneIsPlanning() = runTest(dispatcher) {
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("Ordinary chat completed"), ChatDelta.Done)))
            .apply { responseGates[0] = response }
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(fixture.a.id)
        vm.updateInput("Answer this ordinary question without using the phone")
        vm.send()
        advanceUntilIdle()
        assertEquals(DeviceTaskPhase.WaitingForModel, vm.taskProgress.value[fixture.a.id]?.phase)

        vm.setBackgroundExecutionEnabled(true)
        vm.setBackgroundExecutionEnabled(false)
        vm.setPowerConnectionMode(PowerConnectionMode.Shizuku)
        vm.setPowerConnectionMode(PowerConnectionMode.Root)
        vm.setPowerModeEnabled(false)
        vm.setPowerModeEnabled(true)
        advanceUntilIdle()
        assertTrue(fixture.a.id in vm.activeConversations.value)
        assertTrue(vm.backgroundWorkActive.value)
        response.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, client.requests.size)
        assertTrue(fixture.gateway.calls.isEmpty())
        assertEquals("Ordinary chat completed", fixture.repository.messagesFor(fixture.a.id).last().content)
        assertEquals(MessageStatus.Complete, fixture.repository.messagesFor(fixture.a.id).last().status)
        assertFalse(vm.backgroundWorkActive.value)
        collection.cancel()
    }

    @Test fun aLateApprovalAfterAModeSwitchCannotApproveANewPhoneRun() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("old-stop", AgentToolDefinitions.ForceStopAppToolName,
                """{"app_name":"Old synthetic app"}""")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("new-stop", AgentToolDefinitions.ForceStopAppToolName,
                """{"app_name":"New synthetic app"}""")))),
            listOf(ChatDelta.Content("New request cancelled"), ChatDelta.Done)))
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(fixture.a.id)
        vm.updateInput("Request the old confirmation")
        vm.send()
        advanceUntilIdle()
        val old = requireNotNull(vm.uiState.value.pendingDeviceActionConfirmation)
        val oldRun = requireNotNull(vm.taskProgress.value[fixture.a.id])

        vm.setBackgroundExecutionEnabled(true)
        vm.setBackgroundExecutionEnabled(false)
        advanceUntilIdle()
        vm.respondFromAssistant(fixture.a.id, old.requestId, true)
        advanceUntilIdle()
        assertTrue(fixture.gateway.forceStoppedApps.isEmpty())
        assertEquals(null, vm.uiState.value.pendingDeviceActionConfirmation)

        vm.updateInput("Request a new confirmation after observing the selected target")
        vm.send()
        advanceUntilIdle()
        val fresh = requireNotNull(vm.uiState.value.pendingDeviceActionConfirmation)
        val freshRun = requireNotNull(vm.taskProgress.value[fixture.a.id])
        assertTrue(old.requestId != fresh.requestId)
        assertTrue(oldRun.runId != freshRun.runId)
        assertEquals(oldRun.target.configurationVersion + 2, freshRun.target.configurationVersion)
        vm.respondFromAssistant(fixture.a.id, old.requestId, true)
        advanceUntilIdle()
        assertEquals(fresh, vm.uiState.value.pendingDeviceActionConfirmation)
        assertTrue(fixture.gateway.forceStoppedApps.isEmpty())
        vm.cancelDeviceAction()
        advanceUntilIdle()
        assertTrue(fixture.gateway.calls.isEmpty())
        assertEquals("New request cancelled", fixture.repository.messagesFor(fixture.a.id).last().content)
        collection.cancel()
    }

    @Test fun savingTheSameDeviceSettingsDoesNotInvalidateALegitimatePhonePlan() = runTest(dispatcher) {
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("back", AgentToolDefinitions.PressBackToolName, "{}")))),
            listOf(ChatDelta.Content("Phone plan completed"), ChatDelta.Done)
        )).apply { responseGates[0] = response }
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectConversation(fixture.a.id)
        vm.updateInput("Complete this phone plan without changing its target")
        vm.send()
        advanceUntilIdle()
        val captured = requireNotNull(vm.taskProgress.value[fixture.a.id]).target

        vm.setPowerModeEnabled(true)
        vm.setBackgroundExecutionEnabled(false)
        vm.setPowerConnectionMode(PowerConnectionMode.Root)
        assertEquals(captured.configurationVersion, fixture.settings.state.value.deviceConfigurationVersion)
        response.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fixture.gateway.backCount)
        assertEquals(captured, fixture.gateway.calls.single().second)
        assertEquals("Phone plan completed", fixture.repository.messagesFor(fixture.a.id).last().content)
        assertEquals(MessageStatus.Complete, fixture.repository.messagesFor(fixture.a.id).last().status)
        collection.cancel()
    }

    private data class DeviceQueueFixture(
        val runtime: ChatTaskRuntime,
        val repository: FakeChatRepository,
        val gateway: FakeDeviceAssistantGateway,
        val settings: AppSettingsStore,
        val a: Conversation,
        val b: Conversation
    )

    private fun deviceQueueFixture(
        client: FakeChatCompletionClient,
        background: Boolean = false,
        preparationGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
        connection: PowerConnectionMode = PowerConnectionMode.Root,
        launcher: FakeAppLauncher = FakeAppLauncher()
    ): DeviceQueueFixture {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a).apply { recoveryGate = preparationGate }
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false,
            backgroundExecutionEnabled = background, powerConnectionMode = connection, installId = "synthetic-device-queue"))
        val gateway = FakeDeviceAssistantGateway(settings)
        val provider = ProviderConfig(id = "provider-1", displayName = "Synthetic queue provider",
            baseUrl = "https://example.test", defaultModel = "test")
        val runtime = ChatTaskRuntime(repository, FakeProviderRepository(provider), client, FakeAppUsageReader(),
            launcher, gateway, FakePromptProfileRepository(), FakeMemoryRepository(), settings,
            contextDispatcher = dispatcher).also { testModels += it }
        return DeviceQueueFixture(runtime, repository, gateway, settings, a, b)
    }

    private fun conversationTestViewModel(repository: FakeChatRepository, client: ChatCompletionClient): ChatTaskRuntime {
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        return ChatTaskRuntime(
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
        val vm = ChatTaskRuntime(repository, FakeProviderRepository(provider), client, FakeAppUsageReader(), FakeAppLauncher(),
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

    @Test fun deletingAPausedTaskWaitsForItsEndingRecordBeforeRemovingTheConversation() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.ToolCalls(listOf(
            ChatToolCall("observe-before-delete", AgentToolDefinitions.ObserveScreenToolName, "{}")
        )))))
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        val repository = fixture.repository
        fixture.gateway.observationGate = kotlinx.coroutines.CompletableDeferred()
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertTrue(vm.sendFromAssistant(fixture.a.id, "Inspect the page before deletion"))
        advanceUntilIdle()
        assertEquals(1, fixture.gateway.observeCount)

        val safety = fixture.gateway.safetyState.value
        fixture.gateway.safetyState.value = safety.copy(revision = safety.revision + 1,
            reason = DeviceSafetyReason.AccessibilityLost)
        advanceUntilIdle()
        assertEquals(DeviceTaskPhase.Paused, vm.taskProgress.value[fixture.a.id]?.phase)
        assertFalse(fixture.a.id in vm.activeConversations.value)

        val endingAppendStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseEndingAppend = kotlinx.coroutines.CompletableDeferred<Unit>()
        repository.beforeAppendMessage = { owner, role, status ->
            if (owner == fixture.a.id && role == MessageRole.Assistant && status == MessageStatus.Complete) {
                endingAppendStarted.complete(Unit)
                releaseEndingAppend.await()
            }
        }
        vm.stopFromAssistant(fixture.a.id)
        advanceUntilIdle()
        assertTrue("Ending the paused task must reach its final record", endingAppendStarted.isCompleted)

        vm.deleteConversation(fixture.a.id)
        advanceUntilIdle()
        val deleteEvent = "delete:${fixture.a.id}"
        assertFalse("The ending record still owns the conversation", deleteEvent in repository.persistenceEvents)
        assertTrue(repository.getConversation(fixture.a.id) != null)

        releaseEndingAppend.complete(Unit)
        advanceUntilIdle()
        val endingEvent = "append:${fixture.a.id}:${MessageRole.Assistant}:${MessageStatus.Complete}"
        assertTrue(repository.persistenceEvents.indexOf(endingEvent) >= 0)
        assertTrue(repository.persistenceEvents.indexOf(endingEvent) < repository.persistenceEvents.indexOf(deleteEvent))
        assertEquals(null, repository.getConversation(fixture.a.id))
        assertTrue(repository.messagesFor(fixture.a.id).isEmpty())
        val finishedEvents = repository.persistenceEvents.toList()
        advanceUntilIdle()
        assertEquals("No finishing job may append after deletion", finishedEvents, repository.persistenceEvents)
        assertEquals(deleteEvent, repository.persistenceEvents.last { it.contains(fixture.a.id) })
        assertEquals(1, client.requests.size)
        collection.cancel()
    }

    @Test fun deletingAConversationRejectsItsNewRequestsWhileAnotherConversationCanFinish() = runTest(dispatcher) {
        val (a, b) = twoConversations()
        val repository = FakeChatRepository(listOf(a, b), a)
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("B remains available"), ChatDelta.Done)))
        val vm = conversationTestViewModel(repository, client)
        val deleteStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseDelete = kotlinx.coroutines.CompletableDeferred<Unit>()
        repository.beforeDeleteConversation = { owner ->
            if (owner == a.id) {
                deleteStarted.complete(Unit)
                releaseDelete.await()
            }
        }
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.deleteConversation(a.id)
        advanceUntilIdle()
        assertTrue(deleteStarted.isCompleted)
        assertTrue(repository.getConversation(a.id) != null)

        assertFalse("A pending deletion must reject a new assistant request", vm.sendFromAssistant(a.id, "Do not start A"))
        vm.selectConversation(a.id)
        vm.updateInput("Do not start A from the editor either")
        vm.send()
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertTrue(repository.messagesFor(a.id).isEmpty())

        vm.selectConversation(b.id)
        vm.updateInput("Continue ordinary chat B")
        vm.send()
        advanceUntilIdle()
        assertEquals(listOf(b.id), client.requests.map { it.usageConversationId })
        assertEquals("B remains available", repository.messagesFor(b.id).last().content)
        assertEquals(MessageStatus.Complete, repository.messagesFor(b.id).last().status)
        assertFalse("B can finish before A's deletion is released", "delete:${a.id}" in repository.persistenceEvents)

        releaseDelete.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, repository.getConversation(a.id))
        assertTrue(repository.messagesFor(a.id).isEmpty())
        assertEquals(b.id, repository.getConversation(b.id)?.id)
        assertEquals(1, repository.persistenceEvents.count { it == "delete:${a.id}" })
        assertEquals(listOf(b.id), client.requests.map { it.usageConversationId })
        collection.cancel()
    }

    @Test fun lockedAssistantTaskWaitsForUnlockAndResumesSameConversationAfterUnlock() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("obs-locked", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("obs-unlocked", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("计算完成 114"), ChatDelta.Done)
        ))
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        fixture.gateway.safetyState.value = DeviceSafetyState(
            revision = 1,
            screenLocked = true,
            screenInteractive = true,
            reason = DeviceSafetyReason.Locked
        )
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(vm.sendFromAssistant(fixture.a.id, "用计算器算19乘6"))
        advanceUntilIdle()
        assertEquals(0, fixture.gateway.observeCount)
        assertTrue(vm.isWaitingForUnlock(fixture.a.id))
        assertFalse(vm.resumeFromAssistant(fixture.a.id))

        fixture.gateway.safetyState.value = DeviceSafetyState(
            revision = 2,
            screenLocked = false,
            screenInteractive = true,
            reason = null
        )
        assertTrue(vm.resumeFromAssistant(fixture.a.id))
        advanceUntilIdle()

        assertFalse(vm.isWaitingForUnlock(fixture.a.id))
        assertEquals(1, fixture.gateway.observeCount)
        assertEquals(3, client.requests.size)
        assertEquals(listOf(fixture.a.id, fixture.a.id, fixture.a.id), client.requests.map { it.usageConversationId })
        val lastReply = fixture.repository.messagesFor(fixture.a.id).last()
        assertEquals(MessageStatus.Complete, lastReply.status)
        assertEquals("计算完成 114", lastReply.content)
        collection.cancel()
    }

    @Test fun stoppedWhileWaitingForUnlockNeverResumesAfterUnlock() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("obs-locked", AgentToolDefinitions.ObserveScreenToolName, "{}"))))
        ))
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        fixture.gateway.safetyState.value = DeviceSafetyState(
            revision = 1,
            screenLocked = true,
            screenInteractive = false,
            reason = DeviceSafetyReason.ScreenOff
        )
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(vm.sendFromAssistant(fixture.a.id, "用计算器算19乘6"))
        advanceUntilIdle()
        assertTrue(vm.isWaitingForUnlock(fixture.a.id))

        vm.stopFromAssistant(fixture.a.id)
        advanceUntilIdle()
        assertFalse(vm.isWaitingForUnlock(fixture.a.id))

        fixture.gateway.safetyState.value = DeviceSafetyState(
            revision = 2,
            screenLocked = false,
            screenInteractive = true,
            reason = null
        )
        assertFalse(vm.resumeFromAssistant(fixture.a.id))
        advanceUntilIdle()
        assertEquals(0, fixture.gateway.observeCount)
        assertEquals(1, client.requests.size)
        collection.cancel()
    }

    @Test fun lockedAssistantAppLaunchCanDispatchOnceAfterUnlock() = runTest(dispatcher) {
        val arguments = """{"app_name":"com.android.settings"}"""
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("locked-open", AgentToolDefinitions.OpenAppToolName, arguments)))),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("unlocked-open", AgentToolDefinitions.OpenAppToolName, arguments)))),
            listOf(ChatDelta.Content("系统设置已打开。"), ChatDelta.Done)
        ))
        val launcher = FakeAppLauncher()
        val fixture = deviceQueueFixture(client, launcher = launcher)
        val runtime = fixture.runtime
        val locked = DeviceSafetyState(revision = 1, screenLocked = true, screenInteractive = true,
            reason = DeviceSafetyReason.Locked)
        fixture.gateway.safetyState.value = locked
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(runtime.sendFromAssistant(fixture.a.id, "打开系统设置"))
        advanceUntilIdle()
        assertTrue(runtime.isWaitingForUnlock(fixture.a.id))
        assertTrue(launcher.openedApps.isEmpty())
        val paused = fixture.repository.messagesFor(fixture.a.id)
        assertTrue(paused.any { it.executionContext.orEmpty().contains("cancelled_not_dispatched") })
        assertFalse(paused.any { it.executionContext.orEmpty().contains("interrupted_unconfirmed") })

        fixture.gateway.safetyState.value = locked.copy(revision = 2, screenLocked = false, reason = null)
        assertTrue(runtime.resumeFromAssistant(fixture.a.id))
        advanceUntilIdle()

        assertEquals(listOf("com.android.settings"), launcher.openedApps)
        assertEquals(List(3) { fixture.a.id }, client.requests.map { it.usageConversationId })
        assertEquals("系统设置已打开。", fixture.repository.messagesFor(fixture.a.id).last().content)
        collection.cancel()
    }

    @Test fun accessibilityLostPauseIsNotClassifiedAsWaitingForUnlock() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("obs-a11y", AgentToolDefinitions.ObserveScreenToolName, "{}"))))
        ))
        val fixture = deviceQueueFixture(client)
        val vm = fixture.runtime
        fixture.gateway.observationGate = kotlinx.coroutines.CompletableDeferred()
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(vm.sendFromAssistant(fixture.a.id, "查看当前页面"))
        advanceUntilIdle()
        val safety = fixture.gateway.safetyState.value
        fixture.gateway.safetyState.value = safety.copy(revision = safety.revision + 1, reason = DeviceSafetyReason.AccessibilityLost)
        advanceUntilIdle()

        assertFalse(vm.isWaitingForUnlock(fixture.a.id))
        assertFalse(vm.resumeFromAssistant(fixture.a.id))
        collection.cancel()
    }

    @Test fun deviceTimeToolAnswersReadOnlyWithoutApprovalOrPanelYield() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        var panelYieldRequests = 0
        bridge.setExternalPanelYieldHandler { panelYieldRequests++ }
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("time-1", AgentToolDefinitions.DeviceTimeToolName, "{}")))),
            listOf(ChatDelta.Content("现在是早上六点十分，星期日。"), ChatDelta.Done)
        ))
        val repository = FakeChatRepository(listOf(Conversation("voice-chat", "Voice", "provider-1", "test")),
            Conversation("voice-chat", "Voice", "provider-1", "test"))
        val provider = ProviderConfig("provider-1", "Test", "https://example.test", "test")
        val runtime = ChatTaskRuntime(
            chatRepository = repository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = client,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = FakeDeviceAssistantGateway().apply {
                safetyState.value = DeviceSafetyState(revision = 1, screenLocked = true, screenInteractive = true,
                    reason = DeviceSafetyReason.Locked)
            },
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = false, memoryEnabled = false, installId = "test")),
            floatingAssistantBridge = bridge,
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(runtime.sendFromAssistant("voice-chat", "现在几点了，今天星期几？", conciseVoiceReply = true))
        advanceUntilIdle()

        val firstReq = client.requests.first()
        assertTrue(firstReq.tools.any { it.name == AgentToolDefinitions.DeviceTimeToolName })
        assertTrue(firstReq.messages.any { it.role == "system" && it.content.orEmpty().contains("Native voice reply constraint") })
        val toolContent = client.requests.last().messages.last { it.role == "tool" }.content.orEmpty()
        assertTrue(toolContent.contains("local_date"))
        assertTrue(toolContent.contains("local_time"))
        assertTrue(toolContent.contains("weekday_zh"))
        assertTrue(toolContent.contains("timezone"))
        assertEquals(0, panelYieldRequests)
        assertFalse(runtime.isWaitingForUserConfirmation("voice-chat"))
        assertFalse(runtime.isWaitingForUnlock("voice-chat"))
        assertEquals("现在是早上六点十分，星期日。", repository.messagesFor("voice-chat").last().content)
        collection.cancel()
    }

    @Test fun unboundAccessibilityGivesOneClearReasonOnOpenAppAndUiFallback() = runTest(dispatcher) {
        val noUiCaps = DeviceCapability.entries.toSet() - setOf(
            DeviceCapability.ScreenObservation,
            DeviceCapability.UiElementAction,
            DeviceCapability.VisualTargetAction,
            DeviceCapability.TextInput,
            DeviceCapability.ScreenSwipe,
            DeviceCapability.Navigation
        )
        val states = listOf(
            AccessibilityConnectionState(AccessibilityConnectionStatus.Disabled, enabled = false) to "未开启 Floryx 无障碍服务",
            AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable, enabled = true) to "已开启但尚未连接绑定",
            AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable, enabled = null) to "未能确认或连接 Floryx 无障碍服务"
        )
        for ((state, expectedHint) in states) {
            val client = FakeChatCompletionClient(listOf(
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("open", AgentToolDefinitions.OpenAppToolName, """{"app_name":"计算器"}""")))),
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("obs", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
                listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("tap", AgentToolDefinitions.TapUiElementToolName, """{"screen_id":"s","index":0,"long_press":false}""")))),
                listOf(ChatDelta.Content("已打开计算器，但当前无障碍未连接。"), ChatDelta.Done)
            ))
            val fixture = deviceQueueFixture(client)
            fixture.gateway.accessibilityState.value = state
            fixture.gateway.connectionState.value = fixture.gateway.connectionState.value.copy(capabilities = noUiCaps)
            val collection = backgroundScope.launch { fixture.runtime.uiState.collect {} }
            advanceUntilIdle()

            assertTrue(fixture.runtime.sendFromAssistant(fixture.a.id, "用计算器算 63 乘 9"))
            advanceUntilIdle()

            assertFalse(client.requests.first().tools.any { it.name == AgentToolDefinitions.ObserveScreenToolName })
            val toolMsgs = client.requests.last().messages.filter { it.role == "tool" }.map { it.content.orEmpty() }
            assertEquals(3, toolMsgs.size)
            assertTrue("Expected $expectedHint in open_app tool result: ${toolMsgs[0]}",
                toolMsgs[0].contains("accessibility_unavailable") && toolMsgs[0].contains(expectedHint))
            assertTrue("Expected tool_unavailable (accessibility_unavailable) without repeated long hint: ${toolMsgs[1]}",
                toolMsgs[1].contains("tool_unavailable (accessibility_unavailable)") && !toolMsgs[1].contains(expectedHint))
            assertTrue("Expected short refusal on second unavailable UI call: ${toolMsgs[2]}",
                toolMsgs[2].contains("tool_unavailable (accessibility_unavailable)") && !toolMsgs[2].contains(expectedHint))
            collection.cancel()
        }
    }

    @Test fun waitingForUserConfirmationTracksActiveApprovalPromptAndClearsWhenResolved() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("stop-1", AgentToolDefinitions.ForceStopAppToolName, """{"app_name":"Example"}""")))),
            listOf(ChatDelta.Content("已取消操作。"), ChatDelta.Done)
        ))
        val fixture = deviceQueueFixture(client)
        val runtime = fixture.runtime
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        assertFalse(runtime.isWaitingForUserConfirmation(fixture.a.id))
        assertNull(runtime.pendingUserConfirmationPrompt(fixture.a.id))

        assertTrue(runtime.sendFromAssistant(fixture.a.id, "处理一下示例应用", conciseVoiceReply = true))
        advanceUntilIdle()

        assertTrue(runtime.isWaitingForUserConfirmation(fixture.a.id))
        val prompt = runtime.pendingUserConfirmationPrompt(fixture.a.id).orEmpty()
        assertTrue("Expected Floryx confirmation prompt, got: $prompt", prompt.contains("Floryx") && prompt.contains("确认"))

        runtime.stopFromAssistant(fixture.a.id)
        advanceUntilIdle()

        assertFalse(runtime.isWaitingForUserConfirmation(fixture.a.id))
        assertNull(runtime.pendingUserConfirmationPrompt(fixture.a.id))
        assertTrue(fixture.gateway.forceStoppedApps.isEmpty())
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

        assertEquals(1, repository.messagesFor(a.id).count { it.role == MessageRole.User })
        assertEquals(2, repository.messagesFor(a.id).count { it.role == MessageRole.Assistant })
        assertEquals(1, repository.messagesFor(b.id).count { it.role == MessageRole.User })
        assertEquals(1, repository.messagesFor(b.id).count { it.role == MessageRole.Assistant })
        assertEquals(b.id, vm.uiState.value.currentConversation?.id)
        collection.cancel()
    }

    @Test fun resumeInterruptedTaskDoesNotAppendUserBubbleAndSendsRecoveryInSystemAndWireUser() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        repository.appendMessage(a.id, MessageRole.User, "检查网络代理", MessageStatus.Sent, "test", null, null, null, null)
        repository.appendMessage(a.id, MessageRole.Assistant, "部分诊断", MessageStatus.Stopped, "test", null, null, null, null)
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("代理已恢复"), ChatDelta.Done)))
        val vm = conversationTestViewModel(repository, client)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.selectConversation(a.id)
        vm.resumeInterruptedTask()
        advanceUntilIdle()

        val users = repository.messagesFor(a.id).filter { it.role == MessageRole.User }
        assertEquals(1, users.size)
        assertEquals("检查网络代理", users.single().content)
        val sent = client.requests.single()
        assertTrue(sent.messages.any { it.role == "system" && it.content.orEmpty().contains("不要重复已完成的步骤") })
        assertEquals("user", sent.messages.last().role)
        assertEquals("代理已恢复", repository.messagesFor(a.id).last().content)
        collection.cancel()
    }

    @Test fun resumeDeviceTaskDoesNotAppendUserBubbleWhileSendingInstructionOnWire() = runTest(dispatcher) {
        val bridge = FloatingAssistantBridge()
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("observe", AgentToolDefinitions.ObserveScreenToolName, "{}")))),
            listOf(ChatDelta.Content("已完成剩余步骤"), ChatDelta.Done)
        ))
        val gateway = FakeDeviceAssistantGateway().apply { observationGate = kotlinx.coroutines.CompletableDeferred() }
        val viewModel = deviceTestViewModel(client, gateway, bridge = bridge)
        val collection = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateInput("打开应用并核对状态")
        viewModel.send()
        advanceUntilIdle()
        bridge.setAppInForeground(false)
        bridge.setAppInForeground(true)
        bridge.setAppInForeground(false)
        advanceUntilIdle()
        gateway.observationGate!!.complete(Unit)
        advanceUntilIdle()

        viewModel.resumeDeviceTask()
        advanceUntilIdle()

        val users = viewModel.uiState.value.messages.filter { it.role == MessageRole.User }
        assertEquals(1, users.size)
        assertEquals("打开应用并核对状态", users.single().content)
        assertTrue(client.requests.last().messages.any { it.role == "system" && it.content.orEmpty().contains("Continue the paused phone task: 打开应用并核对状态") })
        assertTrue(client.requests.last().messages.last { it.role == "user" }.content.orEmpty().contains("打开应用并核对状态"))
        collection.cancel()
    }

    @Test fun exhaustedBudgetBlocksResumeLoopUntilTaskIsEnded() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("t1", AgentToolDefinitions.DeviceTimeToolName, "{}")))),
            listOf(ChatDelta.Content("已结束旧任务后重新回答"), ChatDelta.Done)
        ))
        val runtime = ChatTaskRuntime(
            chatRepository = repository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = client,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = FakeDeviceAssistantGateway(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false, installId = "budget-loop")),
            floatingAssistantBridge = FloatingAssistantBridge(),
            contextDispatcher = dispatcher,
            budgetLimits = com.flowchat.app.domain.chat.TaskExecutionBudgetLimits(
                stageToolCalls = 1, stageModelCalls = 5, totalToolCalls = 1, totalModelCalls = 5, maxStages = 1
            )
        ).also { testModels += it }
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        runtime.updateInput("查看设备时间并核对")
        runtime.send()
        advanceUntilIdle()

        assertTrue(runtime.uiState.value.taskBudgetPaused)
        assertTrue(runtime.uiState.value.taskBudget?.isExhausted == true)
        val countAfterPause = repository.messagesFor(a.id).size
        assertEquals(1, client.requests.size)

        runtime.resumeInterruptedTask()
        advanceUntilIdle()
        assertEquals("Exhausted budget must not trigger a doomed continuation run", countAfterPause, repository.messagesFor(a.id).size)
        assertEquals(1, client.requests.size)

        runtime.endInterruptedTask()
        advanceUntilIdle()
        assertFalse(runtime.uiState.value.taskBudgetPaused)

        runtime.updateInput("新问题")
        runtime.send()
        advanceUntilIdle()
        assertEquals(2, client.requests.size)
        assertEquals("已结束旧任务后重新回答", repository.messagesFor(a.id).last().content)
        collection.cancel()
    }

    @Test fun continueWithFreshBudgetResumesExhaustedTaskWithoutFakeUserBubble() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("t1", AgentToolDefinitions.DeviceTimeToolName, "{}")))),
            listOf(ChatDelta.Content("追加额度后已完成任务"), ChatDelta.Done)
        ))
        val runtime = ChatTaskRuntime(
            chatRepository = repository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = client,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = FakeDeviceAssistantGateway(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false, installId = "budget-extend")),
            floatingAssistantBridge = FloatingAssistantBridge(),
            contextDispatcher = dispatcher,
            budgetLimits = com.flowchat.app.domain.chat.TaskExecutionBudgetLimits(
                stageToolCalls = 1, stageModelCalls = 5, totalToolCalls = 1, totalModelCalls = 5, maxStages = 1
            )
        ).also { testModels += it }
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        runtime.updateInput("查看设备时间并核对")
        runtime.send()
        advanceUntilIdle()
        assertTrue(runtime.uiState.value.taskBudgetPaused)
        assertTrue(runtime.uiState.value.taskBudget?.isExhausted == true)

        runtime.resumeInterruptedTask()
        advanceUntilIdle()
        assertEquals(1, client.requests.size)

        runtime.continueWithFreshBudget()
        advanceUntilIdle()
        assertEquals(2, client.requests.size)
        assertEquals(1, repository.messagesFor(a.id).count { it.role == MessageRole.User })
        assertEquals("追加额度后已完成任务", repository.messagesFor(a.id).last().content)
        assertFalse(runtime.uiState.value.taskBudgetPaused)
        collection.cancel()
    }

    @Test fun directUserMessageAfterBudgetPauseStartsFreshWithoutManualEnd() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        val provider = ProviderConfig(id = "provider-1", displayName = "Test", baseUrl = "https://example.test", defaultModel = "test")
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("t1", AgentToolDefinitions.DeviceTimeToolName, "{}")))),
            listOf(ChatDelta.Content("新一轮已直接完成"), ChatDelta.Done)
        ))
        val runtime = ChatTaskRuntime(
            chatRepository = repository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = client,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = FakeDeviceAssistantGateway(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false, installId = "budget-fresh-user")),
            floatingAssistantBridge = FloatingAssistantBridge(),
            contextDispatcher = dispatcher,
            budgetLimits = com.flowchat.app.domain.chat.TaskExecutionBudgetLimits(
                stageToolCalls = 1, stageModelCalls = 5, totalToolCalls = 1, totalModelCalls = 5, maxStages = 1
            )
        ).also { testModels += it }
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        runtime.updateInput("查看设备时间并核对")
        runtime.send()
        advanceUntilIdle()
        assertTrue(runtime.uiState.value.taskBudgetPaused)
        assertTrue(runtime.uiState.value.taskBudget?.isExhausted == true)

        runtime.updateInput("我自己换个问题继续问")
        runtime.send()
        advanceUntilIdle()

        assertEquals(2, client.requests.size)
        assertEquals("新一轮已直接完成", repository.messagesFor(a.id).last().content)
        assertFalse(runtime.uiState.value.taskBudgetPaused)
        collection.cancel()
    }

    @Test fun deepSeekMaxThinkingPreservesOutputCapOnRequestWithoutOverReservingBudget() = runTest(dispatcher) {
        val maxDeepSeek = Conversation(
            id = "ds-max",
            title = "DS",
            providerId = "provider-ds",
            modelName = "deepseek-v4.1-flash-1017",
            enableThinking = true,
            thinkingEffort = com.flowchat.app.domain.model.ThinkingEffort.Max
        )
        val repository = FakeChatRepository(listOf(maxDeepSeek), maxDeepSeek)
        val provider = ProviderConfig(id = "provider-ds", displayName = "Custom", baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-v4.1-flash-1017")
        val client = FakeChatCompletionClient(listOf(listOf(ChatDelta.Content("done"), ChatDelta.Done)))
        val runtime = ChatTaskRuntime(
            chatRepository = repository,
            providerRepository = FakeProviderRepository(provider),
            chatClient = client,
            appUsageReader = FakeAppUsageReader(),
            appLauncher = FakeAppLauncher(),
            deviceAssistantGateway = FakeDeviceAssistantGateway(),
            promptProfileRepository = FakePromptProfileRepository(),
            memoryRepository = FakeMemoryRepository(),
            appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = false, memoryEnabled = false, installId = "ds-reserve")),
            floatingAssistantBridge = FloatingAssistantBridge(),
            contextDispatcher = dispatcher
        ).also { testModels += it }
        val collection = backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        runtime.updateInput("测试提问")
        runtime.send()
        advanceUntilIdle()

        assertEquals(131_072, client.requests.single().maxTokens)
        val reserved = requireNotNull(runtime.uiState.value.taskBudget?.totalEstimatedTokens)
        assertTrue("Expected capped output reserve (< 40,000), got $reserved", reserved < 40_000L)
        collection.cancel()
    }

    @Test fun sharedSummaryAndPageSummarySaveEmptyUserContentAndSendSystemGuidance() = runTest(dispatcher) {
        val client = FakeChatCompletionClient(listOf(
            listOf(ChatDelta.Content("分享摘要结果"), ChatDelta.Done),
            listOf(ChatDelta.Content("页面摘要结果"), ChatDelta.Done)
        ))
        val fixture = deviceQueueFixture(client)
        backgroundScope.launch { fixture.runtime.uiState.collect {} }
        advanceUntilIdle()

        val provider = fixture.runtime.uiState.value.providers.single()
        val destination = com.flowchat.app.domain.assistant.AssistantTaskDestination(
            fixture.b.id,
            com.flowchat.app.domain.assistant.AssistantModelSelection.from(fixture.b),
            provider
        )
        val payload = com.flowchat.app.domain.sharing.SharedText("周六上午10点有手作课。", null, 0L)
        assertTrue(fixture.runtime.sendSharedTextSummary(destination, "分享摘要", payload, "分享的文字", titleHint = "分享摘要"))
        advanceUntilIdle()

        val shareUser = fixture.repository.messagesFor(fixture.b.id).single { it.role == MessageRole.User }
        assertEquals("", shareUser.content)
        assertEquals("分享的文字", shareUser.attachmentName)
        assertEquals("分享摘要", fixture.repository.getConversation(fixture.b.id)?.title)
        assertEquals("system", client.requests[0].messages.first().role)
        assertTrue(client.requests[0].messages.first().content.orEmpty().contains("Summarize the supplied shared text"))
        assertFalse(client.requests[0].messages.last().content.orEmpty().startsWith("\n"))

        assertTrue(fixture.runtime.sendFromAssistant(
            owner = fixture.a.id,
            text = "",
            pageContext = "Source package: com.example\nPage text: 社区图书馆周六开放",
            destination = destination.copy(conversationId = fixture.a.id),
            pageAttachmentName = "当前页面",
            summarizePage = true,
            titleHint = "总结这页"
        ))
        advanceUntilIdle()

        val pageUser = fixture.repository.messagesFor(fixture.a.id).single { it.role == MessageRole.User }
        assertEquals("", pageUser.content)
        assertEquals("当前页面", pageUser.attachmentName)
        assertEquals("总结这页", fixture.repository.getConversation(fixture.a.id)?.title)
        assertTrue(client.requests[1].messages.any { it.role == "system" && it.content.orEmpty().contains("Summarize the attached current page") })
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

    @Test fun pagedDisplayStillSendsTheFullConversationAndEstimatesTheFullContext() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        repeat(180) { index ->
            repository.appendMessage(a.id, if (index % 2 == 0) MessageRole.User else MessageRole.Assistant,
                "History marker $index", if (index % 2 == 0) MessageStatus.Sent else MessageStatus.Complete,
                "test", null, null, null, null)
        }
        val client = FakeChatCompletionClient()
        val vm = conversationTestViewModel(repository, client)
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(80, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.hasOlderMessages)
        vm.updateInput("Continue using the first marker")
        vm.send()
        advanceUntilIdle()
        assertTrue(client.requests.first().messages.any { it.content == "History marker 0" })
        assertTrue(client.requests.first().messages.size > 180)
        vm.loadOlderMessages()
        advanceUntilIdle()
        assertEquals(160, vm.uiState.value.messages.size)
        vm.loadOlderMessages()
        advanceUntilIdle()
        assertEquals(182, vm.uiState.value.messages.size)
        assertFalse(vm.uiState.value.hasOlderMessages)
        collection.cancel()
    }

    @Test fun interruptedLongTurnCanResumeWhenItsUserMessageIsOutsideTheDisplayPage() = runTest(dispatcher) {
        val (a, _) = twoConversations()
        val repository = FakeChatRepository(listOf(a), a)
        repository.appendMessage(a.id, MessageRole.User, "Long task", MessageStatus.Sent, "test", null, null, null, null)
        repeat(90) {
            repository.appendMessage(a.id, MessageRole.Tool, "Completed step $it", MessageStatus.Complete, "test", null, null, null, null)
        }
        repository.appendMessage(a.id, MessageRole.Assistant, "Saved partial reply", MessageStatus.Stopped, "test", null, null, null, null)
        val vm = conversationTestViewModel(repository, FakeChatCompletionClient())
        val collection = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(80, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.interruptedTask)
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
        var beforeAppendMessage: (suspend (String, MessageRole, MessageStatus) -> Unit)? = null
        var beforeUpdateMessage: (suspend (MessageStatus) -> Unit)? = null
        var beforeDeleteConversation: (suspend (String) -> Unit)? = null
        val persistenceEvents = mutableListOf<String>()
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
            beforeAppendMessage?.invoke(conversationId, role, status)
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
            persistenceEvents += "append:$conversationId:$role:$status"
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
            beforeUpdateMessage?.invoke(status)
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
            beforeDeleteConversation?.invoke(id)
            conversations.remove(id)
            messages.remove(id)
            messageFlows[id]?.value = emptyList()
            persistenceEvents += "delete:$id"
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
        val nonCancellableGates = mutableSetOf<Int>()

        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> {
            val responseIndex = requests.size
            requests += request
            val response = scriptedResponses.getOrNull(responseIndex)
                ?: listOf(ChatDelta.Content("ok"), ChatDelta.Done)
            return kotlinx.coroutines.flow.flow {
                if (responseIndex in nonCancellableGates) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    responseGates[responseIndex]?.await()
                } else responseGates[responseIndex]?.await()
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

    private class FakeDeviceAssistantGateway(private val settings: AppSettingsStore? = null) : DeviceAssistantGateway {
        override val rootRequestCleanup = MutableStateFlow<Map<String, com.flowchat.app.domain.device.RootRequestCleanup>>(emptyMap())
        var requestCleanupRechecks = 0
        override fun recheckRootRequestCleanup() { requestCleanupRechecks++ }
        override val executionDisplayCleanup = MutableStateFlow<Map<String, com.flowchat.app.domain.device.ExecutionDisplayCleanup>>(emptyMap())
        var cleanupRechecks = 0
        override fun recheckExecutionDisplayCleanup() { cleanupRechecks++ }
        override val safetyState = MutableStateFlow(DeviceSafetyState(screenLocked = false, screenInteractive = true))
        override val backgroundExecutionRequested: Boolean get() = settings?.state?.value?.backgroundExecutionEnabled ?: false
        override val connectionState: MutableStateFlow<ShizukuConnectionState> = MutableStateFlow(
            ShizukuConnectionState(
                status = ShizukuConnectionStatus.ConnectedAdb,
                capabilities = DeviceCapability.entries.toSet()
            )
        )
        override val accessibilityState: MutableStateFlow<AccessibilityConnectionState> = MutableStateFlow(
            AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
        )
        val forceStoppedApps = mutableListOf<String>()
        var powerSetupCalls = 0
        val powerSetupTargets = mutableListOf<DeviceExecutionTarget?>()
        val brightnessValues = mutableListOf<Int>()
        var observeCount = 0
        var waitCount = 0
        val conditionalWaitRequests = mutableListOf<com.flowchat.app.domain.device.DeviceScreenWaitRequest>()
        val observationScopes = mutableListOf<com.flowchat.app.domain.device.DeviceObservationScope>()
        val imageObservationScopes = mutableListOf<com.flowchat.app.domain.device.DeviceObservationScope?>()
        var captureCount = 0
        val captureRegions = mutableListOf<com.flowchat.app.domain.device.ScreenImageRegion?>()
        var screenshot: com.flowchat.app.domain.model.ChatImage? = null
        var observationGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var actionGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val calls = mutableListOf<Pair<String, DeviceExecutionTarget?>>()
        val tappedIndexes = mutableListOf<Int>()
        val textEdits = mutableListOf<Pair<com.flowchat.app.domain.device.DeviceTextEditRequest, String>>()
        val selections = mutableListOf<Pair<com.flowchat.app.domain.device.DeviceTextSelection, String>>()
        val editorLabels = mutableListOf<String>()
        val containerDirections = mutableListOf<com.flowchat.app.domain.device.DeviceScrollDirection>()
        val anchoredGestures = mutableListOf<com.flowchat.app.domain.device.DeviceAnchoredGesture>()
        val rangeAdjustments = mutableListOf<com.flowchat.app.domain.device.DeviceRangeAdjustment>()
        val visualTargets = mutableListOf<com.flowchat.app.domain.device.VisualTapTarget>()
        var backCount = 0
        var reconnectCalls = 0
        var onReconnect: (() -> Unit)? = null
        var reconnectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var publicAppCalls = 0
        var publicAppResult = DeviceToolResult(true, "prefill only", actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.ProgressObserved)
        override suspend fun openPublicAppAction(tool: String, arguments: String): DeviceToolResult {
            publicAppCalls++
            return publicAppResult
        }

        override fun refreshConnection() = Unit
        override fun requestPermission() = Unit
        override suspend fun reconnectAccessibilityAfterStop() {
            reconnectCalls++
            reconnectGate?.await()
            onReconnect?.invoke()
        }
        override suspend fun getDeviceStatus() = DeviceToolResult(true, "device ok")
        override suspend fun getForegroundApp() = DeviceToolResult(true, "foreground_package=test")
        override suspend fun setScreenBrightness(percent: Int): DeviceToolResult { brightnessValues += percent; return DeviceToolResult(true, "brightness=$percent",
            actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.TargetVerified) }
        override suspend fun setMediaVolume(percent: Int) = DeviceToolResult(true, "volume=$percent")
        override suspend fun enablePowerMode(): DeviceToolResult {
            powerSetupCalls++
            powerSetupTargets += kotlinx.coroutines.currentCoroutineContext()[DeviceExecutionTarget]
            return DeviceToolResult(true, "power mode")
        }
        override suspend fun forceStopApp(appName: String): DeviceToolResult {
            recordCall("force_stop")
            forceStoppedApps += appName
            return DeviceToolResult(true, "stopped $appName")
        }
        override suspend fun observeScreen(): DeviceToolResult {
            recordCall("observe")
            observeCount += 1
            observationGate?.await()
            return DeviceToolResult(true, "screen", screen = testScreen())
        }
        override suspend fun waitForScreen(): DeviceToolResult { waitCount++; return observeScreen() }
        override suspend fun observeScreen(scope: com.flowchat.app.domain.device.DeviceObservationScope): DeviceToolResult {
            observationScopes += scope
            val result = observeScreen()
            return result.copy(screen = result.screen?.copy(observationScope = scope, observationRootPath = "0/1"))
        }
        override suspend fun waitForScreen(request: com.flowchat.app.domain.device.DeviceScreenWaitRequest): DeviceToolResult {
            conditionalWaitRequests += request
            if (request.condition?.regionChangedFrom != null) return DeviceToolResult(true, "Regional progress verified", screen = testScreen(),
                screenImage = screenshot?.let { com.flowchat.app.domain.device.DeviceScreenImage(it, "crop", 400, 100) },
                conditionMatched = true, actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.ProgressObserved)
            return DeviceToolResult(true, "Requested predicate verified", screen = testScreen().copy(packageName = request.condition?.packageName ?: "result"),
                conditionMatched = true, actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.TargetVerified)
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
        override suspend fun captureScreen(region: com.flowchat.app.domain.device.ScreenImageRegion?,
            scope: com.flowchat.app.domain.device.DeviceObservationScope?): DeviceToolResult {
            imageObservationScopes += scope
            val result = captureScreen(region)
            return result.copy(screen = result.screen?.copy(observationScope = scope))
        }
        var tapUiElementResult: DeviceToolResult? = null
        override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult {
            tappedIndexes += index
            return tapUiElementResult ?: DeviceToolResult(true, "tapped", screen = testScreen(),
                actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.ProgressObserved)
        }
        override suspend fun tapVisualTarget(target: com.flowchat.app.domain.device.VisualTapTarget): DeviceToolResult {
            visualTargets += target
            val screen = testScreen().copy(screenId = "visual-after")
            return DeviceToolResult(true, "visual gesture completed; verify the resulting image", screen = screen,
                screenImage = screenshot?.let { com.flowchat.app.domain.device.DeviceScreenImage(it, screen.screenId, 400, 800) })
        }
        override suspend fun inputText(index: Int, text: String, clearExisting: Boolean, screenId: String?) =
            DeviceToolResult(true, "typed", screen = testScreen())
        private fun controlledResult() = DeviceToolResult(true, "verified", screen = testScreen().copy(screenId = "controlled-${calls.size}"),
            screenImage = screenshot?.let { com.flowchat.app.domain.device.DeviceScreenImage(it, "controlled-${calls.size}", 400, 800) })
        override suspend fun editText(request: com.flowchat.app.domain.device.DeviceTextEditRequest, screenId: String): DeviceToolResult {
            recordCall("edit"); textEdits += request to screenId; return controlledResult()
        }
        override suspend fun setTextSelection(selection: com.flowchat.app.domain.device.DeviceTextSelection, screenId: String): DeviceToolResult {
            recordCall("selection"); selections += selection to screenId; return controlledResult()
        }
        override suspend fun performEditorAction(index: Int, expectedLabel: String, screenId: String): DeviceToolResult {
            recordCall("editor"); editorLabels += expectedLabel; return controlledResult()
        }
        override suspend fun scrollContainer(index: Int, direction: com.flowchat.app.domain.device.DeviceScrollDirection, screenId: String): DeviceToolResult {
            recordCall("container"); containerDirections += direction; return controlledResult()
        }
        override suspend fun anchoredGesture(request: com.flowchat.app.domain.device.DeviceAnchoredGesture, screenId: String): DeviceToolResult {
            recordCall(if (request is com.flowchat.app.domain.device.DeviceAnchoredGesture.Drag) "drag" else "double")
            anchoredGestures += request; return controlledResult()
        }
        override suspend fun adjustRange(request: com.flowchat.app.domain.device.DeviceRangeAdjustment, screenId: String): DeviceToolResult {
            recordCall("range"); rangeAdjustments += request; return controlledResult()
        }
        override suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int) =
            DeviceToolResult(true, "swiped", screen = testScreen(), screenImage = screenshot?.let {
                com.flowchat.app.domain.device.DeviceScreenImage(it, "swipe", 400, 800) })
        override suspend fun pressBack(): DeviceToolResult {
            recordCall("back")
            actionGate?.await()
            backCount += 1
            return DeviceToolResult(true, "back", screen = testScreen())
        }
        override suspend fun pressHome() = DeviceToolResult(true, "home", screen = testScreen())

        private suspend fun recordCall(name: String) {
            calls += name to kotlinx.coroutines.currentCoroutineContext()[DeviceExecutionTarget]
        }

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
