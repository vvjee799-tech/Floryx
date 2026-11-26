package com.flowchat.app.domain.workspace

import com.flowchat.app.domain.chat.TaskExecutionBudget
import com.flowchat.app.domain.chat.TaskExecutionBudgetLimits
import com.flowchat.app.domain.chat.TaskInformationObservation
import com.flowchat.app.domain.chat.TaskInformationProgress
import com.flowchat.app.domain.chat.TaskInformationRange
import com.flowchat.app.domain.chat.TaskInformationScan
import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchLoopGuardTest {
    private val location = WorkspaceLocation("content://test/tree/guard", "guard")

    private fun baseRequest(): ChatRequest = WorkspaceToolDefinitions.withCompatibilityAnswer(
        WorkspaceToolDefinitions.attach(
            ChatRequest(
                model = "deepseek-flash",
                messages = listOf(ChatRequestMessage("user", "Inspect analysis.sh compatibility.")),
                temperature = 0.0,
                topP = 1.0,
                maxTokens = 8192
            ),
            location,
            commandsEnabled = true
        ),
        available = true
    )

    private fun statObservation(path: String = "analysis.sh"): TaskInformationObservation =
        TaskInformationObservation(
            sourceSha256 = TaskRecoveryGate.fingerprint("phone:${location.key}:$path"),
            versionSha256 = "v1",
            versionConfidence = "size_mtime_weak",
            ranges = listOf(
                TaskInformationRange(0, 512),
                TaskInformationRange(3_199_920, 3_200_202),
                TaskInformationRange(6_499_785, 6_500_000)
            )
        )

    private fun negativeScanObservation(
        query: String,
        start: Long = 0L,
        end: Long = 6_500_000L,
        path: String = "analysis.sh"
    ): TaskInformationObservation = TaskInformationObservation(
        sourceSha256 = TaskRecoveryGate.fingerprint("phone:${location.key}:$path"),
        versionSha256 = "v1",
        versionConfidence = "size_mtime_weak",
        scan = TaskInformationScan(
            querySha256 = TaskRecoveryGate.fingerprint(query),
            range = TaskInformationRange(start, end)
        )
    )

    private fun hitObservation(
        start: Long,
        end: Long,
        path: String = "analysis.sh"
    ): TaskInformationObservation = TaskInformationObservation(
        sourceSha256 = TaskRecoveryGate.fingerprint("phone:${location.key}:$path"),
        versionSha256 = "v1",
        versionConfidence = "size_mtime_weak",
        ranges = listOf(TaskInformationRange(start, end))
    )

    @Test fun trial1RepeatedSearchBatchTripsGuardBeforeBudgetNoProgressAndSteersToCompatibilityAnswer() {
        val guard = WorkspaceSearchLoopGuard()
        val budget = TaskExecutionBudget(TaskExecutionBudgetLimits.commandCompatible(), clockMillis = { 0L })
        var progress = TaskInformationProgress()

        val statUpdate = progress.record(statObservation())
        progress = statUpdate.progress
        assertTrue(statUpdate.observed)
        assertTrue(budget.recordProgress(statUpdate.observed).canDispatch)

        // Batch of 4 searches from Trial 1: curl (first negative -> observed), wget (false), echo (false), arm64 (blocked).
        val queries = listOf("curl", "wget", "echo", "arm64")
        val blocked = mutableListOf<String>()
        for (query in queries) {
            val path = guard.parsePath("""{"path":"analysis.sh","query":"$query","max_scan_bytes":6500000}""")
            if (guard.shouldBlock(path)) {
                blocked += query
                budget.recordProgress(false)
                continue
            }
            val obs = if (query == "arm64") hitObservation(3_199_924, 3_200_089) else negativeScanObservation(query)
            val update = progress.record(obs)
            progress = update.progress
            budget.recordProgress(update.observed)
            guard.recordSearchResult(
                path = path,
                observedProgress = update.observed,
                compatibilityAnswerAvailable = true,
                currentNoProgressCount = budget.snapshot().noProgressCount,
                maxNoProgress = budget.snapshot().limits.maxNoProgress
            )
        }

        assertEquals(listOf("arm64"), blocked)
        assertTrue(guard.isSearchPaused)
        assertEquals("analysis.sh", guard.pausedPath)
        assertNull(budget.snapshot().stoppedReason)
        assertEquals(3, budget.snapshot().noProgressCount)

        val blockedJson = Json.parseToJsonElement(
            guard.blockedResponseJson("analysis.sh", compatibilityAnswerAvailable = true)
        ).jsonObject
        assertEquals(WorkspaceSearchLoopGuard.ErrorCode, blockedJson.getValue("error").jsonPrimitive.content)
        assertTrue(blockedJson.getValue("compatibility_answer_available").jsonPrimitive.boolean)
        assertTrue(blockedJson.getValue("next_action").jsonPrimitive.content.contains(WorkspaceToolDefinitions.AnswerCompatibility))

        val steeredRequest = WorkspaceToolDefinitions.withSearchAvailability(baseRequest(), !guard.isSearchPaused)
        assertFalse(steeredRequest.tools.any { it.name == WorkspaceToolDefinitions.Search })
        assertTrue(steeredRequest.tools.any { it.name == WorkspaceToolDefinitions.AnswerCompatibility })
        assertTrue(requireNotNull(guard.steeringGuidance(true)).contains(WorkspaceToolDefinitions.AnswerCompatibility))
    }

    @Test fun generalSearchAllowsThreeTriesResetsOnNewUnreadHitAndPausesOnThirdNoProgress() {
        val guard = WorkspaceSearchLoopGuard()
        var progress = TaskInformationProgress().record(statObservation()).progress

        // 1st absent query -> observed=true
        var update = progress.record(negativeScanObservation("curl"))
        progress = update.progress
        assertTrue(update.observed)
        guard.recordSearchResult("analysis.sh", update.observed, false, 0, 8)
        assertFalse(guard.shouldBlock("analysis.sh"))

        // 2nd and 3rd absent queries -> noProgress=1, 2 (< GeneralNoProgressLimit=3)
        for ((index, query) in listOf("wget", "echo").withIndex()) {
            update = progress.record(negativeScanObservation(query))
            progress = update.progress
            assertFalse(update.observed)
            guard.recordSearchResult("analysis.sh", update.observed, false, index + 1, 8)
            assertFalse(guard.shouldBlock("analysis.sh"))
        }

        // 4th search finds a new unread byte range -> resets consecutive no-progress counter
        update = progress.record(hitObservation(1_000_000, 1_000_200))
        progress = update.progress
        assertTrue(update.observed)
        guard.recordSearchResult("analysis.sh", update.observed, false, 0, 8)
        assertFalse(guard.shouldBlock("analysis.sh"))

        // 3 consecutive no-progress searches after reset -> pauses at 3
        for ((index, query) in listOf("q1", "q2", "q3").withIndex()) {
            update = progress.record(negativeScanObservation(query))
            progress = update.progress
            assertFalse(update.observed)
            guard.recordSearchResult("analysis.sh", update.observed, false, index + 1, 8)
            assertEquals(index == 2, guard.shouldBlock("analysis.sh"))
        }
        assertTrue(guard.isSearchPaused)
        assertFalse(requireNotNull(guard.steeringGuidance(false)).contains(WorkspaceToolDefinitions.AnswerCompatibility))
    }

    @Test fun multiChunkStreamingContinuationOfOneQueryNeverTripsGuard() {
        val guard = WorkspaceSearchLoopGuard()
        var progress = TaskInformationProgress()
        val chunkSize = 1_000_000L
        for (i in 0 until 7) {
            val start = i * chunkSize
            val end = minOf(6_500_000L, start + chunkSize)
            val update = progress.record(negativeScanObservation("CPU", start, end))
            progress = update.progress
            assertTrue("Chunk $i should count as new coverage", update.observed)
            guard.recordSearchResult("analysis.sh", update.observed, true, 0, 8)
            assertFalse(guard.shouldBlock("analysis.sh"))
        }
        assertFalse(guard.isSearchPaused)
    }

    @Test fun nearBudgetHeadroomTripsAfterSingleNoProgressSearch() {
        val guard = WorkspaceSearchLoopGuard()
        guard.recordSearchResult(
            path = "analysis.sh",
            observedProgress = false,
            compatibilityAnswerAvailable = false,
            currentNoProgressCount = 6,
            maxNoProgress = 8
        )
        assertTrue(guard.shouldBlock("analysis.sh"))
        assertTrue(guard.isSearchPaused)
    }

    @Test fun pivotingToAnotherFileWithProgressReenablesSearchWhileKeepingStalledFileBlocked() {
        val guard = WorkspaceSearchLoopGuard()
        repeat(2) {
            guard.recordSearchResult("analysis.sh", observedProgress = false, compatibilityAnswerAvailable = true, it + 1, 8)
        }
        assertTrue(guard.shouldBlock("analysis.sh"))
        assertTrue(guard.isSearchPaused)

        // Reading or stating another file with new information restores search availability globally
        // while keeping "analysis.sh" suppressed until "analysis.sh" itself gains unread progress.
        guard.recordProgress("other.sh")
        assertFalse(guard.isSearchPaused)
        assertFalse(guard.shouldBlock("other.sh"))
        assertTrue(guard.shouldBlock("analysis.sh"))

        val restored = WorkspaceToolDefinitions.withSearchAvailability(
            WorkspaceToolDefinitions.withSearchAvailability(baseRequest(), false),
            true
        )
        assertTrue(restored.tools.any { it.name == WorkspaceToolDefinitions.Search })
    }
}
