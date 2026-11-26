package com.flowchat.app.domain

import com.flowchat.app.domain.chat.TaskBudgetStopReason
import com.flowchat.app.domain.chat.TaskEffectDispatchState
import com.flowchat.app.domain.chat.TaskEffectIntent
import com.flowchat.app.domain.chat.TaskEffectProof
import com.flowchat.app.domain.chat.TaskEffectRecord
import com.flowchat.app.domain.chat.TaskEffectRegistry
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskExecutionBudgetLimits
import com.flowchat.app.domain.chat.TaskExecutionBudgetSnapshot
import com.flowchat.app.domain.chat.TaskGoalStatus
import com.flowchat.app.domain.chat.TaskRecoveryConcern
import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.chat.TaskRunMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEffectMetadataCompatibilityTest {
    @Test fun olderVersionOneMetadataDefaultsToNoEffectRecordsWithoutLosingRecoveryOrBudget() {
        val pendingHash = TaskRecoveryGate.fingerprint("pending arguments")
        val reviewedHash = TaskRecoveryGate.fingerprint("reviewed arguments")
        val replayHash = TaskRecoveryGate.fingerprint("reviewed replay arguments")
        // Literal old schema: deliberately contains no effects or postconditionEvidence field.
        val old = """{
            "version":1,"logicalTaskId":"existing-task","continuedFromRunId":"old-run",
            "pendingEffects":[{"stepId":"pending-step","messageId":"old-message","tool":"input_text",
                "argumentsSha256":"$pendingHash"}],
            "acknowledgedEffects":[{"stepId":"reviewed-step","messageId":"reviewed-message","tool":"tap_ui_element",
                "argumentsSha256":"$reviewedHash","reason":"result_unconfirmed","replayFingerprintSha256":"$replayHash"}],
            "toolCallCount":7,"budget":{"totalToolCalls":7,"totalModelCalls":4,"totalEstimatedTokens":900,
                "stageNumber":2,"checkpointRefs":["proof-before-upgrade"]},
            "recoveryBudget":{"totalToolCalls":2,"totalModelCalls":1},
            "lastBudgetEvidenceRef":"proof-before-upgrade"
        }"""
        val restored = TaskRunMetadata.decode(old)!!
        assertEquals("existing-task", restored.logicalTaskId)
        assertEquals("old-run", restored.continuedFromRunId)
        assertEquals(TaskGoalStatus.Unverified, restored.goalStatus)
        assertEquals(listOf(TaskRecoveryConcern("pending-step", "old-message", "input_text", pendingHash)),
            restored.pendingEffects)
        assertEquals(listOf(TaskRecoveryConcern("reviewed-step", "reviewed-message", "tap_ui_element",
            reviewedHash, replayFingerprintSha256 = replayHash)), restored.acknowledgedEffects)
        assertTrue(restored.effects.isEmpty())
        assertTrue(restored.postconditionEvidence.isEmpty())
        assertEquals(7, restored.toolCallCount)
        val budget = restored.budget!!
        assertEquals(7, budget.totalToolCalls)
        assertEquals(4, budget.totalModelCalls)
        assertEquals(900L, budget.totalEstimatedTokens)
        assertEquals(2, budget.stageNumber)
        assertEquals(listOf("proof-before-upgrade"), budget.checkpointRefs)
        assertEquals(2, restored.recoveryBudget!!.totalToolCalls)
        assertEquals("proof-before-upgrade", restored.lastBudgetEvidenceRef)
        assertEquals(restored, TaskRunMetadata.decode(restored.encode()))
    }

    @Test fun currentEffectsRoundTripAlongsidePendingAcknowledgedAndBothBudgetLedgers() {
        val intent = TaskEffectIntent("fixture.records", 3, "create_record", "fixture:records", "collection:one",
            TaskEffectRegistry.contentSha256("synthetic record"))
        val base = TaskEffectRecord("effect:applied", "task", "run", "effect:applied", "message:applied",
            "task_effect_fixture_record", TaskRecoveryGate.fingerprint("{}"), intent,
            TaskEffectDispatchState.Returned)
        val applied = base.copy(proof = TaskEffectProof(base.effectId, intent, TaskEffectResolution.Applied,
            "receipt:applied", "revision:3", 123L))
        val pending = TaskRecoveryConcern("pending-step", "pending-message", "input_text",
            TaskRecoveryGate.fingerprint("pending arguments"))
        val acknowledged = TaskRecoveryConcern("reviewed-step", "reviewed-message", "tap",
            TaskRecoveryGate.fingerprint("reviewed arguments"),
            replayFingerprintSha256 = TaskRecoveryGate.fingerprint("reviewed replay arguments"))
        val budget = TaskExecutionBudgetSnapshot(
            limits = TaskExecutionBudgetLimits(totalToolCalls = 30), totalToolCalls = 11,
            totalModelCalls = 8, totalActiveMillis = 123L, totalEstimatedTokens = 900L,
            stageToolCalls = 4, stageModelCalls = 3, stageNumber = 2, noProgressCount = 1,
            checkpointRefs = listOf("old-checkpoint"), stoppedReason = TaskBudgetStopReason.PendingEffects
        )
        val metadata = TaskRunMetadata(
            logicalTaskId = "task", continuedFromRunId = "previous-run", stopReason = "recovery_required",
            pendingEffects = listOf(pending), acknowledgedEffects = listOf(acknowledged),
            recoveryObservationMessageId = "observation", verifiedStepCount = 2, toolCallCount = 11,
            budget = budget, recoveryBudget = budget.copy(totalToolCalls = 2, checkpointRefs = emptyList()),
            lastBudgetEvidenceRef = "old-checkpoint", progressEvidenceSha256 = listOf("progress-hash"),
            progressScopeSha256 = "scope-hash", effects = listOf(applied,
                base.copy(effectId = "effect:pending", stepId = "effect:pending",
                    dispatchState = TaskEffectDispatchState.Unconfirmed),
                base.copy(effectId = "effect:not-dispatched", stepId = "effect:not-dispatched",
                    dispatchState = TaskEffectDispatchState.NotDispatched))
        )
        val restored = TaskRunMetadata.decode(metadata.encode())!!
        assertEquals(metadata, restored)
        assertEquals(1, restored.version)
        assertEquals(listOf(pending), restored.pendingEffects)
        assertEquals(listOf(acknowledged), restored.acknowledgedEffects)
        assertEquals(budget, restored.budget)
        assertEquals(metadata.recoveryBudget, restored.recoveryBudget)
        assertEquals(applied.proof, restored.effects.first().proof)
        assertNull(restored.effects[1].proof)
        assertEquals(TaskEffectDispatchState.NotDispatched, restored.effects[2].dispatchState)
        assertEquals(TaskGoalStatus.Unverified, restored.goalStatus)
        assertEquals(2, restored.verifiedStepCount)
        assertFalse(restored.endedByUser)
    }

    @Test fun newEffectFieldDoesNotBypassMetadataVersionOrMalformedDataChecks() {
        assertNotNull(TaskRunMetadata.decode("""{"logicalTaskId":"old-minimal-task"}"""))
        assertTrue(TaskRunMetadata.decode("""{"logicalTaskId":"old-minimal-task"}""")!!.effects.isEmpty())
        assertNull(TaskRunMetadata.decode("""{"version":2,"logicalTaskId":"future-task","effects":[]}"""))
        assertNull(TaskRunMetadata.decode("""{"version":1,"logicalTaskId":"task","effects":[{"effectId":"partial"}]}"""))
    }
}
