package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceToolResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A partial accessibility scan is never presented as a completed document read. No input API is available. */
internal object DocumentObservationVerifier {
    suspend fun complete(first: DeviceToolResult, read: suspend () -> DeviceToolResult, check: () -> Unit): DeviceToolResult {
        var latest = first
        var observations = 1
        val original = first.screen
        fun finish(result: DeviceToolResult) = result.copy(summary = result.summary +
            "\ndocument_observation_reads=$observations; document_scroll_dispatches=0")
        fun reject(code: String): DeviceToolResult = finish(latest.copy(success = false, errorCode = code,
            summary = "The document was not verified as a complete, bounded exposed text read. Use a fresh observation or read the visible page; do not claim that the whole article was read.",
            screen = latest.screen?.let { screen -> screen.copy(documentText = screen.documentText?.copy(text = "", treeComplete = false, errorCode = code)) }))
        fun sameSource(current: DeviceScreenSnapshot): Boolean = original != null &&
            original.windowId != null && original.geometry != null && original.interactionContext?.complete == true &&
            original.packageName == current.packageName && original.activityName == current.activityName &&
            original.windowId == current.windowId && original.geometry == current.geometry &&
            original.interactionContext == current.interactionContext && original.hasSameObservationScope(current) &&
            original.documentText?.rootPath == current.documentText?.rootPath
        while (true) {
            val screen = latest.screen ?: return reject(latest.errorCode ?: "document_read_unavailable")
            val document = screen.documentText ?: return reject(latest.errorCode ?: "document_read_unavailable")
            if (!latest.success || screen.sensitive || document.errorCode != null)
                return reject(document.errorCode ?: latest.errorCode ?: "document_read_unavailable")
            if (document.truncated) return reject("document_text_limit")
            if (!sameSource(screen)) return reject("document_source_changed")
            if (document.treeComplete && document.text.isNotBlank()) return finish(latest)
            if (observations == 3) return reject("document_tree_incomplete")
            currentCoroutineContext().ensureActive(); check(); delay(120)
            currentCoroutineContext().ensureActive(); check()
            val previousTime = screen.capturedAt
            latest = read(); observations++
            currentCoroutineContext().ensureActive(); check()
            if (latest.screen?.capturedAt?.let { it > previousTime } != true) return reject("document_observation_stale")
        }
    }
}
