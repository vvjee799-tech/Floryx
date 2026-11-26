package com.flowchat.app.domain.device

import com.flowchat.app.domain.chat.TaskEffectRegistry
import com.flowchat.app.domain.chat.TaskEffectRecord
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRecoveryGate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A bounded read of exact timed, nonrecurring events. An empty read never proves a failed save. */
@Serializable
data class CalendarEventRow(val id: Long, val calendarId: Long, val beginTimeMillis: Long, val endTimeMillis: Long)

@Serializable
data class CalendarEventReceipt(
    val sourceId: String,
    val requestSha256: String,
    val observedAtMillis: Long,
    val rows: List<CalendarEventRow>
)

object CalendarEventSave {
    const val ToolName = "task_effect_save_calendar_event"
    const val AdapterId = "vivo.calendar.saved_event.v1"
    const val SourceId = "android-calendar:V2302A:vivo:api36:user0:com.bbk.calendar:7540"

    fun request(arguments: String): PublicAppActions.Request {
        val fields = Json.parseToJsonElement(arguments) as? JsonObject ?: error("Arguments must be an object")
        require(fields.keys == setOf("title", "begin_time", "end_time"))
        return PublicAppActions.parse(PublicAppActions.Calendar,
            JsonObject(fields + ("completion" to JsonPrimitive("save"))).toString())
    }

    fun fingerprint(request: PublicAppActions.Request): String = TaskEffectRegistry.contentSha256(buildJsonObject {
        put("title", request.title); put("begin", request.beginTimeMillis); put("end", request.endTimeMillis)
    }.toString())

    fun matches(receipt: CalendarEventReceipt, request: PublicAppActions.Request): Boolean =
        receipt.sourceId == SourceId && receipt.requestSha256 == fingerprint(request) && receipt.observedAtMillis >= 0 &&
            receipt.rows.size <= 16 && receipt.rows.map { it.id }.distinct().size == receipt.rows.size &&
            receipt.rows.all { it.id > 0 && it.calendarId > 0 && it.beginTimeMillis == request.beginTimeMillis &&
                it.endTimeMillis == request.endTimeMillis }

    fun referencesSavedTimeCorrection(instruction: String, effects: List<TaskEffectRecord>): Boolean {
        if (!TimeCorrection.containsMatchIn(instruction)) return false
        val requests = effects.mapNotNull(::savedRequest)
        return requests.isNotEmpty() && (ExistingEvent.containsMatchIn(instruction) || requests.any { request ->
            request.title?.let { instruction.contains(it, ignoreCase = true) } == true
        })
    }

    internal fun savedRequest(record: TaskEffectRecord): PublicAppActions.Request? {
        val proof = record.proof ?: return null
        val args = record.preflightArguments ?: return null
        if (record.tool != ToolName || record.intent.adapterId != AdapterId || record.intent.sourceId != SourceId ||
            proof.resolution != TaskEffectResolution.Applied || !TaskEffectRegistry.proofMatches(record, proof) ||
            TaskRecoveryGate.fingerprint(args) != record.argumentsSha256) return null
        return runCatching { request(args).takeIf { fingerprint(it) == record.intent.contentSha256 } }.getOrNull()
    }

    fun verifiedSummary(record: TaskEffectRecord): String? {
        val proof = record.proof ?: return null
        val args = record.preflightArguments ?: return null
        if (proof.resolution != TaskEffectResolution.Applied || !TaskEffectRegistry.proofMatches(record, proof) ||
            TaskRecoveryGate.fingerprint(args) != record.argumentsSha256) return null
        val eventArgs = when (record.tool) {
            ToolName -> args
            CalendarTimeEdit.ToolName -> runCatching { CalendarTimeEdit.parse(args).revised }.getOrNull() ?: return null
            else -> return null
        }
        return runCatching {
            val fields = Json.decodeFromString<Map<String, String>>(eventArgs)
            val title = fields.getValue("title").takeIf { it.isNotBlank() } ?: return@runCatching null
            val start = OffsetDateTime.parse(fields.getValue("begin_time"))
            val end = OffsetDateTime.parse(fields.getValue("end_time"))
            val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val timeOnly = DateTimeFormatter.ofPattern("HH:mm")
            val range = if (start.toLocalDate() == end.toLocalDate()) "${start.format(dateTime)}–${end.format(timeOnly)}"
            else "${start.format(dateTime)}–${end.format(dateTime)}"
            "$title · $range"
        }.getOrNull()
    }

    private val TimeCorrection = Regex(
        "改(?:到|至|成|为|时间)|改期|改时|修改.{0,30}(?:时间|时段)|推迟|延期|延后|提前[到至]|挪到|" +
            "\\b(?:reschedule|move|shift|postpone|bring forward|change (?:the )?(?:time|start|end))\\b",
        RegexOption.IGNORE_CASE
    )
    private val ExistingEvent = Regex(
        "(?:(?:刚才|之前|先前|原来|刚保存|已保存|已经保存|上一个)(?:的)?(?:那[条个项]?)?|[这那][条个项])" +
            "(?:日程|日历事件|活动|会议|约会)|\\b(?:last|previous|earlier|saved|that|this) (?:calendar )?(?:event|appointment|meeting)\\b",
        RegexOption.IGNORE_CASE
    )
}
