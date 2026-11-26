package com.flowchat.app.domain.device

import com.flowchat.app.domain.chat.TaskEffectRecord
import com.flowchat.app.domain.chat.TaskEffectRegistry
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRecoveryGate
import java.time.OffsetDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class CalendarTimeEdit(val eventId: Long, val calendarId: Long, val original: String, val revised: String) {
    fun arguments(): String = Json.encodeToString(serializer(), this)
    val targetId get() = "calendar:$calendarId:event:$eventId"

    companion object {
        const val ToolName = "task_effect_edit_saved_calendar_time"
        const val AdapterId = "vivo.calendar.edit_saved_time.v1"
        val SaveControl = DeviceUiSelector(text = "完成")

        fun parse(arguments: String): CalendarTimeEdit = Json.decodeFromString<CalendarTimeEdit>(arguments).also {
            val before = CalendarEventSave.request(it.original)
            val after = CalendarEventSave.request(it.revised)
            require(it.eventId > 0 && it.calendarId > 0 && before.title == after.title && before != after)
        }

        fun fromInstruction(instruction: String, effects: List<TaskEffectRecord>): CalendarTimeEdit? {
            if (PublicAppActions.draftOnlyInstruction(instruction)) return null
            val match = ExplicitTimeRange.matchEntire(instruction.trim()) ?: return null
            val reference = match.groupValues[1]
            val candidates = linkedMapOf<String, SavedEvent>()
            for (effect in effects) {
                val proof = effect.proof ?: continue
                val args = effect.preflightArguments ?: continue
                if (proof.resolution != TaskEffectResolution.Applied || !TaskEffectRegistry.proofMatches(effect, proof) ||
                    TaskRecoveryGate.fingerprint(args) != effect.argumentsSha256) continue
                when (effect.tool) {
                    CalendarEventSave.ToolName -> {
                        val saved = CalendarEventSave.savedRequest(effect) ?: continue
                        val row = SavedId.matchEntire(proof.receiptId) ?: continue
                        val calendar = row.groupValues[1].toLongOrNull() ?: continue
                        val event = row.groupValues[2].toLongOrNull() ?: continue
                        if (calendar <= 0 || event <= 0 || proof.sourceRevision != "${CalendarEventSave.fingerprint(saved)}:$event") continue
                        candidates[proof.receiptId] = SavedEvent(event, calendar, args)
                    }
                    ToolName -> {
                        val edit = runCatching { parse(args) }.getOrNull() ?: continue
                        val previous = candidates[edit.targetId] ?: continue
                        val digest = CalendarEventSave.fingerprint(CalendarEventSave.request(edit.revised))
                        if (effect.intent.adapterId == AdapterId && effect.intent.sourceId == CalendarEventSave.SourceId &&
                            effect.intent.targetId == edit.targetId && effect.intent.contentSha256 == digest &&
                            edit.original == previous.arguments && proof.receiptId == "calendar-edit:${effect.effectId}" &&
                            proof.sourceRevision == "$digest:${edit.eventId}")
                            candidates[edit.targetId] = previous.copy(arguments = edit.revised)
                    }
                }
            }
            val target = if (GenericReference.matches(reference)) candidates.values.singleOrNull() else
                candidates.values.singleOrNull { candidate ->
                    val title = CalendarEventSave.request(candidate.arguments).title
                    reference.removeSurrounding("《", "》") == title
                }
            val (eventId, calendarId, original) = target ?: return null
            return runCatching {
                val fields = Json.decodeFromString<Map<String, String>>(original)
                val zone = ZoneId.systemDefault()
                val start = OffsetDateTime.parse(fields.getValue("begin_time")).atZoneSameInstant(zone)
                val end = OffsetDateTime.parse(fields.getValue("end_time")).atZoneSameInstant(zone)
                require(start.toLocalDate() == end.toLocalDate())
                val newStart = LocalTime.parse(match.groupValues[2].padStart(5, '0'))
                val newEnd = LocalTime.parse(match.groupValues[3].padStart(5, '0'))
                require(newStart < newEnd)
                val startLocal = start.toLocalDate().atTime(newStart)
                val endLocal = end.toLocalDate().atTime(newEnd)
                val startOffset = zone.rules.getValidOffsets(startLocal).single()
                val endOffset = zone.rules.getValidOffsets(endLocal).single()
                val revised = buildJsonObject {
                    put("title", fields.getValue("title"))
                    put("begin_time", startLocal.atOffset(startOffset).toString())
                    put("end_time", endLocal.atOffset(endOffset).toString())
                }.toString()
                CalendarTimeEdit(eventId, calendarId, original, revised).takeIf { start.toLocalTime() != newStart || end.toLocalTime() != newEnd }
            }.getOrNull()
        }

        private data class SavedEvent(val eventId: Long, val calendarId: Long, val arguments: String)
        private val SavedId = Regex("calendar:(\\d+):event:(\\d+)")
        private val GenericReference = Regex("(?:刚才|之前|原来|已保存|刚保存|上一个)(?:的)?(?:那[条个项]?)?(?:日程|日历事件|活动|会议|约会)|[这那][条个项](?:日程|日历事件|活动|会议|约会)")
        private val ExplicitTimeRange = Regex(
            "(?:请)?把([^，。！？；\\n]{1,122}?)改(?:到|为|成|至)\\s*(?:同一天|当天|原来那天)?\\s*" +
                "([0-9]{1,2}:[0-9]{2})\\s*(?:到|至|[-–—~～])\\s*([0-9]{1,2}:[0-9]{2})[。！!]?"
        )
    }
}
