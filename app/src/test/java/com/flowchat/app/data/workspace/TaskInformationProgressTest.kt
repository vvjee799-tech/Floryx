package com.flowchat.app.data.workspace

import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.workspace.WorkspaceLocation
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskInformationProgressTest {
    private val location = WorkspaceLocation("content://qa/source", "Synthetic evidence")
    private fun observation(start: Long = 0, end: Long = 512, version: String = "v1", source: String = "file") =
        TaskInformationObservation(source, version, "metadata_size_mtime", listOf(TaskInformationRange(start, end)))
    private fun recorded() = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/p0d/candidate12-read-sequence.json")).readText()).jsonArray.map { it.jsonObject }

    @Test fun recordedCandidate12SequenceReproducesLegacyNoProgressAndNowKeepsItsFindings() {
        val old = TaskExecutionBudget(TaskExecutionBudgetLimits.commandCompatible(), clockMillis = { 0 })
        val fixed = TaskExecutionBudget(TaskExecutionBudgetLimits.commandCompatible(), clockMillis = { 0 })
        var information = TaskInformationProgress()
        for ((index, step) in recorded().withIndex()) {
            assertTrue(old.reserveTool().canDispatch)
            old.recordProgress(false) // candidate12 had neither effect nor information keys for these tools.
            val call = ChatToolCall("replay-$index", step.getValue("tool").jsonPrimitive.content, step.getValue("arguments").jsonPrimitive.content)
            val result = Json.parseToJsonElement(step.getValue("result").jsonPrimitive.content).jsonObject
            val evidence = if (call.name == "terminal_status") result.getValue("phone").let {
                TaskInformationObservation("phone", TaskRecoveryGate.fingerprint(it.toString()), "current_android_Build", factSha256 = "phone_facts")
            } else WorkspaceInformationEvidence.capture(call, "qa", location, result)
            val update = information.record(evidence); information = update.progress
            assertTrue(fixed.reserveTool().canDispatch)
            assertTrue("Paused at ${call.name}", fixed.recordProgress(update.observed).canDispatch)
        }
        assertEquals(TaskBudgetStopReason.NoProgress, old.snapshot().stoppedReason)
        assertEquals(8, old.snapshot().noProgressCount)
        assertNull(fixed.snapshot().stoppedReason)
        assertTrue(fixed.snapshot().stageProgressCount >= 5)
        assertEquals(8, fixed.snapshot().totalToolCalls)
        assertTrue(information.sources.any { it.scannedRanges == listOf(TaskInformationRange(0, 6500000)) })
    }

    @Test fun usefulRangesExceedOldThresholdButCannotResetRequestTimeOrTokenBudgets() {
        var state = TaskInformationProgress()
        val budget = TaskExecutionBudget(TaskExecutionBudgetLimits(totalToolCalls = 12), clockMillis = { 0 })
        repeat(12) { i ->
            assertTrue(budget.reserveTool().canDispatch)
            val update = state.record(observation(i * 512L, (i + 1) * 512L)); state = update.progress
            assertTrue(update.observed); budget.recordProgress(update.observed)
        }
        assertEquals(TaskBudgetStopReason.TotalToolCalls, budget.check().reason)
        val tokens = TaskExecutionBudget(TaskExecutionBudgetLimits(totalEstimatedTokens = 10), clockMillis = { 0 })
        assertTrue(tokens.reserveModel(10).canDispatch)
        tokens.recordProgress(true)
        assertEquals(TaskBudgetStopReason.TotalEstimatedTokens, tokens.reserveModel(1).reason)
        var now = 0L
        val time = TaskExecutionBudget(TaskExecutionBudgetLimits(totalActiveMillis = 10), clockMillis = { now })
        now = 10; time.recordProgress(true)
        assertEquals(TaskBudgetStopReason.TotalActiveTime, time.check().reason)
    }

    @Test fun overlapAndIdenticalFactsDoNotRenewTheTask() {
        var state = TaskInformationProgress().record(observation()).progress
        assertFalse(state.record(observation()).observed)
        assertFalse(state.record(observation(1, 500)).observed)
        val added = state.record(observation(400, 600)); assertTrue(added.observed); state = added.progress
        assertEquals(listOf(TaskInformationRange(0, 600)), state.sources.single().ranges)
        val fact = TaskInformationObservation("phone", "build", "android_build", factSha256 = "facts")
        state = state.record(fact).progress
        assertFalse(state.record(fact).observed)
    }

    @Test fun negativeSearchCreditsOnlyNewActualCoverageOfOneExactQuery() {
        fun scan(query: String, start: Long, end: Long) = observation().copy(ranges = emptyList(), scan = TaskInformationScan(query, TaskInformationRange(start, end)))
        var state = TaskInformationProgress().record(scan("literal", 0, 100)).progress
        assertFalse(state.record(scan("literal", 0, 100)).observed)
        for (word in listOf("LITERAL", "synonym", "not_here", "another")) assertFalse(state.record(scan(word, 0, 1000)).observed)
        val continuation = state.record(scan("literal", 99, 200)); assertTrue(continuation.observed); state = continuation.progress
        assertEquals(listOf(TaskInformationRange(0, 200)), state.sources.single().scannedRanges)
        // Coverage remains a bounded scan, never a claim that the whole source was absent.
        assertEquals("literal", state.sources.single().scanQuerySha256)
    }

    @Test fun versionChangeDiscardsCoverageAndRepeatedChurnCannotEarnProgress() {
        var state = TaskInformationProgress().record(observation()).progress
        val changed = state.record(observation(version = "v2")); assertFalse(changed.observed); state = changed.progress
        assertTrue(state.sources.single().ranges.isEmpty())
        val refreshed = state.record(observation(version = "v2")); assertTrue(refreshed.observed); state = refreshed.progress
        for (version in listOf("v3", "v1", "v4", "v4", "v2", "v2")) {
            val update = state.record(observation(version = version)); assertFalse(update.observed); state = update.progress
        }
        assertEquals(2, state.sources.single().versionChanges)
    }

    @Test fun errorsTruncationAndUnsupportedResultsDoNotInventInformation() {
        val steps = recorded()
        val read = steps.first { it.getValue("tool").jsonPrimitive.content == "workspace_read" }
        val call = ChatToolCall("qa", "workspace_read", read.getValue("arguments").jsonPrimitive.content)
        val good = Json.parseToJsonElement(read.getValue("result").jsonPrimitive.content).jsonObject
        assertNotNull(WorkspaceInformationEvidence.capture(call, "qa", location, good))
        assertNull(WorkspaceInformationEvidence.capture(call, "qa", location, JsonObject(good + ("error" to JsonPrimitive("source_changed")))))
        assertNull(WorkspaceInformationEvidence.capture(call, "qa", location, JsonObject(good + ("text" to JsonPrimitive("truncated output")))))
        assertNull(WorkspaceInformationEvidence.capture(call.copy(name = "run_command"), "qa", location, good))
        assertNull(WorkspaceInformationEvidence.capture(call, "qa", location, buildJsonObject { put("success", true); put("progress", true) }))
    }

    @Test fun checkpointRoundTripKeepsDeduplicationAndUnknownEffectsSeparate() {
        val concern = TaskRecoveryConcern("step", "message", "run_command", "unknown")
        val metadata = TaskRunMetadata(logicalTaskId = "task", pendingEffects = listOf(concern),
            informationProgress = TaskInformationProgress().record(observation()).progress)
        val restored = requireNotNull(TaskRunMetadata.decode(metadata.encode()))
        assertEquals(listOf(concern), restored.pendingEffects)
        assertEquals(TaskGoalStatus.Unverified, restored.goalStatus)
        assertEquals(0, restored.verifiedStepCount)
        assertFalse(restored.informationProgress.record(observation()).observed)
        assertTrue(restored.informationProgress.record(observation(512, 1024)).observed)
        assertTrue(requireNotNull(TaskRunMetadata.decode("""{"logicalTaskId":"legacy"}""")).informationProgress.sources.isEmpty())
    }

    @Test fun boundedEvidenceNeverEvictsOldRangesOrSources() {
        var state = TaskInformationProgress()
        repeat(TaskInformationProgress.MaxRanges) { i -> state = state.record(observation(i * 4L, i * 4L + 1)).progress }
        assertFalse(state.record(observation(10000, 10001)).observed)
        assertFalse(state.record(observation(0, 1)).observed)
        repeat(TaskInformationProgress.MaxSources - 1) { i -> state = state.record(observation(source = "other-$i")).progress }
        assertFalse(state.record(observation(source = "overflow")).observed)
        assertFalse(state.record(observation(0, 1)).observed)
    }

    @Test fun pauseExplanationNamesTheActualCauseAndStaticInspectionHasNoRuntimeGuarantee() {
        assertTrue(ReplyPolicy.budgetPause(TaskBudgetStopReason.NoProgress, 8, true).contains("未获得新的可验证信息"))
        assertTrue(ReplyPolicy.budgetPause(TaskBudgetStopReason.TotalModelCalls, 8, true).contains("模型请求次数"))
        assertTrue(ReplyPolicy.budgetPause(TaskBudgetStopReason.TotalEstimatedTokens, 8, true).contains("token"))
        assertTrue(ReplyPolicy.budgetPause(TaskBudgetStopReason.TotalActiveTime, 8, true).contains("执行时长"))
        assertTrue(ReplyPolicy.budgetPause(TaskBudgetStopReason.StageLimit, 23, true).contains("最大执行阶段上限"))
        assertFalse(ReplyPolicy.budgetPause(TaskBudgetStopReason.StageLimit, 23, true).contains("有效检查点"))
        assertTrue(ReplyPolicy.instructions.contains("Static content inspection cannot guarantee"))
    }
}
