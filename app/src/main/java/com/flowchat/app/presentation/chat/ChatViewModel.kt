package com.flowchat.app.presentation.chat

import com.flowchat.app.data.usage.*
import kotlinx.coroutines.flow.flow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.attachment.ExtractedAttachment
import com.flowchat.app.data.attachment.ImageAttachmentStore
import com.flowchat.app.data.terminal.TerminalManager
import com.flowchat.app.data.workspace.WorkspaceManager
import com.flowchat.app.data.workspace.WorkspaceBatch
import com.flowchat.app.domain.workspace.*
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
import com.flowchat.app.domain.chat.AttachmentTools
import com.flowchat.app.domain.chat.TaskRecovery
import com.flowchat.app.data.chat.TaskContextManager
import com.flowchat.app.data.chat.PreparedTaskContext
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
import com.flowchat.app.domain.model.ContextWindowUsage
import com.flowchat.app.domain.model.ChatImage
import com.flowchat.app.domain.model.Message
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
import com.flowchat.app.domain.provider.DeepSeekContextProfile
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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
    private val taskContextManager: TaskContextManager = TaskContextManager(chatClient, chatRepository),
    private val usageTracker: com.flowchat.app.data.usage.UsageTracker? = null,
    private val balanceClient: DeepSeekBalanceClient? = null,
    private val workspaceManager: WorkspaceManager? = null,
    @com.flowchat.app.di.ContextDispatcher private val contextDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val currentConversationId = MutableStateFlow<String?>(null)
    val usageRecords = currentConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else usageTracker?.observe(id) ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun setUsageTrackingEnabled(enabled: Boolean) = appSettingsStore.setUsageTrackingEnabled(enabled)

    private val commandMessages = mutableMapOf<String, String>()
    val terminalState = combine(terminalManager?.state ?: MutableStateFlow(TerminalUiState()), currentConversationId) { state, id ->
        state.copy(commands = state.commands.filter { terminalManager?.owns(id ?: "manual", it.id) == true })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TerminalUiState())
    private val thinkingSettingsMutex = Mutex()
    private val drafts = MutableStateFlow<Map<String, ConversationDraft>>(emptyMap())
    private val runningConversations = MutableStateFlow<Set<String>>(emptySet())
    private val liveContextWindows = MutableStateFlow<Map<String, ContextWindowSnapshot>>(emptyMap())
    private val overlayConversation = MutableStateFlow<String?>(null)
    private val turns = mutableMapOf<String, ConversationTurn>()
    private val endingConversations = mutableSetOf<String>()
    private val stoppedTurnPersistence = mutableMapOf<String, Job>()
    private val pausedDeviceTasks = MutableStateFlow<Map<String, PausedDeviceTask>>(emptyMap())
    private val deviceExecutionMutex = Mutex()
    private val taskControl get() = floatingAssistantBridge.deviceTaskControl
    private val usageAccessPermissionRequests = MutableStateFlow<Map<String, UsageAccessPermissionRequestUi>>(emptyMap())
    private val deviceActionConfirmations = MutableStateFlow<Map<String, DeviceActionConfirmationUi>>(emptyMap())
    private val memories = MutableStateFlow(emptyList<com.flowchat.app.domain.model.MemoryRecord>())
    private var memoryUpdateJob: Job? = null
    private var cleared = false
    @Volatile private var memoryEpoch = 0L
    private val toolJson = Json { ignoreUnknownKeys = true }
    private val recoveryReady = viewModelScope.async { chatRepository.recoverInterruptedTasks() }

    private fun selectedConversationId(): String? = currentConversationId.value ?: uiState.value.currentConversation?.id
    private fun draft(owner: String) = drafts.value[owner] ?: ConversationDraft()
    private fun updateDraft(owner: String, change: (ConversationDraft) -> ConversationDraft) {
        drafts.update { it + (owner to change(it[owner] ?: ConversationDraft())) }
    }
    private fun setError(message: String?, owner: String? = selectedConversationId()) {
        owner?.let { updateDraft(it) { value -> value.copy(error = message) } }
    }
    private suspend fun currentTurn(): ConversationTurn = requireNotNull(currentCoroutineContext()[ConversationTurn])

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
        drafts,
        runningConversations
    ) { state, draftMap, running ->
        val value = draftMap[state.currentConversation?.id] ?: ConversationDraft()
        state.copy(
            input = value.input,
            pendingAttachment = value.attachment,
            isAttachmentLoading = value.loading,
            isStreaming = state.currentConversation?.id in running,
            errorMessage = value.error
        )
    }

    private val stateWithPermissionRequest = combine(
        stateWithoutMessages,
        usageAccessPermissionRequests
    ) { state, permissionRequests ->
        state.copy(
            usageAccessPermissionRequest = permissionRequests[state.currentConversation?.id]
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
        deviceActionConfirmations,
        pausedDeviceTasks
    ) { state, shizukuState, accessibilityState, confirmations, paused ->
        state.copy(
            shizukuState = shizukuState,
            accessibilityState = accessibilityState,
            pendingDeviceActionConfirmation = confirmations[state.currentConversation?.id],
            awaitingConfirmationConversationId = confirmations.keys.firstOrNull { it != state.currentConversation?.id },
            deviceTaskPaused = state.currentConversation?.id in paused
        )
    }

    private val stateWithMemories = combine(
        stateWithDeviceAssistant,
        memories
    ) { state, memoryRecords ->
        state.copy(memories = memoryRecords)
    }

    private val conversationState = combine(
        stateWithMemories,
        currentConversationId.flatMapLatest { id ->
            if (id == null) flowOf(null to emptyList()) else chatRepository.observeMessages(id).map { id to it }
        }
    ) { state, ownedMessages ->
        val messages = ownedMessages.second.takeIf { ownedMessages.first == state.currentConversation?.id }.orEmpty()
        state.copy(
            messages = messages,
            interruptedTask = !state.isStreaming && TaskRecovery.canResume(messages)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    private val contextWindowPreview = conversationState.map { if (it.isStreaming) null else it }
        .distinctUntilChanged().mapLatest { state ->
            if (state?.currentConversation == null) return@mapLatest null
            delay(120) // Coalesce edits; estimating a draft never sends a model request.
            recoveryReady.await()
            try {
                withContext(contextDispatcher) { estimateContextWindow(state) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { null }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState = combine(conversationState, liveContextWindows, contextWindowPreview) { state, active, preview ->
        val owner = state.currentConversation?.id
        val snapshot = if (state.isStreaming) active[owner]?.takeIf {
            it.runId == turns[owner]?.commandRunId
        } else preview
        state.copy(contextWindow = snapshot?.takeIf {
            it.conversationId == owner && it.providerId == state.currentConversation?.providerId &&
                it.modelName == state.currentConversation?.modelName && it.settings == state.currentConversation?.let { chat ->
                    state.providers.firstOrNull { provider -> provider.id == chat.providerId }?.let { provider ->
                        ContextWindowSettings(provider.baseUrl, chat.maxTokens, chat.enableThinking, chat.thinkingEffort)
                    }
                }
        }?.usage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    private suspend fun estimateContextWindow(state: ChatUiState): ContextWindowSnapshot? {
        val conversation = state.currentConversation ?: return null
        val provider = state.providers.firstOrNull { it.id == conversation.providerId } ?: return null
        val restored = taskContextManager.restoreWindow(conversation.id, state.messages)
        val attachment = state.pendingAttachment
        val messages = if (state.input.isNotBlank() || attachment != null) restored.messages + Message(
            id = "context-draft-${conversation.id}", conversationId = conversation.id, role = MessageRole.User,
            content = state.input.trim(), attachmentName = attachment?.name, attachmentText = attachment?.text,
            attachmentImagePath = attachment?.imagePath, attachmentMimeType = attachment?.mimeType
        ) else restored.messages
        val profile = promptProfileRepository.getActiveProfile()
        val memory = if (state.appSettings.memoryEnabled)
            MemoryContextFormatter.format(state.memories.take(profile.memory.topN)) else emptyList()
        val images = messages.mapNotNull { message -> message.attachmentImagePath?.let {
            it to ChatImage(message.attachmentMimeType ?: "image/png", "")
        } }.toMap()
        val request = attachTaskTools(ChatRequestFactory.create(conversation, messages, null,
            profile.thinkingFormat, memory, images, restored.checkpoint), state.appSettings, conversation.workspace)
        return ContextWindowSnapshot(conversation.id, provider.id, conversation.modelName,
            taskContextManager.contextWindow(request, provider), settings = ContextWindowSettings(provider, request))
    }

    private fun attachTaskTools(request: ChatRequest, settings: AppSettings, workspace: WorkspaceLocation?): ChatRequest {
        var result = AgentToolDefinitions.withLifestyleTools(request,
            includeWebSearch = false, includeAppUsage = settings.powerModeEnabled,
            includeRecentActivity = settings.powerModeEnabled, includeOpenApp = settings.powerModeEnabled,
            includeDeviceAssistant = settings.powerModeEnabled &&
                (deviceAssistantGateway.connectionState.value.isConnected || deviceAssistantGateway.accessibilityState.value.isConnected),
            includeForceStop = settings.powerModeEnabled,
            deviceCapabilities = deviceAssistantGateway.connectionState.value.capabilities)
        result = TerminalToolDefinitions.attach(result, settings.commandExecutionEnabled && terminalManager != null)
        if (workspaceManager != null) result = WorkspaceToolDefinitions.attach(result, workspace,
            settings.commandExecutionEnabled && terminalManager != null)
        return AttachmentTools.attach(TaskContextTools.attach(result))
    }

    private fun publishContextWindow(turn: ConversationTurn, request: ChatRequest, provider: ProviderConfig, force: Boolean = true) {
        if (turns[turn.conversationId] !== turn) return
        if (DeepSeekContextProfile.resolve(provider, request) != null) {
            publishDeepSeekContextWindow(turn, request, provider, force)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - turn.lastContextUpdateAt < 150) {
            turn.pendingContextRequest = request
            if (turn.contextRefreshJob?.isActive != true) {
                turn.contextRefreshJob = viewModelScope.launch {
                    delay((150 - (now - turn.lastContextUpdateAt)).coerceIn(1, 150))
                    val pending = turn.pendingContextRequest
                    turn.pendingContextRequest = null
                    turn.contextRefreshJob = null
                    if (pending != null) publishContextWindow(turn, pending, provider)
                }
            }
            return
        }
        turn.contextRefreshJob?.cancel()
        turn.contextRefreshJob = null
        turn.pendingContextRequest = null
        turn.lastContextUpdateAt = now
        val value = ContextWindowSnapshot(turn.conversationId, provider.id, request.model,
            taskContextManager.contextWindow(request, provider), turn.commandRunId, ContextWindowSettings(provider, request))
        liveContextWindows.update { it + (turn.conversationId to value) }
    }

    private fun publishDeepSeekContextWindow(turn: ConversationTurn, request: ChatRequest, provider: ProviderConfig, force: Boolean) {
        turn.pendingContextRequest = request
        turn.contextRevision++
        if (!force && turn.contextRefreshJob?.isActive == true) return
        turn.contextRefreshJob?.cancel()
        turn.contextRefreshJob = viewModelScope.launch {
            try {
                if (!force) delay((150 - (System.currentTimeMillis() - turn.lastContextUpdateAt)).coerceIn(0, 150))
                while (true) {
                    val pending = turn.pendingContextRequest ?: break
                    turn.pendingContextRequest = null
                    val revision = turn.contextRevision
                    val usage = withContext(contextDispatcher) { taskContextManager.contextWindow(pending, provider) }
                    if (turns[turn.conversationId] !== turn) break
                    if (revision == turn.contextRevision) {
                        turn.lastContextUpdateAt = System.currentTimeMillis()
                        val snapshot = ContextWindowSnapshot(turn.conversationId, provider.id, pending.model,
                            usage.copy(isCompacting = turn.isContextCompacting), turn.commandRunId, ContextWindowSettings(provider, pending))
                        liveContextWindows.update { it + (turn.conversationId to snapshot) }
                    }
                    if (turn.pendingContextRequest == null) break
                    delay(150)
                }
            } finally {
                if (turn.contextRefreshJob === currentCoroutineContext()[Job]) turn.contextRefreshJob = null
            }
        }
    }

    private suspend fun <T> contextWork(provider: ProviderConfig, request: ChatRequest, work: suspend () -> T): T =
        if (DeepSeekContextProfile.resolve(provider, request) != null) withContext(contextDispatcher) { work() } else work()

    private val workspaceBusy = MutableStateFlow(false)
    private val workspaceError = MutableStateFlow<String?>(null)
    private val workspaceSaveNotice = MutableStateFlow<Pair<String, WorkspaceSave>?>(null)
    val workspaceState = combine(
        uiState.map { it.currentConversation?.let { chat -> chat.id to chat.workspace } }.distinctUntilChanged(),
        workspaceManager?.changes ?: flowOf(0L), workspaceBusy, workspaceError, workspaceSaveNotice
    ) { selected, _, busy, error, saved ->
        runCatching { workspaceManager?.inspect(selected?.first, if (selected != null) selected.second else uiState.value.appSettings.defaultWorkspace)
            ?: WorkspaceUiState() }.getOrElse { WorkspaceUiState(error = it.message) }.let {
            it.copy(busy = busy, error = error ?: it.error, lastSave = saved?.takeIf { notice -> notice.first == selected?.first }?.second)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceUiState())

    fun refreshWorkspace() { workspaceManager?.refresh() }
    fun workspacePickerFailed(message: String?) { workspaceError.value = message ?: "The system folder picker is unavailable." }
    fun selectWorkspace(uri: String, flags: Int, conversationId: String?) {
        val manager = workspaceManager ?: return
        viewModelScope.launch {
            workspaceBusy.value = true; workspaceError.value = null; workspaceSaveNotice.value = null
            try {
                val selected = manager.select(uri, flags)
                if (conversationId == null) appSettingsStore.setDefaultWorkspace(selected)
                else chatRepository.updateConversationWorkspace(conversationId, selected)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                workspaceError.value = error.message
            } finally { workspaceBusy.value = false; manager.refresh() }
        }
    }
    fun setWorkspaceDefault(enabled: Boolean) {
        appSettingsStore.setDefaultWorkspace(if (enabled) workspaceState.value.location else null)
        refreshWorkspace()
    }
    fun clearWorkspace() {
        workspaceSaveNotice.value = null
        val conversation = uiState.value.currentConversation
        val selected = workspaceState.value.location
        viewModelScope.launch {
            workspaceError.value = null
            runCatching {
                if (conversation != null) chatRepository.updateConversationWorkspace(conversation.id, null)
                if (appSettingsStore.state.value.defaultWorkspace == selected) appSettingsStore.setDefaultWorkspace(null)
            }.onFailure { workspaceError.value = it.message }
            refreshWorkspace()
        }
    }
    fun retryWorkspaceSave(localPath: String) {
        if (selectedConversationId() in runningConversations.value || terminalState.value.commands.any { it.isRunning }) return
        val owner = uiState.value.currentConversation?.id ?: return
        viewModelScope.launch {
            workspaceBusy.value = true; workspaceError.value = null
            try { workspaceSaveNotice.value = workspaceManager?.retry(owner, localPath)?.let { owner to it } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                workspaceError.value = error.message
            } finally { workspaceBusy.value = false; refreshWorkspace() }
        }
    }

    private val balanceRefresh = MutableStateFlow(0)
    fun refreshBalance() { balanceRefresh.update { it + 1 } }
    private data class BalanceSelection(val provider: ProviderConfig?, val enabled: Boolean, val streaming: Boolean)
    val balanceState = combine(
        uiState.map { state -> BalanceSelection(state.providers.firstOrNull { it.id == state.currentConversation?.providerId },
            state.appSettings.usageTrackingEnabled, state.isStreaming) }.distinctUntilChanged(),
        usageRecords.map { records -> records.filter { it.inputTokens != null }.map { it.id to it.outputTokens } }.distinctUntilChanged(),
        balanceRefresh
    ) { selection, _, _ -> selection }.flatMapLatest { selection -> flow {
        val provider = selection.provider
        when {
            !selection.enabled || provider == null -> emit(BalanceState())
            !DeepSeekBalanceClient.supports(provider) -> emit(BalanceState(BalanceStatus.Unsupported))
            selection.streaming -> emit(BalanceState(BalanceStatus.Waiting))
            else -> {
                emit(BalanceState(BalanceStatus.Loading))
                val result = try {
                    balanceClient?.fetch(provider, providerRepository.getApiKey(provider)) ?: BalanceState(BalanceStatus.Failed)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { BalanceState(BalanceStatus.Failed) }
                emit(result)
            }
        }
    } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BalanceState())

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
                        if (!result.success) setError(result.summary)
                    }
                }
        }
        refreshMemories()
        viewModelScope.launch {
            taskControl.foregroundReturns.collect {
                turns.values.toList().filter { it.activeDeviceTask?.interrupted == true }.forEach(::pauseDeviceTaskForUser)
            }
        }
        viewModelScope.launch {
            val overlayState = combine(uiState, overlayConversation, runningConversations,
                deviceActionConfirmations, pausedDeviceTasks) { state, requested, running, confirmations, paused ->
                val owner = turns.values.firstOrNull { it.deviceLockHeld }?.conversationId
                    ?: requested?.takeIf { it in running }
                    ?: running.lastOrNull() ?: state.currentConversation?.id
                state.copy(currentConversation = state.conversations.firstOrNull { it.id == owner },
                    isStreaming = owner in running,
                    pendingDeviceActionConfirmation = confirmations[owner],
                    deviceTaskPaused = owner in paused)
            }.flatMapLatest { state ->
                val owner = state.currentConversation?.id
                if (owner == null) flowOf(state.copy(messages = emptyList()))
                else chatRepository.observeMessages(owner).map { rows ->
                    state.copy(messages = rows, usageAccessPermissionRequest = usageAccessPermissionRequests.value[owner])
                }
            }
            combine(overlayState, terminalManager?.state ?: flowOf(TerminalUiState())) { state, terminal ->
                val owner = state.currentConversation?.id
                FloatingAssistantState.from(state, terminal.commands.filter {
                    owner != null && terminalManager?.owns(owner, it.id) == true
                })
            }.collect(floatingAssistantBridge::publish)
        }
        viewModelScope.launch {
            floatingAssistantBridge.commands.collect { command ->
                when (command) {
                    is FloatingAssistantCommand.Send -> {
                        floatingAssistantBridge.state.value.conversationId?.let(::selectConversation)
                        updateInput(command.text)
                        send()
                    }
                    FloatingAssistantCommand.Stop -> floatingAssistantBridge.state.value.conversationId
                        ?.let { owner -> if (owner in pausedDeviceTasks.value) endTask(owner) else turns[owner]?.let(::stopTurn) }
                }
            }
        }
    }

    fun updateInput(value: String) {
        val owner = selectedConversationId() ?: return
        val valueBefore = draft(owner)
        val pendingEcho = valueBefore.pendingClearedInputEcho
        if (pendingEcho != null && pendingEcho == value) {
            updateDraft(owner) { it.copy(pendingClearedInputEcho = null) }
            return
        }
        if (pendingEcho != null && value.isBlank() && valueBefore.input.isBlank()) {
            return
        }
        updateDraft(owner) { it.copy(input = value, pendingClearedInputEcho = null) }
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
        if (selectedConversationId() in runningConversations.value) return
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
        if (!enabled) turns.values.toList().filter { it.deviceLockHeld }.forEach(::stopTurn)
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
        val owner = selectedConversationId() ?: "manual"
        viewModelScope.launch {
            runCatching {
                terminalManager?.start(owner, CommandEnvironment.Linux,
                    "flowchat-install-tools", "/workspace", 600)
            }.onFailure { if (it !is CancellationException) reportError(it.message ?: "Command failed.") }
        }
    }
    fun stopTerminalCommand(id: String) {
        val owner = selectedConversationId() ?: "manual"
        viewModelScope.launch { runCatching { terminalManager?.cancel(owner, id) } }
    }

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
        turns[selectedConversationId()]?.deviceActionConfirmation?.complete(true)
    }

    fun cancelDeviceAction() {
        turns[selectedConversationId()]?.deviceActionConfirmation?.complete(false)
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
        val owner = selectedConversationId() ?: return
        if (draft(owner).loading) return
        updateDraft(owner) { it.copy(loading = true) }
        viewModelScope.launch {
            var result: ExtractedAttachment? = null
            var accepted = false
            try {
                withContext(Dispatchers.IO) { result = reader() }
                ensureActive()
                val attachment = requireNotNull(result)
                if (chatRepository.getConversation(owner) == null) return@launch
                replaceAttachment(PendingAttachment(attachment.name, attachment.text, attachment.imagePath, attachment.mimeType), owner)
                accepted = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                setError(errorText(error), owner)
            } finally {
                if (!accepted) result?.imagePath?.let { path ->
                    withContext(NonCancellable) { imageAttachmentStore?.discard(path) }
                }
                if (owner in drafts.value) updateDraft(owner) { it.copy(loading = false) }
            }
        }
    }

    private fun replaceAttachment(attachment: PendingAttachment?, owner: String? = selectedConversationId()) {
        if (owner == null) return
        val previous = draft(owner).attachment
        updateDraft(owner) { it.copy(attachment = attachment) }
        previous?.imagePath?.takeIf { it != attachment?.imagePath }?.let { path ->
            viewModelScope.launch { imageAttachmentStore?.discard(path) }
        }
    }

    fun clearAttachment() {
        replaceAttachment(null)
    }

    override fun onCleared() {
        cleared = true
        drafts.value.values.mapNotNull { it.attachment?.imagePath }.forEach { imageAttachmentStore?.discardNow(it) }
        super.onCleared()
    }

    fun reportError(message: String) {
        setError(message)
    }

    fun dismissUsageAccessPermissionRequest() {
        selectedConversationId()?.let { owner -> usageAccessPermissionRequests.update { it - owner } }
    }

    fun selectConversation(id: String) {
        currentConversationId.value = id
    }

    fun deleteConversation(id: String) {
        turns[id]?.let(::stopTurn)
        liveContextWindows.update { it - id }
        pausedDeviceTasks.update { it - id }
        drafts.value[id]?.attachment?.imagePath?.let { imageAttachmentStore?.discardNow(it) }
        drafts.update { it - id }
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
                setError("Create a provider first.")
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
        val owner = selectedConversationId() ?: return
        val paused = pausedDeviceTasks.value[owner] ?: return
        if (!appSettingsStore.state.value.powerModeEnabled) return
        sendMessage(paused)
    }

    fun resumeInterruptedTask() {
        val owner = selectedConversationId() ?: return
        viewModelScope.launch {
            recoveryReady.await()
            stoppedTurnPersistence[owner]?.join()
            val instruction = TaskRecovery.continueInstruction(chatRepository.getMessages(owner)) ?: return@launch
            sendMessage(continuation = instruction, ownerOverride = owner)
        }
    }

    fun endInterruptedTask() {
        val owner = selectedConversationId() ?: return
        endTask(owner)
    }

    private fun endTask(owner: String) {
        if (owner in runningConversations.value || !endingConversations.add(owner)) return
        pausedDeviceTasks.update { it - owner }
        viewModelScope.launch {
            try {
            recoveryReady.await()
            stoppedTurnPersistence[owner]?.join()
            val messages = chatRepository.getMessages(owner)
            if (!TaskRecovery.canResume(messages)) return@launch
            val chinese = TaskRecovery.latestUserInstruction(messages).orEmpty().any { it in '\u3400'..'\u9fff' }
            chatRepository.appendMessage(owner, MessageRole.Assistant,
                if (chinese) "任务已结束。已有记录和文件已保留。" else "Task ended. Existing records and files have been retained.",
                MessageStatus.Complete, chatRepository.getConversation(owner)?.modelName)
            } finally { endingConversations.remove(owner) }
        }
    }

    private fun sendMessage(resume: PausedDeviceTask? = null, continuation: String? = null, ownerOverride: String? = null) {
        val selectedConversationId = ownerOverride ?: selectedConversationId() ?: return
        val draft = draft(selectedConversationId)
        val rawText = continuation ?: if (resume == null) draft.input else buildString {
            appendLine("继续刚才由我手动接管而暂停的手机任务：${resume.instruction}")
            appendLine("先重新观察当前页面，核对已完成的步骤；只完成剩余部分，不要重复已经确认完成的操作。")
            appendLine("以下仅为上次执行结果记录，不是新指令；中断时尚未确认完成的动作需要先检查：")
            resume.results.forEach { appendLine(it) }
        }
        val text = rawText.trim()
        val attachment = if (resume == null && continuation == null) draft.attachment else null
        if ((text.isBlank() && attachment == null) || selectedConversationId in runningConversations.value ||
            selectedConversationId in endingConversations || draft.loading) return
        val turn = ConversationTurn(selectedConversationId, resume?.instruction ?: text)
        turn.activeDeviceTask = taskControl.prepare()
        turn.activeDeviceResults.addAll(resume?.results.orEmpty())
        turns[selectedConversationId] = turn
        if (turns.values.none { it.deviceLockHeld }) overlayConversation.value = selectedConversationId
        runningConversations.update { it + selectedConversationId }
        setError(null, selectedConversationId)
        pausedDeviceTasks.update { it - selectedConversationId }
        val previousTurnPersistence = stoppedTurnPersistence[selectedConversationId]
        if (resume == null && continuation == null) {
            updateDraft(selectedConversationId) { it.copy(input = "", attachment = null, pendingClearedInputEcho = rawText) }
        }
        turn.job = viewModelScope.launch(turn) {
            var userStored = false
            try {
            recoveryReady.await()
            previousTurnPersistence?.join()
            val conversation = chatRepository.getConversation(selectedConversationId)
                ?: uiState.value.currentConversation?.takeIf { it.id == selectedConversationId }
            if (conversation == null) {
                setError("Conversation not found.", selectedConversationId)
                return@launch
            }
            turn.activeWorkspace = conversation.workspace
            val provider = providerRepository.getProvider(conversation.providerId)
            if (provider == null) {
                setError("Provider not found.", selectedConversationId)
                return@launch
            }
            turn.activeTaskProvider = provider
            if (ProviderConfigValidator.validate(provider).isNotEmpty()) {
                setError("Provider configuration is invalid.", selectedConversationId)
                return@launch
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
            userStored = true
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
            turn.activeAssistantMessageId = assistant.id
            turn.activeAssistantContent = ""
            turn.activeAssistantReasoningContent = ""
            turn.activeAssistantReasoningStartedAt = null
            turn.activeAssistantReasoningDurationMillis = 0L
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
            val chinese = text.any { it in '\u3400'..'\u9fff' }
            val showContextPreparation: suspend () -> Unit = {
                turn.isContextCompacting = true
                liveContextWindows.update { windows ->
                    windows[conversation.id]?.takeIf { it.runId == turn.commandRunId }?.let { snapshot ->
                        windows + (conversation.id to snapshot.copy(usage = snapshot.usage.copy(isCompacting = true)))
                    } ?: windows
                }
                chatRepository.updateMessage(assistant.id, ReplyPolicy.contextPreparation(chinese), MessageStatus.Streaming,
                    replyPhase = ReplyPhase.Commentary)
            }
            var appliedCheckpointBoundary: String? = null
            val taskImages = imageAttachmentStore?.loadRecent(history).orEmpty()
            fun buildTaskWindow(prepared: PreparedTaskContext): ChatRequest {
                val rebuilt = ChatRequestFactory.create(freshConversation, prepared.messages, null,
                    activeProfile.thinkingFormat, relevantMemories, taskImages, prepared.checkpoint)
                return attachTaskTools(rebuilt, settings, turn.activeWorkspace)
            }
            var request = runCatching {
                val restored = taskContextManager.restoreWindow(conversation.id, history)
                val template = ChatRequestFactory.create(freshConversation, emptyList())
                val initial = contextWork(provider, template) { buildTaskWindow(restored) }
                publishContextWindow(turn, initial, provider)
                val prepared = contextWork(provider, initial) { taskContextManager.prepare(freshConversation, history, provider, apiKey,
                    onCompacting = showContextPreparation, projectWindow = ::buildTaskWindow) }
                turn.isContextCompacting = false
                appliedCheckpointBoundary = prepared.checkpoint?.throughMessageId
                contextWork(provider, initial) { buildTaskWindow(prepared) }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                val message = error.userFacingChatError()
                chatRepository.updateMessage(assistant.id, message, MessageStatus.Failed, replyPhase = ReplyPhase.Answer)
                setError(message, selectedConversationId)
                turn.activeAssistantMessageId = null
                turn.activeDeviceTask = null
                return@launch
            }
            if (appliedCheckpointBoundary != null) contextWork(provider, request) { taskContextManager.recordCompaction(request, provider) }
            publishContextWindow(turn, request, provider)
            var content = ""
            var reasoningContent = ""
            var toolResultCount = 0
            var closingReason: String? = null
            runCatching {
                var toolRounds = 0
                var toolRoundLimit = if (settings.commandExecutionEnabled || turn.activeWorkspace != null) 80 else MaxToolCallRounds
                val toolCallLoopGuard = ToolCallLoopGuard(MaxConsecutiveIdenticalToolCalls)
                while (true) {
                    if (closingReason == null && contextWork(provider, request) { taskContextManager.shouldCompact(request, provider) }) {
                        // Keep one or two recent native groups whole, including opaque replay state.
                        val retainedStart = contextWork(provider, request) { taskContextManager.retainedNativeStart(request, provider) }
                        val protectedId = retainedStart?.let { request.messages[it].localMessageId }
                        if (retainedStart != null && protectedId != null) {
                            val stored = chatRepository.getMessages(conversation.id).filter { it.id != assistant.id }
                            val nativeTail = request.messages.drop(retainedStart)
                            fun projectActiveWindow(prepared: PreparedTaskContext): ChatRequest {
                                val protectedIndex = prepared.messages.indexOfFirst { it.id == protectedId }
                                val prefix = if (protectedIndex >= 0) prepared.messages.take(protectedIndex) else prepared.messages
                                val rebuilt = buildTaskWindow(prepared.copy(messages = prefix))
                                return rebuilt.copy(messages = rebuilt.messages + nativeTail)
                            }
                            val prepared = contextWork(provider, request) { taskContextManager.prepare(freshConversation, stored, provider, apiKey,
                                protectedMessageId = protectedId, force = true, onCompacting = showContextPreparation,
                                projectWindow = ::projectActiveWindow) }
                            val boundary = prepared.checkpoint?.throughMessageId
                            if (boundary != null && boundary != appliedCheckpointBoundary) {
                                val protectedIndex = prepared.messages.indexOfFirst { it.id == protectedId }
                                if (protectedIndex >= 0) {
                                    request = contextWork(provider, request) { projectActiveWindow(prepared) }
                                    appliedCheckpointBoundary = boundary
                                }
                            }
                        } else if (retainedStart == null) {
                            val stored = chatRepository.getMessages(conversation.id).filter { it.id != assistant.id }
                            val prepared = contextWork(provider, request) { taskContextManager.prepare(freshConversation, stored, provider, apiKey, force = true,
                                onCompacting = showContextPreparation, projectWindow = ::buildTaskWindow) }
                            if (prepared.checkpoint != null && prepared.checkpoint.throughMessageId != appliedCheckpointBoundary) {
                                request = contextWork(provider, request) { buildTaskWindow(prepared) }
                                appliedCheckpointBoundary = prepared.checkpoint.throughMessageId
                            }
                        }
                        turn.isContextCompacting = false
                        contextWork(provider, request) { taskContextManager.recordCompaction(request, provider) }
                        publishContextWindow(turn, request, provider)
                    }
                    contextWork(provider, request) { taskContextManager.requireFits(request, conversation.id, provider) }
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
                        chatRepository.updateMessage(assistant.id, visible, MessageStatus.Failed, reasoningContent, replyPhase = ReplyPhase.Answer)
                        setError(message, selectedConversationId)
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
                            if (content.isNotBlank()) queueMemorySummary(rawText, provider, apiKey, freshConversation.modelName, freshConversation.id)
                            return@runCatching
                        }
                    } else if (closingReason != null) {
                        // A provider may ignore tools being disabled. Never execute another action during finalization.
                        chatRepository.updateMessage(assistant.id,
                            ReplyPolicy.incompleteResult(chinese, toolResultCount, true),
                            MessageStatus.Failed, reasoningContent, replyPhase = ReplyPhase.Answer)
                        return@runCatching
                    } else {
                        if (response.toolCalls.any { it.name in UiAutomationToolNames || it.name == AttachmentTools.Read }) {
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
                        request = com.flowchat.app.domain.chat.ScreenVisionContext.retainRecent(request.copy(
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
                        ))
                        publishContextWindow(turn, request, provider)
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
                    turn.activeAssistantMessageId = assistant.id
                    turn.activeAssistantContent = ""
                    turn.activeAssistantReasoningContent = ""
                    turn.activeAssistantReasoningStartedAt = null
                    turn.activeAssistantReasoningDurationMillis = 0L
                    content = ""
                    reasoningContent = ""
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                } else {
                    val message = ReplyPolicy.errorMessage(throwable.userFacingChatError(), chinese)
                    val visible = ReplyPolicy.interruptedReply(turn.activeAssistantContent, chinese, toolResultCount, closingReason != null, message)
                    chatRepository.updateMessage(assistant.id, visible, MessageStatus.Failed, turn.activeAssistantReasoningContent, replyPhase = ReplyPhase.Answer)
                    setError(message, selectedConversationId)
                }
            }
            if (turn.activeAssistantMessageId == assistant.id) {
                turn.activeAssistantMessageId = null
                turn.activeAssistantContent = ""
                turn.activeAssistantReasoningContent = ""
                turn.activeAssistantReasoningStartedAt = null
                turn.activeAssistantReasoningDurationMillis = 0L
            }

            } catch (cancelled: CancellationException) {
                turn.activeAssistantMessageId?.let { id -> withContext(NonCancellable) {
                    chatRepository.updateMessage(id, turn.activeAssistantContent.ifBlank {
                        if (text.any { it in '\u3400'..'\u9fff' }) "已停止本轮处理。" else "This turn has stopped."
                    }, MessageStatus.Stopped, turn.activeAssistantReasoningContent,
                        reasoningDurationMillis = turn.activeAssistantReasoningDurationMillis.takeIf { it > 0L })
                } }
                throw cancelled
            } catch (error: Exception) {
                val message = error.userFacingChatError()
                setError(message, selectedConversationId)
                turn.activeAssistantMessageId?.let { id ->
                    chatRepository.updateMessage(id, ReplyPolicy.interruptedReply(turn.activeAssistantContent,
                        text.any { it in '\u3400'..'\u9fff' }, turn.activeDeviceResults.size, false, message),
                        MessageStatus.Failed, turn.activeAssistantReasoningContent, replyPhase = ReplyPhase.Answer)
                }
            } finally {
                if (!userStored && resume == null && continuation == null && !cleared && selectedConversationId in drafts.value) {
                    updateDraft(selectedConversationId) { current -> current.copy(
                        input = current.input.ifEmpty { draft.input }, attachment = current.attachment ?: attachment,
                        pendingClearedInputEcho = null) }
                    if (draft(selectedConversationId).attachment != attachment) attachment?.imagePath?.let { imageAttachmentStore?.discardNow(it) }
                } else if (!userStored) {
                    attachment?.imagePath?.let { imageAttachmentStore?.discardNow(it) }
                }
            }
        }
        turn.job?.invokeOnCompletion {
            turn.contextRefreshJob?.cancel()
            turn.pendingContextRequest = null
            taskControl.finish(turn.activeDeviceTask)
            terminalManager?.cancelRun(turn.commandRunId)
            if (turn.deviceLockHeld) {
                turn.deviceLockHeld = false
                deviceExecutionMutex.unlock(turn)
            }
            if (turns[selectedConversationId] === turn) {
                turns.remove(selectedConversationId)
                liveContextWindows.update { windows -> windows - selectedConversationId }
                runningConversations.update { ids -> ids - selectedConversationId }
                deviceActionConfirmations.update { values -> values - selectedConversationId }
            }
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
        val turn = currentTurn()
        var content = currentContent
        var reasoningContent = currentReasoning
        var toolCalls = emptyList<ChatToolCall>()
        var error: String? = null
        var providerData: kotlinx.serialization.json.JsonArray? = null
        val responseFlow = chatClient.streamChat(request, provider, apiKey).let {
            if (DeepSeekContextProfile.resolve(provider, request) != null) it.flowOn(contextDispatcher) else it
        }
        responseFlow.collect { delta ->
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
            val responseWindow = request.copy(messages = request.messages + ChatRequestMessage(
                role = MessageRole.Assistant.apiRole, content = content, reasoningContent = reasoningContent,
                toolCalls = toolCalls, providerData = providerData))
            publishContextWindow(turn, responseWindow, provider,
                force = delta is ChatDelta.Done || delta is ChatDelta.ToolCalls || delta is ChatDelta.ProviderData || delta is ChatDelta.Error)
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
        val turn = currentTurn()
        var uiChanged = false
        val evidence = mutableListOf<com.flowchat.app.domain.chat.ExecutionStep>()
        return calls.map { call ->
            // Commit intent before a side effect, then commit its returned evidence before the next call.
            // Even cancellation or process death leaves an explicit unconfirmed action to inspect.
            evidence += TaskExecutionHistory.pending(call)
            chatRepository.updateExecutionContext(assistantId, TaskExecutionHistory.encode(evidence))
            val outcome = try { if (loopGuard.shouldStopAutomation) {
                ToolExecutionOutcome("cancelled: tool execution has stopped for this turn. Do not retry.")
            } else if (call.name !in allowedTools) {
                loopGuard.recordOutcome(false)
                ToolExecutionOutcome("failed: tool_unavailable. This tool is not enabled for this request.")
            } else if (uiChanged && call.name in UiAutomationToolNames + AgentToolDefinitions.OpenAppToolName) {
                ToolExecutionOutcome("failed: sequential_ui_required. Inspect the previous action result and request the next action in a new response.")
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
            val result = outcome.content
            evidence[evidence.lastIndex] = TaskExecutionHistory.completed(evidence.last(), result)
            withContext(NonCancellable) {
                chatRepository.updateExecutionContext(assistantId, TaskExecutionHistory.encode(evidence))
            }
            if (call.name in UiAutomationToolNames + AgentToolDefinitions.OpenAppToolName) {
                turn.activeDeviceResults += "${call.name}: ${result.lineSequence().firstOrNull().orEmpty().take(300)}"
                while (turn.activeDeviceResults.size > 12) turn.activeDeviceResults.removeAt(0)
            }
            if (call.name in UiChangingToolNames) uiChanged = true
            ChatRequestMessage(
                role = "tool",
                content = result,
                toolCallId = call.id,
                images = listOfNotNull(outcome.screenImage?.image),
                screenImageId = outcome.screenImage?.screenId
            )
        }
    }

    private suspend fun executeSingleToolCall(
        call: ChatToolCall,
        conversationId: String,
        modelName: String?,
        loopGuard: ToolCallLoopGuard
    ): ToolExecutionOutcome {
        val turn = currentTurn()
        if (call.name == AttachmentTools.Read) {
            if (!loopGuard.allow(call)) return ToolExecutionOutcome("Stopped a repeated identical attachment lookup.")
            val result = AttachmentTools.read(call.arguments, conversationId, chatRepository.getMessages(conversationId))
            loopGuard.recordOutcome(!result.startsWith("{\"error\":"))
            return ToolExecutionOutcome(result)
        }
        if (call.name == TaskContextTools.Read) {
            if (!loopGuard.allow(call)) return ToolExecutionOutcome("Stopped a repeated identical history lookup.")
            val result = TaskContextTools.read(call.arguments, conversationId, chatRepository.getMessages(conversationId))
            loopGuard.recordOutcome(!result.startsWith("failed:"))
            return ToolExecutionOutcome(result)
        }
        if (call.name in setOf(TerminalToolDefinitions.Status, TerminalToolDefinitions.Read, TerminalToolDefinitions.Stop)) {
            if (!loopGuard.allow(call)) return ToolExecutionOutcome("Stopped a repeated identical tool loop. No more tools are available.")
            return runCatching {
                check(appSettingsStore.state.value.commandExecutionEnabled) { "AI command execution is disabled." }
                val result = executeTerminalTool(call, conversationId, null)
                loopGuard.recordOutcome(result.errorDetail == null && result.toolStatus != MessageStatus.Failed, result.cancelled)
                result
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                loopGuard.recordOutcome(false)
                ToolExecutionOutcome("Command tool failed: ${error.message}")
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
            if (call.name !in TerminalToolDefinitions.Names + WorkspaceToolDefinitions.Names + AgentToolDefinitions.WebSearchToolName) guardDeviceTask()
            if (call.name !in TerminalToolDefinitions.Names + WorkspaceToolDefinitions.Names + AgentToolDefinitions.WebSearchToolName && !appSettingsStore.state.value.powerModeEnabled) {
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
                in WorkspaceToolDefinitions.Names -> {
                    if (call.name in setOf(WorkspaceToolDefinitions.Import, WorkspaceToolDefinitions.Publish)) {
                        check(appSettingsStore.state.value.commandExecutionEnabled) { "AI command execution is disabled." }
                    }
                    val location = requireNotNull(turn.activeWorkspace) { "Select a workspace in Settings first." }
                    val result = requireNotNull(workspaceManager).execute(call, conversationId, location)
                    ToolExecutionOutcome(result, displayDetail = result.take(1500))
                }
                AgentToolDefinitions.WebSearchToolName -> ToolExecutionOutcome("Web search is currently disabled.", cancelled = true)
                AgentToolDefinitions.AppUsageSummaryToolName -> executeAppUsageSummaryTool(call.arguments)
                AgentToolDefinitions.RecentAppActivityToolName -> executeRecentAppActivityTool(call.arguments)
                AgentToolDefinitions.OpenAppToolName -> executeOpenAppTool(call.arguments)
                AgentToolDefinitions.DeviceStatusToolName -> deviceAssistantGateway.getDeviceStatus().toToolOutcome()
                AgentToolDefinitions.ForegroundAppToolName -> deviceAssistantGateway.getForegroundApp().toToolOutcome()
                AgentToolDefinitions.SetBrightnessToolName -> executeSetBrightnessTool(call.arguments)
                AgentToolDefinitions.SetMediaVolumeToolName -> executeSetMediaVolumeTool(call.arguments)
                AgentToolDefinitions.ForceStopAppToolName -> executeForceStopAppTool(call)
                AgentToolDefinitions.ObserveScreenToolName -> observeScreenForModel(call, modelName)
                AgentToolDefinitions.WaitForScreenToolName -> prepareForExternalUiAction {
                    delay(700)
                    observeScreenForModel(call, modelName)
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
                outcome
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
                ToolExecutionOutcome("Tool call failed: $message", errorDetail = message)
            }
        )
    }

    private suspend fun observeScreenForModel(call: ChatToolCall, modelName: String?): ToolExecutionOutcome = prepareForExternalUiAction {
        val turn = currentTurn()
        val includeImage = parseBooleanArgument(call.arguments, "include_image") ?: true
        if (!includeImage) return@prepareForExternalUiAction deviceAssistantGateway.observeScreen().toToolOutcome()
        val region = try { com.flowchat.app.domain.device.ScreenImageRegion.fromToolArguments(call.arguments) }
        catch (_: IllegalArgumentException) { return@prepareForExternalUiAction invalidDeviceToolArguments(call.name, "image_region (percent bounds)") }
        if (com.flowchat.app.domain.provider.ScreenVisionSupport.knownTextOnly(turn.activeTaskProvider, modelName)) {
            return@prepareForExternalUiAction deviceAssistantGateway.observeScreen().copy(
                summary = "Only UI elements were read. The selected model is text-only; choose a model with image input, such as DeepSeek Flash, for visual analysis.",
                imageErrorCode = "model_vision_unsupported").toToolOutcome()
        }
        floatingAssistantBridge.withScreenCapture {
            guardDeviceTask()
            deviceAssistantGateway.captureScreen(region).toToolOutcome()
        }
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
        val turn = currentTurn()
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
        var workspaceResult = WorkspaceBatch()
        if (call.name == TerminalToolDefinitions.Run) {
            val command = requireNotNull(parseStringArgument(call.arguments, "command")) { "Command is required." }
            val environment = when (parseStringArgument(call.arguments, "environment")) {
                "linux" -> CommandEnvironment.Linux
                "android" -> CommandEnvironment.Android
                else -> error("Choose linux or android as the environment.")
            }
            val workspace = turn.activeWorkspace
            val args = toolJson.parseToJsonElement(call.arguments).jsonObject
            val workspaceInputs = WorkspaceManager.argumentPaths(args, "workspace_inputs")
            val workspaceOutputs = WorkspaceManager.argumentPaths(args, "workspace_outputs")
            if (workspaceInputs.isNotEmpty() || workspaceOutputs.isNotEmpty()) {
                check(environment == CommandEnvironment.Linux && workspace != null && workspaceManager != null) {
                    "Workspace transfers require Linux and a selected phone folder."
                }
            }
            val defaultDirectory = if (environment == CommandEnvironment.Linux && workspace != null)
                workspaceManager?.executionDirectory(owner, workspace) ?: "/workspace" else if (environment == CommandEnvironment.Linux) "/workspace" else "/data/local/tmp"
            val cwd = parseStringArgument(call.arguments, "working_directory") ?: defaultDirectory
            val timeout = parseStringArgument(call.arguments, "timeout_seconds")?.let { it.toIntOrNull() ?: error("Invalid timeout_seconds.") } ?: 120
            CommandValidation.validate(command, cwd, timeout)
            if (environment == CommandEnvironment.Android) {
                guardDeviceTask()
                if (turn.systemCommandsDeclined) return ToolExecutionOutcome("System command permission was declined for this task.", cancelled = true)
                check(appSettingsStore.state.value.powerModeEnabled && deviceAssistantGateway.connectionState.value.isConnected) { "Enable Power and connect Root or Shizuku first." }
                if (!turn.systemCommandsApproved) {
                    turn.systemCommandsApproved = awaitDeviceActionConfirmation(call.name, turn.activeTaskInstruction.take(300), command)
                    if (!turn.systemCommandsApproved) {
                        turn.systemCommandsDeclined = true
                        return ToolExecutionOutcome("Android command permission declined. Do not retry or use another tool to bypass the refusal.", cancelled = true)
                    }
                }
            }
            check(appSettingsStore.state.value.commandExecutionEnabled) { "AI command execution is disabled." }
            if (environment == CommandEnvironment.Android) check(appSettingsStore.state.value.powerModeEnabled) { "Power mode is off." }
            if (environment == CommandEnvironment.Linux && workspace != null && workspaceManager != null) {
                if (messageId != null && (workspaceInputs.isNotEmpty() || workspaceOutputs.isNotEmpty()))
                    chatRepository.updateMessage(messageId, "${call.name}\nPreparing workspace files…", MessageStatus.Streaming)
                workspaceManager.prepareCommand(owner, workspace, workspaceInputs, workspaceOutputs)
            }
            val started = terminal.start(owner, environment, command, cwd, timeout, turn.commandRunId)
            id = started.id
            var progressJob: Job? = null
            if (messageId != null) {
                commandMessages[id] = messageId
                progressJob = observeCommandProgress(id, owner, messageId)
            }
            snapshot = terminal.awaitCompletion(owner, id)
            progressJob?.join()
            if (workspaceOutputs.isNotEmpty() && workspace != null && workspaceManager != null &&
                snapshot.status == CommandStatus.Exited && snapshot.exitCode == 0) {
                if (messageId != null) chatRepository.updateMessage(messageId,
                    "${call.name}\n${commandDetail(snapshot)}\nSaving workspace results…", MessageStatus.Streaming)
                workspaceResult = workspaceManager.publish(owner, workspace, workspaceOutputs)
            }
            workspaceManager?.refresh()
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
            workspaceResult.errors.isNotEmpty() -> MessageStatus.Failed
            else -> MessageStatus.Complete
        }
        val detail = commandDetail(snapshot) + workspaceResult.saved.joinToString("") { "\nSaved: ${it.path}${if (it.conflict) " (conflict copy)" else ""}" } +
            workspaceResult.errors.joinToString("") { "\nSave failed; local result retained: $it" }
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
            put("workspace_saved", kotlinx.serialization.json.buildJsonArray { workspaceResult.saved.forEach { add(WorkspaceManager.saveJson(it)) } })
            put("workspace_errors", kotlinx.serialization.json.buildJsonArray { workspaceResult.errors.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }.toString(), displayDetail = detail, toolStatus = status)
    }

    private fun commandDetail(snapshot: CommandSnapshot): String =
        "${snapshot.command}\n${snapshot.status.name} · elapsed=${snapshot.elapsedMillis() / 1000L}s${snapshot.exitCode?.let { " · exit=$it" }.orEmpty()}\n${snapshot.output.takeLast(8000)}${snapshot.error?.let { "\n$it" }.orEmpty()}"

    private fun observeCommandProgress(id: String, owner: String, messageId: String): Job {
        val terminal = requireNotNull(terminalManager)
        return viewModelScope.launch {
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

    private suspend fun guardDeviceTask() {
        val turn = currentTurn()
        if (!turn.deviceLockHeld) {
            deviceExecutionMutex.lock(turn)
            turn.deviceLockHeld = true
            overlayConversation.value = turn.conversationId
        }
        val task = requireNotNull(turn.activeDeviceTask)
        try {
            taskControl.activate(task)
            taskControl.arm(task)
        } catch (cancelled: CancellationException) {
            if (task.interrupted) pauseDeviceTaskForUser(turn)
            throw cancelled
        }
    }

    private fun pauseDeviceTaskForUser(turn: ConversationTurn) {
        if (turn.activeDeviceTask?.interrupted != true || turns[turn.conversationId] !== turn) return
        val paused = PausedDeviceTask(turn.conversationId, turn.activeTaskInstruction, turn.activeDeviceResults.toList())
        stopTurn(turn)
        pausedDeviceTasks.update { it + (turn.conversationId to paused) }
    }

    private suspend fun awaitDeviceActionConfirmation(toolName: String, title: String, message: String): Boolean {
        val turn = currentTurn()
        guardDeviceTask()
        val task = requireNotNull(turn.activeDeviceTask)
        return taskControl.withUserConfirmation(task) {
            turn.deviceActionConfirmation?.complete(false)
            val confirmation = CompletableDeferred<Boolean>()
            turn.deviceActionConfirmation = confirmation
            deviceActionConfirmations.update { it + (turn.conversationId to DeviceActionConfirmationUi(toolName, title, message)) }
            try {
                confirmation.await()
            } finally {
                if (turn.deviceActionConfirmation === confirmation) {
                    turn.deviceActionConfirmation = null
                    deviceActionConfirmations.update { it - turn.conversationId }
                }
            }
        }
    }

    private fun DeviceToolResult.toToolOutcome(): ToolExecutionOutcome =
        ToolExecutionOutcome(
            content = asToolContent(),
            errorDetail = if (!success) errorCode ?: summary else imageErrorCode,
            screenProgress = screen?.progressKey(),
            screenImage = screenImage,
            displayDetail = screenImage?.let { "image_size=${it.width}x${it.height}\nscreen_id=${it.screenId}" }
        )

    private fun invalidDeviceToolArguments(toolName: String, field: String) = ToolExecutionOutcome(
        content = "Invalid $toolName arguments: missing $field.",
        errorDetail = "Invalid $toolName arguments: missing $field."
    )

    private suspend fun requestUsageAccessPermission(toolName: String): ToolExecutionOutcome {
        val turn = currentTurn()
        val message = "Open Android system Usage Access settings and allow Floryx before retrying this tool."
        usageAccessPermissionRequests.update { it + (turn.conversationId to UsageAccessPermissionRequestUi(toolName)) }
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
        val turn = currentTurn()
        val reasoning = currentReasoning + deltaText
        val startedAt = turn.activeAssistantReasoningStartedAt ?: System.currentTimeMillis().also {
            turn.activeAssistantReasoningStartedAt = it
        }
        turn.activeAssistantReasoningDurationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        turn.activeAssistantReasoningContent = reasoning
        chatRepository.updateMessage(assistantId, currentContent, MessageStatus.Streaming, reasoning,
            reasoningDurationMillis = turn.activeAssistantReasoningDurationMillis)
        return reasoning
    }

    private suspend fun appendContentDelta(
        assistantId: String,
        currentContent: String,
        currentReasoning: String,
        deltaText: String
    ): String {
        val turn = currentTurn()
        val content = currentContent + deltaText
        turn.activeAssistantContent = content
        turn.activeAssistantReasoningContent = currentReasoning
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

    private fun queueMemorySummary(userMessage: String, provider: ProviderConfig, apiKey: String?, model: String, usageConversationId: String) {
        if (!appSettingsStore.state.value.memoryEnabled || !MemoryPolicy.shouldExtract(userMessage)) return
        invalidateMemoryUpdate()
        val epoch = memoryEpoch
        memoryUpdateJob = viewModelScope.launch {
            try {
                val snapshot = memoryRepository.snapshot()
                val drafts = memorySummaryGenerator.summarize(userMessage, snapshot.records, provider, apiKey, model, usageConversationId)
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
        val owner = selectedConversationId() ?: return
        pausedDeviceTasks.update { it - owner }
        turns[owner]?.let(::stopTurn)
    }

    private fun stopTurn(turn: ConversationTurn) {
        turn.contextRefreshJob?.cancel()
        turn.pendingContextRequest = null
        terminalManager?.cancelRun(turn.commandRunId)
        taskControl.finish(turn.activeDeviceTask)
        val assistantId = turn.activeAssistantMessageId
        val stoppedContent = turn.activeAssistantContent.ifBlank {
            if (turn.activeTaskInstruction.any { it in '\u3400'..'\u9fff' }) "已停止本轮处理。" else "This turn has stopped."
        }
        val stoppedReasoning = turn.activeAssistantReasoningContent
        val stoppedDuration = turn.activeAssistantReasoningDurationMillis
        val stoppingJob = turn.job
        stoppingJob?.cancel()
        turn.deviceActionConfirmation?.complete(false)
        turn.deviceActionConfirmation = null
        deviceActionConfirmations.update { it - turn.conversationId }
        if (turns[turn.conversationId] === turn) {
            turns.remove(turn.conversationId)
            runningConversations.update { it - turn.conversationId }
        }
        if (assistantId != null) {
            stoppedTurnPersistence[turn.conversationId] = viewModelScope.launch {
                stoppingJob?.join()
                chatRepository.updateMessage(assistantId, stoppedContent, MessageStatus.Stopped,
                    stoppedReasoning, reasoningDurationMillis = stoppedDuration.takeIf { it > 0L })
            }
        }
    }

    fun retryLastFailed() = resumeInterruptedTask()

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

private data class ConversationDraft(
    val input: String = "",
    val attachment: PendingAttachment? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val pendingClearedInputEcho: String? = null
)

private data class ContextWindowSnapshot(
    val conversationId: String,
    val providerId: String,
    val modelName: String,
    val usage: ContextWindowUsage,
    val runId: String? = null,
    val settings: ContextWindowSettings
)

private data class ContextWindowSettings(val baseUrl: String, val maxTokens: Int?, val thinking: Boolean, val effort: ThinkingEffort) {
    constructor(provider: ProviderConfig, request: ChatRequest) : this(provider.baseUrl, request.maxTokens, request.enableThinking, request.thinkingEffort)
}

/** Every suspended model/tool continuation carries its own execution state across UI selection changes. */
private class ConversationTurn(val conversationId: String, val activeTaskInstruction: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationTurn>
    val commandRunId = UUID.randomUUID().toString()
    var job: Job? = null
    var lastContextUpdateAt = 0L
    var contextRefreshJob: Job? = null
    var contextRevision = 0L
    var isContextCompacting = false
    var pendingContextRequest: ChatRequest? = null
    var activeDeviceTask: DeviceTaskControl.Task? = null
    var deviceLockHeld = false
    var systemCommandsApproved = false
    var systemCommandsDeclined = false
    var deviceActionConfirmation: CompletableDeferred<Boolean>? = null
    var activeTaskProvider: ProviderConfig? = null
    var activeWorkspace: WorkspaceLocation? = null
    val activeDeviceResults = mutableListOf<String>()
    var activeAssistantMessageId: String? = null
    var activeAssistantContent = ""
    var activeAssistantReasoningContent = ""
    var activeAssistantReasoningStartedAt: Long? = null
    var activeAssistantReasoningDurationMillis = 0L
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
    val toolStatus: MessageStatus? = null,
    val screenImage: com.flowchat.app.domain.device.DeviceScreenImage? = null
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
