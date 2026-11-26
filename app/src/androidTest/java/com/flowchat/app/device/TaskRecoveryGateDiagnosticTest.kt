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
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.chat.TaskGoalStatus
import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.repository.*
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
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
 * Three separate instrumentation processes, driven by test-phone-integration-r4.ps1.
 * Only a GUID-named Room database and synthetic counters are used. No Hilt entry point,
 * real provider, credential, phone action, personal conversation or production journal is used.
 */
class TaskRecoveryGateDiagnosticTest {
    private val evidenceJson = Json { ignoreUnknownKeys = true }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val namespace: String get() {
        val value = requireNotNull(InstrumentationRegistry.getArguments().getString("r4RecoveryNamespace"))
        check(value.startsWith(Prefix) && UUID.fromString(value.removePrefix(Prefix)).toString() == value.removePrefix(Prefix)) {
            "R4 requires its runner's fresh GUID namespace"
        }
        return value
    }
    private val marker get() = AtomicFile(context.getFileStreamPath("$namespace.json"))
    private val databaseName get() = "$namespace.db"

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("runTaskRecoveryGateDiagnostics") == "true"
    )
    private fun report(label: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\nTASK_RECOVERY_R4 $label\n")
    })
    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!condition()) delay(30) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        report(label)
    }
    // AtomicFile guarantees a complete commit, not concurrent reader/writer exclusion.
    // Use the same reentrant monitor as increment(), including polling reads.
    @Synchronized private fun readMarker(): JSONObject = marker.openRead().bufferedReader().use { JSONObject(it.readText()) }
    @Synchronized private fun persist(value: JSONObject) {
        val stream = marker.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); marker.finishWrite(stream) }
        catch (error: Throwable) { marker.failWrite(stream); throw error }
    }

    @Test fun aPersistInFlightActionBeforeProcessDeath() = runBlocking<Unit> {
        enabled()
        check(!context.getDatabasePath(databaseName).exists() && !marker.baseFile.exists()) { "R4 seed must be fresh" }
        persist(JSONObject().put("namespace", namespace).put("seedPid", Process.myPid())
            .put("brightnessCalls", 0).put("volumeCalls", 0).put("backCalls", 0).put("openCalls", 0))
        val client = ScriptedModel(listOf(
            response(tool("initial-observe", AgentToolDefinitions.ObserveScreenToolName, """{"include_image":false}""")),
            response(tool("completed-brightness", AgentToolDefinitions.SetBrightnessToolName, """{"percent":42}""")),
            response(tool("inflight-volume", AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":41}"""))
        ))
        // Intentionally retain the live runtime/database. The runner kills this process only
        // after the test has confirmed that dispatch intent and an in-flight effect are durable.
        val fixture = Fixture(client, suspendVolume = true)
        val owner = fixture.chats.createConversation(fixture.provider.id, Model).id
        persist(readMarker().put("owner", owner))
        fixture.open(owner)
        withContext(Dispatchers.Main) {
            fixture.runtime.updateInput("Synthetic R4 task: adjust brightness once, adjust volume once, then go back once.")
            fixture.runtime.send()
        }
        await("actual-fake-dispatch-is-in-flight") { readMarker().getInt("volumeCalls") == 1 }
        val run = requireNotNull(fixture.journal.latest(owner))
        val rows = fixture.chats.getMessages(owner)
        val steps = evidence(rows)
        assertEquals("Running", run.state)
        assertTrue(fixture.runtime.activeConversations.value.contains(owner))
        assertTrue(steps.any { it.tool == AgentToolDefinitions.SetBrightnessToolName && it.outcome == "returned" })
        assertTrue(steps.any { it.tool == AgentToolDefinitions.SetMediaVolumeToolName && it.outcome == "unconfirmed" })
        assertEquals(1, readMarker().getInt("brightnessCalls"))
        persist(readMarker().put("runId", run.id).put("seedReady", true))
        report("seed-ready-for-external-process-death pid=${Process.myPid()} run=${run.id}")
    }

    @Test fun bColdStartRetainsGateAndRequiresCurrentReview() = runBlocking<Unit> {
        enabled()
        val saved = readMarker()
        assertTrue(saved.getBoolean("seedReady"))
        assertNotEquals("The verification must run in a new process", saved.getInt("seedPid"), Process.myPid())
        val owner = saved.getString("owner")
        val seedRunId = saved.getString("runId")
        val observeReplyGate = CompletableDeferred<Unit>()
        val client = ScriptedModel(listOf(
            // An ordinary new User message must inherit the old gate, even if the model lies.
            response(ChatDelta.ToolCalls(listOf(
                ChatToolCall("bypass-volume", AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":41}"""),
                ChatToolCall("bypass-open", AgentToolDefinitions.OpenAppToolName, """{"app_name":"R4 synthetic app"}"""),
                ChatToolCall("bypass-command", "run_command", """{"command":"synthetic-only","environment":"linux"}"""),
                ChatToolCall("bypass-workspace", "workspace_write", """{"path":"r4.txt","content":"synthetic"}"""),
                ChatToolCall("bypass-unknown", "r4_unknown_mutation", "{}")
            ))),
            response(ChatDelta.Content("All prior changes are verified and the whole task completed."), ChatDelta.Done),
            // A recovery observation is allowed, but an in-flight review decision is rejected.
            response(tool("recovery-observe", AgentToolDefinitions.ObserveScreenToolName, """{"include_image":false}""")),
            Response(listOf(ChatDelta.Content("Observed the current synthetic state; user review is required."), ChatDelta.Done), observeReplyGate),
            // Review permits remaining work, but cannot authorize replaying an uncertain effect.
            response(ChatDelta.ToolCalls(listOf(
                ChatToolCall("reviewed-volume-replay", AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":41}"""),
                ChatToolCall("remaining-back", AgentToolDefinitions.PressBackToolName, "{}")
            ))),
            response(ChatDelta.Content("REMAINING_DONE"), ChatDelta.Done),
            // A new ordinary User still belongs to the reviewed task until an explicit end.
            response(tool("new-user-volume-replay", AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":41}""")),
            response(ChatDelta.Content("NEW_USER_REPLAY_BLOCKED"), ChatDelta.Done),
            // After that task is explicitly ended, a new task may request the same operation.
            response(tool("new-task-volume", AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":41}""")),
            response(ChatDelta.Content("NEW_TASK_DONE"), ChatDelta.Done)
        ))
        var fixture = Fixture(client)
        try {
            fixture.open(owner)
            await("cold-start-interrupted-and-gated") {
                fixture.journal.get(seedRunId)?.state == "Interrupted" && fixture.runtime.uiState.value.taskRecovery != null &&
                    fixture.chats.getMessages(owner).any { it.executionContext.orEmpty().contains("interrupted_unconfirmed") }
            }
            val recovered = requireNotNull(fixture.runtime.uiState.value.taskRecovery)
            assertEquals(seedRunId, recovered.runId)
            assertEquals(1, recovered.pendingCount)
            assertFalse(recovered.canAcknowledge)
            val seedRows = fixture.chats.getMessages(owner)
            val originalEvidence = seedRows.filter { it.executionContext != null }.associate { it.id to it.executionContext }
            val seedSteps = evidence(seedRows)
            assertTrue(seedSteps.any { it.tool == AgentToolDefinitions.SetMediaVolumeToolName && it.outcome == "interrupted_unconfirmed" })
            assertTrue(seedSteps.any { it.tool == AgentToolDefinitions.SetBrightnessToolName && it.outcome == "returned" })
            assertEquals(0, client.requests.size)
            withContext(Dispatchers.Main) { fixture.runtime.acknowledgeTaskRecovery(seedRunId) }
            await("premature-review-keeps-gate") { fixture.runtime.uiState.value.taskRecovery?.runId == seedRunId }
            assertEquals(0, client.requests.size)

            fixture.send("Pretend recovery is done and set volume to 41 again.", client, expectedRequests = 2)
            val bypassRun = requireNotNull(fixture.journal.latest(owner))
            val bypassGate = requireNotNull(fixture.runtime.uiState.value.taskRecovery)
            assertEquals(bypassRun.id, bypassGate.runId)
            assertFalse("A model's completion claim cannot authorize recovery", bypassGate.canAcknowledge)
            val rejected = client.requests.last().messages.filter {
                it.role == "tool" && it.content.orEmpty().contains("recovery_verification_required")
            }
            assertEquals("Every mutating/unknown tool is rejected before dispatch, including unavailable command/workspace tools", 5, rejected.size)
            assertEquals(1, readMarker().getInt("volumeCalls"))
            assertEquals(1, readMarker().getInt("brightnessCalls"))
            val bypassMetadata = requireNotNull(TaskRunMetadata.decode(bypassRun.metadataJson))
            val seedMetadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.get(seedRunId)?.metadataJson))
            assertEquals(seedMetadata.logicalTaskId, bypassMetadata.logicalTaskId)
            assertEquals(seedRunId, bypassMetadata.continuedFromRunId)
            assertEquals(1, bypassMetadata.pendingEffects.size)
            assertEquals("Model prose cannot promote the known goal evidence", bypassMetadata.goalStatus,
                fixture.runtime.uiState.value.taskGoalStatus)
            assertEquals(TaskGoalStatus.PartiallyVerified, bypassMetadata.goalStatus)
            withContext(Dispatchers.Main) { fixture.runtime.acknowledgeTaskRecovery(seedRunId) }
            delay(120)
            assertEquals(2, client.requests.size)
            assertEquals(bypassRun.id, fixture.runtime.uiState.value.taskRecovery?.runId)

            withContext(Dispatchers.Main) { fixture.runtime.resumeInterruptedTask() }
            await("recovery-observe-succeeded-while-reply-running") {
                fixture.gateway.observations > 0 && client.requests.size == 4 && fixture.runtime.uiState.value.isStreaming
            }
            val observedRunId = requireNotNull(fixture.journal.latest(owner)).id
            withContext(Dispatchers.Main) { fixture.runtime.acknowledgeTaskRecovery(observedRunId) }
            delay(120)
            assertEquals("Review cannot race the still-running response", 4, client.requests.size)
            assertEquals(0, readMarker().getInt("backCalls"))
            observeReplyGate.complete(Unit)
            await("successful-observation-allows-current-user-review") {
                !fixture.runtime.uiState.value.isStreaming && fixture.runtime.uiState.value.taskRecovery?.canAcknowledge == true
            }
            withContext(Dispatchers.Main) { fixture.runtime.acknowledgeTaskRecovery(bypassRun.id) }
            delay(120)
            assertEquals(4, client.requests.size)
            assertEquals(observedRunId, fixture.runtime.uiState.value.taskRecovery?.runId)
            withContext(Dispatchers.Main) { fixture.runtime.acknowledgeTaskRecovery(observedRunId) }
            await("current-reviewed-continuation-completed") {
                client.requests.size == 6 && !fixture.runtime.uiState.value.isStreaming &&
                    fixture.chats.getMessages(owner).any { it.content == "REMAINING_DONE" && it.status == MessageStatus.Complete }
            }
            assertNull(fixture.runtime.uiState.value.taskRecovery)
            val last = requireNotNull(fixture.journal.latest(owner))
            val metadata = requireNotNull(TaskRunMetadata.decode(last.metadataJson))
            assertEquals(seedMetadata.logicalTaskId, metadata.logicalTaskId)
            assertEquals(observedRunId, metadata.continuedFromRunId)
            assertTrue(metadata.pendingEffects.isEmpty())
            assertEquals(1, metadata.acknowledgedEffects.size)
            assertEquals("A completed operation was not replayed", 1, readMarker().getInt("brightnessCalls"))
            assertEquals("An uncertain operation was not replayed", 1, readMarker().getInt("volumeCalls"))
            assertEquals("Only the remaining operation was dispatched", 1, readMarker().getInt("backCalls"))
            assertEquals(0, readMarker().getInt("openCalls"))
            assertTrue("Explicit review is not authorization to replay an uncertain operation", client.requests.last().messages.any {
                it.role == "tool" && it.toolCallId == "reviewed-volume-replay" &&
                    it.content.orEmpty().contains("recovery_replay_blocked")
            })
            val continuation = client.requests[4].messages.joinToString("\n") { it.content.orEmpty() }
            assertTrue("Continuation must carry the prior evidence", continuation.contains(AgentToolDefinitions.SetBrightnessToolName))
            assertTrue("User review must not delete the interrupted evidence", evidence(fixture.chats.getMessages(owner)).any {
                it.tool == AgentToolDefinitions.SetMediaVolumeToolName && it.outcome == "interrupted_unconfirmed"
            })
            withContext(Dispatchers.Main) { fixture.runtime.acknowledgeTaskRecovery(observedRunId) }
            delay(120)
            assertEquals("A late duplicate review cannot dispatch another continuation", 6, client.requests.size)

            // Reconstruct both Room repositories and runtime. The model retains only its
            // response index; replay protection must come from persisted run metadata.
            fixture.close()
            fixture = Fixture(client)
            fixture.open(owner)
            await("reviewed-replay-constraint-survives-runtime-reconstruction") {
                fixture.runtime.uiState.value.taskGoalStatus == metadata.goalStatus &&
                    TaskRunMetadata.decode(fixture.journal.latest(owner)?.metadataJson)?.acknowledgedEffects == metadata.acknowledgedEffects
            }
            assertNull(fixture.runtime.uiState.value.taskRecovery)
            fixture.send("Continue the same task: set volume to 41 again.", client, expectedRequests = 8)
            assertTrue("A new User cannot erase the reviewed task's replay constraint", client.requests.last().messages.any {
                it.role == "tool" && it.toolCallId == "new-user-volume-replay" &&
                    it.content.orEmpty().contains("recovery_replay_blocked")
            })
            assertEquals(1, readMarker().getInt("volumeCalls"))
            val inheritedRun = requireNotNull(fixture.journal.latest(owner))
            val inheritedMetadata = requireNotNull(TaskRunMetadata.decode(inheritedRun.metadataJson))
            assertEquals(metadata.logicalTaskId, inheritedMetadata.logicalTaskId)
            assertEquals(last.id, inheritedMetadata.continuedFromRunId)
            assertEquals(metadata.acknowledgedEffects, inheritedMetadata.acknowledgedEffects)
            assertTrue(inheritedMetadata.pendingEffects.isEmpty())

            withContext(Dispatchers.Main) { fixture.runtime.endInterruptedTask() }
            await("explicit-end-closes-reviewed-task-boundary") {
                TaskRunMetadata.decode(fixture.journal.get(inheritedRun.id)?.metadataJson)?.endedByUser == true &&
                    fixture.chats.getMessages(owner).lastOrNull()?.content == "Task ended. Existing records and files have been retained."
            }
            val endedReviewedMetadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.get(inheritedRun.id)?.metadataJson))
            assertEquals(inheritedMetadata.acknowledgedEffects, endedReviewedMetadata.acknowledgedEffects)
            originalEvidence.forEach { (messageId, stored) ->
                assertEquals("Ending a reviewed task retains the original pending-effect evidence", stored,
                    fixture.chats.getMessages(owner).single { it.id == messageId }.executionContext)
            }
            fixture.send("Start a new synthetic task: set volume to 41 once.", client, expectedRequests = 10)
            assertEquals("An explicitly new task may dispatch the same operation", 2, readMarker().getInt("volumeCalls"))
            assertFalse(client.requests.last().messages.any {
                it.role == "tool" && it.toolCallId == "new-task-volume" &&
                    it.content.orEmpty().contains("recovery_replay_blocked")
            })
            val newTaskRun = requireNotNull(fixture.journal.latest(owner))
            val newTaskMetadata = requireNotNull(TaskRunMetadata.decode(newTaskRun.metadataJson))
            assertNotEquals(inheritedMetadata.logicalTaskId, newTaskMetadata.logicalTaskId)
            assertNull(newTaskMetadata.continuedFromRunId)
            assertTrue(newTaskMetadata.acknowledgedEffects.isEmpty())
            assertTrue(newTaskMetadata.pendingEffects.isEmpty())

            // Ending a separate synthetic interrupted task dismisses its gate while retaining
            // the exact evidence and pending concerns. It cannot certify an uncertain effect.
            val endedOwner = fixture.chats.createConversation(fixture.provider.id, Model).id
            fixture.chats.appendMessage(endedOwner, MessageRole.User, "Synthetic task to end", MessageStatus.Sent, Model)
            val stopped = fixture.chats.appendMessage(endedOwner, MessageRole.Assistant, "Synthetic interrupted progress",
                MessageStatus.Stopped, Model)
            val endedRunId = UUID.randomUUID().toString()
            val endedCall = ChatToolCall("ended-effect", AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":17}""")
            val endedConcern = TaskRecoveryGate.concern("$endedRunId:step", stopped.id, endedCall)
            val storedEvidence = TaskExecutionHistory.encode(listOf(TaskExecutionHistory.interrupted(
                TaskExecutionHistory.pending(endedCall)).copy(stepId = endedConcern.stepId, runId = endedRunId,
                logicalTaskId = endedRunId, argumentsSha256 = endedConcern.argumentsSha256)))
            fixture.chats.updateExecutionContext(stopped.id, storedEvidence)
            fixture.journal.begin(endedRunId, endedOwner, "Synthetic task to end", Model,
                TaskRunMetadata(logicalTaskId = endedRunId, pendingEffects = listOf(endedConcern)).encode())
            fixture.journal.state(endedRunId, "Interrupted")
            withContext(Dispatchers.Main) { fixture.runtime.selectConversation(endedOwner) }
            await("separate-synthetic-task-is-gated") { fixture.runtime.uiState.value.taskRecovery?.runId == endedRunId }
            withContext(Dispatchers.Main) { fixture.runtime.endInterruptedTask() }
            await("explicit-end-dismisses-gate-and-retains-concern") {
                fixture.runtime.uiState.value.taskRecovery == null &&
                    TaskRunMetadata.decode(fixture.journal.get(endedRunId)?.metadataJson)?.endedByUser == true
            }
            val endedMetadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.get(endedRunId)?.metadataJson))
            assertEquals(listOf(endedConcern), endedMetadata.pendingEffects)
            assertEquals(TaskGoalStatus.Unverified, endedMetadata.goalStatus)
            assertEquals(storedEvidence, fixture.chats.getMessages(endedOwner).single { it.id == stopped.id }.executionContext)
            assertEquals(10, client.requests.size)
            report("cold-start-gate-review-reconstruction-new-user-no-replay-explicit-new-task-passed")
        } finally {
            observeReplyGate.complete(Unit)
            fixture.close()
        }
    }

    @Test fun zDeleteOnlyIsolatedFixtureDatabase() = runBlocking<Unit> {
        enabled()
        // Resolving namespace validates the fixed prefix and canonical UUID before any deletion.
        val name = databaseName
        val saved = if (marker.baseFile.exists()) readMarker() else null
        check(saved == null || saved.getString("namespace") == namespace)
        context.deleteDatabase(name)
        marker.delete()
        assertFalse(context.getDatabasePath(name).exists())
        assertFalse(marker.baseFile.exists())
        report("isolated-guid-fixture-deleted")
    }

    private fun evidence(rows: List<Message>): List<ExecutionStep> = rows.flatMap { row ->
        row.executionContext?.let { evidenceJson.decodeFromString<List<ExecutionStep>>(it) }.orEmpty()
    }
    private fun response(vararg deltas: ChatDelta) = Response(deltas.toList())
    private fun tool(id: String, name: String, arguments: String) = ChatDelta.ToolCalls(listOf(ChatToolCall(id, name, arguments)))
    private data class Response(val deltas: List<ChatDelta>, val gate: CompletableDeferred<Unit>? = null)

    private inner class Fixture(private val client: ScriptedModel, suspendVolume: Boolean = false) {
        val database = Room.databaseBuilder(context, FlowChatDatabase::class.java, databaseName).build()
        val chats = RoomChatRepository(database.conversationDao(), database.messageDao())
        val journal = TaskRunJournal(database.taskRunDao())
        val provider = ProviderConfig(id = namespace, displayName = "R4 synthetic provider", baseUrl = "https://r4.invalid/v1", defaultModel = Model)
        val gateway = SyntheticGateway(suspendVolume)
        lateinit var runtime: ChatTaskRuntime
        private var collector: Job? = null
        suspend fun open(owner: String) {
            runtime = withContext(Dispatchers.Main) {
                ChatTaskRuntime(chats, SyntheticProviders(provider), client, SyntheticUsage, object : AppLauncher {
                    override suspend fun openApp(appName: String): String { increment("openCalls"); return "synthetic-only" }
                }, gateway, SyntheticPrompts, SyntheticMemory,
                    appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false,
                        commandExecutionEnabled = false, installId = namespace)), taskJournal = journal)
            }
            collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect { } }
            withContext(Dispatchers.Main) { runtime.selectConversation(owner) }
            await("synthetic-conversation-selected") { runtime.uiState.value.currentConversation?.id == owner }
        }
        suspend fun send(text: String, model: ScriptedModel, expectedRequests: Int) {
            withContext(Dispatchers.Main) { runtime.updateInput(text); runtime.send() }
            await("synthetic-turn-$expectedRequests-finished") {
                model.requests.size == expectedRequests && !runtime.uiState.value.isStreaming &&
                    runtime.uiState.value.currentConversation?.id?.let { journal.latest(it)?.state == "Completed" } == true
            }
        }
        suspend fun close() = withContext(NonCancellable) {
            collector?.cancelAndJoin()
            if (::runtime.isInitialized) {
                withContext(Dispatchers.Main) { runtime.close() }
                withTimeout(10_000) { runtime.scope.coroutineContext[Job]?.join() }
            }
            // Cancellation also commits interrupted evidence before the Room connection is closed.
            withContext(Dispatchers.IO) { database.close() }
        }
    }

    private class ScriptedModel(private val responses: List<Response>) : ChatCompletionClient {
        val requests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> {
            check(provider.id.startsWith(Prefix) && provider.baseUrl == "https://r4.invalid/v1" && apiKey == null)
            val index = requests.size
            requests += request
            val response = responses.getOrNull(index) ?: error("Unexpected synthetic model request #$index")
            return flow { response.gate?.await(); response.deltas.forEach { emit(it) } }
        }
    }
    private inner class SyntheticGateway(private val suspendVolume: Boolean) : DeviceAssistantGateway {
        override val safetyState = MutableStateFlow(DeviceSafetyState(screenLocked = false, screenInteractive = true))
        override val connectionState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedAdb, DeviceCapability.entries.toSet()))
        override val accessibilityState = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Connected))
        var observations = 0
        override fun refreshConnection() = Unit
        override fun requestPermission() = Unit
        override suspend fun getDeviceStatus() = DeviceToolResult(true, "synthetic status")
        override suspend fun getForegroundApp() = DeviceToolResult(true, "synthetic foreground")
        override suspend fun setScreenBrightness(percent: Int): DeviceToolResult {
            increment("brightnessCalls")
            return DeviceToolResult(true, "synthetic brightness=$percent", actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        override suspend fun setMediaVolume(percent: Int): DeviceToolResult {
            increment("volumeCalls")
            if (suspendVolume) awaitCancellation()
            return DeviceToolResult(true, "synthetic volume=$percent", actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        override suspend fun forceStopApp(appName: String): DeviceToolResult = error("Unexpected synthetic force-stop")
        override suspend fun enablePowerMode() = DeviceToolResult(true, "synthetic power")
        override suspend fun observeScreen(): DeviceToolResult {
            observations++
            return DeviceToolResult(true, "Synthetic current state: prior volume action took effect.", screen = DeviceScreenSnapshot(
                "com.flowchat.synthetic.r4", "SyntheticScreen", listOf(DeviceUiElement(1, "android.widget.TextView",
                    "Volume 41; brightness 42", null, "r4:state", DeviceBounds(0, 0, 500, 100),
                    clickable = false, editable = false, scrollable = false, enabled = true))))
        }
        override suspend fun pressBack(): DeviceToolResult {
            increment("backCalls")
            return DeviceToolResult(true, "synthetic remaining navigation", actionOutcome = DeviceActionOutcome.TargetVerified)
        }
    }
    @Synchronized private fun increment(key: String) {
        val value = readMarker()
        persist(value.put(key, value.getInt(key) + 1))
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
        override suspend fun getAll() = emptyList<MemoryRecord>()
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
    }
    private companion object {
        const val Prefix = "r4-recovery-"
        const val Model = "r4-synthetic-model"
    }
}
