package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.tools.AgentToolDefinitions as Tools
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal interface StockSearchAcceptanceSample : StockPageAcceptanceSample {
    val searchFieldId: String
    fun publicUiResult(result: DeviceToolResult): DeviceToolResult
    suspend fun verifyToolPath(gateway: DeviceAssistantGateway)
}

/** The default OriginOS Settings search, with independent result and return checks. */
internal class StockSettingsSearchSample(
    private val automation: UiAutomation,
    private val chatPackage: String,
    private val screenshot: (String) -> Unit,
    private val fromWrongPage: Boolean
) : StockSearchAcceptanceSample {
    override val packageName = "com.android.settings"
    override val prompt = (if (fromWrongPage) "请先回到打开聊天前的那个设置页面，再在系统设置里搜索" else "请在系统设置里搜索") +
        "「屏幕刷新率」，告诉我当前选中的档位。查完回到能看到这一项的搜索结果页，保持原设置。"
    override val evidence = JSONObject()
    private val checks = JSONArray()
    override val searchFieldId = "$packageName:id/vigour_search_edit"
    private val editId get() = searchFieldId
    private val cancelId = "$packageName:id/vigour_search_right_btn"
    private val landingId = "$packageName:id/homepage_container"
    private val titleId = "$packageName:id/originui_vtoolbar_normal_title_rom14_0"
    private val backId = "$packageName:id/originui_vtoolbar_navigation_view_btn_rom14_0"
    private val rowTitleId = "$packageName:id/title"
    private val searchControlIds = setOf(editId, "$packageName:id/vigour_search_content", "$packageName:id/vigour_search_indicator_image")
    private val readTools = setOf(Tools.ObserveScreenToolName, Tools.WaitForScreenToolName, Tools.ForegroundAppToolName,
        // AutonomyRuntimeFixture answers usage queries from an isolated, empty reader.
        Tools.RecentAppActivityToolName, Tools.AppUsageSummaryToolName)
    private val seen = mutableSetOf<String>()

    override suspend fun stage(shell: (String) -> String): JSONObject {
        check(shell("am start -W --display 0 -f 0x10008000 -a android.settings.APP_SEARCH_SETTINGS").contains("Status: ok"))
        if (fromWrongPage) {
            await("Settings homepage") { home() }
            val row = await("the sound settings entry") {
                settingsNodes().singleOrNull { it.viewIdResourceName == "android:id/title" && it.text?.toString() == WrongPageTitle }
                    ?.let(::clickableRow)
            }
            check(row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not stage the unrelated settings page" }
        }
        return awaitInitial()
    }

    override suspend fun awaitInitial(): JSONObject {
        var stableSince = SystemClock.elapsedRealtime()
        var previous: String? = null
        return await("the stable Settings starting page") {
            val candidate = if (fromWrongPage) wrongPage()?.let {
                JSONObject().put("package", packageName).put("page", "wrong_settings").put("title", WrongPageTitle)
                    .put("title_bounds", it.bounds().flattenToString()).put("exact_result_visible", false)
            } else home()?.let { JSONObject().put("package", packageName).put("page", "settings_home")
                .put("query_bounds", it.bounds().flattenToString()).put("exact_result_visible", false) }
            val serialized = candidate?.toString()
            val now = SystemClock.elapsedRealtime()
            if (serialized != previous) stableSince = now
            previous = serialized
            candidate?.takeIf { now - stableSince >= 400 }
        }
    }

    override fun publicUiResult(result: DeviceToolResult): DeviceToolResult {
        val controlsOnly = if (result.screenImage == null) result else result.copy(screenImage = null,
            imageErrorCode = "sample_controls_only",
            summary = result.screen?.let { "Observed ${it.elements.size} visible UI controls." } ?: result.summary)
        val screen = controlsOnly.screen ?: return controlsOnly
        if (screen.packageName != packageName) return controlsOnly
        val editable = screen.elements.singleOrNull { it.resourceId == editId }
        val detail = screen.elements.any { it.resourceId == titleId && it.text == Target }
        if (detail || correctQuery(editable?.text.orEmpty())) return controlsOnly
        val controls = screen.elements.map { element ->
            element.copy(text = element.text?.takeIf(::publicLabel), contentDescription = element.contentDescription?.takeIf(::publicLabel),
                hint = element.hint?.takeIf(::publicLabel), editorActionLabel = element.editorActionLabel?.takeIf(::publicLabel))
        }
        return controlsOnly.copy(screen = screen.copy(elements = controls, documentText = null))
    }

    override suspend fun verifyToolPath(gateway: DeviceAssistantGateway) {
        if (fromWrongPage) {
            val back = gateway.pressBack()
            evidence.put("offline_exit_wrong_page", JSONObject().put("success", back.success).put("error", back.errorCode))
            check(back.success) { "Production Back did not leave the unrelated settings page: ${back.errorCode}" }
            await("Settings homepage after leaving the unrelated page") { home() }
        }
        val initial = requireNotNull(gateway.observeScreen().screen)
        check(initial.elements.none { it.resourceId?.endsWith(":id/account_title") == true && it.text != null })
        val editor = initial.elements.single { it.resourceId == editId }
        val activated = gateway.tapUiElement(editor.index, longPress = false, screenId = initial.screenId)
        evidence.put("offline_activate", JSONObject().put("success", activated.success).put("error", activated.errorCode))
        await("the expanded search editor") { search()?.takeIf { it.edit.isFocused } }
        val editing = requireNotNull(gateway.observeScreen().screen)
        val edit = editing.elements.single { it.resourceId == editId && it.editable }
        val typed = gateway.inputText(edit.index, Target, clearExisting = true, screenId = editing.screenId)
        evidence.put("offline_input", JSONObject().put("success", typed.success).put("error", typed.errorCode))
        check(typed.success) { "Production search input failed: ${typed.errorCode}" }
        val result = await("the exact refresh-rate search result") { search()?.takeIf { it.row != null && correctQuery(it.query) } }
        evidence.put("offline_result", result.describe())
        val snapshot = requireNotNull(gateway.observeScreen().screen)
        val row = requireNotNull(result.row).bounds()
        val index = snapshot.elements.single { it.clickable && it.enabled &&
            Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) == row }.index
        val opened = gateway.tapUiElement(index, longPress = false, screenId = snapshot.screenId)
        evidence.put("offline_open", JSONObject().put("success", opened.success).put("error", opened.errorCode))
        evidence.put("offline_open_returned", JSONObject().put("package", opened.screen?.packageName)
            .put("toolbar", opened.screen?.elements?.firstOrNull { it.resourceId == titleId }?.text))
        val detail = await("the refresh-rate detail and checked option") { detail() }
        evidence.put("offline_detail", detail)
        val back = gateway.pressBack()
        evidence.put("offline_back", JSONObject().put("success", back.success).put("error", back.errorCode))
        val returned = await("the same search result after Back") { search()?.takeIf { it.query == result.query && it.row != null } }
        evidence.put("offline_return", returned.describe())
    }

    override fun startRecording() { evidence.put("pre_dispatch_checks", checks) }

    override fun stopRecording() = Unit

    override fun checkActivePage() {
        if (automation.rootInActiveWindow?.packageName?.toString() == chatPackage) return
        val nodes = settingsNodes()
        val search = search(nodes)
        val detail = detail(nodes)
        check(home(nodes) != null || wrongPage(nodes) != null || search != null || detail != null) {
            "Outside the staged Settings search and refresh-rate detail"
        }
        if (search?.row != null && correctQuery(search.query) && seen.add("results")) {
            evidence.put("matching_results", search.describe())
            screenshot("search-results")
        }
        if (detail != null && seen.add("detail")) {
            evidence.put("checked_detail", detail)
            screenshot("detail")
        }
    }

    override fun checkToolCalls(request: ChatRequest, calls: List<ChatToolCall>) {
        val inChat = automation.rootInActiveWindow?.packageName?.toString() == chatPackage
        val nodes = settingsNodes()
        val home = home(nodes)
        val wrong = wrongPage(nodes)
        val search = search(nodes)
        val detail = detail(nodes)
        val backControl = nodes.singleOrNull { (detail != null || wrong != null) && it.viewIdResourceName == backId }
        val searchControls = if (home != null || search != null) nodes.filter { it.viewIdResourceName in searchControlIds } else emptyList()
        check(inChat || home != null || wrong != null || search != null || detail != null)
        check(calls.count { it.name !in readTools } <= 1) { "Observe between actions" }
        for (call in calls) {
            val tested = JSONObject().put("tool", call.name).put("page", when {
                inChat -> "chat"
                home != null -> "settings_home"
                wrong != null -> "wrong_settings"
                search != null -> "search"
                else -> "detail"
            })
            checks.put(tested)
            val args = JSONObject(call.arguments)
            val allowed = when (call.name) {
                in readTools -> !args.optBoolean("include_document_text")
                Tools.OpenAppToolName -> (inChat || wrong != null) && args.optString("app_name") in setOf("设置", "系统设置", packageName)
                Tools.PressBackToolName -> inChat || wrong != null || detail != null
                Tools.InputTextToolName -> search != null && correctQuery(args.optString("text")) &&
                    args.optString("mode", "replace") == "replace" && args.optBoolean("clear_existing", true) &&
                    indexedControl(request, args, listOf(search.edit))
                Tools.TapUiElementToolName -> !args.optBoolean("long_press") && indexedControl(request, args,
                    searchControls + listOfNotNull(search?.row, search?.label, backControl))
                Tools.TapUiTargetToolName -> {
                    val target = args.optJSONObject("target")
                    val result = search?.row != null && target?.optString("text") == Target
                    val editor = home ?: search?.edit
                    val editorLabel = editor?.text?.toString()?.takeIf(::publicLabel)
                    val edit = editor != null && target != null && (searchControls.any { it.viewIdResourceName == target.optString("resource_id") } ||
                        editorLabel != null && target.optString("text") == editorLabel)
                    val back = backControl != null && (target?.optString("resource_id") == backId || target?.optString("description") == "返回")
                    result || edit || back
                }
                else -> false
            }
            tested.put("in_scope", allowed)
            check(allowed) { "Tool outside the read-only Settings search sample: ${call.name}" }
        }
    }

    override fun finishObservation(): JSONObject? = search()?.describe() ?: detail()

    private fun indexedControl(request: ChatRequest, args: JSONObject, allowed: List<AccessibilityNodeInfo>): Boolean {
        val screenId = args.optString("screen_id").takeIf { it.isNotBlank() } ?: return false
        val content = request.messages.lastOrNull {
            it.role == "tool" && "screen_id=$screenId" in it.content.orEmpty().lineSequence()
        }?.content.orEmpty()
        if ("package=$packageName" !in content.lineSequence()) return false
        val line = content.lineSequence().singleOrNull { it.startsWith("[${args.optInt("index", -1)}] ") } ?: return false
        return allowed.any { node ->
            val bounds = node.bounds()
            val id = node.viewIdResourceName
            (id == null || " id=$id " in line) && line.endsWith(" bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
        }
    }

    private fun search(nodes: List<AccessibilityNodeInfo> = settingsNodes()): Search? {
        if (nodes.none { it.viewIdResourceName == cancelId }) return null
        val edit = nodes.singleOrNull { it.viewIdResourceName == editId && it.isEditable } ?: return null
        val query = edit.text?.toString().orEmpty()
        if (query.isNotBlank() && !publicLabel(query) && !correctQuery(query)) return null
        val label = nodes.singleOrNull { !it.isEditable && it.text?.toString() == Target }
        val row = label?.let(::clickableRow)
        val empty = nodes.any { it.viewIdResourceName == "$packageName:id/blank_text" }
        return Search(query, edit, row, label, empty)
    }

    private fun clickableRow(label: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(label) { it.parent }.firstOrNull { node ->
            node.isVisibleToUser && node.isClickable && node.isEnabled && !node.isCheckable &&
                node.packageName?.toString() == packageName && node.bounds().height() in 1..500
        }

    private fun home(nodes: List<AccessibilityNodeInfo> = settingsNodes()): AccessibilityNodeInfo? =
        if (nodes.any { it.viewIdResourceName == landingId } && nodes.none { it.viewIdResourceName == cancelId })
            nodes.singleOrNull { it.viewIdResourceName == editId }
        else null

    private fun wrongPage(nodes: List<AccessibilityNodeInfo> = settingsNodes()): AccessibilityNodeInfo? =
        if (fromWrongPage) nodes.singleOrNull { it.viewIdResourceName == titleId && it.text?.toString() == WrongPageTitle }
        else null

    private fun detail(nodes: List<AccessibilityNodeInfo> = settingsNodes()): JSONObject? {
        if (nodes.none { it.viewIdResourceName == titleId && it.text?.toString() == Target }) return null
        val selected = nodes.singleOrNull { it.isCheckable && it.isChecked && it.className?.toString() == "android.widget.RadioButton" }
            ?: return null
        val option = descendants(selected).singleOrNull { it.viewIdResourceName == rowTitleId }?.text?.toString() ?: return null
        return JSONObject().put("package", packageName).put("page", "detail").put("detail_title", Target)
            .put("checked_option", option).put("checked_option_description", selected.contentDescription?.toString())
    }

    private inner class Search(val query: String, val edit: AccessibilityNodeInfo, val row: AccessibilityNodeInfo?,
        val label: AccessibilityNodeInfo?, val empty: Boolean) {
        fun describe() = JSONObject().put("package", packageName).put("page", "search").put("query", query)
            .put("empty_results", empty).put("exact_result_visible", row != null)
            .put("query_bounds", edit.bounds().flattenToString()).put("result_bounds", row?.bounds()?.flattenToString())
    }

    private fun settingsNodes() = descendants(automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName })
        .filter { it.isVisibleToUser }

    private fun AccessibilityNodeInfo.bounds() = Rect().also(::getBoundsInScreen)

    private fun descendants(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else buildList {
        add(root)
        repeat(root.childCount) { addAll(descendants(root.getChild(it))) }
    }

    private suspend fun <T : Any> await(label: String, observe: () -> T?): T =
        withTimeoutOrNull(8_000) {
            var observation = observe()
            while (observation == null) { delay(100); observation = observe() }
            observation
        } ?: error("Could not verify $label")

    private fun correctQuery(query: String) = query.length >= 3 && query in Target

    private fun publicLabel(label: String) = label in PublicLabels || SearchHint.matches(label)

    companion object {
        private const val Target = "屏幕刷新率"
        private const val WrongPageTitle = "声音与振动"
        private val SearchHint = Regex("搜索[手机系统设置项内容]{0,8}")
        private val PublicLabels = setOf("设置", "取消", "返回", "搜索历史", "搜索记录", "清除", "显示、亮度与护眼",
            WrongPageTitle, "蓝牙", "WLAN", "移动网络", "应用与权限", "通知与状态栏", "系统导航", "关于手机", "系统管理")
    }
}
