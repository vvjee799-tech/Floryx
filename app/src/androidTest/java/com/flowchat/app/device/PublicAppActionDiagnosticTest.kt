package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
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
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*

/** Actual registered editors, isolated Room/provider/model; never places calls or saves events. */
class PublicAppActionDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPUBLIC_APP_R5 $value\n") })
    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!condition()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
    }

    @Before fun prepare() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("runPublicAppActionDiagnostics") == "true") { "Explicit phone diagnostics opt-in required" }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (FlowChatAccessibilityService.current() == null) {
            shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
            if (withTimeoutOrNull(1500) { while (FlowChatAccessibilityService.current() == null) delay(100); true } != true) {
                val original = shell("settings --user 0 get secure enabled_accessibility_services")
                val component = ComponentName(context, FlowChatAccessibilityService::class.java)
                check(original.split(':').any { ComponentName.unflattenFromString(it) == component }) { "Service must already be authorized" }
                val remaining = original.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
                automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
                try {
                    check(shell("settings --user 0 get secure enabled_accessibility_services") == original)
                    try { check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, remaining)); delay(250) }
                    finally {
                        check(shell("settings --user 0 get secure enabled_accessibility_services").let { if (it == "null") "" else it } == remaining)
                        check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original))
                    }
                } finally { automation.dropShellPermissionIdentity() }
            }
        }
        await("accessibility bound") { FlowChatAccessibilityService.current() != null }
    }

    @Test fun dialerRuntimeVerifiesNumberStopsBatchAndReopensWithoutAction() = runBlocking<Unit> {
        val name = "r5-public-${UUID.randomUUID()}"
        var fixture = Fixture(name)
        var passed = false
        try {
            fixture.connect()
            val owner = fixture.create(PublicAppActions.Dialer, DialArgs, maliciousFollowup = true)
            fixture.sendAndAwait(owner)
            assertEquals(1, fixture.openCalls.get())
            assertEquals(0, fixture.forbiddenCalls.get())
            assertEquals(1, fixture.modelCalls.get())
            assertTrue(fixture.chats.getMessages(owner).last().content.contains("未执行拨号"))
            val saved = requireNotNull(fixture.journal.latest(owner))
            val metadata = requireNotNull(TaskRunMetadata.decode(saved.metadataJson))
            assertEquals("Paused", saved.state)
            assertTrue(metadata.pendingEffects.isEmpty())
            assertNotEquals(TaskGoalStatus.Verified, metadata.goalStatus)
            assertEquals(0, metadata.verifiedStepCount)
            val before = fixture.chats.getMessages(owner).map { it.executionContext }
            fixture.close()
            fixture = Fixture(name)
            fixture.open(owner, PublicAppActions.Dialer, DialArgs)
            delay(500)
            assertEquals(0, fixture.openCalls.get())
            assertEquals(0, fixture.modelCalls.get())
            assertEquals(metadata, TaskRunMetadata.decode(fixture.journal.get(saved.id)?.metadataJson))
            assertEquals(before, fixture.chats.getMessages(owner).map { it.executionContext })
            report("dialer exact number; batch tap blocked; one local model; paused Room reread; no automatic reopen")
            passed = true
        } finally { fixture.close(); if (passed) context.deleteDatabase("$name.db") else report("retained_failure_database=$name.db") }
    }

    @Test fun calendarFreshEditorVerifiesUnicodeTitleAndBothTimesThenHandsOff() = runBlocking<Unit> {
        val fixture = Fixture("r5-public-${UUID.randomUUID()}")
        var passed = false
        try {
            fixture.connect()
            val old = fixture.real.openPublicAppAction(PublicAppActions.Calendar, calendarArgs("Floryx R5 old draft"))
            assertEquals(old.asToolContent(), DeviceActionOutcome.ProgressObserved, old.actionOutcome)
            val args = calendarArgs("Floryx R5 '日程' 😀")
            val owner = fixture.create(PublicAppActions.Calendar, args, maliciousFollowup = true)
            fixture.sendAndAwait(owner)
            assertEquals(1, fixture.openCalls.get())
            assertEquals(0, fixture.forbiddenCalls.get())
            assertEquals(1, fixture.modelCalls.get())
            val step = fixture.chats.getMessages(owner).mapNotNull { it.executionContext }.flatMap { Json.decodeFromString<List<ExecutionStep>>(it) }
                .single { it.tool == PublicAppActions.Calendar }
            assertTrue(step.result.contains("prefill_verified_waiting_for_user"))
            val screen = requireNotNull(fixture.real.observeScreen().screen)
            assertTrue(PublicAppActions.prefillMatches(PublicAppActions.parse(PublicAppActions.Calendar, args), screen, 0, ZoneId.systemDefault()))
            assertTrue(fixture.chats.getMessages(owner).last().content.contains("未执行拨号或保存日程"))
            assertTrue(requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(owner)?.metadataJson)).pendingEffects.isEmpty())
            report("calendar fresh document replaces no old draft; UTF-8 title and begin/end read from actual controls; no save; local handoff")
            passed = true
        } finally { fixture.close(); if (passed) context.deleteDatabase("${fixture.name}.db") else report("failure_database=${fixture.name}.db exists=${context.getDatabasePath("${fixture.name}.db").exists()}") }
    }

    @Test fun invalidNumberAndNonPrimaryUserAreRejectedBeforeDispatch() = runBlocking<Unit> {
        val fixture = Fixture("r5-public-${UUID.randomUUID()}")
        try {
            fixture.connect()
            val invalid = fixture.real.openPublicAppAction(PublicAppActions.Dialer, """{"phone_number":"*#06#"}""")
            assertEquals(DeviceActionOutcome.NotDispatched, invalid.actionOutcome)
            val wrongUser = fixture.root.withService { it.openPublicAppAction(PublicAppActions.Dialer, DialArgs, 10) }
            assertTrue(wrongUser.contains("public_app_user_unsupported"))
            assertTrue(wrongUser.contains("NotDispatched"))
            val unknown = fixture.root.withService { it.openPublicAppAction("arbitrary_intent", DialArgs, 0) }
            assertTrue(unknown.contains("invalid_public_app_action"))
            assertEquals(0, fixture.root.serviceInfo()?.activeRequests)
            assertEquals(0, fixture.root.serviceInfo()?.activeDisplays)
            report("invalid USSD; arbitrary tool; non-primary user refused; Root requests/displays zero")
        } finally { fixture.close() }
    }

    @Test fun backgroundRequestCannotOpenOnPhysicalScreen() = runBlocking<Unit> {
        val fixture = Fixture("r5-public-${UUID.randomUUID()}", background = true)
        try {
            fixture.connect()
            val result = fixture.real.openPublicAppAction(PublicAppActions.Dialer, DialArgs)
            assertEquals(result.asToolContent(), DeviceActionOutcome.NotDispatched, result.actionOutcome)
            assertEquals("public_app_requires_foreground_root", result.errorCode)
            assertEquals(0, fixture.root.serviceInfo()?.activeDisplays)
            report("background explicit rejection; no physical-screen fallback or execution display")
        } finally { fixture.close() }
    }

    private inner class Fixture(val name: String, background: Boolean = false) {
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false,
            powerConnectionMode = PowerConnectionMode.Root, backgroundExecutionEnabled = background, installId = name))
        val tasks = DeviceTaskControl()
        val bridge = FloatingAssistantBridge(tasks)
        val root = RootDeviceConnection(context)
        val real = ShizukuDeviceAssistantGateway(context, settings, root, tasks, bridge)
        val openCalls = AtomicInteger()
        val forbiddenCalls = AtomicInteger()
        val modelCalls = AtomicInteger()
        val database by lazy { Room.databaseBuilder(context, FlowChatDatabase::class.java, "$name.db").build() }
        val chats by lazy { RoomChatRepository(database.conversationDao(), database.messageDao()) }
        val journal by lazy { TaskRunJournal(database.taskRunDao()) }
        private var databaseUsed = false
        private val provider = ProviderConfig(id = name, displayName = "R5 isolated local model", baseUrl = "https://r5-public.invalid/v1", defaultModel = "r5-local")
        private var runtime: ChatTaskRuntime? = null
        private var collector: Job? = null
        private var closed = false
        suspend fun connect() {
            root.refresh(requestPermission = true)
            real.refreshConnection(); real.refreshAccessibilityConnection()
            await("Root ready") { root.state.value.isConnected && real.accessibilityState.value.isConnected }
        }
        suspend fun create(tool: String, arguments: String, maliciousFollowup: Boolean): String {
            databaseUsed = true
            check(!context.getDatabasePath("$name.db").exists())
            val owner = chats.createConversation(provider.id, provider.defaultModel).id
            open(owner, tool, arguments, maliciousFollowup)
            return owner
        }
        suspend fun open(owner: String, tool: String, arguments: String, maliciousFollowup: Boolean = false) {
            databaseUsed = true
            val gateway = object : DeviceAssistantGateway by real {
                override suspend fun openPublicAppAction(tool: String, arguments: String): DeviceToolResult {
                    openCalls.incrementAndGet()
                    return real.openPublicAppAction(tool, arguments)
                }
                override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult {
                    forbiddenCalls.incrementAndGet(); error("No button tap is authorized by this diagnostic")
                }
            }
            val client = object : ChatCompletionClient {
                override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
                    check(provider.id == name && provider.baseUrl == "https://r5-public.invalid/v1" && apiKey == null)
                    check(request.model == "r5-local" && request.messages.any { it.content.orEmpty().contains("R5_SYNTHETIC_PREFILL_ONLY") })
                    check(modelCalls.incrementAndGet() == 1) { "Handoff must not call a model to finalize" }
                    check(request.tools.any { it.name == tool })
                    emit(ChatDelta.ToolCalls(listOf(ChatToolCall("r5-prefill", tool, arguments)) +
                        if (maliciousFollowup) listOf(ChatToolCall("forbidden-after-handoff", AgentToolDefinitions.TapUiElementToolName,
                            """{"index":1,"screen_id":"stale-r5"}""")) else emptyList()))
                    emit(ChatDelta.Done)
                }
            }
            val value = withContext(Dispatchers.Main) { ChatTaskRuntime(chats, Providers(provider), client, Usage,
                object : AppLauncher { override suspend fun openApp(appName: String): String = error("Only registered editors are allowed") },
                gateway, Prompts, Memory, settings, bridge, taskJournal = journal) }
            runtime = value
            collector = CoroutineScope(Dispatchers.Main).launch { value.uiState.collect {} }
            withContext(Dispatchers.Main) { value.selectConversation(owner) }
            await("isolated conversation") { value.uiState.value.currentConversation?.id == owner }
        }
        suspend fun sendAndAwait(owner: String) {
            val value = requireNotNull(runtime)
            withContext(Dispatchers.Main) { value.updateInput("R5_SYNTHETIC_PREFILL_ONLY 仅预填，不拨号、不保存，随后交给我操作。"); value.send() }
            await("paused handoff") { journal.latest(owner)?.state == "Paused" && !value.uiState.value.isStreaming && owner !in value.activeConversations.value }
        }
        suspend fun close() = withContext(NonCancellable) {
            if (!closed) {
                closed = true
                collector?.cancelAndJoin()
                runtime?.let { value -> withContext(Dispatchers.Main) { value.close() }; withTimeout(10_000) { value.scope.coroutineContext[Job]?.join() } }
                root.release()
                if (databaseUsed) withContext(Dispatchers.IO) { database.close() }
            }
        }
    }
    private class Providers(private val provider: ProviderConfig) : ProviderRepository {
        override fun observeProviders() = flowOf(listOf(provider))
        override suspend fun getProvider(id: String) = provider.takeIf { it.id == id }
        override suspend fun getProvidersOnce() = listOf(provider)
        override suspend fun getApiKey(config: ProviderConfig): String? = null
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = error("Read-only synthetic provider")
        override suspend fun deleteProvider(id: String) = error("Read-only synthetic provider")
        override suspend fun ensureTemplates() = Unit
    }
    private object Usage : AppUsageReader {
        override fun hasUsageAccess() = false
        override suspend fun getUsageSummary(range: String) = AppUsageSummary(range, 0, 0, 0, emptyList())
        override suspend fun getRecentActivity(hours: Int) = RecentAppActivity(hours, emptyList())
    }
    private object Prompts : PromptProfileRepository {
        override suspend fun getActiveProfile() = PromptProfileConfig()
        override suspend fun getActiveThinkingFormat(): String? = null
    }
    private object Memory : MemoryRepository {
        override suspend fun retrieve(userMessage: String, topN: Int) = emptyList<MemoryRecord>()
        override suspend fun snapshot() = MemorySnapshot(emptyList(), 0)
        override suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean) = false
        override suspend fun update(id: String, summary: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAll() = emptyList<MemoryRecord>()
        override suspend fun clear() = Unit
    }
    private fun calendarArgs(title: String) = buildJsonObject {
        put("title", title); put("begin_time", "2026-11-09T10:00:00+08:00"); put("end_time", "2026-11-09T10:10:00+08:00")
    }.toString()
    private companion object { const val DialArgs = """{"phone_number":"01000000000"}""" }
}
