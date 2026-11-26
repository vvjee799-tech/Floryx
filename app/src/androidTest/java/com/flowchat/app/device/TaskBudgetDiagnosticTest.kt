package com.flowchat.app.device

import android.os.Bundle
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
import com.flowchat.app.domain.chat.TaskPostconditionProofLimitation
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicitly opted-in, on-device runtime/Room diagnostics. Every test uses a separate,
 * GUID-named Room database, a synthetic model and a gateway that never calls Android APIs.
 * There is no Hilt entry point, real provider, credential, production journal or main DB.
 */
class TaskBudgetDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val namespace: String get() {
        val value = requireNotNull(InstrumentationRegistry.getArguments().getString("r4BudgetNamespace"))
        check(value.startsWith(Prefix) && UUID.fromString(value.removePrefix(Prefix)).toString() == value.removePrefix(Prefix)) {
            "Budget diagnostics require the runner's fresh canonical GUID namespace"
        }
        return value
    }

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("runTaskBudgetDiagnostics") == "true"
    )
    private fun report(label: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\nTASK_BUDGET_R4 $label\n")
    })
    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!condition()) delay(25) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        report(label)
    }

    @Test fun genuineProgressCrossesTwentyFourToolsWithDurableStageCheckpoints() = runBlocking<Unit> {
        enabled()
        val model = ScriptedModel(List(30) { index -> response(tool("progress-$index", AgentToolDefinitions.PressBackToolName, "{}")) } +
            response(ChatDelta.Content("SYNTHETIC_PROGRESS_FINISHED"), ChatDelta.Done))
        val limits = generousLimits().copy(stageToolCalls = 7, stageModelCalls = 8, maxStages = 6)
        var fixture = Fixture(Case.Progress, model, limits)
        try {
            val owner = fixture.createAndOpen()
            fixture.send("Synthetic long task: traverse thirty distinct verified fixture screens.")
            fixture.awaitFinished(owner, "Completed")
            assertEquals("Every synthetic action changed the fixture screen", 30, fixture.gateway.backCalls.get())
            assertEquals(31, model.requests.size)
            val savedRun = requireNotNull(fixture.journal.latest(owner))
            val saved = metadata(savedRun.metadataJson)
            val budget = requireNotNull(saved.budget)
            assertEquals(30, budget.totalToolCalls)
            assertEquals(31, budget.totalModelCalls)
            assertTrue("A bounded stage must roll over before 30 calls finish", budget.stageNumber > 1)
            assertTrue("Stage transitions need durable evidence references", budget.checkpointRefs.isNotEmpty())
            assertTrue(budget.stageNumber <= limits.maxStages)
            assertTrue(budget.stageToolCalls <= limits.stageToolCalls)
            assertNull(budget.stoppedReason)
            assertNotEquals("A model's success sentence cannot certify a whole task", TaskGoalStatus.Verified, saved.goalStatus)

            // Reopen both Room and runtime; assertions must read the persisted record rather
            // than the completed turn's in-memory budget object.
            fixture.close()
            fixture = Fixture(Case.Progress, model, limits, reopening = true)
            fixture.open(owner)
            val reread = metadata(requireNotNull(fixture.journal.get(savedRun.id)).metadataJson)
            assertEquals(budget, reread.budget)
            assertEquals(saved.logicalTaskId, reread.logicalTaskId)
            assertEquals(0, fixture.gateway.mutationCount)
            assertEquals(31, model.requests.size)
            report("thirty-real-fixture-progress-steps-stage-checkpoints-room-reread-passed")
        } finally { fixture.close(); deleteFixture(Case.Progress) }
    }

    @Test fun batchCountsEachToolAndReconstructionCannotResetExhaustedTotal() = runBlocking<Unit> {
        enabled()
        val model = ScriptedModel(listOf(response(ChatDelta.ToolCalls(List(10) { index ->
            ChatToolCall("batch-$index", AgentToolDefinitions.SetBrightnessToolName, """{"percent":${10 + index}}""")
        }))))
        val limits = generousLimits().copy(stageToolCalls = 12, totalToolCalls = 3)
        var fixture = Fixture(Case.Batch, model, limits)
        try {
            val owner = fixture.createAndOpen()
            fixture.send("Synthetic task: request a batch of ten independent brightness fixture changes.")
            fixture.awaitFinished(owner, "Paused")
            assertEquals("A batch is charged per tool, not per model response", 3, fixture.gateway.brightnessCalls.get())
            assertEquals(1, model.requests.size)
            val exhaustedRun = requireNotNull(fixture.journal.latest(owner))
            val exhausted = metadata(exhaustedRun.metadataJson)
            val exhaustedBudget = requireNotNull(exhausted.budget)
            assertEquals(3, exhaustedBudget.totalToolCalls)
            assertTrue(exhausted.stopReason.orEmpty().startsWith("budget_"))
            assertNotNull(exhaustedBudget.stoppedReason)
            assertTrue(exhausted.pendingEffects.isEmpty())

            fixture.close()
            fixture = Fixture(Case.Batch, model, limits, reopening = true)
            fixture.open(owner)
            assertEquals(exhaustedBudget, metadata(fixture.journal.get(exhaustedRun.id)?.metadataJson).budget)
            fixture.send("Continue that same task; an ordinary user message must not grant a fresh budget.")
            await("ordinary-user-retains-exhausted-budget") {
                val latest = fixture.journal.latest(owner)
                latest != null && latest.id != exhaustedRun.id && latest.state == "Paused" &&
                    !fixture.runtime.uiState.value.isStreaming
            }
            val inherited = metadata(fixture.journal.latest(owner)?.metadataJson)
            assertEquals(exhausted.logicalTaskId, inherited.logicalTaskId)
            assertEquals(exhaustedRun.id, inherited.continuedFromRunId)
            assertEquals(exhaustedBudget.totalToolCalls, requireNotNull(inherited.budget).totalToolCalls)
            assertEquals(exhaustedBudget.totalModelCalls, requireNotNull(inherited.budget).totalModelCalls)
            assertEquals(exhaustedBudget.stoppedReason, requireNotNull(inherited.budget).stoppedReason)
            assertEquals("Exhaustion must be checked before calling the model again", 1, model.requests.size)
            assertEquals("Reconstruction and a new User must not dispatch any new effect", 0, fixture.gateway.mutationCount)
            report("batch-three-of-ten-dispatched-total-durable-new-user-no-reset-passed")
        } finally { fixture.close(); deleteFixture(Case.Batch) }
    }

    @Test fun measuredLatencyCheckpointsBetweenOperationsWithoutInterruptingProgress() = runBlocking<Unit> {
        enabled()
        val model = ScriptedModel(List(9) { index -> Response {
            delay(250)
            emit(tool("time-progress-$index", AgentToolDefinitions.PressBackToolName, "{}"))
        } } + Response { delay(250); emit(ChatDelta.Content("SYNTHETIC_TIMED_PROGRESS_FINISHED")); emit(ChatDelta.Done) })
        // Include real Room/UI bookkeeping in the fixture's allowance; the original 1.6s
        // fixture correctly exhausted four stages after seven actions (not an in-flight cancel).
        val limits = generousLimits().copy(stageActiveMillis = 3_000, totalActiveMillis = 12_000, maxStages = 4)
        val fixture = Fixture(Case.TimeProgress, model, limits)
        try {
            val owner = fixture.createAndOpen()
            fixture.send("Synthetic task: preserve nine verified destinations across timed stage boundaries.")
            await("time-progress-finished") {
                val run = fixture.journal.latest(owner)
                val finished = owner !in fixture.runtime.activeConversations.value && run != null
                if (finished) assertEquals("Timed continuation metadata: ${run?.metadataJson}", "Completed", run?.state)
                finished
            }
            val saved = metadata(fixture.journal.latest(owner)?.metadataJson)
            val budget = requireNotNull(saved.budget)
            assertEquals(9, fixture.gateway.backCalls.get())
            assertEquals(10, model.requests.size)
            assertTrue(budget.stageNumber in 2..limits.maxStages)
            assertEquals(budget.stageNumber - 1, budget.checkpointRefs.size)
            assertEquals(9, budget.totalToolCalls)
            assertEquals(10, budget.totalModelCalls)
            assertTrue(budget.totalActiveMillis > limits.stageActiveMillis)
            assertTrue(budget.totalActiveMillis < limits.totalActiveMillis)
            assertEquals(limits, budget.limits)
            assertTrue(saved.pendingEffects.isEmpty())
            assertNull(budget.stoppedReason)
            assertTrue(model.requests.last().messages.any { it.role == "tool" && it.content.orEmpty().contains("destination 1") })
            assertTrue(model.requests.last().messages.any { it.role == "tool" && it.content.orEmpty().contains("destination 9") })
            report("timed-stage-checkpoints-retain-results-with-nine-dispatches-and-unchanged-totals")
        } finally { fixture.close(); deleteFixture(Case.TimeProgress) }
    }

    @Test fun successfulAnswerOnFinalModelSlotAllowsANewTask() = runBlocking<Unit> {
        enabled()
        val model = ScriptedModel(listOf(
            response(ChatDelta.Content("FIRST_SYNTHETIC_PLAIN_ANSWER"), ChatDelta.Done),
            response(ChatDelta.Content("SECOND_SYNTHETIC_PLAIN_ANSWER"), ChatDelta.Done)
        ))
        val fixture = Fixture(Case.FinalModelSlot, model, generousLimits().copy(totalModelCalls = 1))
        try {
            val owner = fixture.createAndOpen()
            fixture.send("First independent synthetic question: answer directly without device tools.")
            fixture.awaitFinished(owner, "Completed")
            val firstRun = requireNotNull(fixture.journal.latest(owner))
            val first = metadata(firstRun.metadataJson)
            assertEquals("Completed", first.stopReason)
            assertEquals(1, requireNotNull(first.budget).totalModelCalls)
            assertEquals("The successful response consumed the final reserved model slot",
                "TotalModelCalls", requireNotNull(first.budget).stoppedReason?.name)
            assertNull(first.continuedFromRunId)
            assertFalse("Consuming the last slot after a successful answer is not a paused task",
                fixture.runtime.uiState.value.taskBudgetPaused)

            fixture.send("Second independent synthetic question: provide another direct answer.")
            fixture.awaitFinished(owner, "Completed")
            val secondRun = requireNotNull(fixture.journal.latest(owner))
            val second = metadata(secondRun.metadataJson)
            assertEquals(2, model.requests.size)
            assertNotEquals(firstRun.id, secondRun.id)
            assertNotEquals("A successfully completed answer does not trap the next question in its exhausted budget",
                first.logicalTaskId, second.logicalTaskId)
            assertNull(second.continuedFromRunId)
            assertEquals("Completed", second.stopReason)
            assertEquals(1, requireNotNull(second.budget).totalModelCalls)
            assertEquals(0, requireNotNull(second.budget).totalToolCalls)
            assertFalse(fixture.runtime.uiState.value.taskBudgetPaused)
            assertEquals(0, fixture.gateway.mutationCount)
            assertEquals("The completed first task keeps its original budget evidence", firstRun.metadataJson,
                fixture.journal.get(firstRun.id)?.metadataJson)

            // The explicit end action must also work on a completed budget-bearing record.
            val secondBudget = requireNotNull(second.budget)
            withContext(Dispatchers.Main) { fixture.runtime.endInterruptedTask() }
            await("completed-final-slot-task-can-be-explicitly-ended") {
                metadata(fixture.journal.get(secondRun.id)?.metadataJson).endedByUser &&
                    fixture.runtime.uiState.value.taskBudget == null && !fixture.runtime.uiState.value.taskBudgetPaused
            }
            val ended = metadata(fixture.journal.get(secondRun.id)?.metadataJson)
            assertEquals("ended_by_user", ended.stopReason)
            assertEquals(secondBudget, ended.budget)
            assertEquals(firstRun.metadataJson, fixture.journal.get(firstRun.id)?.metadataJson)
            assertEquals(2, model.requests.size)
            assertEquals(0, fixture.gateway.mutationCount)
            report("successful-final-model-slot-new-task-and-explicit-end-preserve-budget-evidence")
        } finally { fixture.close(); deleteFixture(Case.FinalModelSlot) }
    }

    @Test fun unchangedScreenAndDifferentArgumentsCannotManufactureProgress() = runBlocking<Unit> {
        enabled()
        val model = ScriptedModel(List(15) { index -> response(
            ChatDelta.Content("The whole synthetic task succeeded; this different argument proves progress."),
            tool("no-progress-$index", AgentToolDefinitions.SwipeScreenToolName,
                """{"direction":"up","distance_percent":${20 + index}}""")) })
        val limits = generousLimits().copy(maxNoProgress = 3)
        val fixture = Fixture(Case.NoProgress, model, limits)
        try {
            val owner = fixture.createAndOpen()
            fixture.send("Synthetic task: repeatedly swipe a fixed screen; do not accept model prose as proof.")
            fixture.awaitFinished(owner, "Paused")
            val saved = metadata(fixture.journal.latest(owner)?.metadataJson)
            val budget = requireNotNull(saved.budget)
            assertEquals(3, budget.noProgressCount)
            assertTrue("The independent no-progress limit must stop before the old six-call UI guard",
                fixture.gateway.swipeCalls.get() in 3..4)
            assertEquals(fixture.gateway.swipeCalls.get(), budget.totalToolCalls)
            assertEquals(fixture.gateway.swipeCalls.get(), fixture.gateway.swipeDistances.distinct().size)
            assertTrue(saved.stopReason.orEmpty().startsWith("budget_"))
            assertTrue(requireNotNull(budget.stoppedReason).name.contains("Progress", ignoreCase = true))
            assertNotEquals(TaskGoalStatus.Verified, saved.goalStatus)
            assertTrue("Only the initial baseline may count; new IDs, arguments and model prose cannot add progress",
                budget.stageProgressCount <= 1)
            report("same-screen-varied-arguments-and-model-success-prose-no-progress-paused")
        } finally { fixture.close(); deleteFixture(Case.NoProgress) }
    }

    @Test fun nonReturningModelIsCancelledAtWallTimeBudget() = runBlocking<Unit> {
        verifyBoundedModel(Case.NeverReturns, reasoningOnly = false)
    }

    @Test fun endlessReasoningStreamCannotKeepBudgetAliveWithoutContent() = runBlocking<Unit> {
        verifyBoundedModel(Case.ReasoningOnly, reasoningOnly = true)
    }

    @Test fun exactWaitEvidenceSurvivesRoomReopenWithoutResolvingUnverifiedEffect() = runBlocking<Unit> {
        enabled()
        val waitArguments = """{"include_image":false,"timeout_ms":500,"condition":{"package_name":"com.flowchat.synthetic.budget","element":{"resource_id":"budget:state","text":"Synthetic wait target reached"},"state":"exists"}}"""
        val model = ScriptedModel(listOf(
            response(tool("uncertain-volume", AgentToolDefinitions.SetMediaVolumeToolName, """{"percent":37}""")),
            response(tool("exact-wait", AgentToolDefinitions.WaitForScreenToolName, waitArguments)),
            response(ChatDelta.Content("The exact declared fixture screen was seen. The earlier volume effect remains uncertain."), ChatDelta.Done)
        ))
        val limits = generousLimits()
        var fixture = Fixture(Case.Postcondition, model, limits)
        try {
            val owner = fixture.createAndOpen()
            fixture.send("Synthetic task: attempt one uncertain volume effect, then inspect an exact screen condition.")
            fixture.awaitFinished(owner, "Completed")
            assertEquals(1, fixture.gateway.volumeCalls.get())
            assertEquals(1, fixture.gateway.waitCalls.get())
            assertEquals(3, model.requests.size)
            val run = requireNotNull(fixture.journal.latest(owner))
            val saved = metadata(run.metadataJson)
            assertEquals("An exact screen predicate does not resolve an uncertain effect", 1, saved.pendingEffects.size)
            assertEquals(AgentToolDefinitions.SetMediaVolumeToolName, saved.pendingEffects.single().tool)
            assertEquals(TaskGoalStatus.Unverified, saved.goalStatus)
            assertEquals(0, saved.verifiedStepCount)
            val proof = saved.postconditionEvidence.single()
            assertEquals(run.id, proof.runId)
            assertEquals(AgentToolDefinitions.WaitForScreenToolName, proof.sourceTool)
            assertEquals(TaskRecoveryGate.fingerprint(waitArguments), proof.sourceArgumentsSha256)
            assertEquals(TaskRecoveryGate.fingerprint(proof.requestedCondition.toString()), proof.conditionSha256)
            assertEquals(PostconditionScreenId, proof.observedScreenId)
            assertEquals("com.flowchat.synthetic.budget", proof.observedPackageName)
            assertEquals(PostconditionObservedAt, proof.observedAtMillis)
            assertTrue(proof.conditionMatched)
            assertTrue(proof.proofLimitations.contains(TaskPostconditionProofLimitation.DoesNotCompleteOverallGoal))
            assertTrue(proof.proofLimitations.contains(TaskPostconditionProofLimitation.DoesNotResolveRecoveryEffects))
            val sourceMessage = fixture.chats.getMessages(owner).single { it.id == proof.messageId }
            val sourceStep = Json.decodeFromString<List<ExecutionStep>>(requireNotNull(sourceMessage.executionContext))
                .single { it.stepId == proof.stepId }
            assertEquals(run.id, sourceStep.runId)
            assertEquals(waitArguments, sourceStep.arguments)
            assertEquals(proof.sourceArgumentsSha256, sourceStep.argumentsSha256)
            assertEquals("returned", sourceStep.outcome)
            assertTrue(sourceStep.result.contains("condition_matched=true"))
            assertNotNull(fixture.runtime.uiState.value.taskRecovery)

            fixture.close()
            fixture = Fixture(Case.Postcondition, model, limits, reopening = true)
            fixture.open(owner)
            val reread = metadata(fixture.journal.get(run.id)?.metadataJson)
            assertEquals(saved.postconditionEvidence, reread.postconditionEvidence)
            assertEquals(saved.pendingEffects, reread.pendingEffects)
            assertEquals(TaskGoalStatus.Unverified, reread.goalStatus)
            assertEquals(0, fixture.gateway.mutationCount)
            assertEquals(3, model.requests.size)
            report("exact-wait-durable-source-refs-hashes-pending-effect-and-whole-goal-unchanged")
        } finally { fixture.close(); deleteFixture(Case.Postcondition) }
    }

    @Test fun changingImageKeysAndObservationScopesCannotManufactureProgress() = runBlocking<Unit> {
        enabled()
        val model = ScriptedModel(List(10) { index -> response(tool("visual-read-$index",
            AgentToolDefinitions.ObserveScreenToolName,
            """{"include_image":true,"observation_scope":{"root":{"resource_id":"budget:scope-$index"},"kind":"subtree"}}""")) })
        val fixture = Fixture(Case.VisualOnly, model, generousLimits().copy(maxNoProgress = 3))
        try {
            val owner = fixture.createAndOpen()
            fixture.send("Synthetic task: inspect a fixed fixture page through different scopes while an unrelated image changes.")
            fixture.awaitFinished(owner, "Paused")
            val saved = metadata(fixture.journal.latest(owner)?.metadataJson)
            val budget = requireNotNull(saved.budget)
            assertEquals(3, fixture.gateway.captureCalls.get())
            assertEquals(3, fixture.gateway.capturedScopes.distinct().size)
            assertEquals(3, fixture.gateway.capturedVisualKeys.distinct().size)
            assertEquals(0, fixture.gateway.mutationCount)
            assertEquals(3, budget.totalToolCalls)
            assertEquals(3, budget.noProgressCount)
            assertEquals(0, budget.stageProgressCount)
            assertTrue(requireNotNull(budget.stoppedReason).name.contains("Progress", ignoreCase = true))
            assertTrue(saved.postconditionEvidence.isEmpty())
            assertEquals(TaskGoalStatus.Unverified, saved.goalStatus)
            report("changed-image-keys-and-read-scopes-fixed-ui-no-progress-paused")
        } finally { fixture.close(); deleteFixture(Case.VisualOnly) }
    }

    private suspend fun verifyBoundedModel(case: Case, reasoningOnly: Boolean) {
        enabled()
        val cancelled = CompletableDeferred<Unit>()
        val model = ScriptedModel(listOf(Response {
            try {
                if (reasoningOnly) while (true) { emit(ChatDelta.Reasoning("synthetic thought ")); delay(25) }
                else awaitCancellation()
            } finally { cancelled.complete(Unit) }
        }))
        val limits = generousLimits().copy(stageActiveMillis = 800, totalActiveMillis = 800)
        val fixture = Fixture(case, model, limits)
        try {
            val owner = fixture.createAndOpen()
            val started = android.os.SystemClock.elapsedRealtime()
            fixture.send("Synthetic task: exercise a model that never provides a conclusion.")
            fixture.awaitFinished(owner, "Paused")
            withTimeout(2_000) { cancelled.await() }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val saved = metadata(fixture.journal.latest(owner)?.metadataJson)
            val budget = requireNotNull(saved.budget)
            assertEquals(1, model.requests.size)
            assertEquals(0, budget.totalToolCalls)
            assertEquals(0, fixture.gateway.mutationCount)
            assertTrue(saved.stopReason.orEmpty().startsWith("budget_"))
            assertNotNull(budget.stoppedReason)
            assertTrue("The watchdog must interrupt an active collection without waiting for a model delta", elapsed < 10_000)
            assertNotEquals(TaskGoalStatus.Verified, saved.goalStatus)
            report("${case.suffix}-wall-budget-cancelled-without-model-content elapsed_ms=$elapsed")
        } finally { fixture.close(); deleteFixture(case) }
    }

    private fun metadata(json: String?): TaskRunMetadata = requireNotNull(TaskRunMetadata.decode(json))
    private fun response(vararg deltas: ChatDelta) = Response { deltas.forEach { emit(it) } }
    private fun tool(id: String, name: String, arguments: String) = ChatDelta.ToolCalls(listOf(ChatToolCall(id, name, arguments)))
    private data class Response(val emit: suspend kotlinx.coroutines.flow.FlowCollector<ChatDelta>.() -> Unit)
    private enum class Case(val suffix: String) {
        Progress("progress"), Batch("batch"), NoProgress("no-progress"), NeverReturns("never-returns"),
        ReasoningOnly("reasoning-only"), Postcondition("postcondition"), VisualOnly("visual-only"), FinalModelSlot("final-model-slot"),
        TimeProgress("time-progress")
    }
    private fun databaseName(case: Case) = "$namespace-${case.suffix}.db"
    private fun deleteFixture(case: Case) {
        // Both path segments come from the canonical prefix/UUID validator and a fixed enum.
        val name = databaseName(case)
        context.deleteDatabase(name)
        assertFalse("Only the isolated diagnostic database should be removed", context.getDatabasePath(name).exists())
    }

    private inner class Fixture(
        private val case: Case,
        private val client: ScriptedModel,
        private val limits: TaskExecutionBudgetLimits,
        reopening: Boolean = false
    ) {
        init {
            check(reopening || !context.getDatabasePath(databaseName(case)).exists()) { "Budget diagnostic fixture must be fresh" }
        }
        val database = Room.databaseBuilder(context, FlowChatDatabase::class.java, databaseName(case)).build()
        val chats = RoomChatRepository(database.conversationDao(), database.messageDao())
        val journal = TaskRunJournal(database.taskRunDao())
        val provider = ProviderConfig(id = namespace, displayName = "R4 budget synthetic provider",
            baseUrl = SyntheticBaseUrl, defaultModel = Model)
        val gateway = SyntheticGateway(allowUnverifiedVolume = case == Case.Postcondition)
        lateinit var runtime: ChatTaskRuntime
        private var collector: Job? = null
        private var closed = false
        suspend fun createAndOpen(): String {
            val owner = chats.createConversation(provider.id, Model).id
            open(owner)
            return owner
        }
        suspend fun open(owner: String) {
            runtime = withContext(Dispatchers.Main) {
                ChatTaskRuntime(chats, SyntheticProviders(provider), client, SyntheticUsage, object : AppLauncher {
                    override suspend fun openApp(appName: String): String = error("No real or synthetic app launching is needed")
                }, gateway, SyntheticPrompts, SyntheticMemory,
                    appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = true, memoryEnabled = false,
                        commandExecutionEnabled = false, installId = namespace)), taskJournal = journal,
                    budgetLimits = limits, budgetClockMillis = { android.os.SystemClock.elapsedRealtime() })
            }
            collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect { } }
            withContext(Dispatchers.Main) { runtime.selectConversation(owner) }
            await("${case.suffix}-synthetic-conversation-selected") { runtime.uiState.value.currentConversation?.id == owner }
        }
        suspend fun send(text: String) = withContext(Dispatchers.Main) { runtime.updateInput(text); runtime.send() }
        suspend fun awaitFinished(owner: String, state: String) = await("${case.suffix}-$state") {
            journal.latest(owner)?.state == state && !runtime.uiState.value.isStreaming &&
                !runtime.activeConversations.value.contains(owner)
        }
        suspend fun close() = withContext(NonCancellable) {
            if (!closed) {
                closed = true
                collector?.cancelAndJoin()
                if (::runtime.isInitialized) {
                    withContext(Dispatchers.Main) { runtime.close() }
                    withTimeout(10_000) { runtime.scope.coroutineContext[Job]?.join() }
                }
                withContext(Dispatchers.IO) { database.close() }
            }
        }
    }

    private class ScriptedModel(private val responses: List<Response>) : ChatCompletionClient {
        val requests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> {
            check(provider.id.startsWith(Prefix) && provider.baseUrl == SyntheticBaseUrl && apiKey == null)
            val index = requests.size
            requests += request
            val response = responses.getOrNull(index) ?: error("Unexpected synthetic model request #$index")
            return flow(response.emit)
        }
    }

    private class SyntheticGateway(private val allowUnverifiedVolume: Boolean) : DeviceAssistantGateway {
        override val safetyState = MutableStateFlow(DeviceSafetyState(screenLocked = false, screenInteractive = true))
        override val connectionState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedAdb,
            DeviceCapability.entries.toSet()))
        override val accessibilityState = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Connected))
        val backCalls = AtomicInteger()
        val brightnessCalls = AtomicInteger()
        val swipeCalls = AtomicInteger()
        val volumeCalls = AtomicInteger()
        val waitCalls = AtomicInteger()
        val captureCalls = AtomicInteger()
        val swipeDistances: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val capturedScopes: MutableList<DeviceObservationScope?> = Collections.synchronizedList(mutableListOf())
        val capturedVisualKeys: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val mutationCount get() = backCalls.get() + brightnessCalls.get() + swipeCalls.get() + volumeCalls.get()
        override fun refreshConnection() = Unit
        override fun requestPermission() = Unit
        override suspend fun getDeviceStatus() = DeviceToolResult(true, "synthetic status")
        override suspend fun getForegroundApp() = DeviceToolResult(true, "synthetic foreground")
        override suspend fun setScreenBrightness(percent: Int): DeviceToolResult {
            brightnessCalls.incrementAndGet()
            return DeviceToolResult(true, "synthetic brightness=$percent", before = "fixture brightness=${percent - 1}",
                after = "fixture brightness=$percent", actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        override suspend fun setMediaVolume(percent: Int): DeviceToolResult {
            check(allowUnverifiedVolume) { "Unexpected fixture volume operation" }
            volumeCalls.incrementAndGet()
            return DeviceToolResult(true, "synthetic volume request returned without independent effect verification",
                actionOutcome = DeviceActionOutcome.Unverified)
        }
        override suspend fun forceStopApp(appName: String): DeviceToolResult = error("Unexpected fixture force-stop")
        override suspend fun enablePowerMode() = DeviceToolResult(true, "synthetic power")
        override suspend fun observeScreen() = DeviceToolResult(true, "synthetic observation", screen = screen("Fixed synthetic fixture screen"))
        override suspend fun waitForScreen(request: DeviceScreenWaitRequest): DeviceToolResult {
            waitCalls.incrementAndGet()
            val observed = screen("Synthetic wait target reached").copy(screenId = PostconditionScreenId,
                capturedAt = PostconditionObservedAt)
            check(request.condition?.matches(observed) == true) { "Unexpected synthetic wait condition" }
            return DeviceToolResult(true, "The exact declared synthetic predicate matched", screen = observed,
                actionOutcome = DeviceActionOutcome.TargetVerified, conditionMatched = true)
        }
        override suspend fun captureScreen(region: ScreenImageRegion?, scope: DeviceObservationScope?): DeviceToolResult {
            val index = captureCalls.incrementAndGet()
            val visual = "synthetic-unrelated-animation-$index"
            capturedScopes += scope
            capturedVisualKeys += visual
            return DeviceToolResult(true, "synthetic scoped screenshot", screen = screen("Fixed synthetic fixture screen").copy(
                observationScope = scope, observationRootPath = "fixture/root-$index"),
                screenImage = DeviceScreenImage(ChatImage("image/png", OnePixelPng), "synthetic-image-$index", 1, 1,
                    region = region, visualKey = visual), visualProgressKey = visual)
        }
        override suspend fun pressBack(): DeviceToolResult {
            val step = backCalls.incrementAndGet()
            return DeviceToolResult(true, "synthetic navigation reached destination $step",
                screen = screen("Synthetic destination number $step verified"), actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        override suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int): DeviceToolResult {
            swipeCalls.incrementAndGet()
            swipeDistances += distancePercent
            // New screen IDs and changed arguments must not hide identical actual UI content.
            return DeviceToolResult(true, "synthetic swipe dispatched; target remains unchanged",
                screen = screen("Fixed synthetic fixture screen"), actionOutcome = DeviceActionOutcome.Dispatched)
        }
        private fun screen(text: String) = DeviceScreenSnapshot("com.flowchat.synthetic.budget", "BudgetFixtureScreen",
            listOf(DeviceUiElement(1, "android.widget.TextView", text, null, "budget:state", DeviceBounds(0, 0, 500, 100),
                clickable = false, editable = false, scrollable = false, enabled = true)))
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
    private fun generousLimits() = TaskExecutionBudgetLimits(
        stageToolCalls = 24, stageModelCalls = 25, stageActiveMillis = 60_000, stageEstimatedTokens = 10_000_000,
        totalToolCalls = 96, totalModelCalls = 104, totalActiveMillis = 120_000, totalEstimatedTokens = 40_000_000,
        maxStages = 6, maxNoProgress = 8)
    private companion object {
        const val Prefix = "r4-budget-"
        const val Model = "r4-budget-synthetic-model"
        const val SyntheticBaseUrl = "https://r4-budget.invalid/v1"
        const val PostconditionScreenId = "budget-exact-wait-screen"
        const val PostconditionObservedAt = 1_234_567L
        const val OnePixelPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII="
    }
}
