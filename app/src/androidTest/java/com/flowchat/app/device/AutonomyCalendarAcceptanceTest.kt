package com.flowchat.app.device

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.tools.AgentToolDefinitions
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

/** Actual calendar save/reopen/absolute-date verification through the real runtime and local model. */
class AutonomyCalendarAcceptanceTest : ScrollFixtureDiagnostics() {
    private fun clickable(screen: DeviceScreenSnapshot, element: DeviceUiElement): DeviceUiElement {
        var target = element
        while (!target.clickable) target = screen.elements.single { it.index == target.parentIndex }
        return target
    }
    private fun tap(screen: DeviceScreenSnapshot, element: DeviceUiElement) = ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.TapUiElementToolName,
        buildJsonObject { put("screen_id", screen.screenId); put("index", clickable(screen, element).index) }.toString())
    private fun observe() = ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.ObserveScreenToolName, """{"include_image":false}""")

    @Test fun threeClearCalendarGoalsSaveReopenAndVerifyWithoutAUserHandoff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = AutonomyRuntimeFixture(context)
        var passed = false
        try {
            fixture.connect(); fixture.create()
            repeat(3) { sample ->
                val title = "Floryx 自动验收 ${UUID.randomUUID().toString().take(8)}"
                val minute = sample * 15
                val args = buildJsonObject {
                    put("title", title); put("begin_time", "2026-11-15T16:${minute.toString().padStart(2,'0')}:00+08:00")
                    put("end_time", "2026-11-15T16:${(minute+10).toString().padStart(2,'0')}:00+08:00"); put("completion", "save")
                }.toString()
                val request = PublicAppActions.parse(PublicAppActions.Calendar, args)
                CalendarSyntheticLedger.record(context, args)
                var stage = 0; var verified = false; var saveRequests = 0; var saveAttempts = 0; var calls = 0; var editWaits = 0
                fixture.responder = { modelRequest, _ ->
                    check(++calls <= 20) { "Calendar did not reach its saved result" }
                    val next: ChatToolCall? = if (stage == 0) {
                        stage = 1; ChatToolCall("calendar-entry-$sample", PublicAppActions.Calendar, args)
                    } else {
                        val screen = requireNotNull(fixture.lastResult?.screen) { "No fresh calendar UI evidence" }
                        if (screen.interactionContext?.complete != true || screen.elementQuery != null || screen.observationScope != null) observe()
                        else when (stage) {
                            1 -> {
                                check(PublicAppActions.prefillMatches(request, screen, 0, ZoneId.systemDefault())) { "Do not save unverified fields: ${screen.asToolContent()}" }
                                val done = screen.elements.single { it.displayText == "完成" && it.clickable }
                                saveAttempts++; stage = 2; tap(screen, done)
                            }
                            2 -> {
                                if (screen.elements.any { it.editable && it.resourceId == "com.bbk.calendar:id/title" && it.text == title } &&
                                    screen.elements.any { it.displayText == "新建日程" }) {
                                    check(fixture.lastResult?.actionOutcome == DeviceActionOutcome.NotDispatched && saveAttempts < 3) {
                                        "An uncertain save must be investigated rather than repeated"
                                    }
                                    check(PublicAppActions.prefillMatches(request, screen, 0, ZoneId.systemDefault()))
                                    saveAttempts++
                                    tap(screen, screen.elements.single { it.displayText == "完成" && it.clickable })
                                } else if (screen.packageName != PublicAppActions.Event.packageName) ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.OpenAppToolName,
                                    """{"app_name":"日历"}""")
                                else {
                                    saveRequests = 1
                                    val field = screen.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" && it.editable }
                                    if (field == null) {
                                        val search = screen.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/search_btn" }
                                        if (search != null) tap(screen, search)
                                        else if (screen.elements.any { it.displayText == "编辑" } && screen.elements.any { it.displayText == "删除" })
                                            ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.PressBackToolName, "{}")
                                        else observe()
                                    } else if (field.text != title) ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.InputTextToolName,
                                        buildJsonObject { put("screen_id", screen.screenId); put("index", field.index); put("text", title); put("clear_existing", true) }.toString())
                                    else {
                                        val result = CalendarSyntheticLedger.searchResult(screen, title)
                                        if (result == null) observe() else { stage = 3; tap(screen, result) }
                                    }
                                }
                            }
                            3 -> {
                                val rejected = CalendarSyntheticLedger.searchResult(screen, title)
                                if (rejected != null && fixture.lastResult?.actionOutcome == DeviceActionOutcome.NotDispatched) tap(screen, rejected)
                                else {
                                check(screen.elements.any { !it.editable && it.text == title }) { "Saved event identity was not confirmed" }
                                val edit = screen.elements.firstOrNull { it.displayText == "编辑" }
                                    ?: error("The saved event has no unique edit entry: ${screen.asToolContent()}")
                                stage = 4; tap(screen, edit)
                                }
                            }
                            4 -> {
                                if (screen.elements.none { it.editable && it.resourceId == "com.bbk.calendar:id/title" && it.text == title } &&
                                    fixture.lastResult?.actionOutcome == DeviceActionOutcome.NotDispatched && editWaits++ < 2) {
                                    tap(screen, screen.elements.first { it.displayText == "编辑" })
                                } else if (screen.elements.none { it.editable && it.text == title } && editWaits++ < 2) {
                                    ChatToolCall(UUID.randomUUID().toString(), AgentToolDefinitions.WaitForScreenToolName,
                                        buildJsonObject { put("condition", buildJsonObject { put("package_name", "com.bbk.calendar")
                                            put("element", buildJsonObject { put("resource_id", "com.bbk.calendar:id/all_day_row") }) })
                                            put("timeout_ms", 5000); put("include_image", false) }.toString())
                                } else {
                                    check(PublicAppActions.prefillMatches(request, screen, 0, ZoneId.systemDefault(), "编辑")) {
                                        "Saved event absolute title/start/end do not match: ${screen.asToolContent()}"
                                    }
                                    verified = true; null
                                }
                            }
                            else -> error("Unexpected stage")
                        }
                    }
                    if (next == null) listOf(ChatDelta.Content("测试日程已保存，并已重新打开核对标题及开始、结束时间。"), ChatDelta.Done)
                    else {
                        check(modelRequest.tools.any { it.name == next.name }) { "Required tool is unavailable: ${next.name}" }
                        listOf(ChatDelta.ToolCalls(listOf(next)), ChatDelta.Done)
                    }
                }
                val started = SystemClock.elapsedRealtime()
                fixture.send("创建并保存测试日程：$title，2026年11月15日16:${minute.toString().padStart(2,'0')}开始，16:${(minute+10).toString().padStart(2,'0')}结束。办完后核对实际记录。")
                assertTrue(verified); assertEquals(1, saveRequests); assertEquals(0, fixture.confirmations.size)
                report("task=calendar sample=$sample completed=true saved_then_reopened=true absolute_start_end_verified=true save_requests=$saveRequests save_attempts=$saveAttempts app_confirmations=0 manual_steps=0 agent_calls=$calls summary_calls=${fixture.summaryCalls.get()} elapsed_ms=${SystemClock.elapsedRealtime() - started} synthetic_title=$title")
                cleanSyntheticEvent(fixture, title)
                CalendarSyntheticLedger.deleted(context, title)
            }
            passed = true
        } finally { fixture.close(passed) }
    }

    private suspend fun cleanSyntheticEvent(fixture: AutonomyRuntimeFixture, title: String) {
        var current = requireNotNull(fixture.gateway.observeScreen().screen)
        check(current.elements.any { it.editable && it.text == title })
        val cancel = current.elements.firstOrNull { it.displayText == "取消" }
        if (cancel != null) tapCalendarControl(fixture.gateway, current, cancel)
        else fixture.gateway.pressBack()
        current = requireNotNull(fixture.gateway.observeScreen().screen)
        check(current.elements.any { !it.editable && it.text == title }) { "Cleanup lost the exact synthetic event" }
        val delete = current.elements.first { it.displayText == "删除" }
        current = tapCalendarControl(fixture.gateway, current, delete)
        // Confirm only this synthetic event's ordinary delete dialog, never another record.
        val confirm = current.elements.filter { it.clickable && it.displayText == "删除" }
        report("calendar_delete_confirmation controls=" + current.elements.filter { it.clickable }.map { it.displayText })
        if (confirm.size == 1) tapCalendarControl(fixture.gateway, current, confirm.single())
        current = refreshCalendarQuery(fixture.gateway, title)
        withTimeout(10_000) {
            do {
                current = requireNotNull(fixture.gateway.observeScreen().screen)
                if (current.elements.none { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" && it.text == title } ||
                    CalendarSyntheticLedger.searchResult(current, title) != null) delay(150)
            } while (current.elements.none { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" && it.text == title } ||
                CalendarSyntheticLedger.searchResult(current, title) != null)
        }
        report("calendar_cleanup synthetic_title=$title absent_from_returned_search=true")
    }
}
