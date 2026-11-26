package com.flowchat.app.domain

import com.flowchat.app.domain.chat.TaskBudgetDisposition
import com.flowchat.app.domain.chat.TaskBudgetStopReason
import com.flowchat.app.domain.chat.TaskExecutionBudget
import com.flowchat.app.domain.chat.TaskExecutionBudgetLimits
import com.flowchat.app.domain.chat.TaskExecutionBudgetSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionBudgetTest {
    private class Clock(var now: Long = 0L) {
        fun read(): Long = now
    }

    @Test fun eachBatchedToolMustReserveAndDeniedCallDoesNotConsumeAnotherSlot() {
        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(stageToolCalls = 2, totalToolCalls = 7),
            clockMillis = { 0L }
        )

        assertTrue(budget.reserveTool().canDispatch)
        assertTrue(budget.reserveTool().canDispatch)
        assertEquals(TaskBudgetStopReason.StageToolCalls, budget.reserveTool().reason)
        assertEquals(TaskBudgetDisposition.CheckpointRequired, budget.check().disposition)
        assertEquals(2, budget.snapshot().totalToolCalls)
        assertEquals(2, budget.snapshot().stageToolCalls)
    }

    @Test fun modelReservationIncludesOutputCapAndNeverOvershootsTokenAllowance() {
        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(stageEstimatedTokens = 100L, totalEstimatedTokens = 150L),
            clockMillis = { 0L }
        )

        assertTrue(budget.reserveModel(60L).canDispatch)
        assertEquals(TaskBudgetStopReason.StageEstimatedTokens, budget.reserveModel(41L).reason)
        assertEquals(60L, budget.snapshot().totalEstimatedTokens)
        assertEquals(1, budget.snapshot().totalModelCalls)
        assertTrue(budget.reserveModel(40L).canDispatch)
        assertEquals(TaskBudgetStopReason.StageEstimatedTokens, budget.check().reason)
        assertEquals(100L, budget.snapshot().totalEstimatedTokens)
        assertEquals(2, budget.snapshot().totalModelCalls)
    }

    @Test fun oversizedTotalReservationStopsBeforeModelDispatchAndSurvivesRestore() {
        val limits = TaskExecutionBudgetLimits(stageEstimatedTokens = 200L, totalEstimatedTokens = 100L)
        val budget = TaskExecutionBudget(limits = limits, clockMillis = { 0L })

        assertEquals(TaskBudgetStopReason.TotalEstimatedTokens, budget.reserveModel(Long.MAX_VALUE).reason)
        val saved = budget.snapshot()
        assertEquals(0, saved.totalModelCalls)
        assertEquals(0L, saved.totalEstimatedTokens)
        val restored = TaskExecutionBudget(restored = saved, clockMillis = { 0L })
        assertFalse(restored.reserveModel(1L).canDispatch)
        assertEquals(TaskBudgetStopReason.TotalEstimatedTokens, restored.check().reason)
    }

    @Test fun activeTimeExcludesWaitingAndProcessDowntimeButIncludesWorkAfterResume() {
        val clock = Clock()
        val limits = TaskExecutionBudgetLimits(stageActiveMillis = 100L, totalActiveMillis = 150L)
        val budget = TaskExecutionBudget(limits = limits, clockMillis = clock::read)
        clock.now = 30L
        budget.suspendActive()
        clock.now = 1_030L
        budget.suspendActive()
        assertEquals(30L, budget.snapshot().totalActiveMillis)
        assertEquals(70L, budget.remainingActiveMillis)

        budget.resumeActive()
        clock.now = 1_045L
        budget.resumeActive() // Repeated resume must not erase time already spent working.
        assertEquals(45L, budget.snapshot().totalActiveMillis)

        val saved = budget.snapshot()
        clock.now = 50_000L
        val restored = TaskExecutionBudget(restored = saved, clockMillis = clock::read)
        clock.now = 50_010L
        assertEquals(55L, restored.snapshot().totalActiveMillis)
        assertEquals(45L, restored.remainingActiveMillis)
    }

    @Test fun monotonicClockRegressionCannotSubtractOrDoubleCountActiveTime() {
        val clock = Clock(100L)
        val budget = TaskExecutionBudget(clockMillis = clock::read)
        clock.now = 120L
        assertEquals(20L, budget.snapshot().totalActiveMillis)
        clock.now = 110L
        assertEquals(20L, budget.snapshot().totalActiveMillis)
        clock.now = 130L
        assertEquals(30L, budget.snapshot().totalActiveMillis)
    }

    @Test fun safeCheckpointResetsOnlyStageCountersAndPreservesDurableEvidence() {
        val clock = Clock()
        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(stageToolCalls = 2), clockMillis = clock::read
        )
        budget.reserveModel(25L)
        budget.reserveTool()
        budget.recordProgress(true)
        budget.reserveTool()
        budget.recordProgress(false)
        clock.now = 12L

        assertEquals(TaskBudgetStopReason.StageToolCalls, budget.check().reason)
        assertTrue(budget.checkpoint(" persisted/step-2 ", pendingEffects = false).canDispatch)
        val saved = budget.snapshot()
        assertEquals(2, saved.stageNumber)
        assertEquals(listOf("persisted/step-2"), saved.checkpointRefs)
        assertEquals(2, saved.totalToolCalls)
        assertEquals(1, saved.totalModelCalls)
        assertEquals(25L, saved.totalEstimatedTokens)
        assertEquals(12L, saved.totalActiveMillis)
        assertEquals(1, saved.noProgressCount)
        assertEquals(0, saved.stageToolCalls)
        assertEquals(0, saved.stageModelCalls)
        assertEquals(0L, saved.stageEstimatedTokens)
        assertEquals(0L, saved.stageActiveMillis)
        assertEquals(0, saved.stageProgressCount)
        assertNull(saved.stoppedReason)
    }

    @Test fun pendingEffectsMissingOrReusedEvidenceCannotResetStage() {
        val budget = TaskExecutionBudget(clockMillis = { 0L })
        budget.reserveTool()
        budget.recordProgress(true)
        assertEquals(TaskBudgetStopReason.PendingEffects, budget.checkpoint("proof-1", true).reason)
        assertEquals(TaskBudgetStopReason.MissingCheckpointEvidence, budget.checkpoint("  ", false).reason)
        assertEquals(1, budget.snapshot().stageToolCalls)
        assertEquals(1, budget.snapshot().stageNumber)

        assertTrue(budget.checkpoint("proof-1", false).canDispatch)
        budget.reserveTool()
        budget.recordProgress(true)
        assertEquals(TaskBudgetStopReason.ReusedCheckpointEvidence, budget.checkpoint("proof-1", false).reason)
        assertEquals(2, budget.snapshot().stageNumber)
        assertEquals(1, budget.snapshot().stageToolCalls)
        assertEquals(listOf("proof-1"), budget.snapshot().checkpointRefs)
    }

    @Test fun stageRolloverNeedsObservedProgressAndCannotInventUnlimitedStages() {
        val idle = TaskExecutionBudget(clockMillis = { 0L })
        idle.reserveModel(1L)
        assertEquals(TaskBudgetStopReason.NoStageProgress, idle.checkpoint("model-answer", false).reason)
        assertEquals(TaskBudgetDisposition.Stopped, idle.check().disposition)
        assertEquals(1, idle.snapshot().stageNumber)

        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(maxStages = 2), clockMillis = { 0L }
        )
        budget.recordProgress(true)
        assertTrue(budget.checkpoint("proof-1", false).canDispatch)
        budget.recordProgress(true)
        assertEquals(TaskBudgetStopReason.StageLimit, budget.checkpoint("proof-2", false).reason)
        assertEquals(2, budget.snapshot().stageNumber)
        assertEquals(listOf("proof-1"), budget.snapshot().checkpointRefs)
    }

    @Test fun checkpointDifferentToolsAndRestorationDoNotResetNoProgress() {
        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(maxNoProgress = 3), clockMillis = { 0L }
        )
        budget.reserveTool()
        budget.recordProgress(true)
        budget.reserveTool()
        budget.recordProgress(false)
        budget.reserveModel(4L)
        budget.reserveTool()
        budget.recordProgress(false)
        assertTrue(budget.checkpoint("screen-changed-earlier", false).canDispatch)

        val restored = TaskExecutionBudget(restored = budget.snapshot(), clockMillis = { 0L })
        assertEquals(2, restored.snapshot().noProgressCount)
        restored.reserveModel(3L)
        assertEquals(TaskBudgetStopReason.NoProgress, restored.recordProgress(false).reason)
        assertEquals(3, restored.snapshot().noProgressCount)
        assertEquals(TaskBudgetStopReason.NoProgress, restored.recordProgress(true).reason)
        assertEquals(3, restored.snapshot().noProgressCount)
    }

    @Test fun onlyActualObservedProgressClearsConsecutiveFailuresBeforeHardStop() {
        val budget = TaskExecutionBudget(clockMillis = { 0L })
        repeat(3) { budget.recordProgress(false) }
        budget.reserveModel(4L)
        assertEquals(3, budget.snapshot().noProgressCount)
        assertTrue(budget.recordProgress(true).canDispatch)
        assertEquals(0, budget.snapshot().noProgressCount)
        assertEquals(1, budget.snapshot().stageProgressCount)
    }

    @Test fun restoredTaskCannotAcquireLargerProfileOrResetExhaustedTotals() {
        val original = TaskExecutionBudgetLimits(stageToolCalls = 10, totalToolCalls = 2)
        val budget = TaskExecutionBudget(limits = original, clockMillis = { 0L })
        repeat(2) { assertTrue(budget.reserveTool().canDispatch) }
        val encoded = Json.encodeToString(budget.snapshot())
        val saved = Json.decodeFromString<TaskExecutionBudgetSnapshot>(encoded)
        val restored = TaskExecutionBudget(
            limits = original.copy(totalToolCalls = 100), restored = saved, clockMillis = { 0L }
        )

        assertEquals(original, restored.snapshot().limits)
        assertEquals(TaskBudgetStopReason.TotalToolCalls, restored.reserveTool().reason)
        assertEquals(TaskBudgetStopReason.TotalToolCalls, restored.checkpoint("new-proof", false).reason)
        assertEquals(2, restored.snapshot().totalToolCalls)
    }

    @Test fun watchdogStageHaltPersistsAndRequiresSafeCheckpointAfterRestore() {
        val clock = Clock()
        val budget = TaskExecutionBudget(clockMillis = clock::read)
        budget.recordProgress(true)
        budget.recordProgress(false)
        clock.now = 12L
        budget.halt(TaskBudgetStopReason.StageActiveTime)
        val saved = budget.snapshot()
        assertEquals(TaskBudgetStopReason.StageActiveTime, saved.stoppedReason)
        val restored = TaskExecutionBudget(restored = saved, clockMillis = { 10_000L })

        assertEquals(TaskBudgetDisposition.CheckpointRequired, restored.check().disposition)
        assertFalse(restored.reserveTool().canDispatch)
        assertTrue(restored.checkpoint("timeout-evidence", false).canDispatch)
        assertNull(restored.snapshot().stoppedReason)
        assertEquals(12L, restored.snapshot().totalActiveMillis)
        assertEquals(1, restored.snapshot().noProgressCount)
    }

    @Test fun measuredOperationWindowRequestsADurableCheckpointBeforeTheDeadline() {
        val clock = Clock()
        val limits = TaskExecutionBudgetLimits(stageActiveMillis = 100L, totalActiveMillis = 300L)
        val budget = TaskExecutionBudget(limits = limits, clockMillis = clock::read)
        budget.reserveTool()
        budget.recordProgress(true)
        clock.now = 75L
        assertTrue(budget.check().canDispatch)
        assertEquals(TaskBudgetStopReason.StageActiveTime, budget.checkBeforeOperation(30L).reason)
        assertEquals(1, budget.snapshot().stageNumber)
        assertEquals(TaskBudgetStopReason.PendingEffects, budget.checkpoint("proof", true).reason)
        assertEquals(1, budget.snapshot().stageNumber)
        assertTrue(budget.checkpoint("proof", false).canDispatch)
        val saved = budget.snapshot()
        assertEquals(2, saved.stageNumber)
        assertEquals(75L, saved.totalActiveMillis)
        assertEquals(1, saved.totalToolCalls)
        assertEquals(listOf("proof"), saved.checkpointRefs)
        assertEquals(limits, saved.limits)
    }

    @Test fun latencyForecastCannotCreateProgressOrExtendTotalsOrConsumeFreshStages() {
        val clock = Clock()
        val budget = TaskExecutionBudget(limits = TaskExecutionBudgetLimits(stageActiveMillis = 100L,
            totalActiveMillis = 150L), clockMillis = clock::read)
        clock.now = 75L
        assertTrue(budget.checkBeforeOperation(Long.MAX_VALUE).canDispatch)
        budget.recordProgress(true)
        assertEquals(TaskBudgetDisposition.CheckpointRequired, budget.checkBeforeOperation(Long.MAX_VALUE).disposition)
        assertTrue(budget.checkpoint("proof", false).canDispatch)
        assertTrue(budget.checkBeforeOperation(Long.MAX_VALUE).canDispatch)
        clock.now = 150L
        assertEquals(TaskBudgetStopReason.TotalActiveTime, budget.checkBeforeOperation(30L).reason)
        assertEquals(TaskBudgetStopReason.TotalActiveTime, budget.checkpoint("other-proof", false).reason)
    }

    @Test fun lastStageKeepsItsRemainingTimeInsteadOfRequestingAnUnavailableEarlyCheckpoint() {
        val clock = Clock()
        val budget = TaskExecutionBudget(limits = TaskExecutionBudgetLimits(stageActiveMillis = 100L,
            totalActiveMillis = 200L, maxStages = 1), clockMillis = clock::read)
        budget.recordProgress(true)
        clock.now = 75L
        assertTrue(budget.checkBeforeOperation(30L).canDispatch)
        clock.now = 100L
        assertEquals(TaskBudgetStopReason.StageActiveTime, budget.checkBeforeOperation(30L).reason)
        assertEquals(TaskBudgetStopReason.StageLimit, budget.checkpoint("proof", false).reason)
    }

    @Test fun totalTimeStopHasPriorityOverStageRolloverAndRemainsSticky() {
        val clock = Clock()
        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(stageActiveMillis = 10L, totalActiveMillis = 10L),
            clockMillis = clock::read
        )
        budget.recordProgress(true)
        clock.now = 10L
        assertEquals(0L, budget.remainingActiveMillis)
        assertEquals(TaskBudgetStopReason.TotalActiveTime, budget.check().reason)
        assertEquals(TaskBudgetStopReason.TotalActiveTime, budget.halt(TaskBudgetStopReason.StageActiveTime).reason)
        assertEquals(TaskBudgetStopReason.TotalActiveTime, budget.checkpoint("proof", false).reason)
        assertEquals(1, budget.snapshot().stageNumber)
    }

    @Test fun modelCallCountIsBoundedIndependentlyOfTokensAndTools() {
        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(stageModelCalls = 1), clockMillis = { 0L }
        )
        assertTrue(budget.reserveModel(0L).canDispatch)
        assertEquals(TaskBudgetStopReason.StageModelCalls, budget.reserveModel(0L).reason)
        assertEquals(1, budget.snapshot().totalModelCalls)
        assertEquals(0, budget.snapshot().totalToolCalls)
    }

    @Test fun simultaneousReservationsCannotExceedGlobalDispatchCap() {
        val budget = TaskExecutionBudget(
            limits = TaskExecutionBudgetLimits(stageToolCalls = 100, totalToolCalls = 20),
            clockMillis = { 0L }
        )
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val granted = AtomicInteger()
        try {
            val futures = (1..4).map {
                executor.submit {
                    start.await()
                    repeat(20) { if (budget.reserveTool().canDispatch) granted.incrementAndGet() }
                }
            }
            start.countDown()
            futures.forEach { it.get(5L, TimeUnit.SECONDS) }
            assertEquals(20, granted.get())
            assertEquals(20, budget.snapshot().totalToolCalls)
            assertEquals(TaskBudgetStopReason.TotalToolCalls, budget.check().reason)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun commandCompatibilityProfilePreservesEightyRoundAllowance() {
        val limits = TaskExecutionBudgetLimits.commandCompatible(80)
        assertEquals(80, limits.stageToolCalls)
        assertEquals(80, limits.totalToolCalls)
        assertEquals(81, limits.stageModelCalls)
        assertEquals(81, limits.totalModelCalls)
        assertEquals(64_000_000L, limits.stageEstimatedTokens)
        assertEquals(limits.totalEstimatedTokens, limits.stageEstimatedTokens)
        assertEquals(1, limits.maxStages)

        val defaultCommand = TaskExecutionBudgetLimits.commandCompatible()
        assertEquals(160, defaultCommand.totalToolCalls)
        assertEquals(1_800_000L, defaultCommand.totalActiveMillis)
        assertEquals(64_000_000L, defaultCommand.totalEstimatedTokens)

        val defaultGeneral = TaskExecutionBudgetLimits()
        assertEquals(60, defaultGeneral.stageToolCalls)
        assertEquals(240, defaultGeneral.totalToolCalls)
        assertEquals(64_000_000L, defaultGeneral.totalEstimatedTokens)
        assertEquals(8, defaultGeneral.maxStages)
    }

    @Test fun exhaustedPredicateDistinguishesTerminalStageStopsFromResumableStages() {
        val singleStage = TaskExecutionBudgetLimits.commandCompatible(80)
        assertTrue(TaskExecutionBudgetSnapshot(limits = singleStage, stageNumber = 1, stoppedReason = TaskBudgetStopReason.StageLimit).isExhausted)
        assertTrue(TaskExecutionBudgetSnapshot(limits = singleStage, stageNumber = 1, stoppedReason = TaskBudgetStopReason.StageEstimatedTokens).isExhausted)
        assertTrue(TaskExecutionBudgetSnapshot(limits = singleStage, stageNumber = 1, stoppedReason = TaskBudgetStopReason.TotalToolCalls).isExhausted)
        assertTrue(TaskExecutionBudgetSnapshot(limits = singleStage, stageNumber = 1, stoppedReason = TaskBudgetStopReason.NoProgress).isExhausted)

        val multiStage = TaskExecutionBudgetLimits(maxStages = 4)
        assertFalse(TaskExecutionBudgetSnapshot(limits = multiStage, stageNumber = 1, stoppedReason = TaskBudgetStopReason.StageActiveTime).isExhausted)
        assertFalse(TaskExecutionBudgetSnapshot(limits = multiStage, stageNumber = 2, stoppedReason = null).isExhausted)
        assertTrue(TaskExecutionBudgetSnapshot(limits = multiStage, stageNumber = 4, stoppedReason = TaskBudgetStopReason.StageActiveTime).isExhausted)
        assertTrue(TaskExecutionBudgetSnapshot(limits = multiStage, stageNumber = 2, stoppedReason = TaskBudgetStopReason.StageLimit).isExhausted)
    }
}
