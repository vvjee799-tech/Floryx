package com.flowchat.app.device

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.domain.device.DeviceToolResult
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Zero-model-request preflight of the public Tips search sample on the owned background display.
 * The wrong query is arranged through production tools on that display; the physical screen must stay unchanged.
 */
class StockTipsBackgroundPreflightTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pkg = "com.vivo.Tips"
    private fun id(name: String) = "$pkg:id/$name"
    private val searchField = id("vigour_search_edit")
    private val cancel = id("vigour_search_right_btn")
    private val title = id("tv_title")
    private val row = id("search_item")
    private val list = id("search_result_recyclerView")
    private val tab = id("tab_tips_wrap")

    private fun stage(name: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBG_TIPS $name\n") })

    @Test fun tipsSearchReplaceOpenAndReturnOnTheOwnedBackgroundDisplay() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runBgTipsPreflight") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        val label = args.getString("bgTipsRun").orEmpty()
        check(label.matches(Regex("[a-z0-9-]{1,64}")))
        val directory = File(context.getExternalFilesDir(null), "bg-f05/$label").also { check(it.mkdirs()) }
        val record = JSONObject().put("run", label).put("mode", "background_preflight_no_network")
        fun save() = File(directory, "result.json").writeText(record.toString(2))

        val tasks = DeviceTaskControl()
        val root = RootDeviceConnection(context)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root,
            backgroundExecutionEnabled = true))
        val gateway = ShizukuDeviceAssistantGateway(context, settings, root, tasks)
        val launcher = AndroidAppLauncher(context, gateway, tasks)
        var task: DeviceTaskControl.Task? = null

        fun screenOf(result: DeviceToolResult, what: String): DeviceScreenSnapshot =
            requireNotNull(result.screen) { "$what returned no screen: ${result.asToolContent().take(300)}" }

        fun titles(screen: DeviceScreenSnapshot) = JSONArray(screen.elements.filter { it.resourceId == title }.mapNotNull { it.text })
        fun query(screen: DeviceScreenSnapshot) =
            screen.elements.singleOrNull { it.resourceId == searchField && it.editable }?.text.orEmpty()
        fun hasSearch(screen: DeviceScreenSnapshot) = screen.elements.any { it.resourceId == cancel }
        suspend fun observe(description: String, timeoutMs: Long = 8_000, accept: (DeviceScreenSnapshot) -> Boolean): DeviceScreenSnapshot {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            var last: DeviceScreenSnapshot? = null
            while (SystemClock.elapsedRealtime() < end) {
                val screen = gateway.observeScreen().screen
                if (screen != null && screen.packageName == pkg) { last = screen; if (accept(screen)) return screen }
                delay(150)
            }
            error("Could not verify $description; last=${last?.elements?.map { it.resourceId?.substringAfter(":id/") }}")
        }

        try {
            stage("connecting")
            ActionOutcomeServiceReadiness().ensureService()
            root.refresh(requestPermission = true); gateway.refreshConnection()
            withTimeout(15_000) { while (!root.state.value.isConnected) delay(100) }
            val service = withTimeout(15_000) {
                var current = FlowChatAccessibilityService.current()
                while (current == null) { delay(100); current = FlowChatAccessibilityService.current() }
                current
            }
            task = tasks.begin(); tasks.arm(task)
            val physicalBefore = service.observeScreenOnDisplay(0)
            record.put("physical_before", JSONObject().put("package", physicalBefore?.packageName).put("window", physicalBefore?.windowId))
            val started = SystemClock.elapsedRealtime()
            stage("launching_tips_in_background")
            check(launcher.openApp("使用技巧") == "使用技巧")
            var screen = observe("Tips home or search on the background display") { s ->
                s.elements.any { it.resourceId == searchField } || s.elements.any { it.resourceId == tab }
            }
            val displayId = requireNotNull(screen.geometry).displayId
            check(displayId > 0) { "Tips did not open on an owned background display" }
            record.put("display_id", displayId).put("launch_ms", SystemClock.elapsedRealtime() - started)
            if (screen.elements.none { it.resourceId == searchField }) {
                val tabElement = screen.elements.single { it.resourceId == tab }
                check(gateway.tapUiElement(tabElement.index, false, screen.screenId).success)
                screen = observe("the Tips search entry") { s -> s.elements.any { it.resourceId == searchField } }
            }
            if (!hasSearch(screen)) {
                val entry = screen.elements.single { it.resourceId == searchField }
                check(!entry.editable && "set_text" !in entry.supportedActions && entry.clickable) {
                    "Collapsed home search trigger should not advertise direct text editing: $entry"
                }
                val directEdit = gateway.inputText(entry.index, Target, true, screen.screenId)
                check(!directEdit.success && directEdit.errorCode == "ui_input_target_invalid" &&
                    directEdit.actionOutcome == DeviceActionOutcome.NotDispatched) {
                    "Non-focusable home search trigger must refuse direct input_text: ${directEdit.errorCode}"
                }
                record.put("home_direct_edit_blocked", JSONObject().put("editable", entry.editable)
                    .put("error", directEdit.errorCode).put("outcome", directEdit.actionOutcome?.name))
                screen = requireNotNull(gateway.observeScreen().screen)
                val freshEntry = screen.elements.single { it.resourceId == searchField }
                check(gateway.tapUiElement(freshEntry.index, false, screen.screenId).success)
                screen = observe("the active Tips search field") { s -> hasSearch(s) && s.elements.any { it.resourceId == searchField && it.editable } }
            }
            record.put("entry", JSONObject().put("page", "search_field").put("elapsed_ms", SystemClock.elapsedRealtime() - started))
            save()

            stage("staging_wrong_query")
            var field = screen.elements.single { it.resourceId == searchField && it.editable }
            val wrong = gateway.inputText(field.index, WrongQuery, true, screen.screenId)
            check(wrong.success) { "Wrong query input failed: ${wrong.errorCode}" }
            screen = observe("the wrong query and old results") { s -> query(s) == WrongQuery && hasSearch(s) && titles(s).length() > 0 }
            record.put("initial", JSONObject().put("query", query(screen)).put("titles", titles(screen))
                .put("exact_result_visible", Target in screen.elements.map { it.text }))
            check(Target !in screen.elements.map { it.text }) { "The target is already in the wrong-query result" }
            val initialTitles = titles(screen).toString()
            save()

            stage("replacing_query_with_production_input")
            field = screen.elements.single { it.resourceId == searchField && it.editable && it.text == WrongQuery }
            val replaceStarted = SystemClock.elapsedRealtime()
            val replace = gateway.inputText(field.index, Target, true, screen.screenId)
            check(replace.success) { "Replacement failed: ${replace.errorCode}" }
            val replyScreen = screenOf(replace, "input_text")
            record.put("replace_reply", JSONObject().put("query", query(replyScreen)).put("titles", titles(replyScreen))
                .put("matching_result_in_reply", replyScreen.elements.any { it.resourceId == title && it.text == Target })
                .put("elapsed_ms", SystemClock.elapsedRealtime() - replaceStarted)
                .put("display_id", replyScreen.geometry?.displayId))
            val replyEditor = replyScreen.elements.single { it.resourceId == searchField && it.editable && it.text == Target }
            val staleReply = replyScreen.elements.none { it.resourceId == title && it.text == Target }
            if (staleReply) {
                // The old rows may still be in the reply; the editor action must refuse stale page content.
                val enter = gateway.performEditorAction(replyEditor.index, requireNotNull(replyEditor.editorActionLabel), replyScreen.screenId)
                record.put("stale_enter", JSONObject().put("success", enter.success).put("error", enter.errorCode)
                    .put("outcome", enter.actionOutcome?.name)
                    .put("fresh_result_in_reply", enter.screen?.elements?.any { it.resourceId == title && it.text == Target } == true))
                save()
                check(enter.errorCode == "stale_screen" && enter.actionOutcome == com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched &&
                    record.getJSONObject("stale_enter").getBoolean("fresh_result_in_reply")) {
                    "A stale background editor action must be refused with the fresh page: ${enter.errorCode} ${enter.actionOutcome}"
                }
            } else record.put("stale_enter", JSONObject().put("skipped", "reply already showed the exact result"))
            screen = observe("the exact searched article on the background display") { s ->
                query(s) == Target && s.elements.any { it.resourceId == title && it.text == Target }
            }
            record.put("results", JSONObject().put("query", query(screen)).put("titles", titles(screen))
                .put("verified_after_replace_ms", SystemClock.elapsedRealtime() - replaceStarted)
                .put("changed_from_wrong_results", titles(screen).toString() != initialTitles))
            save()

            stage("opening_exact_result")
            screen = requireNotNull(gateway.observeScreen().screen) { "No fresh result screen" }
            check(screen.packageName == pkg && query(screen) == Target)
            val targetLabel = screen.elements.single { it.resourceId == title && it.text == Target }
            val rowElement = screen.elements.single {
                it.resourceId == row && it.clickable && it.bounds.left <= targetLabel.bounds.left && it.bounds.right >= targetLabel.bounds.right &&
                    it.bounds.top <= targetLabel.bounds.top && it.bounds.bottom >= targetLabel.bounds.bottom
            }
            val opened = gateway.tapUiElement(rowElement.index, false, screen.screenId)
            record.put("open", JSONObject().put("success", opened.success).put("error", opened.errorCode))
            check(opened.success) { "Opening the result failed: ${opened.errorCode}" }
            val article = observe("the opened article body") { s ->
                s.elements.none { it.resourceId == cancel || it.resourceId == list } &&
                    s.elements.any { it.text == Target } && s.elements.any { (it.text?.length ?: 0) >= 12 && it.text != Target }
            }
            record.put("article", JSONObject().put("title", Target).put("display_id", article.geometry?.displayId)
                .put("visible_body", JSONArray(article.elements.mapNotNull { it.text }.filter { it.length >= 12 && it != Target }.distinct())))
            save()

            stage("returning_to_results")
            val back = gateway.pressBack()
            record.put("back", JSONObject().put("success", back.success).put("error", back.errorCode).put("outcome", back.actionOutcome?.name))
            check(back.success) { "Back failed: ${back.errorCode}" }
            val returned = observe("the preserved query and exact result after Back") { s ->
                query(s) == Target && s.elements.any { it.resourceId == title && it.text == Target }
            }
            record.put("returned", JSONObject().put("query", query(returned)).put("titles", titles(returned))
                .put("display_id", returned.geometry?.displayId).put("same_display", returned.geometry?.displayId == displayId))
            check(returned.geometry?.displayId == displayId)

            val physicalAfter = service.observeScreenOnDisplay(0)
            record.put("physical_after", JSONObject().put("package", physicalAfter?.packageName).put("window", physicalAfter?.windowId))
            check(physicalBefore?.packageName == physicalAfter?.packageName && physicalBefore?.windowId == physicalAfter?.windowId) {
                "The physical screen changed while Tips ran on the background display"
            }
            record.put("total_ms", SystemClock.elapsedRealtime() - started).put("stage", "recorded")
            save()
            stage("recorded")
        } finally {
            record.put("finally_stage", record.optString("stage")); save()
            tasks.finish(task)
            runCatching { withTimeout(15_000) { while (root.serviceInfo()?.activeDisplays != 0) delay(100) } }
                .onFailure { record.put("cleanup_error", it.toString()); save() }
            root.release()
        }
    }

    private companion object {
        const val WrongQuery = "简易模式"
        const val Target = "锁屏时钟通透效果"
    }
}
