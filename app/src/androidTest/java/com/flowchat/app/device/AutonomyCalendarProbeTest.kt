package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

/** Investigates only the known synthetic probe created in this batch; no personal event writes. */
class AutonomyCalendarProbeTest : ScrollFixtureDiagnostics() {
    @Test fun reopenTheSyntheticProbeAndInspectSavedDetails() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidAppLauncher(context, gateway).openApp("日历")
        var current = usable()
        val search = current.elements.singleOrNull { it.resourceId == "com.bbk.calendar:id/search_btn" }
        if (current.elements.none { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" }) {
            check(search != null)
            gateway.tapUiElement(search.index, false, current.screenId)
            current = usable()
        }
        val field = current.elements.single { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" }
        val typed = gateway.inputText(field.index, Title, true, current.screenId)
        assertTrue(typed.asToolContent(), typed.success)
        current = requireNotNull(typed.screen)
        withTimeout(10_000) {
            while (current.elements.none { !it.editable && it.text == Title }) {
                delay(200); current = requireNotNull(gateway.observeScreen().screen)
            }
        }
        var target = current.elements.single { !it.editable && it.text == Title }
        while (!target.clickable) target = current.elements.single { it.index == target.parentIndex }
        val opened = gateway.tapUiElement(target.index, false, current.screenId)
        current = requireNotNull(opened.screen)
        report("calendar_probe_saved_title=" + current.elements.any { !it.editable && it.text == Title } +
            " details=" + current.elements.filter { it.text?.contains("15:00") == true || it.text?.contains("15:10") == true ||
                it.text?.contains("11月15日") == true || it.displayText == "删除" }.map { it.asModelLine() })
        context.cacheDir.resolve("autonomy-calendar-probe-detail.txt").writeText(current.asToolContent())
        assertTrue(current.asToolContent(), current.elements.any { !it.editable && it.text == Title })
        assertTrue(current.elements.any { it.displayText.contains("15:00") })
        assertTrue(current.elements.any { it.displayText.contains("15:10") })
    }
    private suspend fun usable(): DeviceScreenSnapshot {
        var current: DeviceScreenSnapshot? = null
        withTimeout(10_000) {
            do { current = gateway.observeScreen().screen; if (current?.interactionContext?.complete != true) delay(150) }
            while (current?.interactionContext?.complete != true)
        }
        return requireNotNull(current)
    }
    companion object { const val Title = "Floryx_AUTONOMY_PROBE_20261114" }
}
