package com.flowchat.app.data.device

import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.ChatToolDefinition
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Positive evidence only: a single new exact provider record after one field-checked save. */
class CalendarSaveEffectAdapter(private val gateway: DeviceAssistantGateway) : TaskEffectAdapter {
    override val id = CalendarEventSave.AdapterId
    override val version = 1
    override val tool = ChatToolDefinition(CalendarEventSave.ToolName,
        "Save the currently verified new calendar editor once, then independently read the exact saved title and both absolute times from the calendar source. " +
            "Use after open_calendar_editor with completion=save. Same verified iQOO/foreground Root scope; no guests, recurrence, all-day events, or new permissions. " +
            "If the result remains unknown, use verify_task_effect with its saved effect_id; never tap Save or open another editor to retry it. " +
            "A missing row is unknown; a preexisting or ambiguous match does not verify this new save.",
        buildJsonObject {
            put("type", "object"); putJsonObject("properties") {
                for (key in listOf("title", "begin_time", "end_time")) putJsonObject(key) { put("type", "string") }
            }
            putJsonArray("required") { for (key in listOf("title", "begin_time", "end_time")) add(JsonPrimitive(key)) }
            put("additionalProperties", false)
        })

    override fun describe(arguments: String): TaskEffectIntent {
        val digest = CalendarEventSave.fingerprint(CalendarEventSave.request(arguments))
        return TaskEffectIntent(id, version, "saved_calendar_event", CalendarEventSave.SourceId, "event:$digest", digest)
    }

    override suspend fun prepare(record: TaskEffectRecord, arguments: String): String {
        val request = CalendarEventSave.request(arguments)
        val source = gateway.inspectCalendarEvents(arguments)
            ?.takeIf { CalendarEventSave.matches(it, request) }
            ?: throw TaskEffectNotDispatched("calendar_source_unavailable; no save was dispatched")
        if (source.rows.isNotEmpty()) throw TaskEffectNotDispatched("calendar_existing_event; inspect the exact existing event before creating a duplicate")
        return Json.encodeToString(source)
    }

    override suspend fun dispatch(effectId: String, arguments: String) {
        val request = CalendarEventSave.request(arguments)
        val screen = gateway.observeScreen().screen
            ?: throw TaskEffectNotDispatched("calendar_editor_unavailable")
        if (!PublicAppActions.prefillMatches(request, screen, 0, ZoneId.systemDefault()))
            throw TaskEffectNotDispatched("calendar_fields_changed; observe and correct the editor before saving")
        val save = screen.elements.singleOrNull { it.enabled && it.clickable && !it.password && it.bounds.isVisible && it.displayText == "完成" }
            ?: throw TaskEffectNotDispatched("calendar_save_control_unavailable_or_ambiguous")
        val result = gateway.tapUiElement(save.index, false, screen.screenId)
        if (result.actionOutcome == DeviceActionOutcome.NotDispatched) throw TaskEffectNotDispatched("calendar_save_not_dispatched; refresh the editor before retrying")
        // Even success/progress from a click is not a saved-event receipt. inspect() decides that.
    }

    override suspend fun inspect(record: TaskEffectRecord): TaskEffectProof? {
        if (record.intent.adapterId != id || record.intent.adapterVersion != version || record.dispatchState !in
            setOf(TaskEffectDispatchState.DispatchStarted, TaskEffectDispatchState.Returned, TaskEffectDispatchState.Unconfirmed)) return null
        val baseline = runCatching { Json.decodeFromString<CalendarEventReceipt>(record.preflightEvidence ?: return null) }.getOrNull() ?: return null
        if (baseline.sourceId != record.intent.sourceId || baseline.requestSha256 != record.intent.contentSha256 || baseline.rows.isNotEmpty()) return null
        // Request content is kept in the durable preflight, not reconstructed from model prose.
        val arguments = record.preflightArguments ?: return null
        val request = runCatching { CalendarEventSave.request(arguments) }.getOrNull() ?: return null
        if (describe(arguments) != record.intent || TaskRecoveryGate.fingerprint(arguments) != record.argumentsSha256 ||
            !CalendarEventSave.matches(baseline, request)) return null
        repeat(5) { attempt ->
            val current = gateway.inspectCalendarEvents(arguments)
            if (current != null && CalendarEventSave.matches(current, request) && current.observedAtMillis >= baseline.observedAtMillis) {
                if (current.rows.size > 1) return null
                current.rows.singleOrNull()?.let { row ->
                    return TaskEffectProof(record.effectId, record.intent, TaskEffectResolution.Applied,
                        "calendar:${row.calendarId}:event:${row.id}", "${current.requestSha256}:${row.id}", current.observedAtMillis)
                }
            }
            if (attempt < 4) delay(250)
        }
        return null
    }
}
