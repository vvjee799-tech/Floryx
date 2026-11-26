package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskGoalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic exact-recipient/collection acceptance. This does not send to any external account. */
class TaskEffectDiagnosticTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runTaskEffectDiagnostics") == "true")

    @Test fun submittedMessageUsesExactFixtureRecipientAndSurvivesReplayAndNewTaskBoundaries() = runBlocking<Unit> {
        enabled(); successAndReplay(EffectScenario.Message)
    }
    @Test fun createdRecordUsesExactFixtureCollectionAndSurvivesReplayAndNewTaskBoundaries() = runBlocking<Unit> {
        enabled(); successAndReplay(EffectScenario.Record)
    }

    private suspend fun successAndReplay(scenario: EffectScenario) = isolated(scenario) { fixture, owner ->
        fixture.client.enqueueTurn(effectCall(scenario, "initial-exact-operation"))
        fixture.send("Synthetic ${scenario.argument}: submit this exact content once to ${scenario.target}.")
        fixture.awaitIdle(owner, 2)
        val metadata = fixture.metadata(owner)
        val record = metadata.effects.single()
        val proof = requireNotNull(record.proof)
        assertEquals(TaskEffectResolution.Applied, proof.resolution)
        assertEquals(record.effectId, proof.effectId)
        assertEquals(record.intent, proof.intent)
        assertEquals(fixture.files.namespace, proof.intent.sourceId)
        assertEquals(scenario.target, proof.intent.targetId)
        assertEquals(effectContentSha256(scenario.content), proof.intent.contentSha256)
        assertEquals(scenario.kind, proof.intent.kind)
        assertEquals(TaskGoalStatus.PartiallyVerified, metadata.goalStatus)
        assertTrue(metadata.pendingEffects.isEmpty())
        assertEquals(1, fixture.source.dispatchCount())
        assertEquals(scenario.content, fixture.source.rows().single().content)

        fixture.client.enqueueTurn(effectCall(scenario, "different-model-call-same-intent"))
        fixture.send("Continue the same synthetic task and attempt exactly the same operation again.")
        fixture.awaitIdle(owner, 4)
        assertEquals("Different model call IDs must not recreate one already-applied operation", 1, fixture.source.dispatchCount())
        assertEquals(1, fixture.source.rows().size)
        assertEquals(metadata.logicalTaskId, fixture.metadata(owner).logicalTaskId)
        assertEquals(listOf(record.effectId), fixture.metadata(owner).effects.map { it.effectId })
        assertTrue(fixture.client.requests.last().messages.any { it.role == "tool" &&
            it.toolCallId == "different-model-call-same-intent" && it.content.orEmpty().contains("blocked", ignoreCase = true) })

        withContext(Dispatchers.Main) { fixture.runtime.endInterruptedTask() }
        effectAwait("explicit-end-retains-source-and-task-evidence") {
            fixture.metadata(owner).endedByUser && fixture.chats.getMessages(owner).lastOrNull()?.content ==
                "Task ended. Existing records and files have been retained."
        }
        assertEquals(listOf(record.effectId), fixture.metadata(owner).effects.map { it.effectId })
        fixture.client.enqueueTurn(effectCall(scenario, "explicitly-new-task"))
        fixture.send("Start an explicitly new synthetic task with the same fixture recipient/collection and content.")
        fixture.awaitIdle(owner, 6)
        val next = fixture.metadata(owner)
        assertNotEquals(metadata.logicalTaskId, next.logicalTaskId)
        assertNull(next.continuedFromRunId)
        assertEquals(2, fixture.source.dispatchCount())
        assertEquals(2, fixture.source.rows().size)
        assertNotEquals(record.effectId, next.effects.single().effectId)
        assertEquals(TaskEffectResolution.Applied, next.effects.single().proof?.resolution)
        assertNotEquals(TaskGoalStatus.Verified, next.goalStatus)
        effectReport("${scenario.argument}-exact-source-replay-and-explicit-new-task-passed")
    }

    @Test fun wrongFixtureRecipientOrCollectionNeverVerifiesEitherScenario() = runBlocking<Unit> {
        enabled(); mismatch(EffectSourceMode.WrongTarget)
    }
    @Test fun changedStoredContentNeverVerifiesEitherScenario() = runBlocking<Unit> {
        enabled(); mismatch(EffectSourceMode.WrongContent)
    }
    private suspend fun mismatch(mode: EffectSourceMode) {
        for (scenario in EffectScenario.entries) isolated(scenario) { fixture, owner ->
            fixture.adapter.mode = mode
            fixture.client.enqueueTurn(effectCall(scenario))
            fixture.send("Synthetic mismatch acceptance; verify exact target and content independently.")
            fixture.awaitIdle(owner, 2)
            val record = fixture.metadata(owner).effects.single()
            assertNull("A receipt for different stored fields cannot verify the requested operation", record.proof)
            assertEquals(1, fixture.metadata(owner).pendingEffects.size)
            assertNotEquals(TaskGoalStatus.Verified, fixture.metadata(owner).goalStatus)
            val stored = fixture.source.rows().single()
            if (mode == EffectSourceMode.WrongTarget) assertNotEquals(record.intent.targetId, stored.intent.targetId)
            else assertNotEquals(record.intent.contentSha256, stored.intent.contentSha256)
            fixture.client.enqueueTurn(effectVerifyCall(record), effectCall(scenario, "mismatch-replay"))
            fixture.send("Read the authoritative fixture receipt, then deliberately try to repeat the uncertain request.")
            fixture.awaitIdle(owner, 4)
            assertEquals(1, fixture.source.dispatchCount())
            assertNull(fixture.metadata(owner).effects.single().proof)
            assertEquals(1, fixture.metadata(owner).pendingEffects.size)
            effectReport("${scenario.argument}-$mode-rejected-without-replay")
        }
    }

    @Test fun callbackLossIsResolvedByReadOnlyReceiptQueryWithoutResendingEitherScenario() = runBlocking<Unit> {
        enabled()
        for (scenario in EffectScenario.entries) isolated(scenario) { fixture, owner ->
            fixture.adapter.mode = EffectSourceMode.CallbackLost
            fixture.adapter.inspectionEnabled = false
            fixture.client.enqueueTurn(effectCall(scenario))
            fixture.send("Synthetic operation whose callback is lost after commit.")
            fixture.awaitIdle(owner, 2)
            val old = fixture.metadata(owner).effects.single()
            assertNull(old.proof)
            assertEquals(1, fixture.metadata(owner).pendingEffects.size)
            assertEquals(1, fixture.source.rows().size)
            assertEquals(1, fixture.source.dispatchCount())
            fixture.adapter.inspectionEnabled = true
            fixture.client.enqueueTurn(effectVerifyCall(old))
            fixture.send("Recover by reading the exact operation receipt; do not resubmit.")
            fixture.awaitIdle(owner, 4)
            val recovered = fixture.metadata(owner)
            assertEquals(TaskEffectResolution.Applied, recovered.effects.single().proof?.resolution)
            assertTrue(recovered.pendingEffects.isEmpty())
            assertEquals(TaskGoalStatus.PartiallyVerified, recovered.goalStatus)
            assertEquals(1, fixture.source.dispatchCount())
            fixture.client.enqueueTurn(effectCall(scenario, "lost-callback-replay"))
            fixture.send("Deliberately repeat the same operation after its receipt was verified.")
            fixture.awaitIdle(owner, 6)
            assertEquals(1, fixture.source.dispatchCount())
            assertEquals(1, fixture.source.rows().size)
            effectReport("${scenario.argument}-lost-callback-recovered-from-read-only-source")
        }
    }

    @Test fun missingOrAmbiguousReceiptsStayPendingAndNeverAuthorizeReplay() = runBlocking<Unit> {
        enabled()
        for (scenario in EffectScenario.entries) for (mode in listOf(EffectSourceMode.MissingReceipt, EffectSourceMode.AmbiguousReceipt)) {
            isolated(scenario) { fixture, owner ->
                fixture.adapter.mode = mode
                fixture.client.enqueueTurn(effectCall(scenario))
                fixture.send("Synthetic missing/ambiguous receipt test.")
                fixture.awaitIdle(owner, 2)
                val record = fixture.metadata(owner).effects.single()
                assertNull(record.proof)
                assertEquals(if (mode == EffectSourceMode.MissingReceipt) 0 else 2, fixture.source.rows().size)
                fixture.client.enqueueTurn(effectVerifyCall(record), effectCall(scenario, "unresolved-replay"))
                fixture.send("Read the source, then challenge the unresolved-operation gate.")
                fixture.awaitIdle(owner, 4)
                assertNull(fixture.metadata(owner).effects.single().proof)
                assertEquals(1, fixture.metadata(owner).pendingEffects.size)
                assertEquals(1, fixture.source.dispatchCount())
                effectReport("${scenario.argument}-$mode-stays-unverified")
            }
        }
    }

    @Test fun durableSourceFenceProvesNotAppliedAndAllowsExactlyOneFreshRetry() = runBlocking<Unit> {
        enabled()
        for (scenario in EffectScenario.entries) isolated(scenario) { fixture, owner ->
            fixture.adapter.mode = EffectSourceMode.MissingReceipt
            fixture.client.enqueueTurn(effectCall(scenario))
            fixture.send("Synthetic operation did not commit; a missing row alone is not proof.")
            fixture.awaitIdle(owner, 2)
            val old = fixture.metadata(owner).effects.single()
            assertNull(old.proof)
            fixture.source.fence(old)
            fixture.client.enqueueTurn(effectVerifyCall(old))
            fixture.send("Read the already durable source fence for the old exact token.")
            fixture.awaitIdle(owner, 4)
            assertEquals(TaskEffectResolution.NotApplied, fixture.metadata(owner).effects.single().proof?.resolution)
            assertTrue(fixture.metadata(owner).pendingEffects.isEmpty())
            assertEquals(1, fixture.source.dispatchCount())
            fixture.adapter.mode = EffectSourceMode.Normal
            fixture.client.enqueueTurn(effectCall(scenario, "new-token-after-authoritative-absence"))
            fixture.send("Retry remaining work once, using a fresh operation token.")
            fixture.awaitIdle(owner, 6)
            val records = fixture.metadata(owner).effects
            assertEquals(2, records.size)
            assertEquals(TaskEffectResolution.NotApplied, records.single { it.effectId == old.effectId }.proof?.resolution)
            assertEquals(TaskEffectResolution.Applied, records.single { it.effectId != old.effectId }.proof?.resolution)
            assertEquals(2, fixture.source.dispatchCount())
            assertEquals(1, fixture.source.rows().size)
            val lateDispatch = runCatching { fixture.adapter.dispatch(old.effectId, scenario.arguments()) }
            assertTrue("The source must prevent a late commit for the old token, even outside the runtime gate", lateDispatch.isFailure)
            assertEquals(1, fixture.source.rows().size)
            assertTrue(fixture.source.rows(old.effectId).isEmpty())
            effectReport("${scenario.argument}-fenced-absence-allows-one-new-effect-and-excludes-late-commit")
        }
    }

    @Test fun missingOrUpgradedAdaptersCannotReinterpretSavedReceiptsOrDispatchAgain() = runBlocking<Unit> {
        enabled()
        for (scenario in EffectScenario.entries) for (available in listOf(false, true)) {
            val files = EffectFixtureFiles.fresh(context)
            var fixture = TaskEffectFixture(files, scenario)
            try {
                val owner = fixture.newConversation()
                fixture.adapter.mode = EffectSourceMode.CallbackLost
                fixture.adapter.inspectionEnabled = false
                fixture.client.enqueueTurn(effectCall(scenario))
                fixture.send("Synthetic version-one callback-loss operation.")
                fixture.awaitIdle(owner, 2)
                val old = fixture.metadata(owner).effects.single()
                fixture.close()
                fixture = TaskEffectFixture(files, scenario, adapterVersion = if (available) 2 else 1, adapterAvailable = available)
                fixture.open(owner)
                assertEquals(0, fixture.client.requests.size)
                fixture.client.enqueueTurn(effectVerifyCall(old), effectCall(scenario, "adapter-change-replay"))
                fixture.send("An unavailable or upgraded adapter must not reinterpret prior operation evidence.")
                fixture.awaitIdle(owner, 2)
                val metadata = fixture.metadata(owner)
                assertEquals(old.intent, metadata.effects.single().intent)
                assertNull(metadata.effects.single().proof)
                assertEquals(1, metadata.pendingEffects.size)
                assertEquals(1, fixture.source.dispatchCount())
                assertEquals(1, fixture.source.rows().size)
                assertNotEquals(TaskGoalStatus.Verified, metadata.goalStatus)
                effectReport("${scenario.argument}-adapter-${if (available) "upgraded" else "unavailable"}-keeps-gate")
            } finally { fixture.close(); files.deleteOnlyOwnFiles() }
        }
    }

    @Test fun malformedPersistedProofStaysPendingUntilTheExactSourceReceiptRepairsIt() = runBlocking<Unit> {
        enabled()
        for (corruption in listOf("wrong-effect-id", "empty-absence-receipt")) {
            val files = EffectFixtureFiles.fresh(context)
            val scenario = EffectScenario.Message
            var fixture = TaskEffectFixture(files, scenario)
            try {
                val owner = fixture.newConversation()
                fixture.client.enqueueTurn(effectCall(scenario))
                fixture.send("Create one isolated synthetic message before testing persisted-proof corruption.")
                fixture.awaitIdle(owner, 2)
                val original = fixture.metadata(owner)
                val record = original.effects.single()
                val accepted = requireNotNull(record.proof)
                assertEquals(TaskEffectResolution.Applied, accepted.resolution)
                val oldRun = requireNotNull(fixture.journal.latest(owner))
                val oldExecution = fixture.chats.getMessages(owner).associate { it.id to it.executionContext }
                val malformed = if (corruption == "wrong-effect-id") accepted.copy(effectId = "unknown")
                    else accepted.copy(resolution = TaskEffectResolution.NotApplied, receiptId = "")
                val corruptedJson = original.copy(pendingEffects = emptyList(),
                    effects = listOf(record.copy(proof = malformed))).encode()
                fixture.journal.updateMetadata(oldRun.id, corruptedJson)
                fixture.close()

                fixture = TaskEffectFixture(files, scenario)
                fixture.adapter.inspectionEnabled = false
                fixture.open(owner)
                assertEquals(0, fixture.client.requests.size)
                val firstVerify = effectVerifyCall(record)
                fixture.client.enqueueTurn(firstVerify)
                fixture.send("Read the saved operation without a source receipt; malformed stored proof must stay unverified.")
                fixture.awaitIdle(owner, 2)
                val unresolved = fixture.metadata(owner)
                assertNull(unresolved.effects.single().proof)
                assertEquals(listOf(record.stepId), unresolved.pendingEffects.map { it.stepId })
                assertEquals(original.verifiedStepCount, unresolved.verifiedStepCount)
                assertTrue(fixture.client.requests.last().messages.any { it.role == "tool" &&
                    it.toolCallId == firstVerify.id && it.content.orEmpty().contains("effect_resolution=Unverified") })
                assertEquals(1, fixture.source.dispatchCount())
                assertEquals(1, fixture.source.rows().size)

                // Another unresolved operation must survive repair of this one exact source token.
                val unrelated = com.flowchat.app.domain.chat.TaskRecoveryConcern("unrelated-step", "unrelated-message",
                    "input_text", com.flowchat.app.domain.chat.TaskRecoveryGate.fingerprint("unrelated synthetic input"))
                val unresolvedRun = requireNotNull(fixture.journal.latest(owner))
                fixture.journal.updateMetadata(unresolvedRun.id,
                    unresolved.copy(pendingEffects = unresolved.pendingEffects + unrelated).encode())
                fixture.adapter.inspectionEnabled = true
                val repairVerify = effectVerifyCall(record)
                fixture.client.enqueueTurn(repairVerify)
                fixture.send("Read the true source receipt and repair only its exact pending operation.")
                fixture.awaitIdle(owner, 4)
                val repaired = fixture.metadata(owner)
                val repairedProof = requireNotNull(repaired.effects.single().proof)
                assertEquals(TaskEffectResolution.Applied, repairedProof.resolution)
                assertEquals(record.effectId, repairedProof.effectId)
                assertEquals(record.intent, repairedProof.intent)
                assertEquals(accepted.receiptId, repairedProof.receiptId)
                assertEquals(listOf(unrelated), repaired.pendingEffects)
                assertNotEquals(TaskGoalStatus.Verified, repaired.goalStatus)
                assertEquals(1, fixture.source.dispatchCount())
                assertEquals(1, fixture.source.rows().size)
                assertEquals(corruptedJson, fixture.journal.get(oldRun.id)?.metadataJson)
                val retainedMessages = fixture.chats.getMessages(owner).associateBy { it.id }
                oldExecution.forEach { (id, execution) -> assertEquals(execution, retainedMessages[id]?.executionContext) }
                effectReport("malformed-persisted-$corruption-requires-source-repair-without-resubmit")
            } finally { fixture.close(); files.deleteOnlyOwnFiles() }
        }
    }

    @Test fun upgradingAnAdapterCannotResubmitAnAlreadyAppliedOperation() = runBlocking<Unit> {
        enabled()
        for (scenario in EffectScenario.entries) {
            val files = EffectFixtureFiles.fresh(context)
            var fixture = TaskEffectFixture(files, scenario)
            try {
                val owner = fixture.newConversation()
                fixture.client.enqueueTurn(effectCall(scenario))
                fixture.send("Apply the exact synthetic operation once before upgrading its adapter.")
                fixture.awaitIdle(owner, 2)
                val original = fixture.metadata(owner)
                assertEquals(TaskEffectResolution.Applied, original.effects.single().proof?.resolution)
                fixture.close()
                fixture = TaskEffectFixture(files, scenario, adapterVersion = 2)
                fixture.open(owner)
                fixture.client.enqueueTurn(effectCall(scenario, "upgraded-adapter-same-operation"))
                fixture.send("Continue the same task and challenge a repeat after the adapter upgrade.")
                fixture.awaitIdle(owner, 2)
                assertEquals(1, fixture.source.dispatchCount())
                assertEquals(1, fixture.source.rows().size)
                assertEquals(original.logicalTaskId, fixture.metadata(owner).logicalTaskId)
                assertEquals(original.effects, fixture.metadata(owner).effects)
                assertTrue(fixture.client.requests.last().messages.any { it.role == "tool" &&
                    it.toolCallId == "upgraded-adapter-same-operation" && it.content.orEmpty().contains("blocked", ignoreCase = true) })
            } finally { fixture.close(); files.deleteOnlyOwnFiles() }
        }
    }

    private suspend fun isolated(scenario: EffectScenario, test: suspend (TaskEffectFixture, String) -> Unit) {
        val files = EffectFixtureFiles.fresh(context)
        val fixture = TaskEffectFixture(files, scenario)
        try { test(fixture, fixture.newConversation()) }
        finally { fixture.close(); files.deleteOnlyOwnFiles() }
    }
}
