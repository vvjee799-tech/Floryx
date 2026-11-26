package com.flowchat.app.presentation.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.attachment.ImageAttachmentStore
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.DeviceScreenImage
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.model.AppUsageSummary
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatImage
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.MemoryDraft
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.MemorySnapshot
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.RecentAppActivity
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.domain.repository.AppUsageReader
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.repository.MemoryRepository
import com.flowchat.app.domain.repository.PromptProfileRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import com.flowchat.app.presentation.overlay.ScreenQuestionPhase
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenQuestionAccessibilityReconnectTest {
    private val dispatcher = StandardTestDispatcher()
    private val runtimes = mutableListOf<ChatTaskRuntime>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        try {
            runTest(dispatcher) {
                runtimes.mapNotNull { it.scope.coroutineContext[kotlinx.coroutines.Job] }.forEach {
                    it.cancel()
                    it.join()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun recognizeScreenReconnectsAccessibilityBeforeForegroundCapture() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ImageAttachmentStore(context)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val pngBytes = try {
            bitmap.eraseColor(Color.WHITE)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        val provider = ProviderConfig(
            id = "vision",
            displayName = "Vision",
            baseUrl = "https://example.test/v1",
            defaultModel = "gpt-4o"
        )
        val conversation = Conversation(id = "screen-qa", providerId = provider.id, modelName = provider.defaultModel)
        val chats = InMemoryChatRepo(conversation)
        val events = mutableListOf<String>()
        val gateway = object : DeviceAssistantGateway {
            override val connectionState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedRoot))
            override val accessibilityState = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable))
            override fun refreshConnection() = Unit
            override fun requestPermission() = Unit
            override suspend fun reconnectAccessibilityAfterStop() {
                events += "reconnect"
                accessibilityState.value = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
                connectionState.value = ShizukuConnectionState(
                    status = ShizukuConnectionStatus.ConnectedRoot,
                    capabilities = setOf(DeviceCapability.ScreenObservation)
                )
            }
            override suspend fun captureForegroundScreen(): DeviceToolResult {
                events += "capture:${accessibilityState.value.isConnected}"
                return DeviceToolResult(
                    success = true,
                    summary = "captured",
                    screenImage = DeviceScreenImage(
                        ChatImage("image/png", Base64.getEncoder().encodeToString(pngBytes)),
                        "screen-1",
                        16,
                        16
                    )
                )
            }
            override suspend fun getDeviceStatus() = DeviceToolResult(true, "ok")
            override suspend fun getForegroundApp() = DeviceToolResult(true, "ok")
            override suspend fun setScreenBrightness(percent: Int) = DeviceToolResult(true, "ok")
            override suspend fun setMediaVolume(percent: Int) = DeviceToolResult(true, "ok")
            override suspend fun forceStopApp(appName: String) = DeviceToolResult(true, "ok")
            override suspend fun enablePowerMode() = DeviceToolResult(true, "ok")
        }
        val client = object : ChatCompletionClient {
            val requests = mutableListOf<ChatRequest>()
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
                requests += request
                emit(ChatDelta.Content("已识别"))
                emit(ChatDelta.Done)
            }
        }
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false))
        val bridge = FloatingAssistantBridge()
        val runtime = ChatTaskRuntime(
            chatRepository = chats,
            providerRepository = SingleProviderRepo(provider),
            chatClient = client,
            appUsageReader = UnusedAppUsageReader(),
            appLauncher = UnusedAppLauncher(),
            deviceAssistantGateway = gateway,
            promptProfileRepository = DefaultPromptRepo(),
            memoryRepository = EmptyMemoryRepo(),
            appSettingsStore = settings,
            floatingAssistantBridge = bridge,
            imageAttachmentStore = store,
            contextDispatcher = dispatcher
        ).also { runtimes += it }
        backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(runtime.recognizeScreen(conversation.id))
        advanceTimeBy(200)
        runCurrent()
        val deadline = System.currentTimeMillis() + 5_000L
        while (client.requests.isEmpty() &&
            bridge.screenQuestion.value.phase != ScreenQuestionPhase.Failed &&
            System.currentTimeMillis() < deadline
        ) {
            runCurrent()
            if (client.requests.isEmpty()) Thread.sleep(10)
        }
        runCurrent()

        assertEquals(listOf("reconnect", "capture:true"), events)
        assertEquals("screenQuestion=${bridge.screenQuestion.value}", 1, client.requests.size)
        val user = chats.getMessages(conversation.id).first { it.role == MessageRole.User }
        assertEquals("", user.content)
        assertTrue(client.requests.single().messages.any {
            it.role == "system" && it.content.orEmpty().contains("output only the direct final answer")
        })
        val savedPath = user.attachmentImagePath
        if (savedPath != null) store.discardNow(savedPath)
    }

    @Test
    fun recognizeScreenDoesNotInheritExhaustedBudgetFromActiveConversation() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ImageAttachmentStore(context)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val pngBytes = try {
            bitmap.eraseColor(Color.WHITE)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        val provider = ProviderConfig(
            id = "vision",
            displayName = "Vision",
            baseUrl = "https://example.test/v1",
            defaultModel = "gpt-4o"
        )
        val conversation = Conversation(id = "screen-exhausted", providerId = provider.id, modelName = provider.defaultModel)
        val chats = InMemoryChatRepo(conversation)
        val gateway = object : DeviceAssistantGateway {
            override val connectionState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedRoot, setOf(DeviceCapability.ScreenObservation)))
            override val accessibilityState = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Connected))
            override fun refreshConnection() = Unit
            override fun requestPermission() = Unit
            override suspend fun captureForegroundScreen(): DeviceToolResult = DeviceToolResult(
                success = true,
                summary = "captured",
                screenImage = DeviceScreenImage(
                    ChatImage("image/png", Base64.getEncoder().encodeToString(pngBytes)),
                    "screen-2",
                    16,
                    16
                )
            )
            override suspend fun getDeviceStatus() = DeviceToolResult(true, "ok")
            override suspend fun getForegroundApp() = DeviceToolResult(true, "ok")
            override suspend fun setScreenBrightness(percent: Int) = DeviceToolResult(true, "ok")
            override suspend fun setMediaVolume(percent: Int) = DeviceToolResult(true, "ok")
            override suspend fun forceStopApp(appName: String) = DeviceToolResult(true, "ok")
            override suspend fun enablePowerMode() = DeviceToolResult(true, "ok")
        }
        val client = object : ChatCompletionClient {
            val requests = mutableListOf<ChatRequest>()
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
                requests += request
                if (requests.size == 1) {
                    emit(ChatDelta.ToolCalls(listOf(ChatToolCall("t1", com.flowchat.app.domain.tools.AgentToolDefinitions.DeviceTimeToolName, "{}"))))
                    emit(ChatDelta.Done)
                } else {
                    emit(ChatDelta.Content("42"))
                    emit(ChatDelta.Done)
                }
            }
        }
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false))
        val bridge = FloatingAssistantBridge()
        val runtime = ChatTaskRuntime(
            chatRepository = chats,
            providerRepository = SingleProviderRepo(provider),
            chatClient = client,
            appUsageReader = UnusedAppUsageReader(),
            appLauncher = UnusedAppLauncher(),
            deviceAssistantGateway = gateway,
            promptProfileRepository = DefaultPromptRepo(),
            memoryRepository = EmptyMemoryRepo(),
            appSettingsStore = settings,
            floatingAssistantBridge = bridge,
            imageAttachmentStore = store,
            contextDispatcher = dispatcher,
            budgetLimits = com.flowchat.app.domain.chat.TaskExecutionBudgetLimits(
                stageToolCalls = 1, stageModelCalls = 1, totalToolCalls = 1, totalModelCalls = 1, maxStages = 1
            )
        ).also { runtimes += it }
        backgroundScope.launch { runtime.uiState.collect {} }
        advanceUntilIdle()

        runtime.selectConversation(conversation.id)
        runtime.updateInput("耗时任务")
        runtime.send()
        advanceUntilIdle()
        assertTrue(runtime.uiState.value.taskBudgetPaused)

        assertTrue(runtime.recognizeScreen(conversation.id))
        advanceTimeBy(200)
        runCurrent()
        val deadline = System.currentTimeMillis() + 5_000L
        while (client.requests.size < 2 &&
            bridge.screenQuestion.value.phase != ScreenQuestionPhase.Failed &&
            System.currentTimeMillis() < deadline
        ) {
            runCurrent()
            if (client.requests.size < 2) Thread.sleep(10)
        }
        runCurrent()

        assertEquals("screenQuestion=${bridge.screenQuestion.value}", 2, client.requests.size)
        assertEquals("42", bridge.answerBanner.value?.text)
        assertTrue("Original paused budget must stay preserved", runtime.uiState.value.taskBudgetPaused)
        val savedPath = chats.getMessages(conversation.id).last { it.role == MessageRole.User }.attachmentImagePath
        if (savedPath != null) store.discardNow(savedPath)
    }

    private class InMemoryChatRepo(initial: Conversation) : ChatRepository {
        private val conversations = linkedMapOf(initial.id to initial)
        private val conversationsFlow = MutableStateFlow(listOf(initial))
        private val messages = mutableMapOf<String, MutableList<Message>>(initial.id to mutableListOf())
        private val messageFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        override fun observeConversations() = conversationsFlow
        override fun observeMessages(conversationId: String) =
            messageFlows.getOrPut(conversationId) { MutableStateFlow(messages[conversationId].orEmpty().toList()) }
        override suspend fun getConversation(id: String) = conversations[id]
        override suspend fun getMessages(conversationId: String) = messages[conversationId].orEmpty().toList()
        override suspend fun updateExecutionContext(id: String, context: String) = Unit
        override suspend fun getContextCheckpoint(conversationId: String): com.flowchat.app.domain.chat.TaskCheckpoint? = null
        override suspend fun saveContextCheckpoint(
            checkpoint: com.flowchat.app.domain.chat.TaskCheckpoint,
            expected: com.flowchat.app.domain.chat.TaskCheckpoint?
        ) = false
        override suspend fun createConversation(providerId: String, modelName: String) =
            Conversation(providerId = providerId, modelName = modelName).also { conversations[it.id] = it }
        override suspend fun updateConversationSettings(
            id: String, assistantName: String, assistantAvatarPath: String?, showAvatars: Boolean,
            enableThinking: Boolean, systemPrompt: String, temperature: Double, topP: Double,
            maxTokens: Int?, thinkingEffort: ThinkingEffort
        ) = Unit
        override suspend fun updateThinkingSettings(id: String, enabled: Boolean, effort: ThinkingEffort) = Unit
        override suspend fun updateConversationModel(id: String, providerId: String, modelName: String) = Unit
        override suspend fun updateConversationTitle(id: String, title: String) = Unit
        override suspend fun appendMessage(
            conversationId: String, role: MessageRole, content: String, status: MessageStatus,
            modelName: String?, attachmentName: String?, attachmentText: String?,
            attachmentImagePath: String?, attachmentMimeType: String?
        ): Message {
            val created = Message(
                conversationId = conversationId, role = role, content = content, status = status,
                modelName = modelName, attachmentName = attachmentName, attachmentText = attachmentText,
                attachmentImagePath = attachmentImagePath, attachmentMimeType = attachmentMimeType
            )
            messages.getOrPut(conversationId) { mutableListOf() }.add(created)
            messageFlows.getOrPut(conversationId) { MutableStateFlow(emptyList()) }.value = messages[conversationId].orEmpty().toList()
            return created
        }
        override suspend fun updateMessage(
            id: String, content: String, status: MessageStatus, reasoningContent: String,
            reasoningDurationMillis: Long?, replyPhase: ReplyPhase?
        ) {
            messages.forEach { (convId, rows) ->
                val index = rows.indexOfFirst { it.id == id }
                if (index >= 0) {
                    rows[index] = rows[index].copy(content = content, status = status)
                    messageFlows[convId]?.value = rows.toList()
                }
            }
        }
        override suspend fun markMessageFailed(id: String, error: String) = Unit
        override suspend fun deleteConversation(id: String) = Unit
    }

    private class SingleProviderRepo(private val provider: ProviderConfig) : ProviderRepository {
        override fun observeProviders(): Flow<List<ProviderConfig>> = flowOf(listOf(provider))
        override suspend fun getProvider(id: String) = provider.takeIf { it.id == id }
        override suspend fun getProvidersOnce() = listOf(provider)
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = Unit
        override suspend fun deleteProvider(id: String) = Unit
        override suspend fun ensureTemplates() = Unit
        override suspend fun getApiKey(config: ProviderConfig) = "api-key"
    }

    private class UnusedAppUsageReader : AppUsageReader {
        override fun hasUsageAccess() = false
        override suspend fun getUsageSummary(range: String) = AppUsageSummary(range, 0L, 0L, 0L, emptyList())
        override suspend fun getRecentActivity(hours: Int) = RecentAppActivity(hours, emptyList())
    }

    private class UnusedAppLauncher : AppLauncher {
        override suspend fun openApp(appName: String) = appName
    }

    private class DefaultPromptRepo : PromptProfileRepository {
        override suspend fun getActiveProfile() = PromptProfileConfig()
        override suspend fun getActiveThinkingFormat(): String? = null
    }

    private class EmptyMemoryRepo : MemoryRepository {
        override suspend fun retrieve(userMessage: String, topN: Int) = emptyList<MemoryRecord>()
        override suspend fun snapshot() = MemorySnapshot(emptyList(), 0L)
        override suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean) = false
        override suspend fun update(id: String, summary: String) = Unit
        override suspend fun getAll() = emptyList<MemoryRecord>()
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
    }
}
