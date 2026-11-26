package com.flowchat.app.domain.chat

import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ScreenControlContext

/** Reference exact repeated passive rows within an active native tool chain, without editing evidence. */
object ScreenObservationContext {
    fun capture(result: DeviceToolResult?, content: String, sourceMessageId: String): ScreenControlContext? {
        val screen = result?.screen ?: return null
        // Failures/unknown effects retain their entire original content. Images are managed separately.
        if (!result.success || result.errorCode != null || result.imageErrorCode != null ||
            result.actionOutcome in setOf(DeviceActionOutcome.Unverified, DeviceActionOutcome.NotDispatched) ||
            screen.sensitive || screen.observationErrorCode != null || screen.geometry == null) return null
        val elements = screen.elements.take(160)
        if (elements.isEmpty()) return null
        val rows = elements.map { it.asModelLine() }
        val block = rows.joinToString("\n")
        val screenText = screen.asToolContent()
        val start = content.indexOf(screenText)
        val rowStart = screenText.indexOf("\n$block")
        if (start < 0 || start != content.lastIndexOf(screenText) || rowStart < 0) return null
        val offset = start + rowStart + 1
        return ScreenControlContext(sourceMessageId, screen.screenId,
            listOf(screen.packageName, screen.activityName, screen.windowId, screen.geometry,
                screen.elementQuery, screen.observationScope, screen.observationRootPath).toString(),
            content.substring(0, offset), rows, elements.map { it.index },
            elements.indices.filter { index -> elements[index].let {
                !it.clickable && !it.editable && !it.scrollable && !it.focused && !it.selected &&
                    it.checked == null && it.range == null && it.collection == null && it.collectionItem == null &&
                    it.textSelectionStart == null && it.textSelectionEnd == null &&
                    // OriginOS TextViews expose set_selection even when they are not editable.
                    it.supportedActions.all { action -> action == "show_on_screen" || action == "set_selection" }
            } }.toSet(), content.substring(offset + block.length), result.screenImage != null)
    }

    fun referenceRepeatedControls(request: ChatRequest): ChatRequest {
        val latest = mutableMapOf<String, ScreenControlContext>()
        val projected = request.messages.toMutableList()
        for (index in request.messages.indices.reversed()) {
            val message = request.messages[index]
            val source = message.screenControls ?: continue
            if (message.role != "tool") continue
            val imageNotice = if (source.hasScreenImage && message.images.isEmpty() && message.screenImageId == null)
                ScreenVisionContext.OmittedImageNotice else ""
            val reference = latest[source.scopeKey]
            if (reference == null) {
                latest[source.scopeKey] = source
                projected[index] = message.copy(content = source.originalContent + imageNotice)
                continue
            }
            val referenceRows = reference.passiveRows.map { reference.rows[it] }.toSet()
            val omitted = source.passiveRows.filter { source.rows[it] in referenceRows }.toSet()
            if (omitted.isEmpty()) {
                projected[index] = message.copy(content = source.originalContent + imageNotice)
                continue
            }
            val marker = "[Historical observation: ${omitted.size} identical passive control rows are shown verbatim " +
                "in the later observation screen_id=${reference.screenId}, source_message_id=${reference.sourceMessageId}. " +
                "Referenced control_indices=${indexRanges(omitted.map { source.controlIndices[it] })}. " +
                "Only repeated rows are referenced; this does not prove unchanged pages, missing controls, or action effects. " +
                "Use read_task_history with message_ids=[\"${source.sourceMessageId}\"] for original stored evidence, " +
                "or observe_screen for current targets. Never act using historical indices.]"
            val firstOmitted = omitted.min()
            val rows = source.rows.mapIndexedNotNull { row, value ->
                if (row == firstOmitted) marker else value.takeIf { row !in omitted }
            }
            val compact = source.prefix + rows.joinToString("\n") + source.suffix
            projected[index] = message.copy(content = compact.takeIf { it.length + 256 < source.originalContent.length }
                ?.plus(imageNotice) ?: (source.originalContent + imageNotice))
        }
        return request.copy(messages = projected)
    }

    private fun indexRanges(indices: List<Int>): String {
        val ranges = mutableListOf<IntRange>()
        for (index in indices.sorted()) {
            val last = ranges.lastOrNull()
            if (last != null && last.last + 1 == index) ranges[ranges.lastIndex] = last.first..index
            else ranges += index..index
        }
        return ranges.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}..${it.last}" }
    }
}
