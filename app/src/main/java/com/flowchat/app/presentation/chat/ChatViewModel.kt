package com.flowchat.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.attachment.ExtractedAttachment
import com.flowchat.app.data.attachment.ImageAttachmentStore
import com.flowchat.app.data.terminal.TerminalManager
import com.flowchat.app.domain.terminal.*
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.appusage.AppUsageToolFormatter
import com.flowchat.app.domain.device.DeviceActionPolicy
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.domain.device.UnavailableDeviceAssistantGateway
import com.flowchat.app.domain.chat.ReplyPolicy
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.chat.TaskContextTools
import com.flowchat.app.data.chat.TaskContextManager
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.app.domain.chat.ChatRequestFactory
import com.flowchat.app.domain.memory.MemoryContextFormatter
import com.flowchat.app.data.memory.MemorySummaryGenerator
import com.flowchat.app.domain.memory.MemoryPolicy
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.repository.AppUsageReader
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.repository.MemoryRepository
import com.flowchat.app.domain.repository.PromptProfileRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.domain.validation.ProviderConfigValidator
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.provider.ThinkingSupport
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import com.flowchat.app.presentation.overlay.FloatingAssistantCommand
import com.flowchat.app.presentation.overlay.FloatingAssistantState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val chatClient: ChatCompletionClient,
    private val appUsageReader: AppUsageReader,
    private val appLauncher: AppLauncher,
    private val deviceAssistantGateway: DeviceAssistantGateway = UnavailableDeviceAssistantGateway,
    private val promptProfileRepository: PromptProfileRepository,
    private val memoryRepository: MemoryRepository,
    private val appSettingsStore: AppSettingsStore = AppSettingsStore(
        AppSettings(
            powerModeEnabled = true,
            installId = "test"
        )
    ),
    private val floatingAssistantBridge: FloatingAssistantBridge = FloatingAssistantBridge(),
    private val memorySummaryGenerator: MemorySummaryGenerator = MemorySummaryGenerator(chatClient),
    private val imageAttachmentStore: ImageAttachmentStore? = null,
    private val terminalManager: TerminalManager? = null,
    private val taskContextManager: TaskContextManager = TaskContextManager(chatClient, chatRepository)
) : ViewModel() {
    private val currentConversationId = MutableStateFlow<String?>(null)
    private var commandRunId = UUID.randomUUID().toString()
    private var systemCommandsApproved = false
    private var systemCommandsDeclined = false
    private val commandMessages = mutableMapOf<String, String>()
    val terminalState = combine(terminalManager?.state ?: MutableStateFlow(TerminalUiState()), currentConversationId) { state, id ->
        state.copy(commands = state.commands.filter { terminalManager?.owns(id ?: "manual", it.id) == true })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalUiState())
    private val input = MutableStateFlow("")
    private val thinkingSettingsMutex = Mutex()
    private val isStreaming = MutableStateFlow(false)
    private val pausedDeviceTask = MutableStateFlow<PausedDeviceTask?>(null)
    private val taskControl get() = floatingAssistantBridge.deviceTaskControl
    private var activeDeviceTask: DeviceTaskControl.Task? = null
    private var activeTaskInstruction = ""
    private var activeTaskConversationId: String? = null
    private val activeDeviceResults = mutableListOf<String>()
    private val errorMessage = MutableStateFlow<String?>(null)
    private val usageAccessPermissionRequest = MutableStateFlow<UsageAccessPermissionRequestUi?>(null)
    private val pendingDeviceActionConfirmation = MutableStateFlow<DeviceActionConfirmationUi?>(null)
    private val memories = MutableStateFlow(emptyList<com.flowchat.app.domain.model.MemoryRecord>())
    private val pendingAttachment = MutableStateFlow<PendingAttachment?>(null)
    private val isAttachmentLoading = MutableStateFlow(false)
    private var memoryUpdateJob: Job? = null
    @Volatile private var memoryEpoch = 0L
    private var sendJob: Job? = null
    private var stoppedTurnPersistence: Job? = null
    private var activeAssistantMessageId: String? = null
    private var activeAssistantContent = ""
    private var activeAssistantReasoningContent = ""
    private var activeAssistantReasoningStartedAt: Long? = null
    private var activeAssistantReasoningDurationMillis = 0L
    private var pendingClearedInputEcho: String? = null
    private var deviceActionConfirmation: CompletableDeferred<Boolean>? = null
    private val toolJson = Json { ignoreUnknownKeys = true }

    private val baseState = combine(
        chatRepository.observeConversations(),
        providerRepository.observeProviders(),
        currentConversationId
    ) { conversations: List<Conversation>, providers: List<ProviderConfig>, selectedId: String? ->
        val current = if (selectedId == null) {
            conversations.firstOrNull()
        } else {
            conversations.firstOrNull { it.id == selectedId }
        }
        if (selectedId == null && current != null) {
            currentConversationId.value = current.id
        }
        ChatUiState(
            conversations = conversations,
            currentConversation = current,
            providers = providers
        )
    }

    private val stateWithoutMessages = combine(
        baseState,
        input,
        isStreaming,
        errorMessage
    ) { state, inputValue, streaming, error ->
        state.copy(
            input = inputValue,
            isStreaming = streaming,
            errorMessage = error
        )
    }

    private val stateWithPermissionRequest = combine(
        stateWithoutMessages,
        usageAccessPermissionRequest
    ) { state: ChatUiState, permissionRequest: UsageAccessPermissionRequestUi? ->
        state.copy(
            usageAccessPermissionRequest = permissionRequest
        )
    }

    private val stateWithAppSettings = combine(
        stateWithPermissionRequest,
        appSettingsStore.state
    ) { state, settings ->
        state.copy(appSettings = settings)
    }

    private val stateWithDeviceAssistant = combine(
        stateWithAppSettings,
        deviceAssistantGateway.connectionState,
        deviceAssistantGateway.accessibilityState,
        pendingDeviceActionConfirmation,
        pausedDeviceTask
    ) { state, shizukuState, accessibilityState, confirmation, paused ->
        state.copy(
            shizukuState = shizukuState,
            accessibilityState = accessibilityState,
            pendingDeviceActionConfirmation = confirmation,
            deviceTaskPaused = paused != null && paused.conversationId == state.currentConversation?.id
        )
    }

    private val stateWithMemories = combine(
        stateWithDeviceAssistant,
        memories
    ) { state, memoryRecords ->
        state.copy(memories = memoryRecords)
    }

    private val stateWithAttachment = combine(
        stateWithMemories,
        pendingAttachment,
        isAttachmentLoading
    ) { state, attachment, loading ->
        state.copy(pendingAttachment = attachment, isAttachmentLoading = loading)
    }

    val uiState = combine(
        stateWithAttachment,
        currentConversationId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
        }
    ) { state, messages ->
        state.copy(
            messages = messages
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            providerRepository.ensureTemplates()
        }
        deviceAssistantGateway.refreshConnection()
        viewModelScope.launch {
            combine(
                appSettingsStore.state.map { settings -> settings.powerModeEnabled },
                deviceAssistantGateway.connectionState.map { state -> state.status }
            ) { enabled, status -> enabled to status }
                .distinctUntilChanged()
                .collect { (enabled, status) ->
                    if (enabled && status in ConnectedShizukuStatuses) {
                        val result = deviceAssistantGateway.enablePowerMode()
                        if (!result.success) errorMessage.value = result.summary
                    }
                }
        }
        refreshMemories()
        viewModelScope.launch {
            taskControl.foregroundReturns.collect {
                if (activeDeviceTask?.interrupted == true && isStreaming.value) pauseDeviceTaskForUser()
            }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                floatingAssistantBridge.publish(FloatingAssistantState.from(state))
            }
        }
        viewModelScope.launch {
            floatingAssistantBridge.commands.collect { command ->
                when (command) {
                    is FloatingAssistantCommand.Send -> {
                        updateInput(command.text)
                        send()
                    }
                    FloatingAssistantCommand.Stop -> stop()
                }
            }
        }
    }

    fun updateInput(value: String) {
        val pendingEcho = pendingClearedInputEcho
        if (pendingEcho != null && pendingEcho == value) {
            pendingClearedInputEcho = null
            return
        }
        if (pendingEcho != null && value.isBlank() && input.value.isBlank()) {
            input.value = ""
            return
        }
        pendingClearedInputEcho = null
        input.value = value
    }

    fun setThinkingEnabled(enabled: Boolean) {
        updateThinkingSettings { conversation, support ->
            if (!support.canDisable) conversation.enableThinking to conversation.thinkingEffort
            else enabled to conversation.thinkingEffort
        }
    }

    fun setThinkingEffort(effort: ThinkingEffort) {
        updateThinkingSettings { conversation, support ->
            conversation.enableThinking to if (support.isEnabled(conversation.enableThinking)) support.normalize(effort)
                else conversation.thinkingEffort
        }
    }

    private fun updateThinkingSettings(change: (Conversation, ThinkingSupport) -> Pair<Boolean, ThinkingEffort>) {
        if (isStreaming.value) return
        val id = uiState.value.currentConversation?.id ?: return
        viewModelScope.launch {
            thinkingSettingsMutex.withLock {
                val conversation = chatRepository.getConversation(id) ?: return@withLock
                val provider = uiState.value.providers.firstOrNull { it.id == conversation.providerId }
                val support = ThinkingSupport.resolve(provider, conversation.modelName)
                if (!support.supported) return@withLock
                val (enabled, effort) = change(conversation, support)
                chatRepository.updateThinkingSettings(id, enabled, effort)
            }
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        invalidateMemoryUpdate()
        appSettingsStore.setMemoryEnabled(enabled)
    }

    fun setPowerModeEnabled(enabled: Boolean) {
        if (!enabled && isStreaming.value) stop()
        appSettingsStore.setPowerModeEnabled(enabled)
        if (enabled) {
            refreshDeviceAssistantConnections()
            deviceAssistantGateway.requestPermission()
        }
    }

    fun setCommandExecutionEnabled(enabled: Boolean) {
        appSettingsStore.setCommandExecutionEnabled(enabled)
        if (enabled) terminalManager?.prepare() else terminalManager?.cancelAgentCommands()
    }
    fun prepareTerminal() { terminalManager?.prepare() }
    fun installDeveloperTools() {
        viewModelScope.launch {
            runCatching {
                terminalManager?.start(currentConversationId.value ?: "manual", CommandEnvironment.Linux,
                    "flowchat-install-tools", "/workspace", 600)
            }.onFailure { if (it !is CancellationException) reportError(it.message ?: "Command failed.") }
        }
    }
    fun stopTerminalCommand(id: String) { viewModelScope.launch { runCatching { terminalManager?.cancel(currentConversationId.value ?: "manual", id) } } }

    fun setPowerConnectionMode(mode: com.flowchat.app.domain.device.PowerConnectionMode) {
        appSettingsStore.setPowerConnectionMode(mode)
    }

    fun requestShizukuPermission() {
        deviceAssistantGateway.requestPermission()
    }

    fun refreshShizukuConnection() {
        deviceAssistantGateway.refreshConnection()
    }

    fun refreshAccessibilityConnection() {
        deviceAssistantGateway.refreshAccessibilityConnection()
    }

    fun refreshDeviceAssistantConnections() {
        deviceAssistantGateway.refreshConnection()
        deviceAssistantGateway.refreshAccessibilityConnection()
    }

    fun openAccessibilitySettings() {
        deviceAssistantGateway.openAccessibilitySettings()
    }

    fun confirmDeviceAction() {
        deviceActionConfirmation?.complete(true)
    }

    fun cancelDeviceAction() {
        deviceActionConfirmation?.complete(false)
    }

    fun deleteMemory(id: String) {
        invalidateMemoryUpdate()
        viewModelScope.launch {
            memoryRepository.delete(id)
            refreshMemoriesNow()
        }
    }

    fun clearMemories() {
        invalidateMemoryUpdate()
        viewModelScope.launch {
            memoryRepository.clear()
            refreshMemoriesNow()
        }
    }

    fun attachTextFile(name: String, text: String) {
        replaceAttachment(PendingAttachment(name = name, text = text))
    }

    fun loadAttachment(reader: () -> ExtractedAttachment, errorText: (Throwable) -> String) {
        if (isAttachmentLoading.value) return
        isAttachmentLoading.value = true
        viewModelScope.launch {
            var result: ExtractedAttachment? = null
            var accepted = false
            try {
                withContext(Dispatchers.IO) { result = reader() }
                ensureActive()
                val attachment = requireNotNull(result)
                replaceAttachment(PendingAttachment(attachment.name, attachment.text, attachment.imagePath, attachment.mimeType))
                accepted = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage.value = errorText(error)
            } finally {
                if (!accepted) result?.imagePath?.let { path ->
                    withContext(NonCancellable) { imageAttachmentStore?.discard(path) }
                }
                isAttachmentLoading.value = false
            }
        }
    }

    private fun replaceAttachment(attachment: PendingAttachment?) {
        val previous = pendingAttachment.value
        pendingAttachment.value = attachment
        previous?.imagePath?.takeIf { it != attachment?.imagePath }?.let { path ->
            viewModelScope.launch { imageAttachmentStore?.discard(path) }
        }
    }

    fun clearAttachment() {
        replaceAttachment(null)
    }

    override fun onCleared() {
        pendingAttachment.value?.imagePath?.let { imageAttachmentStore?.discardNow(it) }
        super.onCleared()
    }

    fun reportError(message: String) {
        errorMessage.value = message
    }

    fun dismissUsageAccessPermissionRequest() {
        usageAccessPermissionRequest.value = null
    }

    fun selectConversation(id: String) {
        currentConversationId.value = id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
            if (currentConversationId.value == id) {
                currentConversationId.value = null
            }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val providers = providerRepository.getProvidersOnce()
            val provider = providers.firstOrNull { provider ->
                ProviderConfigValidator.validate(provider).isEmpty() &&
                    providerRepository.getApiKey(provider) != null
            } ?: providers.firstOrNull { provider -> ProviderConfigValidator.validate(provider).isEmpty() }
            if (provider == null) {
                errorMessage.value = "Create a provider first."
                return@launch
            }
            val conversation = chatRepository.createConversation(provider.id, provider.defaultModel)
            currentConversationId.value = conversation.id
        }
    }

    fun updateConversationSettings(
        assistantName: String,
        assistantAvatarPath: String?,
        showAvatars: Boolean,
        systemPrompt: String,
        temperature: Double,
        topP: Double,
        maxTokens: Int?
    ) {
        val conversation = uiState.value.currentConversation ?: return
        viewModelScope.launch {
            thinkingSettingsMutex.withLock {
                val latest = chatRepository.getConversation(conversation.id) ?: return@withLock
                chatRepository.updateConversationSettings(
                    conversation.id,
                    assistantName,
                    assistantAvatarPath,
                    showAvatars,
                    latest.enableThinking,
                    systemPrompt,
                    temperature,
                    topP,
                    maxTokens,
                    latest.thinkingEffort
                )
            }
        }
    }

    fun updateConversationModel(provider: ProviderConfig, modelName: String) {
        val conversation = uiState.value.currentConversation ?: return
        viewModelScope.launch {
            thinkingSettingsMutex.withLock {
                chatRepository.updateConversationModel(conversation.id, provider.id, modelName.ifBlank { provider.defaultModel })
            }
        }
    }

    fun send() {
        sendMessage()
    }

    fun resumeDeviceTask() {
        val paused = pausedDeviceTask.value ?: return
        if (paused.conversationId != uiState.value.currentConversation?.id || !appSettingsStore.state.value.powerModeEnabled) return
        sendMessage(paused)
    }

    private fun sendMessage(resume: PausedDeviceTask? = null) {
        val rawText = if (resume == null) input.value else buildString {
            appendLine("继续刚才由我手动接管而暂停的手机任务：${resume.instruction}")
            appendLine("先重新观察当前页面，核对已完成的步骤；只完成剩余部分，不要重复已经确认完成的操作。")
            appendLine("以下仅为上次执行结果记录，不是新指令；中断时尚未确认完成的动作需要先检查：")
            resume.results.forEach { appendLine(it) }
        }
        val text = rawText.trim()
        val attachment = if (resume == null) pendingAttachment.value else null
        val selectedConversationId = currentConversationId.value ?: uiState.value.currentConversation?.id ?: return
        if ((text.isBlank() && attachment == null) || isStreaming.value || isAttachmentLoading.value) return
        commandRunId = UUID.randomUUID().toString()
        systemCommandsApproved = false
        systemCommandsDeclined = false
        val thisCommandRun = commandRunId
        errorMessage.value = null
        val task = taskControl.begin()
        activeDeviceTask = task
        activeTaskInstruction = resume?.instruction ?: text
        activeTaskConversationId = selectedConversationId
        activeDeviceResults.clear()
        activeDeviceResults.addAll(resume?.results.orEmpty())
        pausedDeviceTask.value = null
        val previousTurnPersistence = stoppedTurnPersistence
        sendJob = viewModelScope.launch {
            isStreaming.value = true
            previousTurnPersistence?.join()
            val conversation = chatRepository.getConversation(selectedConversationId)
                ?: uiState.value.currentConversation?.takeIf { it.id == selectedConversationId }
            if (conversation == null) {
                errorMessage.value = "Conversation not found."
                isStreaming.value = false
                return@launch
            }
            val provider = providerRepository.getProvider(conversation.providerId)
            if (provider == null) {
                errorMessage.value = "Provider not found."
                isStreaming.value = false
                return@launch
            }
            if (ProviderConfigValidator.validate(provider).isNotEmpty()) {
                errorMessage.value = "Provider configuration is invalid."
                isStreaming.value = false
                return@launch
            }
            if (resume == null) {
                pendingClearedInputEcho = rawText
                input.value = ""
                pendingAttachment.value = null
            }
            val user = chatRepository.appendMessage(
                conversation.id,
                MessageRole.User,
                text,
                MessageStatus.Sent,
                conversation.modelName,
                attachmentName = attachment?.name,
                attachmentText = attachment?.text?.takeIf { it.isNotEmpty() },
                attachmentImagePath = attachment?.imagePath,
                attachmentMimeType = attachment?.mimeType
            )
            val previousUserMessages = chatRepository.getMessages(conversation.id)
                .count { it.role == MessageRole.User && it.id != user.id }
            if (previousUserMessages == 0) {
                chatRepository.updateConversationTitle(
                    conversation.id,
                    com.flowchat.app.domain.chat.ConversationTitle.from(text, attachment?.name)
                )
            }
            var assistant = chatRepository.appendMessage(
                conversation.id,
                MessageRole.Assistant,
                "",
                MessageStatus.Streaming,
                conversation.modelName
            )
            activeAssistantMessageId = assistant.id
            activeAssistantContent = ""
            activeAssistantReasoningContent = ""
            activeAssistantReasoningStartedAt = null
            activeAssistantReasoningDurationMillis = 0L
            val freshConversation = thinkingSettingsMutex.withLock { chatRepository.getConversation(conversation.id) ?: conversation }
            val history = chatRepository.getMessages(conversation.id)
                .filter { it.id != assistant.id }
                .ifEmpty { listOf(user) }
            val activeProfile = promptProfileRepository.getActiveProfile()
            val settings = appSettingsStore.state.value
            deviceAssistantGateway.refreshAccessibilityConnection()
            val relevantMemories = if (settings.memoryEnabled) {
                MemoryContextFormatter.format(
                    memoryRepository.retrieve(text, activeProfile.memory.topN)
                )
            } else {
                emptyList()
            }
            val apiKey = providerRepository.getApiKey(provider)
            var appliedCheckpointBoundary: String? = null
            var request = runCatching {
                val prepared = taskContextManager.prepare(freshConversation, history, provider, apiKey)
                appliedCheckpointBoundary = prepared.checkpoint?.throughMessageId
                ChatRequestFactory.create(
                freshConversation,
                prepared.messages,
                null,
                activeProfile.thinkingFormat,
                relevantMemories,
                imageAttachments = imageAttachmentStore?.loadRecent(prepared.messages).orEmpty(),
                checkpoint = prepared.checkpoint
            ) }.getOrElse { error ->
                if (error is CancellationException) throw error
                val message = error.userFacingChatError()
                chatRepository.markMessageFailed(assistant.id, message)
                errorMessage.value = message
                activeAssistantMessageId = null
                activeDeviceTask = null
                isStreaming.value = false
                return@launch
            }
            request = AgentToolDefinitions.withLifestyleTools(
                request,
                includeWebSearch = false,
                includeAppUsage = settings.powerModeEnabled,
                includeRecentActivity = settings.powerModeEnabled,
                includeOpenApp = settings.powerModeEnabled,
                includeDeviceAssistant = settings.powerModeEnabled &&
                    (deviceAssistantGateway.connectionState.value.isConnected ||
                        deviceAssistantGateway.accessibilityState.value.isConnected),
                includeForceStop = settings.powerModeEnabled,
                deviceCapabilities = deviceAssistantGateway.connectionState.value.capabilities
            )
            request = TerminalToolDefinitions.attach(request, settings.commandExecutionEnabled && terminalManager != null)
            request = TaskContextTools.attach(request)
            var content = ""
            var reasoningContent = ""
            var toolResultCount = 0
            var closingReason: String? = null
            val chinese = text.any { it in '\u3400'..'\u9fff' }
            runCatching {
                var toolRounds = 0
                var toolRoundLimit = if (settings.commandExecutionEnabled) 80 else MaxToolCallRounds
                val toolCallLoopGuard = ToolCallLoopGuard(MaxConsecutiveIdenticalToolCalls)
                while (true) {
                    if (closingReason == null && taskContextManager.shouldCompact(request)) {
                        // Keep the last two native call/result groups intact, including provider reasoning signatures.
                        val retainedStart = request.messages.withIndex().filter { it.value.toolCalls.isNotEmpty() }.takeLast(2).firstOrNull()
                        val protectedId = retainedStart?.value?.localMessageId
                        if (retainedStart != null && protectedId != null) {
                            val stored = chatRepository.getMessages(conversation.id).filter { it.id != assistant.id }
                            val prepared = taskContextManager.prepare(freshConversation, stored, provider, apiKey,
                                protectedMessageId = protectedId, force = true)
                            val boundary = prepared.checkpoint?.throughMessageId
                            if (boundary != null && boundary != appliedCheckpointBoundary) {
                                val protectedIndex = prepared.messages.indexOfFirst { it.id == protectedId }
                                if (protectedIndex >= 0) {
                                    val prefix = prepared.messages.take(protectedIndex)
                                    val rebuilt = ChatRequestFactory.create(freshConversation, prefix, null, activeProfile.thinkingFormat,
                                        relevantMemories, imageAttachmentStore?.loadRecent(prefix).orEmpty(), prepared.checkpoint)
                                    request = request.copy(messages = rebuilt.messages + request.messages.drop(retainedStart.index))
                                    appliedCheckpointBoundary = boundary
                                }
                            }
                        } else if (retainedStart == null) {
                            val stored = chatRepository.getMessages(conversation.id).filter { it.id != assistant.id }
                            val prepared = taskContextManager.prepare(freshConversation, stored, provider, apiKey, force = true)
                            if (prepared.checkpoint != null && prepared.checkpoint.throughMessageId != appliedCheckpointBoundary) {
                                val rebuilt = ChatRequestFactory.create(freshConversation, prepared.messages, null, activeProfile.thinkingFormat,
                                    relevantMemories, imageAttachmentStore?.loadRecent(prepared.messages).orEmpty(), prepared.checkpoint)
                                request = request.copy(messages = rebuilt.messages)
                                appliedCheckpointBoundary = prepared.checkpoint.throughMessageId
                            }
                        }
                    }
                    taskContextManager.requireFits(request)
                    val response = collectAssistantResponse(
                        request = request,
                        provider = provider,
                        apiKey = apiKey,
                        assistantId = assistant.id,
                        currentContent = content,
                        currentReasoning = reasoningContent
                    )
                    content = response.content
                    reasoningContent = response.reasoningContent
                    response.errorMessage?.let { rawError ->
                        val message = ReplyPolicy.errorMessage(rawError, chinese)
                        val visible = ReplyPolicy.interruptedReply(content, chinese, toolResultCount, closingReason != null, message)
                        chatRepository.updateMessage(assistant.id, visible, MessageStatus.Failed, reasoningContent)
                        errorMessage.value = message
                        return@runCatching
                    }
                    if (response.toolCalls.isEmpty()) {
                        if (content.isBlank() && closingReason == null && (toolResultCount > 0 || reasoningContent.isNotBlank())) {
                            closingReason = "The model returned no user-facing conclusion."
                            chatRepository.updateMessage(assistant.id, "", MessageStatus.Complete, reasoningContent, replyPhase = ReplyPhase.Commentary)
                            request = ReplyPolicy.finalization(request, closingReason)
                        } else {
                            val finalContent = content.ifBlank {
                                ReplyPolicy.incompleteResult(chinese, toolResultCount, closingReason != null)
                            }
                            chatRepository.updateMessage(assistant.id, finalContent,
                                if (content.isBlank()) MessageStatus.Failed else MessageStatus.Complete,
                                reasoningContent, replyPhase = ReplyPhase.Answer)
                            if (content.isNotBlank()) queueMemorySummary(rawText, provider, apiKey, freshConversation.modelName)
                            return@runCatching
                        }
                    } else if (closingReason != null) {
                        // A provider may ignore tools being disabled. Never execute another action during finalization.
                        chatRepository.updateMessage(assistant.id,
                            ReplyPolicy.incompleteResult(chinese, toolResultCount, true),
                            MessageStatus.Failed, reasoningContent, replyPhase = ReplyPhase.Answer)
                        return@runCatching
                    } else {
                        if (response.toolCalls.any { it.name in UiAutomationToolNames }) {
                            toolRoundLimit = maxOf(toolRoundLimit, MaxUiAutomationToolCallRounds)
                        }
                        ensureVisibleAssistantSegmentBeforeToolCall(
                            assistantId = assistant.id,
                            content = content,
                            reasoningContent = reasoningContent,
                            fallback = if (toolRounds == 0) ReplyPolicy.toolPreface(response.toolCalls, chinese) else ""
                        )
                        val toolResultMessages = executeToolCalls(
                            calls = response.toolCalls,
                            assistantId = assistant.id,
                            conversationId = conversation.id,
                            modelName = conversation.modelName,
                            loopGuard = toolCallLoopGuard,
                            allowedTools = request.tools.map { it.name }.toSet()
                        )
                        toolResultCount += toolResultMessages.size
                        request = request.copy(
                            messages = request.messages +
                                ChatRequestMessage(
                                    role = MessageRole.Assistant.apiRole,
                                    content = content,
                                    reasoningContent = reasoningContent,
                                    toolCalls = response.toolCalls,
                                    providerData = response.providerData,
                                    localMessageId = assistant.id
                                ) +
                                toolResultMessages
                        )
                        toolRounds += 1
                        closingReason = when {
                            toolCallLoopGuard.shouldStopAutomation -> "Repeated attempts failed, made no progress, or permission was refused."
                            toolRounds >= toolRoundLimit -> "The tool execution limit for this turn was reached."
                            else -> null
                        }
                        closingReason?.let { request = ReplyPolicy.finalization(request, it) }
                    }
                    assistant = chatRepository.appendMessage(
                        conversation.id,
                        MessageRole.Assistant,
                        "",
                        MessageStatus.Streaming,
                        conversation.modelName
                    )
                    activeAssistantMessageId = assistant.id
                    activeAssistantContent = ""
                    activeAssistantReasoningContent = ""
                    activeAssistantReasoningStartedAt = null
                    activeAssistantReasoningDurationMillis = 0L
                    content = ""
                    reasoningContent = ""
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    return@onFailure
                } else {
                    val message = ReplyPolicy.errorMessage(throwable.userFacingChatError(), chinese)
                    val visible = ReplyPolicy.interruptedReply(activeAssistantContent, chinese, toolResultCount, closingReason != null, message)
                    chatRepository.updateMessage(assistant.id, visible, MessageStatus.Failed, activeAssistantReasoningContent)
                    errorMessage.value = message
                }
            }
            if (activeAssistantMessageId == assistant.id) {
                activeAssistantMessageId = null
                activeAssistantContent = ""
                activeAssistantReasoningContent = ""
                activeAssistantReasoningStartedAt = null
                activeAssistantReasoningDurationMillis = 0L
            }
            if (activeDeviceTask === task) {
                activeDeviceTask = null
                isStreaming.value = false
            }
        }
        sendJob?.invokeOnCompletion {
            taskControl.finish(task)
            terminalManager?.cancelRun(thisCommandRun)
        }
    }

    private suspend fun collectAssistantResponse(
        request: ChatRequest,
        provider: ProviderConfig,
        apiKey: String?,
        assistantId: String,
        currentContent: String,
        currentReasoning: String
    ): AssistantResponseResult {
        var content = currentContent
        var reasoningContent = currentReasoning
        var toolCalls = emptyList<ChatToolCall>()
        var error: String? = null
        var providerData: kotlinx.serialization.json.JsonArray? = null
        chatClient.streamChat(request, provider, apiKey).collect { delta ->
            when (delta) {
                is ChatDelta.Content -> {
                    content = appendContentDelta(
                        assistantId,
                        content,
                        reasoningContent,
                        delta.text
                    )
                }
                is ChatDelta.Reasoning -> {
                    reasoningContent = appendReasoningDelta(
                        assistantId,
                        content,
                        reasoningContent,
                        delta.text
                    )
                }
                is ChatDelta.FullResponse -> {
                    reasoningContent = appendReasoningDelta(
                        assistantId,
                        content,
                        reasoningContent,
                        delta.reasoningText
                    )
                    content = appendContentDelta(
                        assistantId,
                        content,
                        reasoningContent,
                        delta.contentText
                    )
                }
                is ChatDelta.ToolCalls -> {
                    toolCalls = delta.calls
                }
                is ChatDelta.ProviderData -> providerData = delta.blocks
                is ChatDelta.ToolCallDelta -> Unit
                ChatDelta.Done -> Unit
                is ChatDelta.Error -> {
                    error = delta.message
                }
            }
        }
        return AssistantResponseResult(
            content = content,
            reasoningContent = reasoningContent,
            toolCalls = toolCalls,
            errorMessage = error,
            providerData = providerData
        )
    }

    private suspend fun executeToolCalls(
        calls: List<ChatToolCall>,
        assistantId: String,
        conversationId: String,
        modelName: String?,
        loopGuard: ToolCallLoopGuard,
        allowedTools: Set<String>
    ): List<ChatRequestMessage> {
        var uiChanged = false
        val evidence = mutableListOf<com.flowchat.app.domain.chat.ExecutionStep>()
        return calls.map { call ->
            // Commit intent before a side effect, then commit its returned evidence before the next call.
            // Even cancellation or process death leaves an explicit unconfirmed action to inspect.
            evidence += TaskExecutionHistory.pending(call)
            chatRepository.updateExecutionContext(assistantId, TaskExecutionHistory.encode(evidence))
            val result = try { if (loopGuard.shouldStopAutomation) {
                "cancelled: tool execution has stopped for this turn. Do not retry."
            } else if (call.name !in allowedTools) {
                loopGuard.recordOutcome(false)
                "failed: tool_unavailable. This tool is not enabled for this request."
            } else if (uiChanged && call.name in UiAutomationToolNames + AgentToolDefinitions.OpenAppToolName) {
                "failed: sequential_ui_required. Inspect the previous action result and request the next action in a new response."
            } else executeSingleToolCall(
                call,
                conversationId,
                modelName,
                loopGuard
            )
            } catch (cancelled: CancellationException) {
                evidence[evidence.lastIndex] = TaskExecutionHistory.interrupted(evidence.last())
                withContext(NonCancellable) {
                    chatRepository.updateExecutionContext(assistantId, TaskExecutionHistory.encode(evidence))
                }
                throw cancelled
            }
            evidence[evidence.lastIndex] = TaskExecutionHistory.completed(evidence.last(), result)
            withContext(NonCancellable) {
                chatRepository.updateExecutionContext(assistantId, TaskExecutionHistory.encode(evidence))
            }
            if (call.name in UiAutomationToolNames + AgentToolDefinitions.OpenAppToolName) {
                activeDeviceResults += "${call.name}: ${result.lineSequence().firstOrNull().orEmpty().take(300)}"
                while (activeDeviceResults.size > 12) activeDeviceResults.removeAt(0)
            }
            if (call.name in UiChangingToolNames) uiChanged = true
            ChatRequestMessage(
                role = "tool",
                content = result,
                toolCallId = call.id
            )
        }
    }

    private suspend fun executeSingleToolCall(
        call: ChatToolCall,
        conversationId: String,
        modelName: String?,
        loopGuard: ToolCallLoopGuard
    ): String {
        if (call.name == TaskContextTools.Read) {
            if (!loopGuard.allow(call)) return "Stopped a repeated identical history lookup."
            val result = TaskContextTools.read(call.arguments, conversationId, chatRepository.getMessages(conversationId))
            loopGuard.recordOutcome(!result.startsWith("failed:"))
            return result
        }
        if (call.name in setOf(TerminalToolDefinitions.Status, TerminalToolDefinitions.Read, TerminalToolDefinitions.Stop)) {
            if (!loopGuard.allow(call)) return "Stopped a repeated identical tool loop. No more tools are available."
            return runCatching {
                check(appSettingsStore.state.value.commandExecutionEnabled) { "AI command execution is disabled." }
                val result = executeTerminalTool(call, conversationId, null)
                loopGuard.recordOutcome(result.errorDetail == null && result.toolStatus != MessageStatus.Failed, result.cancelled)
                result.content
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                loopGuard.recordOutcome(false)
                "Command tool failed: ${error.message}"
            }
        }
        val toolName = call.displayName()
        val toolMessage = chatRepository.appendMessage(
            conversationId,
            MessageRole.Tool,
            toolName,
            MessageStatus.Streaming,
            modelName
        )
        return runCatching {
            if (call.name in UiAutomationToolNames + AgentToolDefinitions.OpenAppToolName) guardDeviceTask()
            if (call.name !in TerminalToolDefinitions.Names + AgentToolDefinitions.WebSearchToolName && !appSettingsStore.state.value.powerModeEnabled) {
                return@runCatching ToolExecutionOutcome("Power mode is off. Device operation cancelled.", cancelled = true)
            }
            if (!loopGuard.allow(call)) {
                return@runCatching ToolExecutionOutcome(
                    content = "Stopped a repeated identical tool loop. Use a different next action or finish the task.",
                    errorDetail = "Repeated identical tool call blocked."
                )
            }
            when (call.name) {
                TerminalToolDefinitions.Run -> executeTerminalTool(call, conversationId, toolMessage.id)
                AgentToolDefinitions.WebSearchToolName -> ToolExecutionOutcome("Web search is currently disabled.", cancelled = true)
                AgentToolDefinitions.AppUsageSummaryToolName -> executeAppUsageSummaryTool(call.arguments)
                AgentToolDefinitions.RecentAppActivityToolName -> executeRecentAppActivityTool(call.arguments)
                AgentToolDefinitions.OpenAppToolName -> executeOpenAppTool(call.arguments)
                AgentToolDefinitions.DeviceStatusToolName -> deviceAssistantGateway.getDeviceStatus().toToolOutcome()
                AgentToolDefinitions.ForegroundAppToolName -> deviceAssistantGateway.getForegroundApp().toToolOutcome()
                AgentToolDefinitions.SetBrightnessToolName -> executeSetBrightnessTool(call.arguments)
                AgentToolDefinitions.SetMediaVolumeToolName -> executeSetMediaVolumeTool(call.arguments)
                AgentToolDefinitions.ForceStopAppToolName -> executeForceStopAppTool(call)
                AgentToolDefinitions.ObserveScreenToolName ->
                    prepareForExternalUiAction {
                        deviceAssistantGateway.observeScreen().toToolOutcome()
                    }
                AgentToolDefinitions.WaitForScreenToolName -> prepareForExternalUiAction {
                    delay(700)
                    deviceAssistantGateway.observeScreen().toToolOutcome()
                }
                AgentToolDefinitions.TapUiElementToolName -> executeTapUiElementTool(call.arguments)
                AgentToolDefinitions.InputTextToolName -> executeInputTextTool(call.arguments)
                AgentToolDefinitions.SwipeScreenToolName -> executeSwipeScreenTool(call.arguments)
                AgentToolDefinitions.PressBackToolName -> prepareForExternalUiAction {
                    deviceAssistantGateway.pressBack().toToolOutcome()
                }
                AgentToolDefinitions.PressHomeToolName -> prepareForExternalUiAction {
                    deviceAssistantGateway.pressHome().toToolOutcome()
                }
                else -> ToolExecutionOutcome(
                    content = "Unsupported tool: ${call.name}",
                    errorDetail = "Unsupported tool: ${call.name}"
                )
            }
        }.fold(
            onSuccess = { outcome ->
                val success = outcome.errorDetail == null && !outcome.cancelled && outcome.toolStatus !in setOf(MessageStatus.Failed, MessageStatus.Cancelled)
                loopGuard.recordProgress(outcome.screenProgress, call.name, success)
                loopGuard.recordOutcome(success, outcome.cancelled)
                val detail = outcome.errorDetail
                if (outcome.cancelled) {
                    chatRepository.updateMessage(
                        toolMessage.id,
                        toolName,
                        MessageStatus.Cancelled
                    )
                } else if (detail == null) {
                    chatRepository.updateMessage(
                        toolMessage.id,
                        toolName + outcome.displayDetail?.let { "\n$it" }.orEmpty(),
                        outcome.toolStatus ?: MessageStatus.Complete
                    )
                } else {
                    chatRepository.updateMessage(
                        toolMessage.id,
                        failedToolMessageContent(toolName, detail),
                        MessageStatus.Failed
                    )
                }
                outcome.content
            },
            onFailure = { throwable ->
                if (throwable is CancellationException) {
                    withContext(NonCancellable) {
                        chatRepository.updateMessage(toolMessage.id, toolName, MessageStatus.Cancelled)
                    }
                    throw throwable
                }
                loopGuard.recordOutcome(false)
                val message = throwable.message?.takeIf { it.isNotBlank() } ?: "Tool call failed."
                chatRepository.updateMessage(
                    toolMessage.id,
                    failedToolMessageContent(toolName, message),
                    MessageStatus.Failed
                )
                "Tool call failed: $message"
            }
        )
    }

    private suspend fun ensureVisibleAssistantSegmentBeforeToolCall(
        assistantId: String,
        content: String,
        reasoningContent: String,
        fallback: String = ""
    ) {
        val visibleContent = content.ifBlank { fallback }
        chatRepository.updateMessage(
            assistantId,
            visibleContent,
            MessageStatus.Complete,
            reasoningContent,
            replyPhase = ReplyPhase.Commentary
        )
    }

    private suspend fun executeTerminalTool(call: ChatToolCall, owner: String, messageId: String?): ToolExecutionOutcome {
        check(appSettingsStore.state.value.commandExecutionEnabled) { "AI command execution is disabled." }
        val terminal = requireNotNull(terminalManager) { "Command environment unavailable." }
        if (call.name == TerminalToolDefinitions.Status) {
            val runtime = terminal.state.value.linux
            return ToolExecutionOutcome(buildJsonObject {
                put("linux_ready", runtime.ready); put("linux_distribution", "Alpine Linux ${runtime.version}")
                put("developer_tools_ready", runtime.developerToolsReady); put("linux_requires_android_root", false)
                put("android_available", appSettingsStore.state.value.powerModeEnabled && deviceAssistantGateway.connectionState.value.isConnected)
                put("android_connection", deviceAssistantGateway.connectionState.value.status.name)
                runtime.error?.let { put("error", it) }
            }.toString())
        }
        val id: String
        val snapshot: CommandSnapshot
        if (call.name == TerminalToolDefinitions.Run) {
            val command = requireNotNull(parseStringArgument(call.arguments, "command")) { "Command is required." }
            val environment = when (parseStringArgument(call.arguments, "environment")) {
                "linux" -> CommandEnvironment.Linux
                "android" -> CommandEnvironment.Android
                else -> error("Choose linux or android as the environment.")
            }
            val cwd = parseStringArgument(call.arguments, "working_directory") ?: if (environment == CommandEnvironment.Linux) "/workspace" else "/data/local/tmp"
            val timeout = parseStringArgument(call.arguments, "timeout_seconds")?.let { it.toIntOrNull() ?: error("Invalid timeout_seconds.") } ?: 120
            CommandValidation.validate(command, cwd, timeout)
            if (environment == CommandEnvironment.Android) {
                guardDeviceTask()
                if (systemCommandsDeclined) return ToolExecutionOutcome("System command permission was declined for this task.", cancelled = true)
                check(appSettingsStore.state.value.powerModeEnabled && deviceAssistantGateway.connectionState.value.isConnected) { "Enable Power and connect Root or Shizuku first." }
                if (!systemCommandsApproved) {
                    systemCommandsApproved = awaitDeviceActionConfirmation(call.name, activeTaskInstruction.take(300), command)
                    if (!systemCommandsApproved) {
                        systemCommandsDeclined = true
                        return ToolExecutionOutcome("Android command permission declined. Do not retry or use another tool to bypass the refusal.", cancelled = true)
                    }
                }
            }
            check(appSettingsStore.state.value.commandExecutionEnabled) { "AI command execution is disabled." }
            if (environment == CommandEnvironment.Android) check(appSettingsStore.state.value.powerModeEnabled) { "Power mode is off." }
            val started = terminal.start(owner, environment, command, cwd, timeout, commandRunId)
            id = started.id
            if (messageId != null) {
                commandMessages[id] = messageId
                observeCommandProgress(id, owner, messageId)
            }
            snapshot = terminal.awaitCompletion(owner, id)
        } else {
            id = requireNotNull(parseStringArgument(call.arguments, "command_id")) { "command_id is required." }
            if (call.name == TerminalToolDefinitions.Stop) snapshot = terminal.cancel(owner, id)
            else {
                val offset = parseStringArgument(call.arguments, "offset")?.let { it.toLongOrNull() ?: error("Invalid offset.") } ?: 0L
                val current = terminal.read(owner, id, offset)
                snapshot = if (current.isRunning) terminal.awaitCompletion(owner, id) else current
            }
        }
        val offset = if (call.name == TerminalToolDefinitions.Read) parseStringArgument(call.arguments, "offset")?.toLongOrNull() ?: 0L else 0L
        val page = snapshot.outputSince(offset)
        val status = when {
            snapshot.isRunning -> MessageStatus.Streaming
            snapshot.status == CommandStatus.Cancelled -> MessageStatus.Cancelled
            snapshot.status != CommandStatus.Exited || snapshot.exitCode != 0 -> MessageStatus.Failed
            else -> MessageStatus.Complete
        }
        val detail = commandDetail(snapshot)
        commandMessages[id]?.takeIf { it != messageId }?.let { existing ->
            chatRepository.updateMessage(existing, "${TerminalToolDefinitions.Run}\n$detail", status)
        }
        return ToolExecutionOutcome(buildJsonObject {
            put("command_id", id); put("environment", snapshot.environment.name.lowercase()); put("working_directory", snapshot.workingDirectory)
            put("status", snapshot.status.name); snapshot.exitCode?.let { put("exit_code", it) }
            put("elapsed_seconds", snapshot.elapsedMillis() / 1000L)
            put("output", page.output); put("output_offset", page.outputOffset); put("next_offset", page.nextOffset)
            put("has_more", page.nextOffset < snapshot.nextOffset); put("truncated", offset < snapshot.outputOffset)
            snapshot.error?.let { put("error", it) }
        }.toString(), displayDetail = detail, toolStatus = status)
    }

    private fun commandDetail(snapshot: CommandSnapshot): String =
        "${snapshot.command}\n${snapshot.status.name} · elapsed=${snapshot.elapsedMillis() / 1000L}s${snapshot.exitCode?.let { " · exit=$it" }.orEmpty()}\n${snapshot.output.takeLast(8000)}${snapshot.error?.let { "\n$it" }.orEmpty()}"

    private fun observeCommandProgress(id: String, owner: String, messageId: String) {
        val terminal = terminalManager ?: return
        viewModelScope.launch {
            try {
                terminal.awaitCompletion(owner, id) { progress ->
                    val status = when {
                        progress.isRunning -> MessageStatus.Streaming
                        progress.status == CommandStatus.Cancelled -> MessageStatus.Cancelled
                        progress.status != CommandStatus.Exited || progress.exitCode != 0 -> MessageStatus.Failed
                        else -> MessageStatus.Complete
                    }
                    chatRepository.updateMessage(messageId, "${TerminalToolDefinitions.Run}\n${commandDetail(progress)}", status)
                }
            } finally {
                commandMessages.remove(id)
            }
        }
    }

    private suspend fun executeAppUsageSummaryTool(arguments: String): ToolExecutionOutcome {
        if (!appUsageReader.hasUsageAccess()) {
            return requestUsageAccessPermission(AgentToolDefinitions.AppUsageSummaryToolName)
        }
        val range = parseAppUsageRange(arguments)
        val summary = appUsageReader.getUsageSummary(range)
        return ToolExecutionOutcome(AppUsageToolFormatter.formatSummary(summary))
    }

    private suspend fun executeRecentAppActivityTool(arguments: String): ToolExecutionOutcome {
        if (!appUsageReader.hasUsageAccess()) {
            return requestUsageAccessPermission(AgentToolDefinitions.RecentAppActivityToolName)
        }
        val hours = parseRecentActivityHours(arguments)
        val activity = appUsageReader.getRecentActivity(hours)
        return ToolExecutionOutcome(AppUsageToolFormatter.formatRecentActivity(activity))
    }

    private suspend fun executeOpenAppTool(arguments: String): ToolExecutionOutcome {
        val appName = parseOpenAppName(arguments)
            ?: return ToolExecutionOutcome(
                content = "Invalid open_app arguments: missing app_name.",
                errorDetail = "Invalid open_app arguments: missing app_name."
            )
        return prepareForExternalUiAction {
            val openedApp = appLauncher.openApp(appName)
            if (deviceAssistantGateway.accessibilityState.value.isConnected) {
                val observed = deviceAssistantGateway.observeScreen()
                observed.toToolOutcome().copy(content = "Opened app: $openedApp.\n${observed.asToolContent()}")
            } else {
                ToolExecutionOutcome("Opened app: $openedApp.")
            }
        }
    }

    private suspend fun executeSetBrightnessTool(arguments: String): ToolExecutionOutcome {
        val percent = parsePercentage(arguments)
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.SetBrightnessToolName, "percent")
        return deviceAssistantGateway.setScreenBrightness(percent).toToolOutcome()
    }

    private suspend fun executeSetMediaVolumeTool(arguments: String): ToolExecutionOutcome {
        val percent = parsePercentage(arguments)
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.SetMediaVolumeToolName, "percent")
        return deviceAssistantGateway.setMediaVolume(percent).toToolOutcome()
    }

    private suspend fun executeForceStopAppTool(call: ChatToolCall): ToolExecutionOutcome {
        val appName = parseAppName(call.arguments)
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.ForceStopAppToolName, "app_name")
        if (DeviceActionPolicy.requiresConfirmation(call.name)) {
            val confirmed = awaitDeviceActionConfirmation(
                toolName = call.name,
                title = appName,
                message = "Unsaved work in $appName may be lost."
            )
            if (!confirmed) {
                return ToolExecutionOutcome(
                    content = "User cancelled force stopping $appName.",
                    cancelled = true
                )
            }
        }
        return deviceAssistantGateway.forceStopApp(appName).toToolOutcome()
    }

    private suspend fun executeTapUiElementTool(arguments: String): ToolExecutionOutcome {
        val index = parseIntArgument(arguments, "index", minimum = 0)
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.TapUiElementToolName, "index")
        val longPress = parseBooleanArgument(arguments, "long_press") ?: false
        val screenId = parseStringArgument(arguments, "screen_id")
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.TapUiElementToolName, "screen_id")
        return prepareForExternalUiAction {
            deviceAssistantGateway.tapUiElement(index, longPress, screenId).toToolOutcome()
        }
    }

    private suspend fun executeInputTextTool(arguments: String): ToolExecutionOutcome {
        val index = parseIntArgument(arguments, "index", minimum = 0)
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.InputTextToolName, "index")
        val text = parseStringArgument(arguments, "text", allowBlank = true)
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.InputTextToolName, "text")
        val clearExisting = parseBooleanArgument(arguments, "clear_existing") ?: true
        val screenId = parseStringArgument(arguments, "screen_id")
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.InputTextToolName, "screen_id")
        if (text.length > 4_000) return invalidDeviceToolArguments(AgentToolDefinitions.InputTextToolName, "text (maximum 4000 characters)")
        return prepareForExternalUiAction {
            deviceAssistantGateway.inputText(index, text, clearExisting, screenId).toToolOutcome()
        }
    }

    private suspend fun executeSwipeScreenTool(arguments: String): ToolExecutionOutcome {
        val direction = parseStringArgument(arguments, "direction")
            ?.let { value ->
                DeviceSwipeDirection.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            }
            ?: return invalidDeviceToolArguments(AgentToolDefinitions.SwipeScreenToolName, "direction")
        val distance = parseIntArgument(arguments, "distance_percent", minimum = 20, maximum = 80) ?: 50
        return prepareForExternalUiAction {
            deviceAssistantGateway.swipeScreen(direction, distance).toToolOutcome()
        }
    }

    private suspend fun prepareForExternalUiAction(
        action: suspend () -> ToolExecutionOutcome
    ): ToolExecutionOutcome = floatingAssistantBridge.withExternalUiAction {
        delay(OverlayAutomationPrepareDelayMillis)
        guardDeviceTask()
        action()
    }

    private fun guardDeviceTask() {
        val task = activeDeviceTask ?: return
        try {
            taskControl.arm(task)
        } catch (cancelled: CancellationException) {
            if (task.interrupted) pauseDeviceTaskForUser()
            throw cancelled
        }
    }

    private fun pauseDeviceTaskForUser() {
        val conversationId = activeTaskConversationId ?: return
        if (activeDeviceTask?.interrupted != true || !isStreaming.value) return
        val paused = PausedDeviceTask(conversationId, activeTaskInstruction, activeDeviceResults.toList())
        stop()
        pausedDeviceTask.value = paused
    }

    private suspend fun awaitDeviceActionConfirmation(
        toolName: String,
        title: String,
        message: String
    ): Boolean {
        deviceActionConfirmation?.complete(false)
        val confirmation = CompletableDeferred<Boolean>()
        deviceActionConfirmation = confirmation
        pendingDeviceActionConfirmation.value = DeviceActionConfirmationUi(toolName, title, message)
        return try {
            confirmation.await()
        } finally {
            if (deviceActionConfirmation === confirmation) {
                deviceActionConfirmation = null
                pendingDeviceActionConfirmation.value = null
            }
        }
    }

    private fun DeviceToolResult.toToolOutcome(): ToolExecutionOutcome =
        ToolExecutionOutcome(
            content = asToolContent(),
            errorDetail = if (success) null else errorCode ?: summary,
            screenProgress = screen?.progressKey()
        )

    private fun invalidDeviceToolArguments(toolName: String, field: String) = ToolExecutionOutcome(
        content = "Invalid $toolName arguments: missing $field.",
        errorDetail = "Invalid $toolName arguments: missing $field."
    )

    private fun requestUsageAccessPermission(toolName: String): ToolExecutionOutcome {
        val message = "Open Android system Usage Access settings and allow Floryx before retrying this tool."
        usageAccessPermissionRequest.value = UsageAccessPermissionRequestUi(toolName)
        return ToolExecutionOutcome(
            content = "App usage access is not enabled. $message",
            errorDetail = message
        )
    }

    private fun parseAppUsageRange(arguments: String): String =
        runCatching {
            toolJson.parseToJsonElement(arguments)
                .jsonObject["range"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it == "today" || it == "yesterday" || it == "last_7_days" }
        }.getOrNull() ?: "today"

    private fun parseRecentActivityHours(arguments: String): Int =
        runCatching {
            toolJson.parseToJsonElement(arguments)
                .jsonObject["hours"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull()
                ?.coerceIn(1, 24)
        }.getOrNull() ?: 6

    private fun parseOpenAppName(arguments: String): String? =
        runCatching {
            toolJson.parseToJsonElement(arguments)
                .jsonObject["app_name"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun parseAppName(arguments: String): String? =
        runCatching {
            toolJson.parseToJsonElement(arguments)
                .jsonObject["app_name"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun parsePercentage(arguments: String): Int? =
        runCatching {
            toolJson.parseToJsonElement(arguments)
                .jsonObject["percent"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull()
                ?.coerceIn(0, 100)
        }.getOrNull()

    private fun parseStringArgument(
        arguments: String,
        field: String,
        allowBlank: Boolean = false
    ): String? = runCatching {
        toolJson.parseToJsonElement(arguments)
            .jsonObject[field]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { allowBlank || it.isNotBlank() }
    }.getOrNull()

    private fun parseIntArgument(
        arguments: String,
        field: String,
        minimum: Int,
        maximum: Int = Int.MAX_VALUE
    ): Int? = parseStringArgument(arguments, field, allowBlank = false)
        ?.toIntOrNull()
        ?.takeIf { it in minimum..maximum }

    private fun parseBooleanArgument(arguments: String, field: String): Boolean? =
        parseStringArgument(arguments, field, allowBlank = false)?.toBooleanStrictOrNull()

    private fun ChatToolCall.displayName(): String =
        when (name) {
            AgentToolDefinitions.OpenAppToolName ->
                parseOpenAppName(arguments)?.let { "$name:$it" } ?: name
            AgentToolDefinitions.ForceStopAppToolName ->
                parseAppName(arguments)?.let { "$name:$it" } ?: name
            AgentToolDefinitions.TapUiElementToolName ->
                parseIntArgument(arguments, "index", minimum = 0)?.let { "$name:$it" } ?: name
            else -> name
        }

    private fun failedToolMessageContent(toolName: String, detail: String): String =
        "$toolName\n$detail"

    private suspend fun appendReasoningDelta(
        assistantId: String,
        currentContent: String,
        currentReasoning: String,
        deltaText: String
    ): String {
        val reasoning = currentReasoning + deltaText
        val startedAt = activeAssistantReasoningStartedAt ?: System.currentTimeMillis().also {
            activeAssistantReasoningStartedAt = it
        }
        activeAssistantReasoningDurationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        activeAssistantReasoningContent = reasoning
        chatRepository.updateMessage(assistantId, currentContent, MessageStatus.Streaming, reasoning,
            reasoningDurationMillis = activeAssistantReasoningDurationMillis)
        return reasoning
    }

    private suspend fun appendContentDelta(
        assistantId: String,
        currentContent: String,
        currentReasoning: String,
        deltaText: String
    ): String {
        val content = currentContent + deltaText
        activeAssistantContent = content
        activeAssistantReasoningContent = currentReasoning
        chatRepository.updateMessage(assistantId, content, MessageStatus.Streaming, currentReasoning,
            reasoningDurationMillis = null)
        return content
    }

    private fun Throwable.userFacingChatError(): String {
        val rawMessage = message.orEmpty()
        return if (rawMessage.contains("Request timeout has expired", ignoreCase = true)) {
            "请求超时：模型思考时间过长或网络中断，请重试。"
        } else {
            rawMessage.ifBlank { "Request failed" }
        }
    }

    private fun invalidateMemoryUpdate() {
        memoryEpoch++
        memoryUpdateJob?.cancel()
        memoryUpdateJob = null
    }

    fun updateMemory(id: String, summary: String) {
        if (!MemoryPolicy.validSummary(summary.trim())) return
        invalidateMemoryUpdate()
        viewModelScope.launch {
            memoryRepository.update(id, summary)
            refreshMemoriesNow()
        }
    }

    private fun queueMemorySummary(userMessage: String, provider: ProviderConfig, apiKey: String?, model: String) {
        if (!appSettingsStore.state.value.memoryEnabled || !MemoryPolicy.shouldExtract(userMessage)) return
        invalidateMemoryUpdate()
        val epoch = memoryEpoch
        memoryUpdateJob = viewModelScope.launch {
            try {
                val snapshot = memoryRepository.snapshot()
                val drafts = memorySummaryGenerator.summarize(userMessage, snapshot.records, provider, apiKey, model)
                val saved = memoryRepository.merge(drafts, snapshot.revision) {
                    epoch == memoryEpoch && appSettingsStore.state.value.memoryEnabled
                }
                if (saved) refreshMemoriesNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Memory is optional; malformed/failed extraction must not store a raw turn or fail the reply.
            }
        }
    }

    private fun refreshMemories() {
        viewModelScope.launch {
            refreshMemoriesNow()
        }
    }

    private suspend fun refreshMemoriesNow() {
        memories.value = memoryRepository.getAll()
    }

    fun stop() {
        terminalManager?.cancelRun(commandRunId)
        taskControl.finish(activeDeviceTask)
        activeDeviceTask = null
        pausedDeviceTask.value = null
        val assistantId = activeAssistantMessageId
        val stoppedContent = activeAssistantContent.ifBlank {
            if (activeTaskInstruction.any { it in '\u3400'..'\u9fff' }) "已停止本轮处理。" else "This turn has stopped."
        }
        val stoppedReasoningContent = activeAssistantReasoningContent
        val stoppedReasoningDuration = activeAssistantReasoningDurationMillis
        val stoppingJob = sendJob
        stoppingJob?.cancel()
        deviceActionConfirmation?.complete(false)
        deviceActionConfirmation = null
        pendingDeviceActionConfirmation.value = null
        sendJob = null
        isStreaming.value = false
        if (assistantId != null) {
            stoppedTurnPersistence = viewModelScope.launch {
                stoppingJob?.join()
                chatRepository.updateMessage(
                    assistantId,
                    stoppedContent,
                    MessageStatus.Stopped,
                    stoppedReasoningContent,
                    reasoningDurationMillis = stoppedReasoningDuration.takeIf { it > 0L }
                )
            }
        }
        activeAssistantMessageId = null
        activeAssistantContent = ""
        activeAssistantReasoningContent = ""
        activeAssistantReasoningStartedAt = null
        activeAssistantReasoningDurationMillis = 0L
    }

    fun retryLastFailed() {
        val failed = uiState.value.messages.lastOrNull { it.status == MessageStatus.Failed } ?: return
        viewModelScope.launch {
            chatRepository.updateMessage(failed.id, "", MessageStatus.Streaming)
        }
    }

    private companion object {
        const val OverlayAutomationPrepareDelayMillis = 160L
        const val MaxToolCallRounds = 8
        const val MaxUiAutomationToolCallRounds = 24
        const val MaxConsecutiveIdenticalToolCalls = 4
        val UiAutomationToolNames = setOf(
            AgentToolDefinitions.ObserveScreenToolName,
            AgentToolDefinitions.WaitForScreenToolName,
            AgentToolDefinitions.TapUiElementToolName,
            AgentToolDefinitions.InputTextToolName,
            AgentToolDefinitions.SwipeScreenToolName,
            AgentToolDefinitions.PressBackToolName,
            AgentToolDefinitions.PressHomeToolName
        )
        val UiChangingToolNames = UiAutomationToolNames - setOf(
            AgentToolDefinitions.ObserveScreenToolName,
            AgentToolDefinitions.WaitForScreenToolName
        ) + AgentToolDefinitions.OpenAppToolName
        val ConnectedShizukuStatuses = setOf(
            com.flowchat.app.domain.device.ShizukuConnectionStatus.ConnectedAdb,
            com.flowchat.app.domain.device.ShizukuConnectionStatus.ConnectedRoot,
            com.flowchat.app.domain.device.ShizukuConnectionStatus.ConnectedDirectRoot
        )
    }
}

private data class PausedDeviceTask(val conversationId: String, val instruction: String, val results: List<String>)

private data class AssistantResponseResult(
    val content: String,
    val reasoningContent: String,
    val toolCalls: List<ChatToolCall>,
    val errorMessage: String?,
    val providerData: kotlinx.serialization.json.JsonArray? = null
)

private data class ToolExecutionOutcome(
    val content: String,
    val errorDetail: String? = null,
    val cancelled: Boolean = false,
    val screenProgress: String? = null,
    val displayDetail: String? = null,
    val toolStatus: MessageStatus? = null
)

internal class ToolCallLoopGuard(
    private val maxConsecutiveIdenticalCalls: Int
) {
    private var previousFingerprint: String? = null
    private var consecutiveIdenticalCalls = 0
    private var previousScreen: String? = null
    private var noProgressUiCalls = 0
    private var consecutiveFailures = 0
    var shouldStopAutomation: Boolean = false
        private set

    fun recordOutcome(success: Boolean, cancelled: Boolean = false) {
        consecutiveFailures = if (success) 0 else consecutiveFailures + 1
        if (cancelled || consecutiveFailures >= 3) shouldStopAutomation = true
    }

    fun recordProgress(screen: String?, toolName: String? = null, success: Boolean = true) {
        val changed = screen != null && screen != previousScreen
        if (changed && success && previousScreen != null) consecutiveIdenticalCalls = 0
        if (toolName in UiTools) {
            noProgressUiCalls = if (changed && success) 0 else noProgressUiCalls + 1
            if (noProgressUiCalls >= 6) shouldStopAutomation = true
        }
        if (screen != null) previousScreen = screen
    }

    fun allow(call: ChatToolCall): Boolean {
        val arguments = runCatching {
            Json.parseToJsonElement(call.arguments).jsonObject.toSortedMap().apply { remove("screen_id") }.toString()
        }.getOrDefault(call.arguments.trim())
        val fingerprint = "${call.name}:$arguments"
        consecutiveIdenticalCalls = if (fingerprint == previousFingerprint) {
            consecutiveIdenticalCalls + 1
        } else {
            1
        }
        previousFingerprint = fingerprint
        val allowed = consecutiveIdenticalCalls <= maxConsecutiveIdenticalCalls
        if (!allowed) shouldStopAutomation = true
        return allowed
    }

    private companion object {
        val UiTools = setOf("open_app", "observe_screen", "wait_for_screen", "tap_ui_element", "input_text", "swipe_screen", "press_back", "press_home")
    }
}
