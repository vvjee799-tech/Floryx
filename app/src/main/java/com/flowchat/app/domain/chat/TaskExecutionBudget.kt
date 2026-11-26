package com.flowchat.app.domain.chat

import kotlinx.serialization.Serializable

/** Limits apply to one durable task, including every continuation of that task. */
@Serializable
data class TaskExecutionBudgetLimits(
    val stageToolCalls: Int = 60,
    val stageModelCalls: Int = 64,
    val stageActiveMillis: Long = 600_000L,
    val stageEstimatedTokens: Long = 16_000_000L,
    val totalToolCalls: Int = 240,
    val totalModelCalls: Int = 256,
    val totalActiveMillis: Long = 3_600_000L,
    val totalEstimatedTokens: Long = 64_000_000L,
    val maxStages: Int = 8,
    val maxNoProgress: Int = 8
) {
    init {
        require(stageToolCalls > 0 && stageModelCalls > 0)
        require(stageActiveMillis > 0 && stageEstimatedTokens > 0)
        require(totalToolCalls > 0 && totalModelCalls > 0)
        require(totalActiveMillis > 0 && totalEstimatedTokens > 0)
        require(maxStages > 0 && maxNoProgress > 0)
    }

    companion object {
        /** Retains the existing command profile's round allowance within a single stage. */
        fun commandCompatible(maxRounds: Int = 160): TaskExecutionBudgetLimits {
            require(maxRounds > 0 && maxRounds < Int.MAX_VALUE)
            return TaskExecutionBudgetLimits(
                stageToolCalls = maxRounds,
                stageModelCalls = maxRounds + 1,
                stageActiveMillis = 1_800_000L,
                stageEstimatedTokens = 64_000_000L,
                totalToolCalls = maxRounds,
                totalModelCalls = maxRounds + 1,
                totalActiveMillis = 1_800_000L,
                totalEstimatedTokens = 64_000_000L,
                maxStages = 1
            )
        }
    }
}

@Serializable
enum class TaskBudgetStopReason {
    StageToolCalls,
    StageModelCalls,
    StageActiveTime,
    StageEstimatedTokens,
    TotalToolCalls,
    TotalModelCalls,
    TotalActiveTime,
    TotalEstimatedTokens,
    NoProgress,
    StageLimit,
    NoStageProgress,
    PendingEffects,
    MissingCheckpointEvidence,
    ReusedCheckpointEvidence;

    /** Stage boundaries can be cleared only by a successful, explicit checkpoint. */
    val isHardStop: Boolean
        get() = this in setOf(
            TotalToolCalls, TotalModelCalls, TotalActiveTime, TotalEstimatedTokens,
            NoProgress, StageLimit, NoStageProgress
        )
}

enum class TaskBudgetDisposition { Continue, CheckpointRequired, Stopped }

data class TaskBudgetDecision(
    val disposition: TaskBudgetDisposition,
    val reason: TaskBudgetStopReason? = null
) {
    val canDispatch: Boolean get() = disposition == TaskBudgetDisposition.Continue
}

/**
 * Token values are conservative estimates reserved before dispatch, not provider billing usage.
 * Clock origins are deliberately absent: restoring a task never charges process downtime.
 */
@Serializable
data class TaskExecutionBudgetSnapshot(
    val limits: TaskExecutionBudgetLimits = TaskExecutionBudgetLimits(),
    val totalToolCalls: Int = 0,
    val totalModelCalls: Int = 0,
    val totalActiveMillis: Long = 0L,
    val totalEstimatedTokens: Long = 0L,
    val stageToolCalls: Int = 0,
    val stageModelCalls: Int = 0,
    val stageActiveMillis: Long = 0L,
    val stageEstimatedTokens: Long = 0L,
    val stageNumber: Int = 1,
    val noProgressCount: Int = 0,
    val stageProgressCount: Int = 0,
    val checkpointRefs: List<String> = emptyList(),
    val stoppedReason: TaskBudgetStopReason? = null
) {
    /** True when the budget can no longer advance to a fresh stage on resume. */
    val isExhausted: Boolean
        get() = stoppedReason?.let { reason ->
            reason.isHardStop || (reason in setOf(
                TaskBudgetStopReason.StageToolCalls,
                TaskBudgetStopReason.StageModelCalls,
                TaskBudgetStopReason.StageActiveTime,
                TaskBudgetStopReason.StageEstimatedTokens
            ) && stageNumber >= limits.maxStages)
        } == true
}

/** Pure task accounting; callers persist reservations before performing effects. */
class TaskExecutionBudget(
    limits: TaskExecutionBudgetLimits = TaskExecutionBudgetLimits(),
    restored: TaskExecutionBudgetSnapshot? = null,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    // A resumed task cannot obtain a fresh or larger allowance from the current profile.
    private var state = restored ?: TaskExecutionBudgetSnapshot(limits = limits)
    private var lastClockMillis = clockMillis()
    private var active = true

    init {
        require(state.totalToolCalls >= 0 && state.totalModelCalls >= 0)
        require(state.totalActiveMillis >= 0L && state.totalEstimatedTokens >= 0L)
        require(state.stageToolCalls >= 0 && state.stageModelCalls >= 0)
        require(state.stageActiveMillis >= 0L && state.stageEstimatedTokens >= 0L)
        require(state.stageNumber in 1..state.limits.maxStages)
        require(state.noProgressCount >= 0 && state.stageProgressCount >= 0)
    }

    @get:Synchronized
    val remainingActiveMillis: Long
        get() {
            sampleActiveTime()
            return minOf(
                (state.limits.totalActiveMillis - state.totalActiveMillis).coerceAtLeast(0L),
                (state.limits.stageActiveMillis - state.stageActiveMillis).coerceAtLeast(0L)
            )
        }

    @Synchronized
    fun suspendActive() {
        sampleActiveTime()
        active = false
    }

    @Synchronized
    fun resumeActive() {
        sampleActiveTime()
        active = true
    }

    @Synchronized
    fun snapshot(): TaskExecutionBudgetSnapshot {
        check()
        return state.copy(checkpointRefs = state.checkpointRefs.toList())
    }

    @Synchronized
    fun check(): TaskBudgetDecision {
        sampleActiveTime()
        return decision()
    }

    /**
     * Leave room for the next operation using observed latency, while still between effects.
     * This requests the existing durable checkpoint; it grants no time or dispatch allowance.
     * The watchdog remains the hard deadline if a future operation is unexpectedly slow.
     */
    @Synchronized
    fun checkBeforeOperation(expectedActiveMillis: Long): TaskBudgetDecision {
        require(expectedActiveMillis >= 0L)
        val before = check()
        if (!before.canDispatch || state.stageProgressCount == 0 || state.stageNumber >= state.limits.maxStages) return before
        // A single slow sample cannot consume a fresh stage without doing any work.
        val reserve = expectedActiveMillis.coerceAtMost(state.limits.stageActiveMillis / 2)
        return if (state.limits.stageActiveMillis - state.stageActiveMillis <= reserve)
            boundary(TaskBudgetStopReason.StageActiveTime) else before
    }

    /** Reserves one individual call, including each call in a model-produced batch. */
    @Synchronized
    fun reserveTool(): TaskBudgetDecision {
        val before = check()
        if (!before.canDispatch) return before
        state = state.copy(
            totalToolCalls = state.totalToolCalls + 1,
            stageToolCalls = state.stageToolCalls + 1
        )
        // The final available call is allowed; the next dispatch must check again.
        return TaskBudgetDecision(TaskBudgetDisposition.Continue)
    }

    /**
     * Reserve estimated input plus the request's capped maximum output before contacting a model.
     * Reservations are never refunded after failure, interruption, or an unexpectedly short reply.
     */
    @Synchronized
    fun reserveModel(estimatedTokens: Long): TaskBudgetDecision {
        require(estimatedTokens >= 0L)
        val before = check()
        if (!before.canDispatch) return before
        if (estimatedTokens > state.limits.totalEstimatedTokens - state.totalEstimatedTokens) {
            return stop(TaskBudgetStopReason.TotalEstimatedTokens)
        }
        if (estimatedTokens > state.limits.stageEstimatedTokens - state.stageEstimatedTokens) {
            return boundary(TaskBudgetStopReason.StageEstimatedTokens)
        }
        state = state.copy(
            totalModelCalls = state.totalModelCalls + 1,
            stageModelCalls = state.stageModelCalls + 1,
            totalEstimatedTokens = state.totalEstimatedTokens + estimatedTokens,
            stageEstimatedTokens = state.stageEstimatedTokens + estimatedTokens
        )
        return TaskBudgetDecision(TaskBudgetDisposition.Continue)
    }

    /** Only verified task progress may clear consecutive no-progress observations. */
    @Synchronized
    fun recordProgress(observed: Boolean): TaskBudgetDecision {
        val before = check()
        if (before.disposition == TaskBudgetDisposition.Stopped) return before
        state = if (observed) {
            state.copy(noProgressCount = 0, stageProgressCount = increment(state.stageProgressCount))
        } else {
            state.copy(noProgressCount = increment(state.noProgressCount))
        }
        return decision()
    }

    /**
     * Explicit stage rollover after the caller has durably stored the referenced evidence.
     * The caller must ensure that no effect remains pending; no counters reset on rejection.
     */
    @Synchronized
    fun checkpoint(evidenceRef: String, pendingEffects: Boolean): TaskBudgetDecision {
        val before = check()
        if (before.disposition == TaskBudgetDisposition.Stopped) return before
        if (pendingEffects) return boundary(TaskBudgetStopReason.PendingEffects)
        val reference = evidenceRef.trim()
        if (reference.isEmpty()) return boundary(TaskBudgetStopReason.MissingCheckpointEvidence)
        if (reference in state.checkpointRefs) return boundary(TaskBudgetStopReason.ReusedCheckpointEvidence)
        if (state.stageProgressCount == 0) return stop(TaskBudgetStopReason.NoStageProgress)
        if (state.stageNumber >= state.limits.maxStages) return stop(TaskBudgetStopReason.StageLimit)
        state = state.copy(
            stageNumber = state.stageNumber + 1,
            stageToolCalls = 0,
            stageModelCalls = 0,
            stageActiveMillis = 0L,
            stageEstimatedTokens = 0L,
            stageProgressCount = 0,
            checkpointRefs = state.checkpointRefs + reference,
            stoppedReason = null
        )
        return TaskBudgetDecision(TaskBudgetDisposition.Continue)
    }

    /** Latches an interrupted operation's reason, including a resumable stage timeout. */
    @Synchronized
    fun halt(reason: TaskBudgetStopReason): TaskBudgetDecision {
        val before = check()
        if (before.disposition == TaskBudgetDisposition.Stopped) return before
        state = state.copy(stoppedReason = reason)
        return decision()
    }

    private fun decision(): TaskBudgetDecision {
        state.stoppedReason?.takeIf { it.isHardStop }?.let {
            return TaskBudgetDecision(TaskBudgetDisposition.Stopped, it)
        }
        val limits = state.limits
        val hardReason = when {
            state.totalToolCalls >= limits.totalToolCalls -> TaskBudgetStopReason.TotalToolCalls
            state.totalModelCalls >= limits.totalModelCalls -> TaskBudgetStopReason.TotalModelCalls
            state.totalActiveMillis >= limits.totalActiveMillis -> TaskBudgetStopReason.TotalActiveTime
            state.totalEstimatedTokens >= limits.totalEstimatedTokens -> TaskBudgetStopReason.TotalEstimatedTokens
            state.noProgressCount >= limits.maxNoProgress -> TaskBudgetStopReason.NoProgress
            else -> null
        }
        if (hardReason != null) return stop(hardReason)
        state.stoppedReason?.let { return boundary(it) }
        val stageReason = when {
            state.stageToolCalls >= limits.stageToolCalls -> TaskBudgetStopReason.StageToolCalls
            state.stageModelCalls >= limits.stageModelCalls -> TaskBudgetStopReason.StageModelCalls
            state.stageActiveMillis >= limits.stageActiveMillis -> TaskBudgetStopReason.StageActiveTime
            state.stageEstimatedTokens >= limits.stageEstimatedTokens -> TaskBudgetStopReason.StageEstimatedTokens
            else -> null
        }
        return if (stageReason == null) TaskBudgetDecision(TaskBudgetDisposition.Continue) else boundary(stageReason)
    }

    private fun sampleActiveTime() {
        val now = clockMillis()
        val elapsed = if (active && now > lastClockMillis) {
            (now - lastClockMillis).takeIf { it > 0L } ?: Long.MAX_VALUE
        } else 0L
        lastClockMillis = maxOf(lastClockMillis, now)
        if (elapsed > 0L) state = state.copy(
            totalActiveMillis = saturatedAdd(state.totalActiveMillis, elapsed),
            stageActiveMillis = saturatedAdd(state.stageActiveMillis, elapsed)
        )
    }

    private fun stop(reason: TaskBudgetStopReason): TaskBudgetDecision {
        state = state.copy(stoppedReason = reason)
        return TaskBudgetDecision(TaskBudgetDisposition.Stopped, reason)
    }

    private fun boundary(reason: TaskBudgetStopReason) =
        TaskBudgetDecision(TaskBudgetDisposition.CheckpointRequired, reason)

    private fun increment(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private fun saturatedAdd(value: Long, extra: Long): Long =
        if (extra > Long.MAX_VALUE - value) Long.MAX_VALUE else value + extra
}
