package com.flowchat.app.data.device

import com.flowchat.app.domain.device.CalendarEventSave
import org.junit.Assert.*
import org.junit.Test

class CalendarReceiptCommandsTest {
    private val arguments = """{"title":"测试 ' $(never) 😀","begin_time":"2026-11-15T18:00:00+08:00","end_time":"2026-11-15T18:10:00+08:00"}"""
    private val request = CalendarEventSave.request(arguments)
    private val row = "Row: 0 _id=801, calendar_id=2, dtstart=${request.beginTimeMillis}, dtend=${request.endTimeMillis}, allDay=0, deleted=0"
    private inner class Source(var output: String = "No result found.") : DeviceCommandExecutor {
        val calls = mutableListOf<List<String>>()
        var user = "0"
        var version = "7540"
        var success = true
        var localCalendar = "Row: 0 _id=2"
        var handler = "com.bbk.calendar/.event.EditEventActivity"
        override fun execute(command: List<String>): DeviceCommandResult {
            calls += command
            val value = when {
                command == listOf("am", "get-current-user") -> user
                command == listOf("getprop", "ro.product.model") -> "V2302A"
                command == listOf("getprop", "ro.product.manufacturer") -> "vivo"
                command == listOf("getprop", "ro.build.version.sdk") -> "36"
                command.take(3) == listOf("cmd", "package", "list") -> "package:com.bbk.calendar versionCode:$version"
                command.take(3) == listOf("cmd", "package", "query-activities") -> handler
                command.take(2) == listOf("content", "query") && "content://com.android.calendar/calendars" in command -> localCalendar
                command.take(2) == listOf("content", "query") -> return DeviceCommandResult(success, output)
                command.take(2) == listOf("am", "start") -> "Status: ok"
                else -> error("Unapproved command $command")
            }
            return DeviceCommandResult(true, value)
        }
    }

    @Test fun exactReadKeepsUntrustedTitleInsideOneSqlLiteralAndNeverWrites() {
        val source = Source(row)
        val result = requireNotNull(CalendarReceiptCommands(source) { 500 }.inspect(arguments, 0))
        assertEquals(801L, result.rows.single().id)
        assertEquals(500L, result.observedAtMillis)
        val query = source.calls.single { it.first() == "content" }
        assertEquals("query", query[1]); assertEquals("content://com.android.calendar/events", query[query.indexOf("--uri") + 1])
        assertTrue(query.last().startsWith("title COLLATE BINARY='测试 '' $(never) 😀' AND "))
        assertTrue(CalendarEventSave.matches(result, request))
        assertFalse(source.calls.any { it.first() in setOf("input", "settings") })
    }

    @Test fun onlyExactEmptyMessageIsAnAvailableEmptySnapshot() {
        val empty = CalendarReceiptCommands(Source()).inspect(arguments, 0)
        assertTrue(requireNotNull(empty).rows.isEmpty())
        for (invalid in listOf("", "Error while accessing provider", "No result found.\nSecurityException", row.replace("allDay=0", "allDay=1"),
            row.replace("deleted=0", "deleted=1"), row.replace("_id=801", "_id=0"), row + "\n" + row,
            row.replace("dtend=${request.endTimeMillis}", "dtend=1"), row + "\nRow: partial"))
            assertNull(invalid, CalendarReceiptCommands(Source(invalid)).inspect(arguments, 0))
        assertNull(CalendarReceiptCommands(Source(row).also { it.success = false }).inspect(arguments, 0))
    }

    @Test fun wrongUserVersionAndInvalidInputCannotQueryTheProvider() {
        for (source in listOf(Source().also { it.user = "10" }, Source().also { it.version = "7541" })) {
            assertNull(CalendarReceiptCommands(source).inspect(arguments, 0))
            assertFalse(source.calls.any { it.first() == "content" })
        }
        val source = Source()
        assertNull(CalendarReceiptCommands(source).inspect(arguments, 1))
        assertNull(CalendarReceiptCommands(source).inspect("{}", 0))
        assertTrue(source.calls.isEmpty())
    }

    @Test fun editLaunchUsesExactExistingItemAndBothOriginalTimes() {
        val source = Source(row)
        assertTrue(CalendarReceiptCommands(source).openForEdit(arguments, 801, 2, 0))
        val launch = source.calls.single { it.take(2) == listOf("am", "start") }
        assertEquals("android.intent.action.EDIT", launch[launch.indexOf("-a") + 1])
        assertEquals("content://com.android.calendar/events/801", launch[launch.indexOf("-d") + 1])
        assertEquals("vnd.android.cursor.item/event", launch[launch.indexOf("-t") + 1])
        assertEquals(listOf("--el", "beginTime", "${request.beginTimeMillis}", "--el", "endTime", "${request.endTimeMillis}"), launch.takeLast(6))
        assertFalse(source.calls.any { it.first() == "content" && it[1] != "query" })
    }

    @Test fun changedRowNonlocalCalendarOrAmbiguousEditorCannotOpen() {
        for (source in listOf(Source(row.replace("_id=801", "_id=802")), Source(row).also { it.localCalendar = "No result found." },
            Source(row).also { it.handler += "\nother/.Editor" })) {
            assertFalse(CalendarReceiptCommands(source).openForEdit(arguments, 801, 2, 0))
            assertFalse(source.calls.any { it.take(2) == listOf("am", "start") })
        }
    }
}
