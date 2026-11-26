package com.flowchat.app.device

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.tools.AgentToolDefinitions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Seed/cold/cleanup methods must run in separate instrumentation processes. The runner kills
 * the seed process after its marker is durable. A simulated close is not a process-death test.
 * Only GUID Room/source databases and local synthetic model/device counters are used.
 */
class TaskEffectPersistenceDiagnosticTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val files get() = EffectFixtureFiles(context, requireNotNull(arguments.getString("r4EffectNamespace")))
    private val boundary get() = TaskPersistenceBoundary.valueOf(requireNotNull(arguments.getString("r4EffectBoundary")))
    private val scenario get() = EffectScenario.parse(requireNotNull(arguments.getString("r4EffectScenario")))
    private fun enabled() = assumeTrue(arguments.getString("runTaskEffectPersistenceDiagnostics") == "true")
    private val confirmation get() = boundary in setOf(TaskPersistenceBoundary.ConfirmationSaved, TaskPersistenceBoundary.ConfirmationAccepted)
    private val checkpoint get() = boundary in setOf(TaskPersistenceBoundary.BeforeCheckpointCommit, TaskPersistenceBoundary.CheckpointCommitted)

    @Test fun aSeedAtBoundary() = runBlocking<Unit> {
        enabled()
        val files = files
        check(!context.getDatabasePath(files.runtimeDatabase).exists() && !context.getDatabasePath(files.sourceDatabase).exists() && !files.marker.baseFile.exists()) {
            "Every interruption scenario requires a fresh GUID namespace"
        }
        files.persistMarker(JSONObject().put("namespace", files.namespace).put("seedPid", Process.myPid())
            .put("boundary", boundary.name).put("scenario", scenario.argument))
        val reached = CompletableDeferred<Unit>()
        val observer = TaskPersistenceObserver { event ->
            if (event == boundary && !reached.isCompleted) {
                reached.complete(Unit)
                awaitCancellation()
            }
        }
        val model = EffectScriptedModel().apply {
            enqueue(ChatDelta.ToolCalls(listOf(if (confirmation) confirmationCall() else effectCall(scenario, "seed-exact-operation"))))
            enqueueBlocked()
        }
        // Intentionally keep the runtime, collector and databases alive. Only the external
        // runner may terminate the process after the boundary and marker have been checked.
        val fixture = TaskEffectFixture(files, scenario, model, observer, effectFixtureLimits(checkpoint))
        val owner = fixture.newConversation()
        files.persistMarker(files.readMarker().put("owner", owner))
        fixture.send("Synthetic ${scenario.argument} interruption acceptance at ${boundary.name}.")
        if (boundary == TaskPersistenceBoundary.ConfirmationAccepted) {
            effectAwait("confirmation-request-exists-before-explicit-test-approval") {
                fixture.runtime.uiState.value.pendingDeviceActionConfirmation != null
            }
            withContext(Dispatchers.Main) { fixture.runtime.confirmDeviceAction() }
        }
        withTimeout(25_000) { reached.await() }
        val run = requireNotNull(fixture.journal.latest(owner))
        val metadata = requireNotNull(TaskRunMetadata.decode(run.metadataJson))
        val budget = requireNotNull(metadata.budget)
        val rows = fixture.chats.getMessages(owner)
        val sourceSteps = rows.flatMap(::effectSteps)
        val calls = fixture.source.dispatchCount()
        val receipts = fixture.source.rows().size
        assertEquals(0, fixture.source.deviceCount())
        assertEquals("The boundary observer must hold the live runtime before it completes", true,
            fixture.runtime.activeConversations.value.contains(owner))
        if (confirmation) {
            assertTrue(metadata.effects.isEmpty())
            assertEquals(0, calls)
            assertEquals(0, receipts)
        } else when (boundary) {
            TaskPersistenceBoundary.BeforeIntentSaved -> {
                assertTrue(sourceSteps.isEmpty()); assertTrue(metadata.effects.isEmpty()); assertEquals(0, calls)
            }
            TaskPersistenceBoundary.IntentSaved -> {
                assertTrue(sourceSteps.any { it.outcome == "unconfirmed" })
                assertTrue(metadata.effects.isEmpty()); assertEquals(0, calls)
            }
            TaskPersistenceBoundary.PendingEffectsSaved -> {
                assertEquals(1, metadata.effects.size); assertEquals(1, metadata.pendingEffects.size); assertEquals(0, calls)
            }
            TaskPersistenceBoundary.ActionReturned, TaskPersistenceBoundary.ResultStepSaved -> {
                assertEquals(1, metadata.effects.size); assertEquals(1, calls); assertEquals(1, receipts)
            }
            TaskPersistenceBoundary.ResultMetadataSaved, TaskPersistenceBoundary.BeforeCheckpointCommit, TaskPersistenceBoundary.CheckpointCommitted -> {
                assertEquals(1, calls); assertEquals(1, receipts)
                assertEquals(TaskEffectResolution.Applied, metadata.effects.single().proof?.resolution)
                assertTrue(metadata.pendingEffects.isEmpty())
            }
            else -> error("Unexpected boundary")
        }
        if (boundary == TaskPersistenceBoundary.ResultStepSaved) assertTrue(sourceSteps.any { it.outcome == "returned" })
        if (checkpoint) {
            assertEquals(1, budget.totalToolCalls)
            assertEquals(1, budget.totalModelCalls)
            assertEquals(if (boundary == TaskPersistenceBoundary.CheckpointCommitted) 2 else 1, budget.stageNumber)
            assertEquals(if (boundary == TaskPersistenceBoundary.CheckpointCommitted) 1 else 0, budget.checkpointRefs.size)
        }
        val contexts = JSONObject()
        rows.filter { it.executionContext != null }.forEach { contexts.put(it.id, it.executionContext) }
        files.persistMarker(files.readMarker().put("runId", run.id).put("seedMetadata", metadata.encode())
            .put("sourceContexts", contexts).put("dispatchCalls", calls).put("receiptRows", receipts)
            .put("deviceCalls", fixture.source.deviceCount()).put("seedReady", true))
        effectReport("seed-ready-for-external-process-death boundary=${boundary.name} scenario=${scenario.argument} pid=${Process.myPid()} run=${run.id}")
    }

    @Test fun bColdStartChecksBoundary() = runBlocking<Unit> {
        enabled()
        val files = files
        val saved = files.readMarker()
        check(saved.getString("namespace") == files.namespace && saved.getString("boundary") == boundary.name && saved.getString("scenario") == scenario.argument)
        assertTrue(saved.getBoolean("seedReady"))
        assertNotEquals("The cold method requires a genuinely different instrumentation process", saved.getInt("seedPid"), Process.myPid())
        val owner = saved.getString("owner")
        val runId = saved.getString("runId")
        val seed = requireNotNull(TaskRunMetadata.decode(saved.getString("seedMetadata")))
        val seedBudget = requireNotNull(seed.budget)
        // A larger profile must not replace the smaller limits stored by the seed.
        val fixture = TaskEffectFixture(files, scenario, limits = effectFixtureLimits().copy(
            stageToolCalls = 100, totalToolCalls = 200, totalModelCalls = 300, maxStages = 20))
        try {
            fixture.open(owner)
            effectAwait("cold-start-marks-old-run-interrupted-without-dispatch") {
                fixture.journal.get(runId)?.state == "Interrupted" && fixture.runtime.uiState.value.taskBudget != null
            }
            assertEquals(0, fixture.client.requests.size)
            assertEquals(saved.getInt("dispatchCalls"), fixture.source.dispatchCount())
            assertEquals(saved.getInt("receiptRows"), fixture.source.rows().size)
            assertEquals(saved.getInt("deviceCalls"), fixture.source.deviceCount())
            val restored = requireNotNull(TaskRunMetadata.decode(fixture.journal.get(runId)?.metadataJson))
            val restoredBudget = requireNotNull(restored.budget)
            assertEquals(seed.logicalTaskId, restored.logicalTaskId)
            assertEquals(seedBudget.totalToolCalls, restoredBudget.totalToolCalls)
            assertEquals(seedBudget.totalModelCalls, restoredBudget.totalModelCalls)
            assertEquals(seedBudget.totalEstimatedTokens, restoredBudget.totalEstimatedTokens)
            assertEquals(seedBudget.stageNumber, restoredBudget.stageNumber)
            assertEquals(seedBudget.stageToolCalls, restoredBudget.stageToolCalls)
            assertEquals(seedBudget.stageModelCalls, restoredBudget.stageModelCalls)
            assertEquals(seedBudget.checkpointRefs, restoredBudget.checkpointRefs)
            assertEquals(seedBudget.limits, restoredBudget.limits)
            assertTrue(restoredBudget.totalActiveMillis >= seedBudget.totalActiveMillis)
            assertEquals(seed.effects, restored.effects)
            val oldContexts = saved.getJSONObject("sourceContexts")
            val unfinishedStepIds = oldContexts.keys().asSequence().flatMap { id ->
                kotlinx.serialization.json.Json.decodeFromString<List<ExecutionStep>>(oldContexts.getString(id))
                    .filter { it.outcome == "unconfirmed" }.mapNotNull { it.stepId }.asSequence()
            }.toSet()
            // Journal recovery precedes message recovery in startup. Seeing Interrupted on the
            // run alone does not mean the second asynchronous database transaction has finished.
            if (unfinishedStepIds.isNotEmpty()) effectAwait("cold-start-source-step-recovery-is-durable") {
                val current = fixture.chats.getMessages(owner).flatMap(::effectSteps).associateBy { it.stepId }
                unfinishedStepIds.all { current[it]?.outcome == "interrupted_unconfirmed" }
            }
            val coldRows = fixture.chats.getMessages(owner).associateBy { it.id }
            for (id in oldContexts.keys()) {
                val before = oldContexts.getString(id)
                val after = requireNotNull(coldRows[id]?.executionContext)
                val oldSteps = kotlinx.serialization.json.Json.decodeFromString<List<ExecutionStep>>(before)
                val newSteps = kotlinx.serialization.json.Json.decodeFromString<List<ExecutionStep>>(after)
                assertEquals(oldSteps.map { it.stepId }, newSteps.map { it.stepId })
                oldSteps.zip(newSteps).forEach { (old, current) ->
                    assertEquals(old.argumentsSha256, current.argumentsSha256)
                    if (old.outcome == "returned") assertEquals(old, current)
                    else if (old.outcome == "unconfirmed") assertEquals("interrupted_unconfirmed", current.outcome)
                }
            }
            if (confirmation) {
                assertNull("A dead process's action confirmation must never reappear as a live approval", fixture.runtime.uiState.value.pendingDeviceActionConfirmation)
                withContext(Dispatchers.Main) { fixture.runtime.confirmDeviceAction() }
                assertEquals(0, fixture.client.requests.size)
                fixture.client.enqueueTurn(confirmationCall())
                fixture.send("Challenge recovery with the previous confirmation action; old approval cannot authorize it.")
                fixture.awaitIdle(owner, 2)
                assertEquals(0, fixture.source.deviceCount())
                assertTrue(fixture.metadata(owner).pendingEffects.isNotEmpty())
            } else when (boundary) {
                TaskPersistenceBoundary.BeforeIntentSaved -> {
                    assertTrue(restored.effects.isEmpty()); assertTrue(restored.pendingEffects.isEmpty())
                    assertNull(fixture.runtime.uiState.value.taskRecovery)
                    fixture.client.enqueueTurn(effectCall(scenario, "first-real-dispatch-after-preintent-death"))
                    fixture.send("No source intent existed; execute the remaining synthetic operation once.")
                    fixture.awaitIdle(owner, 2)
                    assertEquals(1, fixture.source.dispatchCount())
                    assertEquals(TaskEffectResolution.Applied, fixture.metadata(owner).effects.single().proof?.resolution)
                    verifyAndChallengeApplied(fixture, owner)
                }
                TaskPersistenceBoundary.IntentSaved -> {
                    assertTrue(restored.effects.isEmpty())
                    fixture.client.enqueueTurn(effectCall(scenario, "source-only-intent-must-block"))
                    fixture.send("Challenge the source-only interrupted intent with a new mutating request.")
                    fixture.awaitIdle(owner, 2)
                    assertEquals(0, fixture.source.dispatchCount())
                    assertTrue(fixture.metadata(owner).pendingEffects.isNotEmpty())
                }
                TaskPersistenceBoundary.PendingEffectsSaved -> {
                    val record = restored.effects.single()
                    fixture.source.fence(record)
                    fixture.client.enqueueTurn(effectVerifyCall(record))
                    fixture.send("Read the source's durable fence for the not-dispatched old operation token.")
                    fixture.awaitIdle(owner, 2)
                    assertEquals(TaskEffectResolution.NotApplied, fixture.metadata(owner).effects.single().proof?.resolution)
                    assertTrue(fixture.metadata(owner).pendingEffects.isEmpty())
                    assertEquals(0, fixture.source.dispatchCount())
                    fixture.client.enqueueTurn(effectCall(scenario, "fresh-token-after-cold-authoritative-absence"))
                    fixture.send("Execute the remaining synthetic operation once using a fresh token.")
                    fixture.awaitIdle(owner, 4)
                    assertEquals(1, fixture.source.dispatchCount())
                    assertEquals(1, fixture.source.rows().size)
                    val records = fixture.metadata(owner).effects
                    assertEquals(TaskEffectResolution.NotApplied, records.single { it.effectId == record.effectId }.proof?.resolution)
                    assertEquals(TaskEffectResolution.Applied, records.single { it.effectId != record.effectId }.proof?.resolution)
                    challengeReplay(fixture, owner)
                }
                TaskPersistenceBoundary.ActionReturned, TaskPersistenceBoundary.ResultStepSaved, TaskPersistenceBoundary.ResultMetadataSaved -> {
                    verifyAndChallengeApplied(fixture, owner)
                }
                TaskPersistenceBoundary.BeforeCheckpointCommit, TaskPersistenceBoundary.CheckpointCommitted -> {
                    // The next blocked replay may exhaust this deliberately one-tool stage.
                    // That must pause locally rather than acquiring an unearned new stage.
                    fixture.client.enqueue(ChatDelta.ToolCalls(listOf(effectCall(scenario, "checkpoint-replay"))))
                    fixture.client.enqueue(ChatDelta.Content("SYNTHETIC_CHECKPOINT_REPLAY_BLOCKED"), ChatDelta.Done)
                    fixture.send("Challenge replay after the saved stage boundary.")
                    fixture.awaitIdle(owner, 1)
                    assertEquals(1, fixture.source.dispatchCount())
                    assertEquals(1, fixture.source.rows().size)
                    val after = fixture.metadata(owner)
                    assertEquals(seed.logicalTaskId, after.logicalTaskId)
                    assertEquals(seedBudget.limits, after.budget?.limits)
                    val refs = requireNotNull(after.budget).checkpointRefs
                    assertTrue(refs.containsAll(seedBudget.checkpointRefs))
                    val completedReferences = fixture.chats.getMessages(owner).flatMap { row ->
                        effectSteps(row).filter { it.outcome == "returned" }.map { "${row.id}:${it.stepId}" }
                    }
                    assertTrue(completedReferences.containsAll(refs))
                    assertEquals(TaskEffectResolution.Applied, after.effects.single().proof?.resolution)
                }
                else -> error("Unexpected boundary")
            }
            assertNotEquals("One exact source receipt cannot certify the entire business goal", TaskGoalStatus.Verified, fixture.metadata(owner).goalStatus)
            assertEquals(0, fixture.source.deviceCount())
            files.persistMarker(saved.put("coldPid", Process.myPid()).put("coldVerified", true)
                .put("finalDispatchCalls", fixture.source.dispatchCount()).put("finalReceiptRows", fixture.source.rows().size))
            effectReport("cold-boundary-verified-without-replay boundary=${boundary.name} scenario=${scenario.argument} pid=${Process.myPid()}")
        } finally { fixture.close() }
    }

    private suspend fun verifyAndChallengeApplied(fixture: TaskEffectFixture, owner: String) {
        val record = fixture.metadata(owner).effects.single { it.proof?.resolution != TaskEffectResolution.NotApplied }
        val requests = fixture.client.requests.size
        fixture.client.enqueueTurn(effectVerifyCall(record))
        fixture.send("Read the exact authoritative operation receipt without re-submission.")
        fixture.awaitIdle(owner, requests + 2)
        val verified = fixture.metadata(owner)
        assertEquals(TaskEffectResolution.Applied, verified.effects.single { it.effectId == record.effectId }.proof?.resolution)
        assertTrue(verified.pendingEffects.isEmpty())
        assertEquals(TaskGoalStatus.PartiallyVerified, verified.goalStatus)
        assertEquals(1, fixture.source.dispatchCount())
        assertEquals(1, fixture.source.rows().size)
        challengeReplay(fixture, owner)
    }

    private suspend fun challengeReplay(fixture: TaskEffectFixture, owner: String) {
        val requests = fixture.client.requests.size
        fixture.client.enqueueTurn(effectCall(scenario, "cold-deliberate-same-intent-replay"))
        fixture.send("Deliberately request the identical operation again with a different model call ID.")
        fixture.awaitIdle(owner, requests + 2)
        assertEquals(1, fixture.source.dispatchCount())
        assertEquals(1, fixture.source.rows().size)
        assertTrue(fixture.client.requests.last().messages.any { it.role == "tool" &&
            it.toolCallId == "cold-deliberate-same-intent-replay" && it.content.orEmpty().contains("blocked", ignoreCase = true) })
    }

    @Test fun zDeleteOnlyIsolatedFixtureDatabases() = runBlocking<Unit> {
        enabled()
        val files = files
        files.deleteOnlyOwnFiles()
        effectReport("isolated-guid-runtime-source-databases-and-marker-deleted")
    }

    private fun confirmationCall() = ChatToolCall("synthetic-confirmation-action", AgentToolDefinitions.ForceStopAppToolName,
        """{"app_name":"R4 synthetic confirmation fixture"}""")
}
