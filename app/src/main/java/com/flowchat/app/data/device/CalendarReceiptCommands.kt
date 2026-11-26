package com.flowchat.app.data.device

import com.flowchat.app.domain.device.CalendarEventReceipt
import com.flowchat.app.domain.device.CalendarEventRow
import com.flowchat.app.domain.device.CalendarEventSave
import com.flowchat.app.domain.device.PublicAppActions

/** Fixed projection and exact selection only. No arbitrary provider, SQL, writes, or permission changes. */
internal class CalendarReceiptCommands(private val executor: DeviceCommandExecutor, private val clock: () -> Long = System::currentTimeMillis) {
    fun openForEdit(arguments: String, eventId: Long, calendarId: Long, userId: Int): Boolean {
        val request = runCatching { CalendarEventSave.request(arguments) }.getOrNull() ?: return false
        if (eventId <= 0 || calendarId <= 0 || userId != 0) return false
        val event = CalendarEventRow(eventId, calendarId, requireNotNull(request.beginTimeMillis), requireNotNull(request.endTimeMillis))
        if (inspect(arguments, userId)?.rows != listOf(event)) return false
        val local = executor.execute(listOf("content", "query", "--user", "0", "--uri", "content://com.android.calendar/calendars",
            "--projection", "_id", "--where", "_id=$calendarId AND account_type='LOCAL' AND visible=1 AND calendar_access_level>=500"))
        if (!local.success || local.output.trim() != "Row: 0 _id=$calendarId") return false
        val registration = PublicAppActions.Event
        val intent = listOf("-a", "android.intent.action.EDIT", "-d", "content://com.android.calendar/events/$eventId",
            "-t", requireNotNull(registration.mimeType))
        val resolved = executor.execute(listOf("cmd", "package", "query-activities", "--components", "--user", "0") +
            intent + listOf("-p", registration.packageName))
        if (!resolved.success || resolved.output.trim() != registration.component || inspect(arguments, userId)?.rows != listOf(event)) return false
        val result = executor.execute(listOf("am", "start", "-W", "--user", "0", "--display", "0", "-f", "0x18080000") +
            intent + listOf("-n", registration.component, "--el", "beginTime", "${event.beginTimeMillis}",
                "--el", "endTime", "${event.endTimeMillis}"))
        return result.success && result.output.lineSequence().any { it.trim() == "Status: ok" } &&
            result.output.lineSequence().none { it.trim().startsWith("Error", true) || it.contains("Permission Denial", true) }
    }

    fun inspect(arguments: String, userId: Int): CalendarEventReceipt? {
        val request = runCatching { CalendarEventSave.request(arguments) }.getOrNull() ?: return null
        if (userId != 0) return null
        fun read(vararg args: String) = executor.execute(args.toList()).takeIf { it.success }?.output?.trim()
        fun identityMatches(): Boolean = read("am", "get-current-user") == "0" &&
            read("getprop", "ro.product.model") == "V2302A" && read("getprop", "ro.product.manufacturer") == "vivo" &&
            read("getprop", "ro.build.version.sdk") == "36" &&
            read("cmd", "package", "list", "packages", "--show-versioncode", "--user", "0", "com.bbk.calendar") ==
                "package:com.bbk.calendar versionCode:7540"
        if (!identityMatches()) return null
        // The title is SQL data inside a single argv element. The executor separately quotes shell argv.
        val title = requireNotNull(request.title).replace("'", "''")
        val selection = "title COLLATE BINARY='$title' AND dtstart=${request.beginTimeMillis} AND dtend=${request.endTimeMillis}" +
            " AND allDay=0 AND deleted=0 AND (rrule IS NULL OR rrule='') AND (rdate IS NULL OR rdate='')" +
            " AND original_id IS NULL AND (eventStatus IS NULL OR eventStatus!=2)"
        val result = executor.execute(listOf("content", "query", "--user", "0", "--uri", "content://com.android.calendar/events",
            "--projection", "_id:calendar_id:dtstart:dtend:allDay:deleted", "--where", selection))
        if (!result.success || result.output.length > 16_384 || !identityMatches()) return null
        val output = result.output.trim()
        val rows = if (output == "No result found.") emptyList() else {
            val lines = output.lines()
            if (lines.isEmpty() || lines.size > 16) return null
            lines.mapIndexed { index, line ->
                val match = Row.matchEntire(line.trim()) ?: return null
                val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
                if (values[0] != index.toLong() || values[5] != 0L || values[6] != 0L) return null
                CalendarEventRow(values[1], values[2], values[3], values[4])
            }
        }
        return CalendarEventReceipt(CalendarEventSave.SourceId, CalendarEventSave.fingerprint(request), clock(), rows)
            .takeIf { CalendarEventSave.matches(it, request) }
    }

    private companion object {
        val Row = Regex("Row: (\\d+) _id=(\\d+), calendar_id=(\\d+), dtstart=(\\d+), dtend=(\\d+), allDay=(\\d+), deleted=(\\d+)")
    }
}
