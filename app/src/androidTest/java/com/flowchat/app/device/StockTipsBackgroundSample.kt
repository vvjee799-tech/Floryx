package com.flowchat.app.device

import android.app.UiAutomation
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.tools.AgentToolDefinitions as Tools
import org.json.JSONArray
import org.json.JSONObject

/**
 * The public Tips search on the owned background display. The physical screen stays on the Floryx chat. Evidence comes from
 * the production tool replies, which are reduced to public Tips controls before they reach the model.
 */
internal class StockTipsBackgroundSample(
    private val automation: UiAutomation,
    private val chatPackage: String,
    private val screenshot: (String) -> Unit
) : StockSearchAcceptanceSample {
    override val packageName = "com.vivo.Tips"
    override val searchFieldId = id("vigour_search_edit")
    override val prompt = "请在后台打开「使用技巧」，搜索「${Target}」，打开这条说明并告诉我它介绍了什么。" +
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
    private var last: JSONObject? = null
    private var articleSeen = false
    private var physicalWindow: String? = null

    override suspend fun stage(shell: (String) -> String): JSONObject {
        shell("am start -W -n $chatPackage/com.flowchat.app.MainActivity")
        return awaitInitial()
    }

    override suspend fun awaitInitial(): JSONObject = JSONObject().put("page", "floryx_chat_foreground")
        .put("tips_started", false).put("execution", "background")

    // The same production tools are exercised with no model request by StockTipsBackgroundPreflightTest.
    override suspend fun verifyToolPath(gateway: DeviceAssistantGateway) = Unit

    override fun publicUiResult(result: DeviceToolResult): DeviceToolResult {
        val screen = result.screen
        val state = screen?.takeIf { it.packageName == packageName }?.let(::describe)
        if (state != null && recording) {
            last = state
            if (state.optString("page") == "article") {
                articleSeen = true
                if (!evidence.has("opened_article")) evidence.put("opened_article", state)
            }
            if (state.optString("page") == "search" && state.optBoolean("exact_result_visible")) {
                if (!evidence.has("matching_results")) evidence.put("matching_results", state)
                if (articleSeen) evidence.put("returned_results", state)
            }
        }
        val ids = when (state?.optString("page")) {
            null -> emptySet()
            "search" -> searchControls + resultsControls
            "article" -> null // The verified public article can supply its visible text.
            else -> setOf(searchFieldId, id("vigour_search_content"), id("tab_tips_wrap"), id("tab_txt_tips"))
        }
        val elements = screen?.elements?.filter {
            !it.password && (ids == null || it.resourceId in ids) &&
                (it.resourceId != searchFieldId || publicQuery(it.text.orEmpty()))
        }.orEmpty()
        return result.copy(screenImage = null, before = null, after = null,
            summary = if (screen == null || screen.packageName == packageName) result.summary else "Observed ${elements.size} public UI controls.",
            screen = screen?.copy(elements = elements, documentText = null))
    }

    private fun describe(screen: DeviceScreenSnapshot): JSONObject {
        val search = screen.elements.any { it.resourceId == cancelId }
        val query = screen.elements.firstOrNull { it.resourceId == searchFieldId }?.text.orEmpty()
        val titles = screen.elements.filter { it.resourceId == resultTitleId }.mapNotNull { it.text }
        val texts = screen.elements.mapNotNull { it.text?.takeIf(String::isNotBlank) }.distinct()
        val body = texts.filter { it != Target && it.length >= 12 }
        val isHome = screen.elements.any {
            it.resourceId in setOf(id("vtab_layout_wrap"), id("tab_tips_wrap"), id("tab_txt_tips"))
        } || (screen.elements.any { it.resourceId == searchFieldId } && !search)
        val page = when {
            search -> "search"
            !isHome && screen.elements.none { it.resourceId == resultListId } && Target in texts && body.isNotEmpty() -> "article"
            else -> "tips_home"
        }
        val out = JSONObject().put("package", packageName).put("page", page).put("display_id", screen.geometry?.displayId)
        if (page == "search") out.put("query", query).put("exact_result_visible", Target in titles)
        if (page == "article") out.put("title", Target).put("visible_body", JSONArray(body))
        return out
    }

    override fun startRecording() {
        recording = true
        physicalWindow = automation.rootInActiveWindow?.packageName?.toString()
        evidence.put("pre_dispatch_checks", calls).put("physical_package_at_start", physicalWindow)
    }

    override fun stopRecording() { recording = false }

    override fun checkActivePage() {
        val physical = automation.rootInActiveWindow?.packageName?.toString()
        check(physical != packageName) { "The background Tips page reached the physical display" }
        if (recording) evidence.put("physical_package_latest", physical)
    }

    override fun checkToolCalls(request: ChatRequest, calls: List<ChatToolCall>) {
        check(automation.rootInActiveWindow?.packageName?.toString() != packageName) {
            "The background Tips page reached the physical display"
        }
        check(calls.count { it.name !in readTools } <= 1) { "Observe between actions" }
        val allowedTapIds = searchControls + setOf(id("tab_tips_wrap"), id("tab_txt_tips"), backId, resultRowId, resultTitleId)
        for (call in calls) {
            val args = JSONObject(call.arguments)
            val allowed = when (call.name) {
                in readTools -> !args.optBoolean("include_document_text")
                Tools.PressBackToolName -> true
                Tools.OpenAppToolName -> args.optString("app_name") in setOf(packageName, "使用技巧")
                Tools.InputTextToolName -> last?.optString("page") == "search" && correctQuery(args.optString("text")) &&
                    args.optString("mode", "replace") == "replace" && args.optBoolean("clear_existing", true) &&
                    indexedLine(request, args)?.contains(" id=$searchFieldId ") == true
                Tools.PerformEditorActionToolName -> last?.optString("page") == "search" &&
                    indexedLine(request, args)?.contains(" id=$searchFieldId ") == true
                Tools.TapUiElementToolName -> !args.optBoolean("long_press") &&
                    indexedLine(request, args)?.let { line ->
                        allowedTapIds.any { " id=$it " in line } &&
                            (" id=$resultRowId " !in line && " id=$resultTitleId " !in line || targetRowTapped(request, args, line))
                    } == true
                Tools.TapUiTargetToolName -> args.optJSONObject("target")?.let { target ->
                    target.optString("text") == Target ||
                        target.optString("resource_id") in allowedTapIds - setOf(resultRowId, resultTitleId) ||
                        target.optString("description") in setOf("返回", "搜索使用技巧")
                } == true
                else -> false
            }
            this.calls.put(JSONObject().put("tool", call.name).put("in_scope", allowed))
            check(allowed) { "Tool outside the read-only background Tips search sample: ${call.name}" }
        }
    }

    private fun indexedLine(request: ChatRequest, args: JSONObject): String? {
        val screenId = args.optString("screen_id").takeIf(String::isNotBlank) ?: return null
        val content = request.messages.lastOrNull {
            it.role == "tool" && "screen_id=$screenId" in it.content.orEmpty().lineSequence()
        }?.content.orEmpty()
        if ("package=$packageName" !in content.lineSequence()) return null
        return content.lineSequence().singleOrNull { it.startsWith("[${args.optInt("index", -1)}] ") }
    }

    private fun targetRowTapped(request: ChatRequest, args: JSONObject, line: String): Boolean {
        if ("\"$Target\"" in line) return true
        val screenId = args.optString("screen_id").takeIf(String::isNotBlank) ?: return false
        val index = args.optInt("index", -1)
        val content = request.messages.lastOrNull {
            it.role == "tool" && "screen_id=$screenId" in it.content.orEmpty().lineSequence()
        }?.content.orEmpty()
        return content.lineSequence().any { " parent=$index " in it && "\"$Target\"" in it }
    }

    override fun finishObservation(): JSONObject? = last

    private fun id(name: String) = "$packageName:id/$name"
    private fun correctQuery(query: String) = query.length >= 4 && query in Target
    private fun publicQuery(query: String) = query.isBlank() || query in setOf("搜索使用技巧") || correctQuery(query)

    private companion object {
        const val Target = "锁屏时钟通透效果"
    }
}
