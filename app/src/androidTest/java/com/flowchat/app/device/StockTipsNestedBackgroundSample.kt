package com.flowchat.app.device

import android.app.UiAutomation
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.tools.AgentToolDefinitions as Tools
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real-app F04 nested-container scrolling on the owned background display using native Vivo Tips.
 * The physical screen remains on Floryx chat; only public Tips controls are exposed to Flash.
 */
internal class StockTipsNestedBackgroundSample(
    private val automation: UiAutomation,
    private val chatPackage: String,
    private val screenshot: (String) -> Unit
) : StockPageAcceptanceSample {
    override val packageName = "com.vivo.Tips"
    override val prompt = "请在后台打开「使用技巧」，在主页的推荐列表里向下滑动找到「悦目护眼」，" +
        "等这一项完整显示后打开它，并告诉我它介绍了什么。不要通过搜索查找。"
    override val evidence = JSONObject()

    private val recyclerId = id("recycler_view")
    private val viewPagerId = id("view_pager")
    private val bannerPagerId = id("banners_content")
    private val searchFieldId = id("vigour_search_edit")
    private val searchWrapId = id("vigour_search_content")
    private val readTools = setOf(
        Tools.ObserveScreenToolName,
        Tools.WaitForScreenToolName,
        Tools.ForegroundAppToolName,
        Tools.RecentAppActivityToolName,
        Tools.AppUsageSummaryToolName
    )
    private val calls = JSONArray()
    private val scrollRequests = JSONArray()
    private val observations = JSONArray()
    private var recording = false
    private var lastState: JSONObject? = null
    private var physicalWindow: String? = null

    override suspend fun stage(shell: (String) -> String): JSONObject {
        shell("am force-stop $packageName")
        shell("am start -W -n $chatPackage/com.flowchat.app.MainActivity")
        return awaitInitial()
    }

    override suspend fun awaitInitial(): JSONObject = JSONObject()
        .put("page", "floryx_chat_foreground")
        .put("tips_started", false)
        .put("execution", "background")
        .put("target", Target)

    fun publicUiResult(result: DeviceToolResult): DeviceToolResult {
        val screen = result.screen
        if (screen != null && screen.packageName == packageName && recording) {
            val described = describe(screen.elements, screen.geometry?.displayId)
            lastState = described
            observations.put(described)
            if (described.optBoolean("target_visible") && !evidence.has("first_target_sighting")) {
                evidence.put("first_target_sighting", described)
            }
            if (described.optString("page") == "article" && !evidence.has("opened_article")) {
                evidence.put("opened_article", described)
            }
        }
        val elements = if (screen?.packageName == packageName) {
            screen.elements.filter { el ->
                !el.password &&
                    el.text != "设置" &&
                    el.contentDescription != "设置" &&
                    (el.resourceId != searchFieldId || el.text in setOf(null, "", "搜索使用技巧"))
            }
        } else {
            emptyList()
        }
        return result.copy(
            screenImage = null,
            before = null,
            after = null,
            summary = if (screen == null || screen.packageName == packageName) result.summary
                else "Observed ${elements.size} public UI controls.",
            screen = screen?.copy(elements = elements, documentText = null)
        )
    }

    private fun describe(elements: List<DeviceUiElement>, displayId: Int?): JSONObject {
        val hasRecycler = elements.any { it.resourceId == recyclerId }
        val texts = elements.mapNotNull { it.text?.takeIf(String::isNotBlank) }.distinct()
        val body = texts.filter { it != Target && it.length >= 12 }
        val targetEl = elements.firstOrNull { it.text == Target }
        val page = when {
            hasRecycler -> "tips_home"
            Target in texts && body.isNotEmpty() -> "article"
            else -> "other"
        }
        val out = JSONObject().put("package", packageName).put("page", page).put("display_id", displayId)
        if (page == "tips_home") {
            out.put("scrollable_ids", JSONArray(elements.filter { it.scrollable }.map { "${it.index}:${it.resourceId ?: it.className}" }))
            out.put("target_visible", targetEl != null)
            if (targetEl != null) {
                out.put("target_bounds", targetEl.bounds.toString())
            }
        }
        if (page == "article") {
            out.put("title", Target).put("visible_body", JSONArray(body))
        }
        return out
    }

    override fun startRecording() {
        recording = true
        physicalWindow = automation.rootInActiveWindow?.packageName?.toString()
        evidence.put("pre_dispatch_checks", calls)
            .put("scroll_requests", scrollRequests)
            .put("observations", observations)
            .put("physical_package_at_start", physicalWindow)
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
        for (call in calls) {
            val args = JSONObject(call.arguments)
            val allowed = when (call.name) {
                in readTools -> !args.optBoolean("include_document_text")
                Tools.OpenAppToolName -> args.optString("app_name") in setOf(packageName, "使用技巧")
                Tools.PressBackToolName -> true
                Tools.ScrollContainerToolName, Tools.SwipeScreenToolName -> {
                    val containerIndex = if (call.name == Tools.ScrollContainerToolName) args.optInt("index", -1)
                        else args.optInt("container_index", -1)
                    val screenId = args.optString("screen_id")
                    val line = if (containerIndex >= 0 && screenId.isNotBlank()) {
                        findLine(request, screenId, containerIndex)
                    } else null
                    val containerId = line?.let { Regex(""" id=([^\s]+)""").find(it)?.groupValues?.get(1) }
                    scrollRequests.put(
                        JSONObject().put("tool", call.name)
                            .put("container_index", containerIndex)
                            .put("container_id", containerId ?: JSONObject.NULL)
                            .put("is_inner_recycler", containerId == recyclerId)
                            .put("is_pager_or_banner", containerId in setOf(viewPagerId, bannerPagerId))
                            .put("target_visible_before", lastState?.optBoolean("target_visible") == true)
                            .put("page", lastState?.optString("page") ?: JSONObject.NULL)
                            .put("arguments", args)
                    )
                    lastState?.optString("page") in setOf("tips_home", "article")
                }
                Tools.TapUiElementToolName -> !args.optBoolean("long_press") &&
                    isTargetCardTap(request, args).also { ok ->
                        if (ok) evidence.put("tapped_target", args)
                    }
                Tools.TapUiTargetToolName -> (args.optJSONObject("target")?.let { target ->
                    target.optString("text") == Target || Target in target.optString("description")
                } == true).also { ok ->
                    if (ok) evidence.put("tapped_target", args)
                }
                else -> false
            }
            this.calls.put(JSONObject().put("tool", call.name).put("in_scope", allowed))
            check(allowed) { "Tool outside the read-only F04 nested Tips sample: ${call.name} (${call.arguments})" }
        }
    }

    private fun screenContent(request: ChatRequest, screenId: String): String {
        return request.messages.lastOrNull {
            it.role == "tool" && "screen_id=$screenId" in it.content.orEmpty().lineSequence()
        }?.content.orEmpty()
    }

    private fun findLine(request: ChatRequest, screenId: String, index: Int): String? {
        val content = screenContent(request, screenId)
        if ("package=$packageName" !in content.lineSequence()) return null
        return content.lineSequence().singleOrNull { it.startsWith("[$index] ") }
    }

    private fun isTargetCardTap(request: ChatRequest, args: JSONObject): Boolean {
        val screenId = args.optString("screen_id").takeIf(String::isNotBlank) ?: return false
        val index = args.optInt("index", -1)
        val content = screenContent(request, screenId)
        if ("package=$packageName" !in content.lineSequence()) return false
        val line = content.lineSequence().singleOrNull { it.startsWith("[$index] ") } ?: return false
        if (searchFieldId in line || searchWrapId in line) return false
        if (Target in line) return true
        return content.lineSequence().any { " parent=$index " in it && Target in it }
    }

    override fun finishObservation(): JSONObject? = lastState

    private fun id(name: String) = "$packageName:id/$name"

    private companion object {
        const val Target = "悦目护眼"
    }
}
