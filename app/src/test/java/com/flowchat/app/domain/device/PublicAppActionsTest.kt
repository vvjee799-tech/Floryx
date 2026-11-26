package com.flowchat.app.domain.device

import java.time.ZoneId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PublicAppActionsTest {
    private val dial = """{"phone_number":"+8610000000000"}"""
    private val calendar = """{"title":"测试 ' $(never) 😀","begin_time":"2026-11-09T10:00:00+08:00","end_time":"2026-11-09T10:10:00+08:00"}"""
    private val zone = ZoneId.of("Asia/Shanghai")
    private val bounds = DeviceBounds(0, 0, 100, 100)
    private fun node(index: Int, id: String, text: String, editable: Boolean = false, parent: Int? = null, checked: Boolean? = null) =
        DeviceUiElement(index, if (editable) "EditText" else if (checked != null) "android.widget.Switch" else "TextView", text, null, id, bounds,
            clickable = false, editable = editable, scrollable = false, enabled = true, parentIndex = parent, checked = checked)
    private fun screen(pkg: String, elements: List<DeviceUiElement>) = DeviceScreenSnapshot(pkg, "editor", elements,
        capturedAt = 2000, windowId = 10, geometry = DeviceScreenGeometry(bounds, 100, 100, 0),
        interactionContext = DeviceInteractionContext(listOf(DeviceWindowRegion(10, DeviceWindowKind.Application,
            1, bounds, listOf(bounds), focused = true, active = true))))
    private fun eventScreen(): DeviceScreenSnapshot {
        val request = PublicAppActions.parse(PublicAppActions.Calendar, calendar)
        return screen("com.bbk.calendar", listOf(
            node(1, "com.bbk.calendar:id/title", request.title!!, true),
            node(2, "com.bbk.calendar:id/all_day_row", "", checked = false),
            node(3, "header", "新建日程"),
            node(4, "com.bbk.calendar:id/title", "开始", parent = 10),
            node(5, "com.bbk.calendar:id/summary", "2026年11月9日 10:00", parent = 10),
            node(6, "com.bbk.calendar:id/title", "结束", parent = 20),
            node(7, "com.bbk.calendar:id/summary", "2026年11月9日 10:10", parent = 20)))
    }

    @Test fun strictDialerInputExcludesSchemesContactsAndUssd() {
        assertEquals("+8610000000000", PublicAppActions.parse(PublicAppActions.Dialer, dial).phoneNumber)
        for (value in listOf("*#06#", "tel:123", "Alice", "12", "123;456", "123\n", "１２３")) {
            assertThrows(Exception::class.java) { PublicAppActions.parse(PublicAppActions.Dialer,
                buildJsonObject { put("phone_number", value) }.toString()) }
        }
        for (value in listOf("""{"phone_number":123}""", """{"phone_number":"123","call":true}""", "[]"))
            assertThrows(Exception::class.java) { PublicAppActions.parse(PublicAppActions.Dialer, value) }
    }

    @Test fun calendarRequiresExactOffsetWholeMinutesAndBoundedDuration() {
        val request = PublicAppActions.parse(PublicAppActions.Calendar, calendar)
        assertEquals(1794189600000L, request.beginTimeMillis)
        assertEquals(600000L, request.endTimeMillis!! - request.beginTimeMillis!!)
        for (invalid in listOf(calendar.replace("10:00:00+08:00", "10:00:00"),
            calendar.replace("10:10:00+08:00", "09:59:00+08:00"),
            calendar.replace("10:00:00+08:00", "10:00:01+08:00"),
            calendar.dropLast(1) + ",\"attendees\":\"someone@example.invalid\"}"))
            assertThrows(Exception::class.java) { PublicAppActions.parse(PublicAppActions.Calendar, invalid) }
    }
    @Test fun savingIsExplicitWhileLegacyAndDraftOnlyRequestsRemainUnsaved() {
        assertEquals(PublicAppActions.CalendarCompletion.Draft, PublicAppActions.parse(PublicAppActions.Calendar, calendar).completion)
        assertEquals(PublicAppActions.CalendarCompletion.Save, PublicAppActions.parse(PublicAppActions.Calendar,
            calendar.dropLast(1) + ",\"completion\":\"save\"}").completion)
        assertTrue(PublicAppActions.draftOnlyInstruction("仅预填，不保存，交给我操作"))
        assertFalse(PublicAppActions.draftOnlyInstruction("创建并保存测试日程，然后核对"))
        assertThrows(Exception::class.java) { PublicAppActions.parse(PublicAppActions.Calendar, calendar.dropLast(1) + ",\"completion\":true}") }
    }

    @Test fun commandUsesOnlyRegisteredEditorAndTypedExactExtraArguments() {
        val request = PublicAppActions.parse(PublicAppActions.Calendar, calendar)
        val argv = PublicAppActions.launchArguments(request, 0)
        assertTrue(argv.contains(request.title))
        assertTrue(argv.contains("0x18080000"))
        assertFalse(argv.any { it == "android.intent.action.CALL" || it == "--activity-clear-task" })
        assertEquals(1, argv.count { it == request.title })
        assertThrows(Exception::class.java) { PublicAppActions.launchArguments(request, 10) }
    }

    @Test fun exactDialerNumberAllowsOnlyVisibleFormatting() {
        val request = PublicAppActions.parse(PublicAppActions.Dialer, dial)
        val s = screen("com.android.contacts", listOf(node(1, "com.android.contacts:id/digits", "+86 10000000000", true)))
        assertTrue(PublicAppActions.prefillMatches(request, s, 1000, zone))
        for (changed in listOf(s.copy(packageName = "other"), s.copy(capturedAt = 999), s.copy(sensitive = true),
            s.copy(truncated = true), s.copy(geometry = s.geometry!!.copy(displayId = 2)),
            s.copy(elements = s.elements + s.elements.first()), s.copy(interactionContext = null),
            s.copy(elements = listOf(s.elements.first().copy(text = "+86 10000000001")))))
            assertFalse(PublicAppActions.prefillMatches(request, changed, 1000, zone))
    }

    @Test fun eventNeedsMatchingTitleAllDayAndBothLabelAssociatedTimes() {
        val request = PublicAppActions.parse(PublicAppActions.Calendar, calendar)
        val s = eventScreen()
        assertTrue(PublicAppActions.prefillMatches(request, s, 1000, zone))
        for (changed in listOf(s.copy(elements = s.elements.map { if (it.index == 1) it.copy(text = "old draft") else it }),
            s.copy(elements = s.elements.map { if (it.index == 2) it.copy(checked = true) else it }),
            s.copy(elements = s.elements.map { if (it.index == 7) it.copy(parentIndex = 10) else it }),
            s.copy(elements = s.elements.map { if (it.index == 5) it.copy(text = "2026年11月9日 10:10") else it })))
            assertFalse(PublicAppActions.prefillMatches(request, changed, 1000, zone))
        assertFalse(PublicAppActions.prefillMatches(request, s, 1000, ZoneId.of("UTC")))
    }

    @Test fun handoffSeparatesVerifiedPrefillUnknownResultAndNoDispatch() {
        assertTrue(PublicAppActions.handoffMessage(DeviceToolResult(true, "", actionOutcome = DeviceActionOutcome.ProgressObserved), true).contains("未执行拨号"))
        assertTrue(PublicAppActions.handoffMessage(DeviceToolResult(false, "", actionOutcome = DeviceActionOutcome.Unverified), true).contains("尚未核实"))
        assertTrue(PublicAppActions.handoffMessage(DeviceToolResult(false, "", actionOutcome = DeviceActionOutcome.NotDispatched), true).contains("未打开"))
    }

    @Test fun calendarReadsTheUniqueCheckableChildWithoutTreatingUnknownParentAsFalse() {
        val request = PublicAppActions.parse(PublicAppActions.Calendar, calendar)
        val source = eventScreen()
        val withoutState = source.copy(elements = source.elements.map { if (it.index == 2) it.copy(checked = null) else it })
        assertFalse(PublicAppActions.prefillMatches(request, withoutState, 1000, zone))
        val child = node(8, "", "", parent = 2, checked = false)
        val realLayout = withoutState.copy(elements = withoutState.elements + child)
        assertTrue(PublicAppActions.prefillMatches(request, realLayout, 1000, zone))
        assertFalse(PublicAppActions.prefillMatches(request, realLayout.copy(elements = realLayout.elements + child.copy(index = 9)), 1000, zone))
        assertFalse(PublicAppActions.prefillMatches(request, withoutState.copy(elements = withoutState.elements + child.copy(parentIndex = 99)), 1000, zone))
    }
}
