package com.flowchat.app.data.device

import com.flowchat.app.domain.chat.TaskEffectAdapter
import com.flowchat.app.domain.chat.TaskEffectDispatchState
import com.flowchat.app.domain.chat.TaskEffectIntent
import com.flowchat.app.domain.chat.TaskEffectNotDispatched
import com.flowchat.app.domain.chat.TaskEffectProof
import com.flowchat.app.domain.chat.TaskEffectRecord
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.ChatToolDefinition
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs

/** Edits one receipt-bound local event through its own editor, with a read-only result check. */
class CalendarTimeEditAdapter(private val gateway: DeviceAssistantGateway) : TaskEffectAdapter {
    override val id = CalendarTimeEdit.AdapterId
    override val version = 1
    override val exposedToModel = false
    override val tool = ChatToolDefinition(CalendarTimeEdit.ToolName, "Edit the saved event time.", buildJsonObject {})

    override fun describe(arguments: String): TaskEffectIntent {
        val edit = CalendarTimeEdit.parse(arguments)
        val digest = CalendarEventSave.fingerprint(CalendarEventSave.request(edit.revised))
        return TaskEffectIntent(id, version, "edited_calendar_time", CalendarEventSave.SourceId, edit.targetId, digest)
    }

    override suspend fun prepare(record: TaskEffectRecord, arguments: String): String =
        Json.encodeToString(CalendarEventReceipt.serializer(), checkOriginal(CalendarTimeEdit.parse(arguments)))

    override suspend fun dispatch(effectId: String, arguments: String) {
        val edit = CalendarTimeEdit.parse(arguments)
        val original = CalendarEventSave.request(edit.original)
        val revised = CalendarEventSave.request(edit.revised)
        val zone = ZoneId.systemDefault()
        val date = localTime(original.beginTimeMillis, zone).toLocalDate()
        if (listOf(original.endTimeMillis, revised.beginTimeMillis, revised.endTimeMillis).any { localTime(it, zone).toLocalDate() != date })
            unavailable("calendar_edit_date_changed")
        if (!gateway.openSavedCalendarEvent(edit)) unavailable("calendar_edit_entry_unverified")
        val initial = awaitScreen { PublicAppActions.prefillMatches(original, it, 0, zone, "编辑") }
        setTime(initial, "开始", localTime(revised.beginTimeMillis, zone))
        setTime(initial, "结束", localTime(revised.endTimeMillis, zone))
        checkOriginal(edit)
        val ready = awaitScreen { it.windowId == initial.windowId && PublicAppActions.prefillMatches(revised, it, 0, zone, "编辑") }
        val save = DeviceUiTargetPolicy.resolve(ready, CalendarTimeEdit.SaveControl)
            ?: unavailable("calendar_edit_save_control_unverified")
        gateway.checkExecutionTarget()
        if (gateway.commitCalendarTimeEdit(save.index, ready.screenId).actionOutcome == DeviceActionOutcome.NotDispatched)
            unavailable("calendar_edit_save_not_dispatched")
    }

    override suspend fun inspect(record: TaskEffectRecord): TaskEffectProof? {
        if (record.intent.adapterId != id || record.dispatchState !in setOf(TaskEffectDispatchState.DispatchStarted,
                TaskEffectDispatchState.Returned, TaskEffectDispatchState.Unconfirmed)) return null
        val arguments = record.preflightArguments ?: return null
        val edit = runCatching { CalendarTimeEdit.parse(arguments) }.getOrNull() ?: return null
        val before = runCatching { Json.decodeFromString<CalendarEventReceipt>(record.preflightEvidence ?: return null) }.getOrNull() ?: return null
        if (describe(arguments) != record.intent || TaskRecoveryGate.fingerprint(arguments) != record.argumentsSha256 ||
            !CalendarEventSave.matches(before, CalendarEventSave.request(edit.original)) || before.rows != listOf(row(edit, edit.original))) return null
        repeat(6) { attempt ->
            val revised = read(edit.revised)
            if (revised?.rows == listOf(row(edit, edit.revised)) && revised.observedAtMillis >= before.observedAtMillis &&
                read(edit.original)?.let { it.rows.isEmpty() && it.observedAtMillis >= revised.observedAtMillis } == true) {
                return TaskEffectProof(record.effectId, record.intent, TaskEffectResolution.Applied,
                    "calendar-edit:${record.effectId}", "${revised.requestSha256}:${edit.eventId}", revised.observedAtMillis)
            }
            if (attempt < 5) delay(300)
        }
        return null
    }

    private suspend fun checkOriginal(edit: CalendarTimeEdit): CalendarEventReceipt {
        val before = read(edit.original) ?: unavailable("calendar_edit_source_unavailable")
        if (before.rows != listOf(row(edit, edit.original)) || read(edit.revised)?.rows?.isEmpty() != true)
            unavailable("calendar_edit_source_changed_or_ambiguous")
        return before
    }

    private suspend fun read(arguments: String): CalendarEventReceipt? = gateway.inspectCalendarEvents(arguments)
        ?.takeIf { CalendarEventSave.matches(it, CalendarEventSave.request(arguments)) }

    private fun row(edit: CalendarTimeEdit, arguments: String): CalendarEventRow = CalendarEventSave.request(arguments).let {
        CalendarEventRow(edit.eventId, edit.calendarId, requireNotNull(it.beginTimeMillis), requireNotNull(it.endTimeMillis))
    }

    private suspend fun awaitScreen(matches: (DeviceScreenSnapshot) -> Boolean): DeviceScreenSnapshot {
        repeat(10) { attempt ->
            val screen = gateway.observeScreen().screen
            if (screen != null && matches(screen)) return screen
            if (attempt < 9) delay(250)
        }
        unavailable("calendar_edit_screen_unverified")
    }

    private suspend fun setTime(initial: DeviceScreenSnapshot, label: String, desired: LocalDateTime) {
        fun current(screen: DeviceScreenSnapshot): Pair<DeviceUiElement, LocalDateTime>? {
            if (screen.windowId != initial.windowId || screen.packageName != initial.packageName || screen.sensitive || screen.truncated ||
                !screen.searchComplete || screen.geometry?.displayId != 0 || screen.interactionContext?.complete != true ||
                screen.elements.none { it.displayText == "编辑" } ||
                screen.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/title" && it.editable }?.text !=
                initial.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/title" && it.editable }?.text) return null
            val heading = screen.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/title" && it.text == label } ?: return null
            val row = screen.elements.singleOrNull { it.index == heading.parentIndex && it.resourceId == "com.bbk.calendar:id/display_row" &&
                it.enabled && it.clickable && !it.password && it.bounds.isVisible } ?: return null
            val summary = screen.elements.singleOrNull { it.parentIndex == row.index && it.resourceId == "com.bbk.calendar:id/summary" } ?: return null
            val time = runCatching { LocalDateTime.parse(summary.text, EditorTime) }.getOrNull() ?: return null
            return (row to time).takeIf { time.toLocalDate() == desired.toLocalDate() }
        }
        val start = awaitScreen { current(it) != null }
        val (row, displayed) = requireNotNull(current(start))
        if (displayed == desired) return
        if (gateway.tapUiElement(row.index, false, start.screenId).actionOutcome == DeviceActionOutcome.NotDispatched)
            unavailable("calendar_edit_picker_unavailable")
        delay(250)
        repeat(36) {
            val screen = awaitScreen { current(it) != null }
            val time = requireNotNull(current(screen)).second
            if (time == desired) return
            val hours = time.hour != desired.hour
            val value = if (hours) time.hour else time.minute
            val difference = (if (hours) desired.hour else desired.minute) - value
            val wheelId = "com.bbk.calendar:id/not_allday_col${if (hours) 3 else 4}"
            val wheel = screen.elements.singleOrNull { it.resourceId == wheelId && it.enabled && !it.password &&
                it.className == "android.widget.SeekBar" && it.bounds.isVisible && it.text?.toIntOrNull() == value }
                ?: unavailable("calendar_edit_wheel_unverified")
            val steps = if (abs(difference) >= 3) 2 else 1
            if (gateway.moveCalendarTimeWheel(wheel.index, if (difference > 0) steps else -steps, screen.screenId)
                    .actionOutcome == DeviceActionOutcome.NotDispatched) unavailable("calendar_edit_wheel_not_dispatched")
            // Each adjustment needs a newly observed change before another gesture is allowed.
            awaitScreen { current(it)?.second?.let { next -> next != time } == true }
        }
        unavailable("calendar_edit_time_unsettled")
    }

    private fun unavailable(reason: String): Nothing = throw TaskEffectNotDispatched(reason)

    private companion object {
        val EditorTime = DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm", Locale.CHINA)
        fun localTime(millis: Long?, zone: ZoneId): LocalDateTime = Instant.ofEpochMilli(requireNotNull(millis)).atZone(zone).toLocalDateTime()
    }
}
