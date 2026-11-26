package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.domain.device.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

/** Deletes only exact synthetic identities with this batch's recorded title AND absolute times. */
class AutonomyCalendarCleanupTest : ScrollFixtureDiagnostics() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fresh(): DeviceScreenSnapshot = withTimeout(10_000) {
        while (true) {
            val screen = gateway.observeScreen().screen
            if (screen?.interactionContext?.complete == true) return@withTimeout screen
            delay(150)
        }
        error("unreachable")
    }
    private suspend fun tap(screen: DeviceScreenSnapshot, element: DeviceUiElement): DeviceScreenSnapshot {
        return tapCalendarControl(gateway, screen, element)
    }
    private suspend fun search(title: String, owned: Set<String>): DeviceScreenSnapshot {
        var current = fresh()
        repeat(8) {
            val field = current.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" && it.editable }
            if (field != null) {
                return refreshCalendarQuery(gateway, title).also { screen -> check(screen.elements.any { it.editable && it.text == title }) }
            }
            val editor = current.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/title" && it.editable }
            if (editor != null) {
                check(editor.text in owned) { "An unrelated calendar editor must be preserved" }
                current = tap(current, current.elements.first { it.displayText == "取消" })
            } else {
                val searchButton = current.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/search_btn" }
                if (searchButton != null) current = tap(current, searchButton)
                else {
                    check(current.elements.any { it.displayText == "编辑" } && current.elements.any { it.displayText == "删除" }) {
                        "No recognized read-only route back to calendar search: ${current.asToolContent()}"
                    }
                    check(gateway.pressBack().success); current = fresh()
                }
            }
        }
        error("Search entry did not become available")
    }

    @Test fun cleanOnlyRecordedSyntheticEventsAndVerifyTheirAbsence() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("runCurrentFlashCleanup") == "true") {
            val file = java.io.File(context.getExternalFilesDir(null), "flash-d1/baseline-results.json")
            val arguments = org.json.JSONObject(file.readText()).getJSONObject("calendar_identity").toString()
            check(org.json.JSONObject(arguments).getString("title").startsWith("Floryx 自动验收 D1-"))
            cleanup(listOf(arguments))
            return@runBlocking
        }
        // Exact identities from the preserved earlier failure/probe receipts, never a prefix query/delete.
        listOf("Floryx_AUTONOMY_PROBE_20261114" to "15:00", "Floryx 自动验收 30ecfb45" to "16:00",
            "Floryx 自动验收 6c1b4a7e" to "16:00", "Floryx 自动验收 c0dad621" to "16:00").forEach { (title, time) ->
            val end = time.substringBefore(':') + ":10"
            CalendarSyntheticLedger.record(context, buildJsonObject {
                put("title", title); put("begin_time", "2026-11-15T$time:00+08:00")
                put("end_time", "2026-11-15T$end:00+08:00"); put("completion", "save")
            }.toString())
        }
        cleanup(CalendarSyntheticLedger.all(context))
        check(CalendarSyntheticLedger.pending(context).isEmpty())
    }

    private suspend fun cleanup(pending: List<String>) {
        val owned = pending.map { PublicAppActions.parse(PublicAppActions.Calendar, it).title!! }.toSet()
        AndroidAppLauncher(context, gateway).openApp("日历")
        for (arguments in pending) {
            val request = PublicAppActions.parse(PublicAppActions.Calendar, arguments)
            val title = requireNotNull(request.title)
            var current = search(title, owned)
            val result = CalendarSyntheticLedger.searchResult(current, title)
            report("calendar_search exact_title=$title result_found=${result != null} state=" +
                current.elements.filter { it.resourceId?.contains("empty") == true || it.resourceId?.contains("no_result") == true ||
                    it.resourceId?.contains("search") == true }.map { "${it.resourceId}:${it.text}:${it.contentDescription}" })
            if (result != null) {
                current = tap(current, result)
                check(current.elements.any { !it.editable && it.text == title })
                current = tap(current, current.elements.first { it.displayText == "编辑" })
                withTimeout(5000) {
                    while (!PublicAppActions.prefillMatches(request, current, 0, ZoneId.systemDefault(), "编辑")) {
                        delay(150); current = fresh()
                    }
                }
                current = tap(current, current.elements.first { it.displayText == "取消" })
                check(current.elements.any { !it.editable && it.text == title })
                current = tap(current, current.elements.first { it.displayText == "删除" })
                val confirmation = current.elements.filter { it.clickable && it.displayText == "删除" }
                report("calendar_delete_confirmation controls=" + current.elements.filter { it.clickable }.map { it.displayText })
                if (confirmation.size == 1) current = tap(current, confirmation.single())
            }
            current = search(title, owned)
            delay(700)
            withTimeout(10_000) {
                do {
                    current = fresh()
                    val query = current.elements.any { it.editable && it.text == title && it.resourceId == "com.bbk.calendar:id/vigour_search_edit" }
                    if (query && CalendarSyntheticLedger.searchResult(current, title) == null) break
                    delay(150)
                } while (true)
            }
            CalendarSyntheticLedger.deleted(context, title)
            report("calendar_cleanup synthetic_title=$title identity_and_absolute_times_verified=${result != null} absent_from_returned_search=true")
        }
    }
}
