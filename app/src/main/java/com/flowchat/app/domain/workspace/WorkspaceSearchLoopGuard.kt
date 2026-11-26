package com.flowchat.app.domain.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stops consecutive non-progressing `workspace_search` loops (including batched calls with varying queries)
 * before they exhaust `TaskExecutionBudgetLimits.maxNoProgress` and steers the model to existing answers or reads.
 */
internal class WorkspaceSearchLoopGuard {
    private val consecutiveNoProgressByPath = mutableMapOf<String, Int>()
    private val suppressedPaths = mutableSetOf<String>()

    var pausedPath: String? = null
        private set

    val isSearchPaused: Boolean
        get() = pausedPath != null

    fun parsePath(arguments: String): String? = runCatching {
        val raw = (Json.parseToJsonElement(arguments) as? JsonObject)
            ?.get("path")
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
            ?: return null
        WorkspacePaths.relative(raw)
    }.getOrNull()

    fun shouldBlock(path: String?): Boolean = path != null && path in suppressedPaths

    fun recordSearchResult(
        path: String?,
        observedProgress: Boolean,
        compatibilityAnswerAvailable: Boolean,
        currentNoProgressCount: Int,
        maxNoProgress: Int
    ) {
        if (path == null) return
        if (observedProgress) {
            recordProgress(path)
            return
        }
        val count = (consecutiveNoProgressByPath[path] ?: 0) + 1
        consecutiveNoProgressByPath[path] = count
        val threshold = if (compatibilityAnswerAvailable) CompatibilityNoProgressLimit else GeneralNoProgressLimit
        val nearBudgetLimit = maxNoProgress > 0 && currentNoProgressCount >= (maxNoProgress - 2).coerceAtLeast(1)
        if (count >= threshold || nearBudgetLimit) {
            suppressedPaths += path
            pausedPath = path
        }
    }

    fun recordProgress(path: String?) {
        if (path == null) return
        consecutiveNoProgressByPath.remove(path)
        suppressedPaths.remove(path)
        pausedPath = null
    }

    fun blockedResponseJson(path: String, compatibilityAnswerAvailable: Boolean): String = buildJsonObject {
        put("error", ErrorCode)
        put("path", path)
        put("read_only", true)
        put("compatibility_answer_available", compatibilityAnswerAvailable)
        put(
            "detail",
            "Consecutive workspace_search calls on this unchanged file yielded no new unread byte ranges or scan coverage."
        )
        put(
            "next_action",
            if (compatibilityAnswerAvailable) {
                "If this request is solely a single-file Shell compatibility check, call workspace_answer_compatibility alone with path and expected_source_version from workspace_stat. Otherwise use workspace_read on a specific unread byte range, inspect another file, or answer with verified findings and explicit unknowns."
            } else {
                "Stop repeating full-file or overlapping workspace_search calls on this unchanged file. Use workspace_read on a specific unread byte range if needed, inspect another file, or answer with verified findings and explicit unknowns."
            }
        )
    }.toString()

    fun steeringGuidance(compatibilityAnswerAvailable: Boolean): String? {
        val path = pausedPath ?: return null
        return if (compatibilityAnswerAvailable) {
            "workspace_search has been paused because consecutive searches on \"$path\" yielded no new unread byte ranges or scan coverage. " +
                "For a single-file Shell compatibility check, call workspace_answer_compatibility alone now using the path and expected_source_version from workspace_stat. " +
                "Otherwise use workspace_read on an unread byte range, inspect another file, or answer directly with verified findings and explicit unknowns."
        } else {
            "workspace_search has been paused because consecutive searches on \"$path\" yielded no new unread byte ranges or scan coverage. " +
                "Do not repeat whole-file or overlapping searches on this unchanged file; use workspace_read on an unread byte range if needed, inspect another file, or answer directly with verified findings and explicit unknowns."
        }
    }

    companion object {
        const val ErrorCode = "workspace_search_no_progress"
        const val CompatibilityNoProgressLimit = 2
        const val GeneralNoProgressLimit = 3
    }
}
