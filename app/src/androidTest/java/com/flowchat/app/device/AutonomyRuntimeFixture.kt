package com.flowchat.app.device

import android.content.Context
import androidx.room.Room
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.data.chat.ContextLimits
import com.flowchat.app.data.chat.TaskContextManager
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.device.*
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.preferences.*
import com.flowchat.app.data.repository.RoomChatRepository
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.repository.*
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Isolated Room/runtime/device tools. Network access requires an explicitly supplied client/config. */
internal class AutonomyRuntimeFixture(val context: Context, providerOverride: ProviderConfig? = null,
    private val apiKeyOverride: String? = null,
    private val terminalOverride: com.flowchat.app.data.terminal.TerminalManager? = null,
    private val workspaceOverride: com.flowchat.app.data.workspace.WorkspaceManager? = null,
    settingsOverride: AppSettings? = null,
    private val allowEmptyUsageQueries: Boolean = false,
    private val contextLimits: ContextLimits? = null,
    gatewayOverride: DeviceAssistantGateway? = null,
    val name: String = "autonomyqa-${UUID.randomUUID()}",
    private val wrapCalendarEdit: ((TaskEffectAdapter) -> TaskEffectAdapter)? = null) {
    val settings = AppSettingsStore(settingsOverride?.copy(installId = name) ?: AppSettings(powerModeEnabled = true, memoryEnabled = false,
        powerConnectionMode = PowerConnectionMode.Root, installId = name))
    val tasks = DeviceTaskControl()
    val bridge = FloatingAssistantBridge(tasks)
    val root = RootDeviceConnection(context)
    val real = ShizukuDeviceAssistantGateway(context, settings, root, tasks, bridge)
    val database = Room.databaseBuilder(context, FlowChatDatabase::class.java, "$name.db").build()
    val chats = RoomChatRepository(database.conversationDao(), database.messageDao())
    val journal = TaskRunJournal(database.taskRunDao())
    val provider = providerOverride ?: ProviderConfig(id = name, displayName = "Autonomy local QA", baseUrl = "https://autonomy-local.invalid/v1", defaultModel = "autonomy-local")
    var externalClient: ChatCompletionClient? = null
    val modelCalls = AtomicInteger()
    val summaryCalls = AtomicInteger()
    val observationCalls = AtomicInteger()
    val waitCalls = AtomicInteger()
    val modelRequestTokens = mutableListOf<Int>()
    val modelRequestChars = mutableListOf<Int>()
    var modelMillis = 0L
    var summaryMillis = 0L
    val confirmations = mutableSetOf<String>()
    var forceStopMock: (() -> DeviceToolResult)? = null
    var forceStopCalls = 0
    var calendarTapTransform: ((DeviceToolResult) -> DeviceToolResult)? = null
    var calendarSourceTransform: ((CalendarEventReceipt?) -> CalendarEventReceipt?)? = null
    var persistenceObserver = TaskPersistenceObserver()
    var beforeUiTap: (suspend () -> Unit)? = null
    val uiTapOutcomes = mutableListOf<DeviceActionOutcome?>()
    val uiTapErrorCodes = mutableListOf<String?>()
    val targetWaitResults = mutableListOf<DeviceToolResult>()
    @Volatile var lastResult: DeviceToolResult? = null
    var responder: suspend (ChatRequest, Int) -> List<ChatDelta> = { _, _ -> error("Configure the local QA model first") }
    var projectUiResult: (DeviceToolResult) -> DeviceToolResult = { it }
    private fun record(result: DeviceToolResult) = projectUiResult(result).also { lastResult = it }
    val gateway: DeviceAssistantGateway = gatewayOverride ?: object : DeviceAssistantGateway by real {
        override suspend fun scrollContainer(request: DeviceContainerScrollRequest) = record(real.scrollContainer(request))
        override suspend fun openPublicAppAction(tool: String, arguments: String) = record(real.openPublicAppAction(tool, arguments))
        override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult {
            beforeUiTap?.invoke()
            val result = real.tapUiElement(index, longPress, screenId)
            uiTapOutcomes += result.actionOutcome
            uiTapErrorCodes += result.errorCode
            return record(calendarTapTransform?.invoke(result) ?: result)
        }
        override suspend fun tapLocatedUiTarget(index: Int, target: DeviceUiSelector, screenId: String): DeviceToolResult {
            beforeUiTap?.invoke()
            val result = real.tapLocatedUiTarget(index, target, screenId)
            uiTapOutcomes += result.actionOutcome
            uiTapErrorCodes += result.errorCode
            return record(calendarTapTransform?.invoke(result) ?: result)
        }
        override suspend fun inspectCalendarEvents(arguments: String): CalendarEventReceipt? {
            val result = real.inspectCalendarEvents(arguments)
            return if (calendarSourceTransform != null) calendarSourceTransform!!.invoke(result) else result
        }
        override suspend fun inputText(index: Int, text: String, clearExisting: Boolean, screenId: String?) = record(real.inputText(index, text, clearExisting, screenId))
        override suspend fun editText(request: DeviceTextEditRequest, screenId: String) = record(real.editText(request, screenId))
        override suspend fun performEditorAction(index: Int, expectedLabel: String, screenId: String) =
            record(real.performEditorAction(index, expectedLabel, screenId))
        override suspend fun pressBack() = record(real.pressBack())
        override suspend fun observeScreen(): DeviceToolResult {
            observationCalls.incrementAndGet(); return record(real.observeScreen())
        }
        override suspend fun observeDocument(): DeviceToolResult {
            observationCalls.incrementAndGet(); return record(real.observeDocument())
        }
        override suspend fun observeScreen(scope: DeviceObservationScope): DeviceToolResult {
            observationCalls.incrementAndGet(); return record(real.observeScreen(scope))
        }
        override suspend fun captureScreen(region: ScreenImageRegion?) = projectUiResult(real.captureScreen(region))
        override suspend fun captureScreen(region: ScreenImageRegion?, scope: DeviceObservationScope?) =
            projectUiResult(real.captureScreen(region, scope))
        override suspend fun waitForScreen(): DeviceToolResult {
            waitCalls.incrementAndGet(); return record(real.waitForScreen())
        }
        override suspend fun waitForScreen(request: DeviceScreenWaitRequest): DeviceToolResult {
            waitCalls.incrementAndGet()
            val result = real.waitForScreen(request)
            targetWaitResults += result
            return record(result)
        }
        override suspend fun forceStopApp(appName: String): DeviceToolResult {
            forceStopCalls++
            return record(forceStopMock?.invoke() ?: real.forceStopApp(appName))
        }
    }
    lateinit var runtime: ChatTaskRuntime
    lateinit var owner: String
    private var collector: Job? = null
    suspend fun connect() {
        root.refresh(requestPermission = true)
        real.refreshConnection(); real.refreshAccessibilityConnection()
        await { root.state.value.isConnected && real.connectionState.value.isConnected && real.accessibilityState.value.isConnected }
    }
    suspend fun create() {
        owner = chats.createConversation(provider.id, provider.defaultModel).id
        attach()
    }
    suspend fun reopen(conversationId: String) {
        check(chats.getConversation(conversationId)?.providerId == provider.id)
        owner = conversationId
        attach()
    }
    private suspend fun attach() {
        val local = object : ChatCompletionClient {
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
                check(provider.id == name && apiKey == null && provider.baseUrl == "https://autonomy-local.invalid/v1")
                if (request.messages.firstOrNull()?.content?.startsWith("# Floryx context checkpoint writer") == true) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    // Checkpoint writing is a separate model request, not an agent action.
                    // Do not advance the task driver's state while the real runtime summarizes history.
                    summaryCalls.incrementAndGet()
                    val input = Json.parseToJsonElement(requireNotNull(request.messages.last().content)).jsonObject
                    val records = input.getValue("new_records").jsonArray.map { it.jsonObject }
                    val source = records.firstOrNull { it["role"]?.jsonPrimitive?.content == "User" } ?: records.first()
                    val previous = input["previous_checkpoint"]?.jsonObject?.get("summary")?.jsonObject?.get("objective")
                    val objective = if (source["role"]?.jsonPrimitive?.content == "User" || previous == null)
                        ContextNote(source["content"]?.jsonPrimitive?.content.orEmpty().take(500), listOf(source.getValue("id").jsonPrimitive.content))
                    else Json.decodeFromJsonElement<ContextNote>(previous)
                    // Keep navigation evidence when the local QA writer replaces old native tool pairs.
                    // These notes come only from actual recorded results, with exact message sources.
                    val previousCompleted = input["previous_checkpoint"]?.jsonObject?.get("summary")?.jsonObject?.get("completed")
                        ?.let { Json.decodeFromJsonElement<List<ContextNote>>(it) }.orEmpty()
                    val navigation = records.mapNotNull { record ->
                        val evidence = record["tool_evidence"]?.jsonPrimitive?.content.orEmpty()
                        val digest = Regex("to_page_sha256=([0-9a-f]{64})").find(evidence)?.groupValues?.get(1)
                        if (digest == null || !evidence.contains("navigation_only=true; submitted_effect_verified=false")) null
                        else ContextNote("Navigation progress: to_page_sha256=$digest; submitted_effect_verified=false",
                            listOf(record.getValue("id").jsonPrimitive.content))
                    }
                    emit(ChatDelta.Content(Json.encodeToString(TaskSummary(objective,
                        completed = (previousCompleted + navigation).distinctBy { it.sources }.takeLast(16)))))
                    emit(ChatDelta.Done)
                    summaryMillis += android.os.SystemClock.elapsedRealtime() - started
                    return@flow
                }
                modelRequestTokens += com.flowchat.app.data.chat.ContextBudget.request(request, provider)
                modelRequestChars += request.messages.sumOf { it.content.orEmpty().length }
                val started = android.os.SystemClock.elapsedRealtime()
                for (event in responder(request, modelCalls.incrementAndGet())) emit(event)
                modelMillis += android.os.SystemClock.elapsedRealtime() - started
            }
        }
        val data = Data(provider, apiKeyOverride, allowEmptyUsageQueries)
        val client = externalClient ?: local
        runtime = withContext(Dispatchers.Main) { ChatTaskRuntime(chats, data, client, data,
            AndroidAppLauncher(context, gateway, tasks), gateway, data, data, settings, bridge,
            terminalManager = terminalOverride, taskContextManager = TaskContextManager(client, chats, contextLimits),
            workspaceManager = workspaceOverride, taskJournal = journal,
            effectRegistry = TaskEffectRegistry(listOf(CalendarSaveEffectAdapter(gateway),
                CalendarTimeEditAdapter(gateway).let { wrapCalendarEdit?.invoke(it) ?: it })),
            persistenceObserver = persistenceObserver) }
        collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect { state ->
            state.pendingDeviceActionConfirmation?.requestId?.let { confirmations += it }
        } }
        withContext(Dispatchers.Main) { runtime.selectConversation(owner) }
        await { runtime.uiState.value.currentConversation?.id == owner }
    }
    suspend fun send(instruction: String, state: String = "Completed") {
        withContext(Dispatchers.Main) { runtime.updateInput(instruction); runtime.send() }
        await {
            val saved = journal.latest(owner)
            if (state == "Completed" && saved?.state in setOf("Failed", "Stopped", "Paused") && owner !in runtime.activeConversations.value)
                throw AssertionError("QA task stopped as ${saved?.state}: " + chats.getMessages(owner).lastOrNull()?.content.orEmpty().take(1600))
            saved?.state == state && owner !in runtime.activeConversations.value
        }
    }
    suspend fun rebuildRuntime() {
        stopRuntime(); attach()
    }
    suspend fun await(condition: suspend () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(50) }
    private suspend fun stopRuntime() {
        collector?.cancelAndJoin(); collector = null
        if (::runtime.isInitialized) {
            withContext(Dispatchers.Main) { runtime.close() }
            withTimeout(10_000) { runtime.scope.coroutineContext[Job]?.join() }
        }
    }
    suspend fun close(passed: Boolean) = withContext(NonCancellable) {
        stopRuntime(); root.release(); withContext(Dispatchers.IO) { database.close() }
        if (passed) deleteIsolatedDatabase(context, name)
    }

    companion object {
        fun deleteIsolatedDatabase(context: Context, name: String) {
            require(name.startsWith("autonomyqa-") && name.all { it.isLetterOrDigit() || it == '-' })
            val databaseName = "$name.db"
            if (context.getDatabasePath(databaseName).exists()) check(context.deleteDatabase(databaseName))
            // Room keeps this per-database lock file in the cache after deleting the database.
            val lock = File(context.cacheDir, "$databaseName.lck")
            if (lock.exists()) check(lock.delete())
        }
    }

    private class Data(private val provider: ProviderConfig, private val apiKey: String?, private val allowEmptyUsageQueries: Boolean) :
        ProviderRepository, AppUsageReader, PromptProfileRepository, MemoryRepository {
        override fun observeProviders() = flowOf(listOf(provider))
        override suspend fun getProvider(id: String) = provider.takeIf { it.id == id }
        override suspend fun getProvidersOnce() = listOf(provider)
        override suspend fun getApiKey(config: ProviderConfig): String? = apiKey.takeIf { config == provider }
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = error("Read-only QA provider")
        override suspend fun deleteProvider(id: String) = error("Read-only QA provider")
        override suspend fun ensureTemplates() = Unit
        override fun hasUsageAccess() = allowEmptyUsageQueries
        override suspend fun getUsageSummary(range: String) = AppUsageSummary(range, 0, 0, 0, emptyList())
        override suspend fun getRecentActivity(hours: Int) = RecentAppActivity(hours, emptyList())
        override suspend fun getActiveProfile() = PromptProfileConfig()
        override suspend fun getActiveThinkingFormat(): String? = null
        override suspend fun retrieve(userMessage: String, topN: Int) = emptyList<MemoryRecord>()
        override suspend fun snapshot() = MemorySnapshot(emptyList(), 0)
        override suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean) = false
        override suspend fun update(id: String, summary: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAll() = emptyList<MemoryRecord>()
        override suspend fun clear() = Unit
    }
}
