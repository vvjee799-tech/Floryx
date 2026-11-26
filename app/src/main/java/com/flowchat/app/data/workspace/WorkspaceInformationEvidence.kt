package com.flowchat.app.data.workspace

import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.workspace.*
import kotlinx.serialization.json.*

data class WorkspaceToolResult(val content: String, val information: TaskInformationObservation? = null)

/** Interprets only the selected workspace reader's own structured results. */
internal object WorkspaceInformationEvidence {
    fun capture(call: ChatToolCall, owner: String, location: WorkspaceLocation, result: JsonObject): TaskInformationObservation? = runCatching {
        if ("error" in result) return null
        val args = Json.parseToJsonElement(call.arguments).jsonObject
        val data = when (call.name) {
            WorkspaceToolDefinitions.Stat -> result["preview"]?.jsonObject
            WorkspaceToolDefinitions.InspectExecution -> result["range"]?.jsonObject
            WorkspaceToolDefinitions.Read, WorkspaceToolDefinitions.Search -> result
            else -> null
        } ?: return null
        val source = data["source"]?.jsonObject ?: return null
        val path = WorkspacePaths.relative(source.getValue("path").jsonPrimitive.content)
        if (path != args["path"]?.jsonPrimitive?.content) return null
        val version = source["source_version"]?.jsonPrimitive?.contentOrNull ?: return null
        val confidence = source.getValue("version_confidence").jsonPrimitive.content
        val scope = if (call.name == WorkspaceToolDefinitions.InspectExecution) "execution:$owner" else "phone"
        val identity = TaskRecoveryGate.fingerprint("$scope:${location.key}:$path")
        val ranges = when (call.name) {
            WorkspaceToolDefinitions.Search -> data.getValue("hits").jsonArray.map { it.jsonObject.getValue("context").jsonObject }
            WorkspaceToolDefinitions.Stat -> listOf(data) + result["inspection_hints"]?.jsonObject?.get("hints")?.jsonArray.orEmpty()
                .map { it.jsonObject.getValue("context").jsonObject } + result["text_structure"]?.jsonObject?.get("samples")?.jsonArray.orEmpty()
                .map { it.jsonObject.getValue("prefix").jsonObject }
            else -> listOf(data)
        }
        val verifiedRanges = ranges.mapNotNull { range ->
            val sameSource = range["source"] == source || (range["source"] == null &&
                call.name == WorkspaceToolDefinitions.Stat && range["source_ref"]?.jsonPrimitive?.contentOrNull == "stat.source" &&
                result["source"] == source)
            if (!sameSource) return null
            val start = range.getValue("byte_start").jsonPrimitive.long
            val end = range.getValue("byte_end_exclusive").jsonPrimitive.long
            val size = source["total_bytes"]?.jsonPrimitive?.longOrNull
            val text = range["text"]?.jsonPrimitive?.contentOrNull
            val hex = range["hex"]?.jsonPrimitive?.contentOrNull
            val bytes = text?.toByteArray(Charsets.UTF_8)?.size ?: hex?.takeIf { it.matches(Regex("[0-9a-f]*")) && it.length % 2 == 0 }?.let { it.length / 2 }
            if (start < 0 || end < start || size != null && end > size || bytes?.toLong() != end - start) return null
            TaskInformationRange(start, end).takeIf { end > start }
        }
        val scan = if (call.name == WorkspaceToolDefinitions.Search && ranges.isEmpty()) {
            val start = data.getValue("byte_start").jsonPrimitive.long
            val end = data.getValue("byte_end_exclusive").jsonPrimitive.long
            val size = source["total_bytes"]?.jsonPrimitive?.longOrNull
            if (start < 0 || end <= start || size != null && end > size || data["truncated"]?.jsonPrimitive?.booleanOrNull == true) null
            else TaskInformationScan(TaskRecoveryGate.fingerprint(args.getValue("query").jsonPrimitive.content), TaskInformationRange(start, end))
        } else null
        val empty = ranges.singleOrNull()?.takeIf { source["total_bytes"]?.jsonPrimitive?.longOrNull == 0L &&
            it["eof"]?.jsonPrimitive?.booleanOrNull == true && it["text"]?.jsonPrimitive?.contentOrNull == "" }
        TaskInformationObservation(identity, TaskRecoveryGate.fingerprint(version), confidence, verifiedRanges, scan,
            factSha256 = empty?.let { TaskRecoveryGate.fingerprint("empty") })
    }.getOrNull()
}
