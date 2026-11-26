package com.flowchat.app.domain.device

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Read-only snapshot of the phone's current local clock and timezone. */
object DeviceClockFacts {
    private val DateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    fun snapshot(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): JsonObject =
        buildJsonObject {
            put("local_date", DateFormatter.format(now))
            put("local_time", TimeFormatter.format(now))
            put("weekday", englishWeekday(now.dayOfWeek))
            put("weekday_zh", chineseWeekday(now.dayOfWeek))
            put("timezone", now.zone.id)
            put("utc_offset", now.offset.id)
            put("iso_datetime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now))
            put("source", "android_system_clock")
            put("scope", "read_only_device_clock")
        }

    private fun englishWeekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }

    private fun chineseWeekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }
}
