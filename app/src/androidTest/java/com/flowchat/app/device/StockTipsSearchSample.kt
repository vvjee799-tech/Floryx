package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.tools.AgentToolDefinitions as Tools
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A real, public Tips article. Only the starting query is arranged by the fixture. */
internal class StockTipsSearchSample(
    private val automation: UiAutomation,
    private val chatPackage: String,
    private val screenshot: (String) -> Unit
) : StockSearchAcceptanceSample {
    override val packageName = "com.vivo.Tips"
    override val searchFieldId = id("vigour_search_edit")
    override val prompt = "请回到打开聊天前的「使用技巧」，搜索「${Target}」，打开这条说明并告诉我它介绍了什么。" +
        "看完回到能看到这条说明的搜索结果页。"
    override val evidence = JSONObject()

    private val cancelId = id("vigour_search_right_btn")
    private val clearId = id("vigour_search_clear_image")
    private val resultListId = id("search_result_recyclerView")
    private val resultTitleId = id("tv_title")
    private val resultRowId = id("search_item")
    private val backId = id("originui_vtoolbar_navigation_view_btn_rom14_0")
    private val searchControls = setOf(searchFieldId, cancelId, clearId, id("vigour_search_content"))
    private val resultsControls = setOf(resultTitleId, resultRowId, id("tv_content"), resultListId)
    private val readTools = setOf(Tools.ObserveScreenToolName, Tools.WaitForScreenToolName, Tools.ForegroundAppToolName,
        Tools.RecentAppActivityToolName, Tools.AppUsageSummaryToolName)
    private val calls = JSONArray()
    private var recording = false

    override suspend fun stage(shell: (String) -> String): JSONObject {
        check(shell("am start -W --display 0 -f 0x10008000 -n $packageName/.MainActivity").contains("Status: ok"))
        val tab = await("the Tips tab") { nodes().singleOrNull { it.viewIdResourceName == id("tab_tips_wrap") } }
        if (nodes().none { it.viewIdResourceName == searchFieldId }) tap(tab, shell)
        val entry = await("the Tips search entry") { nodes().singleOrNull { it.viewIdResourceName == searchFieldId } }
        tap(entry, shell)
        val edit = await("the active Tips search field") { search()?.edit }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, WrongQuery)
        }
        check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        return awaitInitial()
    }

    override suspend fun awaitInitial(): JSONObject = await("the original wrong query and result") {
        search()?.takeIf { it.query == WrongQuery && it.hasWrongResult && it.targetLabel == null }?.describe()
    }

    override suspend fun verifyToolPath(gateway: DeviceAssistantGateway) {
        val original = requireNotNull(gateway.observeScreen().screen)
        val field = original.elements.single { it.resourceId == searchFieldId && it.editable && it.text == WrongQuery }
        val started = SystemClock.elapsedRealtime()
        val input = gateway.inputText(field.index, Target, clearExisting = true, screenId = original.screenId)
        val reply = requireNotNull(input.screen)
        evidence.put("offline_input", JSONObject().put("success", input.success).put("error", input.errorCode)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("visible_result_titles", JSONArray(reply.elements.filter { it.resourceId == resultTitleId }.map { it.text }))
            .put("matching_result_in_tool_reply", reply.elements.any { it.resourceId == resultTitleId && it.text == Target }))
        check(input.success) { "Production text replacement failed: ${input.errorCode}" }
        val matches = await("the exact searched article") { search()?.takeIf { it.query == Target && it.targetRow != null } }
        evidence.put("offline_result", matches.describe()).put("results_loaded_after_edit_without_enter", true)
            .put("results_verified_after_input_ms", SystemClock.elapsedRealtime() - started)
        val editor = reply.elements.single { it.resourceId == searchFieldId && it.editable && it.text == Target }
        check(reply.elements.none { it.resourceId == resultTitleId && it.text == Target }) { "The stale result snapshot was not reproduced" }
        val enter = gateway.performEditorAction(editor.index, requireNotNull(editor.editorActionLabel), reply.screenId)
        val freshResult = enter.screen?.elements?.any { it.resourceId == resultTitleId && it.text == Target } == true
        evidence.put("offline_stale_enter", JSONObject().put("error", enter.errorCode)
            .put("outcome", enter.actionOutcome?.name).put("matching_result_in_tool_reply", freshResult))
        check(enter.actionOutcome == DeviceActionOutcome.NotDispatched && enter.errorCode == "stale_screen" && freshResult) {
            "The stale editor action did not return the refreshed result: ${enter.errorCode}"
        }
        screenshot("search-results")
        val current = requireNotNull(gateway.observeScreen().screen)
        val row = requireNotNull(matches.targetRow).bounds()
        val control = current.elements.single {
            it.resourceId == resultRowId && it.clickable && Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) == row
        }
        val opened = gateway.tapUiElement(control.index, longPress = false, screenId = current.screenId)
        evidence.put("offline_open", JSONObject().put("success", opened.success).put("error", opened.errorCode))
        val article = await("the opened article with visible text") { article(nodes()) }
        evidence.put("offline_article", article)
        screenshot("detail")
        val back = gateway.pressBack()
        evidence.put("offline_back", JSONObject().put("success", back.success).put("error", back.errorCode))
        val returned = await("the preserved query and matching result after Back") {
            search()?.takeIf { it.query == matches.query && it.targetRow != null }
        }
        evidence.put("offline_return", returned.describe())
        screenshot("final-list")

        val beforeTargetTap = requireNotNull(gateway.observeScreen().screen)
        val targetOpened = com.flowchat.app.data.device.UiTargetActionController(gateway).tap(
            com.flowchat.app.domain.device.DeviceUiTargetRequest(
                beforeTargetTap.screenId,
                com.flowchat.app.domain.device.DeviceUiSelector(text = Target),
                3_000
            ),
            beforeTargetTap
        )
        evidence.put("offline_tap_ui_target_text_only", JSONObject()
            .put("success", targetOpened.success)
            .put("error", targetOpened.errorCode ?: JSONObject.NULL)
            .put("outcome", targetOpened.actionOutcome?.name ?: JSONObject.NULL)
            .put("summary", targetOpened.summary))
        check(
            targetOpened.success || (
                targetOpened.errorCode == "ui_target_ambiguous" &&
                    targetOpened.actionOutcome == DeviceActionOutcome.NotDispatched &&
                    targetOpened.summary.contains("resource_id")
                )
        ) {
            "Unexpected text-only tap_ui_target outcome on Tips: ${targetOpened.errorCode}: ${targetOpened.summary}"
        }
        if (targetOpened.success) {
            await("the opened article from text-only tap_ui_target") { article(nodes()) }
            check(gateway.pressBack().success) { "Back after text-only tap_ui_target failed" }
            await("search results after text-only tap_ui_target Back") {
                search()?.takeIf { it.query == matches.query && it.targetRow != null }
            }
        }

        val beforeExactTap = requireNotNull(gateway.observeScreen().screen)
        val exactOpened = com.flowchat.app.data.device.UiTargetActionController(gateway).tap(
            com.flowchat.app.domain.device.DeviceUiTargetRequest(
                beforeExactTap.screenId,
                com.flowchat.app.domain.device.DeviceUiSelector(resourceId = resultTitleId, text = Target),
                3_000
            ),
            beforeExactTap
        )
        evidence.put("offline_tap_ui_target_with_resource_id", JSONObject()
            .put("success", exactOpened.success)
            .put("error", exactOpened.errorCode ?: JSONObject.NULL)
            .put("outcome", exactOpened.actionOutcome?.name ?: JSONObject.NULL)
            .put("summary", exactOpened.summary))
        check(exactOpened.success && exactOpened.errorCode == null) {
            "Production exact resource_id tap_ui_target failed on Tips: ${exactOpened.errorCode}: ${exactOpened.summary}"
        }
        val targetArticle = await("the opened article from exact tap_ui_target") { article(nodes()) }
        evidence.put("offline_tap_ui_target_article", targetArticle)
        val targetBack = gateway.pressBack()
        evidence.put("offline_tap_ui_target_back", JSONObject().put("success", targetBack.success).put("error", targetBack.errorCode ?: JSONObject.NULL))
        val targetReturned = await("the preserved query and matching result after exact tap_ui_target Back") {
            search()?.takeIf { it.query == matches.query && it.targetRow != null }
        }
        evidence.put("offline_tap_ui_target_return", targetReturned.describe())
    }

    override fun publicUiResult(result: DeviceToolResult): DeviceToolResult {
        val screen = result.screen
        val current = nodes()
        val ids = when {
            screen?.packageName != packageName -> emptySet()
            search(current) != null -> searchControls + resultsControls
            article(current) != null -> null // The verified public article can supply its visible text.
            else -> setOf(searchFieldId, id("vigour_search_content"))
        }
        val elements = screen?.elements?.filter {
            !it.password && (ids == null || it.resourceId in ids) &&
                (it.resourceId != searchFieldId || publicQuery(it.text.orEmpty()))
        }.orEmpty()
        return result.copy(screenImage = null, before = null, after = null,
            summary = if (screen == null || screen.packageName == packageName) result.summary else "Observed ${elements.size} public UI controls.",
            screen = screen?.copy(elements = elements, documentText = null))
    }

    override fun startRecording() {
        recording = true
        evidence.put("pre_dispatch_checks", calls)
    }

    override fun stopRecording() { recording = false }

    override fun checkActivePage() {
        if (automation.rootInActiveWindow?.packageName?.toString() == chatPackage) return
        val current = nodes()
        val search = search(current)
        val article = article(current)
        check(search != null || article != null || home(current)) { "Outside the staged Tips pages" }
        if (!recording) return
        if (search?.query == WrongQuery && !evidence.has("wrong_search_after_chat")) {
            evidence.put("wrong_search_after_chat", search.describe())
        }
        if (search?.targetRow != null && !evidence.has("matching_results")) {
            evidence.put("matching_results", search.describe())
            screenshot("search-results")
        }
        if (article != null && !evidence.has("opened_article")) {
            evidence.put("opened_article", article)
            screenshot("detail")
        }
    }

    override fun checkToolCalls(request: ChatRequest, calls: List<ChatToolCall>) {
        val inChat = automation.rootInActiveWindow?.packageName?.toString() == chatPackage
        val current = nodes()
        val search = search(current)
        val article = article(current)
        val home = home(current)
        check(inChat || search != null || article != null || home) { "Outside the staged Tips pages" }
        check(calls.count { it.name !in readTools } <= 1) { "Observe between actions" }
        val clickNodes = current.filter { node ->
            (search != null || home) && node.viewIdResourceName in searchControls ||
                article != null && node.viewIdResourceName == backId
        } + listOfNotNull(search?.targetRow, search?.targetLabel)
        for (call in calls) {
            val args = JSONObject(call.arguments)
            val allowed = when (call.name) {
                in readTools -> !args.optBoolean("include_document_text")
                Tools.PressBackToolName -> inChat || search != null || article != null
                Tools.OpenAppToolName -> inChat && args.optString("app_name") in setOf(packageName, "使用技巧")
                Tools.InputTextToolName -> search != null && correctQuery(args.optString("text")) &&
                    args.optString("mode", "replace") == "replace" && args.optBoolean("clear_existing", true) &&
                    indexedControl(request, args, listOf(search.edit))
                // Some replies still show the old result rows while the newly entered query is being filtered.
                Tools.PerformEditorActionToolName -> search != null && search.edit.isFocused && correctQuery(search.query) &&
                    indexedControl(request, args, listOf(search.edit))
                Tools.TapUiElementToolName -> !args.optBoolean("long_press") && indexedControl(request, args, clickNodes)
                Tools.TapUiTargetToolName -> args.optJSONObject("target")?.let { target ->
                    target.optString("text") == Target && search?.targetLabel != null ||
                        clickNodes.any { node -> node.viewIdResourceName in searchControls + backId &&
                            (node.viewIdResourceName == target.optString("resource_id") ||
                                node.contentDescription?.toString() == target.optString("description").takeIf(String::isNotBlank)) }
                } == true
                else -> false
            }
            this.calls.put(JSONObject().put("tool", call.name).put("page", when {
                inChat -> "chat"
                search != null -> "search"
                home -> "tips_home"
                else -> "article"
            }).put("query", search?.query).put("in_scope", allowed))
            check(allowed) { "Tool outside the read-only Tips search sample: ${call.name}" }
        }
    }

    override fun finishObservation(): JSONObject? = search()?.describe() ?: article(nodes())

    private fun indexedControl(request: ChatRequest, args: JSONObject, allowed: List<AccessibilityNodeInfo>): Boolean {
        val screenId = args.optString("screen_id").takeIf(String::isNotBlank) ?: return false
        val content = request.messages.lastOrNull {
            it.role == "tool" && "screen_id=$screenId" in it.content.orEmpty().lineSequence()
        }?.content.orEmpty()
        if ("package=$packageName" !in content.lineSequence()) return false
        val line = content.lineSequence().singleOrNull { it.startsWith("[${args.optInt("index", -1)}] ") } ?: return false
        return allowed.any { node ->
            val b = node.bounds()
            " id=${node.viewIdResourceName} " in line && line.endsWith(" bounds=${b.left},${b.top},${b.right},${b.bottom}")
        }
    }

    private inner class Search(val edit: AccessibilityNodeInfo, val labels: List<AccessibilityNodeInfo>) {
        val query = edit.text?.toString().orEmpty()
        val targetLabel = labels.singleOrNull { it.text?.toString() == Target }
        val targetRow = targetLabel?.let { label ->
            generateSequence(label) { it.parent }.firstOrNull {
                it.viewIdResourceName == resultRowId && it.isClickable && it.isEnabled && it.isVisibleToUser
            }
        }
        val hasWrongResult = labels.any { WrongQuery in it.text?.toString().orEmpty() }
        fun describe() = JSONObject().put("package", packageName).put("page", "search").put("query", query)
            .put("wrong_result_visible", hasWrongResult).put("exact_result_visible", targetRow != null)
    }

    private fun search(current: List<AccessibilityNodeInfo> = nodes()): Search? {
        if (current.none { it.viewIdResourceName == cancelId }) return null
        val edit = current.singleOrNull { it.viewIdResourceName == searchFieldId && it.isEditable } ?: return null
        if (!publicQuery(edit.text?.toString().orEmpty())) return null
        val labels = current.filter { it.viewIdResourceName == resultTitleId &&
            generateSequence(it.parent) { node -> node.parent }.any { node -> node.viewIdResourceName == resultListId } }
        return Search(edit, labels)
    }

    private fun article(current: List<AccessibilityNodeInfo>): JSONObject? {
        if (home(current) || current.any { it.viewIdResourceName == cancelId || it.viewIdResourceName == resultListId }) return null
        val texts = current.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.distinct()
        if (Target !in texts) return null
        val body = texts.filter { it != Target && it.length >= 12 }
        if (body.isEmpty()) return null
        return JSONObject().put("package", packageName).put("page", "article").put("title", Target).put("visible_body", JSONArray(body))
    }

    private fun home(current: List<AccessibilityNodeInfo>) =
        current.any { it.viewIdResourceName == searchFieldId && !it.isEditable } && current.none { it.viewIdResourceName == cancelId }

    private fun nodes(): List<AccessibilityNodeInfo> = descendants(automation.rootInActiveWindow)
        .filter { it.isVisibleToUser && it.packageName?.toString() == packageName }

    private fun descendants(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else buildList {
        add(root)
        repeat(root.childCount) { addAll(descendants(root.getChild(it))) }
    }

    private fun tap(node: AccessibilityNodeInfo, shell: (String) -> String) {
        check(node.isEnabled && node.isVisibleToUser)
        val b = node.bounds()
        shell("input tap ${b.centerX()} ${b.centerY()}")
    }

    private fun AccessibilityNodeInfo.bounds() = Rect().also(::getBoundsInScreen)
    private fun id(name: String) = "$packageName:id/$name"
    private fun correctQuery(query: String) = query.length >= 4 && query in Target
    private fun publicQuery(query: String) = query.isBlank() || query in setOf(WrongQuery, "搜索使用技巧") || correctQuery(query)

    private suspend fun <T : Any> await(description: String, read: () -> T?): T = withTimeoutOrNull(8_000) {
        var value = read()
        while (value == null) { delay(100); value = read() }
        value
    } ?: error("Could not verify $description")

    private companion object {
        const val WrongQuery = "简易模式"
        const val Target = "锁屏时钟通透效果"
    }
}
