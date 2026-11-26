package com.flowchat.app.domain

import com.flowchat.app.domain.chat.AttachmentTools
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskContextTools
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.chat.TaskGoalStatus
import com.flowchat.app.domain.chat.TaskRecoveryConcern
import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.terminal.TerminalToolDefinitions
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecoveryGateTest {
    @Test fun recoveryAllowsActualObservationToolsButRejectsChangesAndUnknownNames() {
        val observations = listOf(
            AgentToolDefinitions.ObserveScreenToolName,
            AgentToolDefinitions.WaitForScreenToolName,
            AgentToolDefinitions.DeviceStatusToolName,
            AgentToolDefinitions.ForegroundAppToolName,
            AgentToolDefinitions.AppUsageSummaryToolName,
            AgentToolDefinitions.RecentAppActivityToolName,
            AgentToolDefinitions.WebSearchToolName,
            AttachmentTools.Read,
            TaskContextTools.Read,
            WorkspaceToolDefinitions.Status,
            WorkspaceToolDefinitions.ListFiles,
            WorkspaceToolDefinitions.Read,
            WorkspaceToolDefinitions.Stat,
            WorkspaceToolDefinitions.Search,
            WorkspaceToolDefinitions.InspectExecution,
            TerminalToolDefinitions.Status,
            TerminalToolDefinitions.Read
        )
        observations.forEach { assertTrue("Recovery must permit $it", TaskRecoveryGate.isReadOnly(it)) }

        val changesOrUnknown = listOf(
            AgentToolDefinitions.OpenAppToolName,
            AgentToolDefinitions.TapUiElementToolName,
            AgentToolDefinitions.InputTextToolName,
            AgentToolDefinitions.SetMediaVolumeToolName,
            WorkspaceToolDefinitions.Write,
            WorkspaceToolDefinitions.Rename,
            WorkspaceToolDefinitions.Import,
            WorkspaceToolDefinitions.Publish,
            TerminalToolDefinitions.Run,
            TerminalToolDefinitions.Stop,
            "read_unknown_resource", "observe_screen_and_tap", "OBSERVE_SCREEN", ""
        )
        changesOrUnknown.forEach { assertFalse("Recovery must reject $it", TaskRecoveryGate.isReadOnly(it)) }
    }

    @Test fun legacyRecoverySeparatesInterruptedReadsFromUnconfirmedEffects() {
        val read = ExecutionStep(AgentToolDefinitions.ObserveScreenToolName, "{}", "interrupted_unconfirmed", "")
        val interruptedWrite = ExecutionStep(AgentToolDefinitions.SetMediaVolumeToolName,
            """{"percent":41}""", "interrupted_unconfirmed", "", stepId = "write-interrupted")
        val unverifiedReturn = ExecutionStep(WorkspaceToolDefinitions.Write,
            """{"path":"draft.txt","text":"hello"}""", "returned", "save could not be verified",
            stepId = "write-returned", effectState = "Unverified")
        val dispatched = ExecutionStep(AgentToolDefinitions.PressBackToolName,
            "{}", "returned", "dispatched", effectState = "Dispatched")
        val unknown = ExecutionStep("future_tool", "{}", "unconfirmed", "")
        val concerns = TaskRecoveryGate.legacyConcerns(listOf(user(), evidence("legacy", listOf(
            read, interruptedWrite, unverifiedReturn, dispatched, unknown,
            read.copy(outcome = "returned", effectState = "Unverified")
        ))))

        assertEquals(listOf("write-interrupted", "write-returned", "legacy:4"), concerns.map { it.stepId })
        assertEquals(listOf(AgentToolDefinitions.SetMediaVolumeToolName, WorkspaceToolDefinitions.Write, "future_tool"),
            concerns.map { it.tool })
        assertTrue(concerns.all { it.messageId == "legacy" && it.reason == "result_unconfirmed" })
        assertEquals(TaskRecoveryGate.fingerprint(interruptedWrite.arguments), concerns.first().argumentsSha256)
    }

    @Test fun malformedLegacyEvidenceCreatesAnExplicitConcernInsteadOfSilentlyAllowingChanges() {
        listOf("not-json", "{}", """[{"tool":"workspace_write"}]""").forEach { malformed ->
            val concerns = TaskRecoveryGate.legacyConcerns(listOf(user(),
                evidence("broken", emptyList()).copy(executionContext = malformed)))
            assertEquals(1, concerns.size)
            assertEquals("broken:unreadable", concerns.single().stepId)
            assertEquals("broken", concerns.single().messageId)
            assertEquals("unknown", concerns.single().tool)
            assertEquals("evidence_unreadable", concerns.single().reason)
            assertEquals(TaskRecoveryGate.fingerprint(malformed), concerns.single().argumentsSha256)
        }
    }

    @Test fun legacyReturnedFailuresAndTextOnlyUnverifiedOutcomesRequireReviewUnlessNotDispatched() {
        val unverified = ExecutionStep(AgentToolDefinitions.InputTextToolName,
            """{"index":2,"text":"hello"}""", "returned",
            "success: action was sent\naction_outcome=Unverified", stepId = "text-only-unverified")
        val failed = ExecutionStep(TerminalToolDefinitions.Run,
            """{"command":"touch marker; exit 1","environment":"linux"}""", "returned",
            "Tool call failed: connection lost after command dispatch", stepId = "legacy-failure")
        val rejected = ExecutionStep(AgentToolDefinitions.TapUiElementToolName,
            "{}", "returned", "Tool call failed: stale screen\naction_outcome=NotDispatched", stepId = "not-dispatched")
        val readFailure = ExecutionStep(WorkspaceToolDefinitions.Read,
            "{}", "returned", "failed: no such file", stepId = "read-failure")
        val concerns = TaskRecoveryGate.legacyConcerns(listOf(user(),
            evidence("legacy-returned", listOf(unverified, failed, rejected, readFailure))))
        assertEquals(listOf("text-only-unverified", "legacy-failure"), concerns.map { it.stepId })
    }

    @Test fun fingerprintsNormalizeNestedObjectKeysButPreserveArgumentChangesAndArrayOrder() {
        val first = """{"z":{"b":true,"a":[1,2]},"a":1}"""
        val reordered = """ { "a": 1, "z": { "a": [1, 2], "b": true } } """
        val fingerprint = TaskRecoveryGate.fingerprint(first)
        assertEquals("7b7c96f31cfb4885995f29f3e5268df0b015df32ea73f80c7f35d580cc3fd3cf", fingerprint)
        assertEquals(fingerprint, TaskRecoveryGate.fingerprint(reordered))
        listOf(
            """{"z":{"b":false,"a":[1,2]},"a":1}""",
            """{"z":{"b":true,"a":[2,1]},"a":1}""",
            """{"z":{"b":true,"a":[1,2]},"a":2}""",
            """{"z":{"b":true,"a":[1,2]},"a":1,"extra":null}"""
        ).forEach { changed -> assertNotEquals(fingerprint, TaskRecoveryGate.fingerprint(changed)) }
        assertNotEquals(TaskRecoveryGate.fingerprint("malformed one"), TaskRecoveryGate.fingerprint("malformed two"))
    }

    @Test fun concernsRetainFullArgumentIdentityEvenWhenVisibleExecutionEvidenceIsClipped() {
        val arguments = """{"text":"${"x".repeat(8_000)}"}"""
        val call = ChatToolCall("provider-call", WorkspaceToolDefinitions.Write, arguments)
        val concern = TaskRecoveryGate.concern("step", "message", call)
        assertEquals("step", concern.stepId)
        assertEquals("message", concern.messageId)
        assertEquals(WorkspaceToolDefinitions.Write, concern.tool)
        assertEquals(TaskRecoveryGate.fingerprint(arguments), concern.argumentsSha256)
        assertNotEquals(TaskRecoveryGate.fingerprint(TaskExecutionHistory.pending(call).arguments), concern.argumentsSha256)
    }

    @Test fun userReviewBlocksTheSameEffectAfterARefreshedScreenButPermitsDifferentRemainingWork() {
        val original = ChatToolCall("original", AgentToolDefinitions.InputTextToolName,
            """{"screen_id":"before","index":2,"text":"hello"}""")
        val reviewed = TaskRecoveryGate.concern("old-step", "old-message", original)
        val refreshed = original.copy(id = "new-provider-id",
            arguments = """ { "text": "hello", "index": 2, "screen_id": "after" } """)

        assertNotEquals(TaskRecoveryGate.fingerprint(original.arguments), TaskRecoveryGate.fingerprint(refreshed.arguments))
        assertEquals(TaskRecoveryGate.replayFingerprint(original.arguments), reviewed.replayFingerprintSha256)
        assertTrue(TaskRecoveryGate.isAcknowledgedReplay(original, listOf(reviewed)))
        assertTrue(TaskRecoveryGate.isAcknowledgedReplay(refreshed, listOf(reviewed)))
        assertFalse(TaskRecoveryGate.isAcknowledgedReplay(refreshed.copy(
            arguments = """{"screen_id":"after","index":2,"text":"remaining text"}"""), listOf(reviewed)))
        assertFalse(TaskRecoveryGate.isAcknowledgedReplay(refreshed.copy(name = AgentToolDefinitions.TapUiElementToolName),
            listOf(reviewed)))

        val oldSchemaConcern = reviewed.copy(replayFingerprintSha256 = null)
        assertTrue("Older concerns still block exact argument replay",
            TaskRecoveryGate.isAcknowledgedReplay(original, listOf(oldSchemaConcern)))
        assertFalse(TaskRecoveryGate.isAcknowledgedReplay(original, emptyList()))
    }

    @Test fun metadataRoundTripPreservesTaskLineageEvidenceAndUserReviewWithoutClaimingVerification() {
        val pending = TaskRecoveryConcern("pending", "message-a", WorkspaceToolDefinitions.Write, "abc",
            replayFingerprintSha256 = "semantic-abc")
        val reviewed = TaskRecoveryConcern("reviewed", "message-b", AgentToolDefinitions.InputTextToolName, "def")
        val metadata = TaskRunMetadata(logicalTaskId = "task", continuedFromRunId = "previous-run",
            goalStatus = TaskGoalStatus.PartiallyVerified, stopReason = "recovery_verification_required",
            pendingEffects = listOf(pending), acknowledgedEffects = listOf(reviewed),
            recoveryObservationMessageId = "observation", endedByUser = true, verifiedStepCount = 2, toolCallCount = 4)
        assertEquals(metadata, TaskRunMetadata.decode(metadata.encode()))
        assertEquals(TaskGoalStatus.Unverified, TaskRunMetadata.decode(
            TaskRunMetadata(logicalTaskId = "reviewed-only", acknowledgedEffects = listOf(reviewed)).encode())?.goalStatus)
    }

    @Test fun metadataAcceptsOldFieldsAndUnknownExtensionsButRejectsUnsupportedVersionsAndBrokenRecords() {
        val old = TaskRunMetadata.decode("""{"logicalTaskId":"legacy-task","extension":{"future":true}}""")
        assertEquals(TaskRunMetadata(logicalTaskId = "legacy-task"), old)
        val extendedConcern = TaskRunMetadata.decode("""{"version":1,"logicalTaskId":"task",
            "pendingEffects":[{"stepId":"step","messageId":"message","tool":"run_command",
            "argumentsSha256":"abc","unknownField":true}],"unknownField":42}""")
        assertEquals(listOf(TaskRecoveryConcern("step", "message", TerminalToolDefinitions.Run, "abc")),
            extendedConcern?.pendingEffects)
        listOf(null, "not-json", "{}", """{"version":2,"logicalTaskId":"future"}""",
            """{"version":0,"logicalTaskId":"unsupported"}""").forEach { assertNull(TaskRunMetadata.decode(it)) }
    }

    private fun user() = Message(id = "user", conversationId = "conversation", role = MessageRole.User, content = "task")

    private fun evidence(id: String, steps: List<ExecutionStep>) = Message(id = id,
        conversationId = "conversation", role = MessageRole.Assistant, content = "",
        executionContext = TaskExecutionHistory.encode(steps))
}
