package com.flowchat.app.device

import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.data.repository.RoomChatRepository
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskExecutionBudgetLimits
import com.flowchat.app.domain.chat.TaskGoalStatus
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.repository.*
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Three explicitly opted-in instrumentation processes with an external kill between seed
 * and verification. Only this GUID-named Room database, marker and synthetic gateway exist;
 * no production database, Hilt repository, real provider or Android device action is used.
 */
class TaskBudgetPersistenceDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val namespace: String get() {
        val value = requireNotNull(InstrumentationRegistry.getArguments().getString("r4BudgetNamespace"))
        check(value.startsWith(Prefix) && UUID.fromString(value.removePrefix(Prefix)).toString() == value.removePrefix(Prefix)) {
            "Budget persistence diagnostics require a fresh canonical GUID namespace"
        }
        return value
    }
    private val databaseName get() = "$namespace-persistence.db"
    private val marker get() = AtomicFile(context.getFileStreamPath("$namespace-persistence.json"))

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("runTaskBudgetPersistenceDiagnostics") == "true"
    )
    private fun report(label: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\nTASK_BUDGET_PERSISTENCE_R4 $label\n")
    })
    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!condition()) delay(25) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        report(label)
    }
    private fun readMarker(): JSONObject = marker.openRead().bufferedReader().use { JSONObject(it.readText()) }
    private fun persist(value: JSONObject) {
        val stream = marker.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); marker.finishWrite(stream) }
        catch (error: Throwable) { marker.failWrite(stream); throw error }
    }

    @Test fun aSeedCheckpointBeforeProcessDeath() = runBlocking<Unit> {
        enabled()
        check(!context.getDatabasePath(databaseName).exists() && !marker.baseFile.exists()) { "Budget persistence seed must be fresh" }
        persist(JSONObject().put("namespace", namespace).put("seedPid", Process.myPid()).put("brightnessCalls", 0))
        val client = ScriptedModel(listOf(
            response(ChatDelta.ToolCalls(listOf(ChatToolCall("seed-brightness", AgentToolDefinitions.SetBrightnessToolName,
                """{"percent":43}""")))),
            Response { awaitCancellation() }
        ))
        // Keep the runtime, collector and Room connection alive after this method returns.
        // The runner kills the process only after these durability assertions have passed.
        val fixture = Fixture(client, seedLimits())
        val owner = fixture.chats.createConversation(fixture.provider.id, Model).id
        fixture.open(owner)
        fixture.send("Synthetic persistence task: change fixture brightness once, then await the next model response.")
        await("seed-second-model-is-blocked-after-durable-stage-checkpoint") {
            val budget = TaskRunMetadata.decode(fixture.journal.latest(owner)?.metadataJson)?.budget
            client.requests.size == 2 && budget?.totalToolCalls == 1 && budget.totalModelCalls == 2 &&
                budget.stageNumber == 2 && budget.checkpointRefs.size == 1 &&
                fixture.runtime.activeConversations.value.contains(owner)
        }
        val run = requireNotNull(fixture.journal.latest(owner))
        val metadata = metadata(run.metadataJson)
        val budget = requireNotNull(metadata.budget)
        assertEquals("Running", run.state)
        assertEquals(1, readMarker().getInt("brightnessCalls"))
        assertEquals(1, fixture.gateway.brightnessCalls.get())
        assertTrue(metadata.pendingEffects.isEmpty())
        assertNull(metadata.stopReason)
        assertNull(budget.stoppedReason)
        assertEquals(seedLimits(), budget.limits)
        assertEquals(0, budget.stageToolCalls)
        assertEquals(1, budget.stageModelCalls)
        val originalMessage = fixture.chats.getMessages(owner).single { row -> steps(row).any {
            it.tool == AgentToolDefinitions.SetBrightnessToolName && it.outcome == "returned"
        } }
        val originalStep = steps(originalMessage).single()
        assertEquals(DeviceActionOutcome.TargetVerified.name, originalStep.effectState)
        assertEquals(run.id, originalStep.runId)
        assertEquals("${originalMessage.id}:${originalStep.stepId}", budget.checkpointRefs.single())
        persist(readMarker().put("owner", owner).put("runId", run.id).put("seedMetadata", metadata.encode())
            .put("originalMessageId", originalMessage.id).put("originalExecutionContext", originalMessage.executionContext)
            .put("seedReady", true))
        report("seed-ready-for-external-process-death pid=${Process.myPid()} run=${run.id}")
    }

    @Test fun bColdStartRetainsCheckpointAndOrdinaryUserContinuesTotals() = runBlocking<Unit> {
        enabled()
        val saved = readMarker()
        assertEquals(namespace, saved.getString("namespace"))
        assertTrue(saved.getBoolean("seedReady"))
        assertNotEquals("Cold verification requires an actual new instrumentation process", saved.getInt("seedPid"), Process.myPid())
        val owner = saved.getString("owner")
        val seedRunId = saved.getString("runId")
        val seedMetadata = metadata(saved.getString("seedMetadata"))
        val seedBudget = requireNotNull(seedMetadata.budget)
        val waitArguments = """{"include_image":false,"timeout_ms":500,"condition":{"package_name":"com.flowchat.synthetic.budget.persistence","element":{"resource_id":"budget:result","text":"Synthetic checkpoint continuation observed"},"state":"exists"}}"""
        val client = ScriptedModel(listOf(
            response(ChatDelta.ToolCalls(listOf(ChatToolCall("cold-read-only-wait", AgentToolDefinitions.WaitForScreenToolName, waitArguments)))),
            response(ChatDelta.Content("SYNTHETIC_PERSISTED_BUDGET_CONTINUATION_FINISHED"), ChatDelta.Done)
        ))
        // A larger current profile must not replace the task's smaller persisted limits.
        val fixture = Fixture(client, TaskExecutionBudgetLimits(
            stageToolCalls = 24, stageModelCalls = 25, stageActiveMillis = 180_000, stageEstimatedTokens = 20_000_000,
            totalToolCalls = 200, totalModelCalls = 220, totalActiveMillis = 900_000,
            totalEstimatedTokens = 80_000_000, maxStages = 20, maxNoProgress = 20))
        try {
            fixture.open(owner)
            await("cold-start-recovered-interrupted-run-without-dispatch") {
                fixture.journal.get(seedRunId)?.state == "Interrupted" &&
                    fixture.runtime.uiState.value.taskBudget?.totalModelCalls == 2
            }
            assertEquals(0, client.requests.size)
            assertEquals(0, fixture.gateway.brightnessCalls.get())
            assertEquals(0, fixture.gateway.waitCalls.get())
            assertEquals(1, readMarker().getInt("brightnessCalls"))
            assertNull("The process died during a model call, with no uncertain device effect", fixture.runtime.uiState.value.taskRecovery)
            val recovered = metadata(fixture.journal.get(seedRunId)?.metadataJson)
            val recoveredBudget = requireNotNull(recovered.budget)
            assertEquals(seedMetadata.logicalTaskId, recovered.logicalTaskId)
            assertEquals(seedBudget.totalToolCalls, recoveredBudget.totalToolCalls)
            assertEquals(seedBudget.totalModelCalls, recoveredBudget.totalModelCalls)
            assertEquals(seedBudget.totalEstimatedTokens, recoveredBudget.totalEstimatedTokens)
            assertEquals(seedBudget.stageNumber, recoveredBudget.stageNumber)
            assertEquals(seedBudget.stageModelCalls, recoveredBudget.stageModelCalls)
            assertEquals(seedBudget.stageToolCalls, recoveredBudget.stageToolCalls)
            assertEquals(seedBudget.checkpointRefs, recoveredBudget.checkpointRefs)
            assertEquals(seedBudget.limits, recoveredBudget.limits)
            // The seed watchdog may persist a later sample before the external kill.
            assertTrue(recoveredBudget.totalActiveMillis >= seedBudget.totalActiveMillis)
            assertTrue(recoveredBudget.stageActiveMillis >= seedBudget.stageActiveMillis)
            assertTrue(recovered.pendingEffects.isEmpty())
            assertNull(recoveredBudget.stoppedReason)
            assertEquals(saved.getString("originalExecutionContext"), fixture.chats.getMessages(owner)
                .single { it.id == saved.getString("originalMessageId") }.executionContext)

            fixture.send("Continue the same interrupted synthetic task using only a fresh exact screen observation.")
            await("ordinary-user-continues-persisted-checkpoint-and-cumulative-budget") {
                val latest = fixture.journal.latest(owner)
                latest != null && latest.id != seedRunId && latest.state == "Completed" &&
                    client.requests.size == 2 && !fixture.runtime.uiState.value.isStreaming &&
                    !fixture.runtime.activeConversations.value.contains(owner)
            }
            val continuedRun = requireNotNull(fixture.journal.latest(owner))
            val continued = metadata(continuedRun.metadataJson)
            val continuedBudget = requireNotNull(continued.budget)
            assertEquals(seedMetadata.logicalTaskId, continued.logicalTaskId)
            assertEquals(seedRunId, continued.continuedFromRunId)
            assertEquals(seedBudget.totalToolCalls + 1, continuedBudget.totalToolCalls)
            assertEquals(seedBudget.totalModelCalls + 2, continuedBudget.totalModelCalls)
            assertTrue(continuedBudget.totalEstimatedTokens > seedBudget.totalEstimatedTokens)
            assertEquals(seedBudget.limits, continuedBudget.limits)
            assertEquals("The persisted one-tool stage limit must still govern continuation", 3, continuedBudget.stageNumber)
            assertEquals(2, continuedBudget.checkpointRefs.size)
            assertEquals(seedBudget.checkpointRefs, continuedBudget.checkpointRefs.take(seedBudget.checkpointRefs.size))
            assertEquals(0, fixture.gateway.brightnessCalls.get())
            assertEquals(1, fixture.gateway.waitCalls.get())
            assertEquals("The previously completed effect must never be replayed", 1, readMarker().getInt("brightnessCalls"))
            assertTrue(continued.pendingEffects.isEmpty())
            assertNotEquals("A fresh exact screen condition does not certify the entire task", TaskGoalStatus.Verified, continued.goalStatus)
            assertEquals(1, continued.postconditionEvidence.size)
            val rows = fixture.chats.getMessages(owner)
            assertEquals(saved.getString("originalExecutionContext"), rows.single {
                it.id == saved.getString("originalMessageId")
            }.executionContext)
            val completedReferences = rows.flatMap { row -> steps(row).filter { it.outcome == "returned" }.map { "${row.id}:${it.stepId}" } }
            assertTrue("Every checkpoint must still reference a durable completed source step",
                completedReferences.containsAll(continuedBudget.checkpointRefs))
            assertEquals("Interrupted", fixture.journal.get(seedRunId)?.state)
            persist(readMarker().put("coldPid", Process.myPid()).put("coldVerified", true).put("continuedRunId", continuedRun.id))
            report("new-process-checkpoints-limits-totals-and-original-evidence-retained-without-replay")
        } finally { fixture.close() }
    }

    @Test fun zDeleteOnlyIsolatedFixtureDatabase() = runBlocking<Unit> {
        enabled()
        val name = databaseName // Validate the fixed prefix and canonical UUID before deletion.
        val saved = if (marker.baseFile.exists()) readMarker() else null
        check(saved == null || saved.getString("namespace") == namespace)
        context.deleteDatabase(name)
        marker.delete()
        assertFalse(context.getDatabasePath(name).exists())
        assertFalse(marker.baseFile.exists())
        report("isolated-persistence-guid-database-and-marker-deleted")
    }

    private fun metadata(json: String?): TaskRunMetadata = requireNotNull(TaskRunMetadata.decode(json))
    private fun steps(message: Message): List<ExecutionStep> = message.executionContext?.let {
        Json.decodeFromString<List<ExecutionStep>>(it)
    }.orEmpty()
    private fun response(vararg deltas: ChatDelta) = Response { deltas.forEach { emit(it) } }
    private data class Response(val emit: suspend kotlinx.coroutines.flow.FlowCollector<ChatDelta>.() -> Unit)

    private inner class Fixture(private val client: ScriptedModel, private val limits: TaskExecutionBudgetLimits) {
        val database = Room.databaseBuilder(context, FlowChatDatabase::class.java, databaseName).build()
        val chats = RoomChatRepository(database.conversationDao(), database.messageDao())
        val journal = TaskRunJournal(database.taskRunDao())
        val provider = ProviderConfig(id = namespace, displayName = "R4 persistence synthetic provider",
            baseUrl = SyntheticBaseUrl, defaultModel = Model)
        val gateway = SyntheticGateway()
        lateinit var runtime: ChatTaskRuntime
        private var collector: Job? = null
        suspend fun open(owner: String) {
            runtime = withContext(Dispatchers.Main) {
                ChatTaskRuntime(chats, SyntheticProviders(provider), client, SyntheticUsage, object : AppLauncher {
                    override suspend fun openApp(appName: String): String = error("Unexpected fixture app launching")
                }, gateway, SyntheticPrompts, SyntheticMemory,
                    appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false,
                        commandExecutionEnabled = false, installId = namespace)), taskJournal = journal,
                    budgetLimits = limits, budgetClockMillis = { android.os.SystemClock.elapsedRealtime() })
            }
            collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect { } }
            withContext(Dispatchers.Main) { runtime.selectConversation(owner) }
            await("persistence-synthetic-conversation-selected") { runtime.uiState.value.currentConversation?.id == owner }
        }
        suspend fun send(text: String) = withContext(Dispatchers.Main) { runtime.updateInput(text); runtime.send() }
        suspend fun close() = withContext(NonCancellable) {
            collector?.cancelAndJoin()
            if (::runtime.isInitialized) {
                withContext(Dispatchers.Main) { runtime.close() }
                withTimeout(10_000) { runtime.scope.coroutineContext[Job]?.join() }
            }
            withContext(Dispatchers.IO) { database.close() }
        }
    }

    private class ScriptedModel(private val responses: List<Response>) : ChatCompletionClient {
        val requests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> {
            check(provider.id.startsWith(Prefix) && provider.baseUrl == SyntheticBaseUrl && apiKey == null)
            val index = requests.size
            requests += request
            val response = responses.getOrNull(index) ?: error("Unexpected synthetic persistence model request #$index")
            return flow(response.emit)
        }
    }
    private inner class SyntheticGateway : DeviceAssistantGateway {
        override val safetyState = MutableStateFlow(DeviceSafetyState(screenLocked = false, screenInteractive = true))
        override val connectionState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedAdb,
            DeviceCapability.entries.toSet()))
        override val accessibilityState = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Connected))
        val brightnessCalls = AtomicInteger()
        val waitCalls = AtomicInteger()
        override fun refreshConnection() = Unit
        override fun requestPermission() = Unit
        override suspend fun getDeviceStatus() = DeviceToolResult(true, "synthetic status")
        override suspend fun getForegroundApp() = DeviceToolResult(true, "synthetic foreground")
        override suspend fun setScreenBrightness(percent: Int): DeviceToolResult {
            brightnessCalls.incrementAndGet()
            incrementPersistedBrightness()
            return DeviceToolResult(true, "synthetic brightness independently verified=$percent", before = "fixture brightness=42",
                after = "fixture brightness=$percent", actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        override suspend fun setMediaVolume(percent: Int): DeviceToolResult = error("Unexpected fixture volume operation")
        override suspend fun forceStopApp(appName: String): DeviceToolResult = error("Unexpected fixture force-stop")
        override suspend fun enablePowerMode() = DeviceToolResult(true, "synthetic power")
        override suspend fun waitForScreen(request: DeviceScreenWaitRequest): DeviceToolResult {
            waitCalls.incrementAndGet()
            val observed = DeviceScreenSnapshot("com.flowchat.synthetic.budget.persistence", "PersistenceFixtureScreen",
                listOf(DeviceUiElement(1, "android.widget.TextView", "Synthetic checkpoint continuation observed", null,
                    "budget:result", DeviceBounds(0, 0, 500, 100), clickable = false, editable = false,
                    scrollable = false, enabled = true)))
            check(request.condition?.matches(observed) == true) { "Unexpected synthetic persistence condition" }
            return DeviceToolResult(true, "Exact synthetic continuation condition matched", screen = observed,
                actionOutcome = DeviceActionOutcome.TargetVerified, conditionMatched = true)
        }
    }
    @Synchronized private fun incrementPersistedBrightness() {
        val saved = readMarker()
        check(saved.getString("namespace") == namespace)
        persist(saved.put("brightnessCalls", saved.getInt("brightnessCalls") + 1))
    }
    private class SyntheticProviders(private val provider: ProviderConfig) : ProviderRepository {
        override fun observeProviders() = flowOf(listOf(provider))
        override suspend fun getProvider(id: String) = provider.takeIf { it.id == id }
        override suspend fun getProvidersOnce() = listOf(provider)
        override suspend fun getApiKey(config: ProviderConfig): String? = null
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = error("Read-only synthetic provider")
        override suspend fun deleteProvider(id: String) = error("Read-only synthetic provider")
        override suspend fun ensureTemplates() = Unit
    }
    private object SyntheticUsage : AppUsageReader {
        override fun hasUsageAccess() = false
        override suspend fun getUsageSummary(range: String) = AppUsageSummary(range, 0, 0, 0, emptyList())
        override suspend fun getRecentActivity(hours: Int) = RecentAppActivity(hours, emptyList())
    }
    private object SyntheticPrompts : PromptProfileRepository {
        override suspend fun getActiveProfile() = PromptProfileConfig()
        override suspend fun getActiveThinkingFormat(): String? = null
    }
    private object SyntheticMemory : MemoryRepository {
        override suspend fun retrieve(userMessage: String, topN: Int) = emptyList<MemoryRecord>()
        override suspend fun snapshot() = MemorySnapshot(emptyList(), 0)
        override suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean) = false
        override suspend fun update(id: String, summary: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAll() = emptyList<MemoryRecord>()
        override suspend fun clear() = Unit
    }
    private fun seedLimits() = TaskExecutionBudgetLimits(stageToolCalls = 1, stageModelCalls = 3,
        stageActiveMillis = 180_000, stageEstimatedTokens = 10_000_000, totalToolCalls = 12,
        totalModelCalls = 20, totalActiveMillis = 600_000, totalEstimatedTokens = 40_000_000,
        maxStages = 4, maxNoProgress = 8)
    private companion object {
        const val Prefix = "r4-budget-"
        const val Model = "r4-budget-persistence-synthetic-model"
        const val SyntheticBaseUrl = "https://r4-budget.invalid/v1"
    }
}
