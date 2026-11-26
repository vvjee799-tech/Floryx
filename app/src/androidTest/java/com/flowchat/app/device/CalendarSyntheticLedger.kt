package com.flowchat.app.device

import android.content.Context
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Survives a failed instrumentation run; contains only this batch's synthetic event identities. */
internal object CalendarSyntheticLedger {
    fun searchResult(screen: DeviceScreenSnapshot, title: String): DeviceUiElement? = screen.elements.singleOrNull {
        it.resourceId == "com.bbk.calendar:id/agenda" && it.contentDescription?.split(',')?.getOrNull(2) == title
    }
    private fun file(context: Context) = context.cacheDir.resolve("autonomy-calendar-ledger.json")
    private fun read(context: Context) = file(context).takeIf { it.exists() }?.readText()?.let(::JSONObject) ?: JSONObject()
    fun record(context: Context, arguments: String) {
        val request = JSONObject(arguments)
        val ledger = read(context)
        val title = request.getString("title")
        check(title.startsWith("Floryx 自动验收 ") || title == "Floryx_AUTONOMY_PROBE_20261114")
        if (!ledger.has(title)) {
            ledger.put(title, JSONObject().put("arguments", request).put("deleted", false))
            file(context).writeText(ledger.toString(2))
        }
    }
    fun deleted(context: Context, title: String) {
        val ledger = read(context)
        ledger.getJSONObject(title).put("deleted", true)
        file(context).writeText(ledger.toString(2))
    }
    fun pending(context: Context): List<String> {
        val ledger = read(context)
        return ledger.keys().asSequence().map { ledger.getJSONObject(it) }.filterNot { it.getBoolean("deleted") }
            .map { it.getJSONObject("arguments").toString() }.toList()
    }
    fun all(context: Context): List<String> {
        val ledger = read(context)
        return ledger.keys().asSequence().map { ledger.getJSONObject(it).getJSONObject("arguments").toString() }.toList()
    }
}

/** Requery with two explicit strings; this ROM exposes its hint as text on a blank search field. */
internal suspend fun refreshCalendarQuery(gateway: DeviceAssistantGateway, title: String): DeviceScreenSnapshot {
    var current = requireNotNull(gateway.observeScreen().screen)
    for (value in listOf("$title#", title)) {
        var matched = false
        repeat(4) {
            if (matched) return@repeat
            val field = current.elements.single { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" && it.editable }
            if (field.text.orEmpty() == value) { matched = true; return@repeat }
            val result = gateway.inputText(field.index, value, true, current.screenId)
            current = requireNotNull(gateway.observeScreen().screen)
            val actual = current.elements.single { it.resourceId == "com.bbk.calendar:id/vigour_search_edit" && it.editable }
            if (actual.text.orEmpty() == value) matched = true
            else check(result.actionOutcome == DeviceActionOutcome.NotDispatched) { result.asToolContent() }
        }
        check(matched) { "Search query could not be verified" }
    }
    // Search and its AI card update independently. Field text alone does not certify the result list.
    repeat(20) {
        delay(250)
        current = requireNotNull(gateway.observeScreen().screen)
        if (CalendarSyntheticLedger.searchResult(current, title) != null) return current
    }
    return current
}

/** A rejected stale click can be relocated; an accepted or uncertain click is never replayed here. */
internal suspend fun tapCalendarControl(gateway: DeviceAssistantGateway, initial: DeviceScreenSnapshot,
    element: DeviceUiElement): DeviceScreenSnapshot {
    var current = initial
    var target = element
    while (!target.clickable) target = current.elements.single { it.index == target.parentIndex }
    val identity = target
    repeat(3) {
        val result = gateway.tapUiElement(target.index, false, current.screenId)
        if (result.success) return requireNotNull(gateway.observeScreen().screen)
        check(result.actionOutcome == DeviceActionOutcome.NotDispatched) { result.asToolContent() }
        current = requireNotNull(gateway.observeScreen().screen)
        check(current.packageName == initial.packageName && current.windowId == initial.windowId)
        target = current.elements.single { it.clickable && it.className == identity.className &&
            it.resourceId == identity.resourceId && it.displayText == identity.displayText && identity.displayText.isNotBlank() }
    }
    error("Calendar control remained stale")
}
