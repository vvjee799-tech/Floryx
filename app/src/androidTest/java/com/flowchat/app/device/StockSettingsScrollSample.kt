package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.tools.AgentToolDefinitions as Tools
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal interface StockPageAcceptanceSample {
    val packageName: String
    val evidence: JSONObject
    val prompt: String
    suspend fun stage(shell: (String) -> String): JSONObject
    suspend fun awaitInitial(): JSONObject
    fun startRecording()
    fun stopRecording()
    fun checkActivePage()
    fun checkToolCalls(request: ChatRequest, calls: List<ChatToolCall>)
    fun finishObservation(): JSONObject?
}

/** Observe the stock iQOO display list without adding its measurements to the model context. */
internal class StockSettingsScrollSample(
    private val automation: UiAutomation,
    private val chatPackage: String,
    private val screenshot: (String) -> Unit
) : StockPageAcceptanceSample {
    override val packageName = "com.android.settings"
    override val evidence = JSONObject()
    override val prompt = Prompt
    private val gestures = JSONArray()
    private val scrollEvents = JSONArray()
    private val toolChecks = JSONArray()
    private val titleId = "$packageName:id/originui_vtoolbar_normal_title_rom14_0"
    private val rowTitleId = "$packageName:id/title"
    private val recyclerId = "$packageName:id/recycler_view"

    override suspend fun stage(shell: (String) -> String): JSONObject {
        check(shell("am start -W --display 0 -f 0x10008000 -a android.settings.DISPLAY_SETTINGS").contains("Status: ok"))
        return awaitInitial()
    }

    override suspend fun awaitInitial(): JSONObject = withTimeout(10_000) {
        var previous: String? = null
        var stableSince = 0L
        var initial: JSONObject? = null
        while (initial == null) {
            val root = automation.rootInActiveWindow
            var candidate: JSONObject? = null
            if (title(root) == DisplayTitle) {
                val visible = nodes(root).filter { it.isVisibleToUser }
                val recycler = visible.singleOrNull { it.viewIdResourceName == recyclerId && it.isScrollable }
                val anchor = visible.singleOrNull { it.viewIdResourceName == rowTitleId && it.text?.toString() == "定时切换" }
                if (recycler != null && anchor != null && target(visible) == null) {
                    candidate = JSONObject().put("page", DisplayTitle).put("package", packageName)
                        .put("container_class", recycler.className.toString()).put("container_id", recyclerId)
                        .put("container_bounds", bounds(recycler).flattenToString())
                        .put("top_anchor", anchor.text.toString()).put("top_anchor_bounds", bounds(anchor).flattenToString())
                        .put("target_visible", false)
                }
            }
            val current = candidate?.toString()
            val now = SystemClock.elapsedRealtime()
            if (current != previous) {
                previous = current
                stableSince = now
            }
            // OriginOS can report the foreground window before the launch transition finishes.
            if (current != null && now - stableSince >= 400) initial = candidate
            if (initial == null) delay(50)
        }
        initial
    }

    override fun startRecording() {
        evidence.put("scroll_commands", gestures).put("pre_dispatch_checks", toolChecks)
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED || event.packageName?.toString() != packageName)
                return@setOnAccessibilityEventListener
            val record = JSONObject().put("event_ms", event.eventTime).put("class", event.className?.toString())
                .put("container_id", event.source?.viewIdResourceName ?: JSONObject.NULL)
                .put("from_index", event.fromIndex).put("to_index", event.toIndex)
                .put("scroll_y", event.scrollY).put("delta_y", event.scrollDeltaY)
            synchronized(scrollEvents) { scrollEvents.put(record) }
        }
    }

    override fun stopRecording() = automation.setOnAccessibilityEventListener(null)

    override fun checkActivePage() {
        val root = automation.rootInActiveWindow
        check(root?.packageName?.toString() == chatPackage || title(root) in setOf(DisplayTitle, Target)) {
            "F04 left the isolated chat and read-only display pages"
        }
    }

    override fun checkToolCalls(request: ChatRequest, calls: List<ChatToolCall>) {
        val root = automation.rootInActiveWindow
        val page = title(root)
        val inChat = root?.packageName?.toString() == chatPackage
        val visible = if (page == DisplayTitle) nodes(root).filter { it.isVisibleToUser } else emptyList()
        for (call in calls) {
            val check = JSONObject().put("tool", call.name).put("page", page ?: if (inChat) "chat" else "outside")
            toolChecks.put(check)
            when (call.name) {
                Tools.ObserveScreenToolName, Tools.WaitForScreenToolName, Tools.ForegroundAppToolName ->
                    check(inChat || page in setOf(DisplayTitle, Target))
                // The display page is staged immediately under chat. Do not traverse the account-bearing home page.
                Tools.PressBackToolName -> check(inChat && calls.size == 1)
                Tools.ScrollContainerToolName, Tools.SwipeScreenToolName -> {
                    check(page == DisplayTitle)
                    val row = target(visible)?.parent?.let(::bounds)
                    gestures.put(JSONObject().put("tool", call.name).put("arguments", JSONObject(call.arguments))
                        .put("preceding_row_bounds", row?.flattenToString() ?: JSONObject.NULL)
                        .put("preceding_row_fully_visible", fullyVisible(row, visible)))
                }
                Tools.TapUiElementToolName, Tools.TapUiTargetToolName -> {
                    check(page == DisplayTitle && calls.size == 1)
                    val label = requireNotNull(target(visible)) { "The refresh-rate label is not visible" }
                    val row = requireNotNull(label.parent)
                    val rowBounds = bounds(row)
                    check(row.isClickable && !row.isCheckable && fullyVisible(rowBounds, visible)) {
                        "Refresh-rate row must be fully visible before opening"
                    }
                    val args = JSONObject(call.arguments)
                    val screen = request.messages.lastOrNull {
                        it.role == "tool" && "screen_id=${args.getString("screen_id")}" in it.content.orEmpty().lineSequence()
                    }?.content.orEmpty()
                    check("package=$packageName" in screen.lineSequence()) { "No matching Settings observation" }
                    if (call.name == Tools.TapUiElementToolName) {
                        val control = screen.lineSequence().singleOrNull { it.startsWith("[${args.getInt("index")}] ") }.orEmpty()
                        check(!args.optBoolean("long_press") && listOf(rowBounds, bounds(label)).any {
                            control.endsWith(" bounds=${it.left},${it.top},${it.right},${it.bottom}")
                        }) { "Indexed action is outside the read-only refresh-rate row" }
                    } else {
                        check(args.getJSONObject("target").optString("text") == Target) { "Only the refresh-rate row may be opened" }
                    }
                    check.put("row_bounds", rowBounds.flattenToString()).put("row_fully_visible", true)
                        .put("list_summary", nodes(row).single { it.viewIdResourceName == "$packageName:id/summary" }.text.toString())
                    screenshot("before-open")
                }
                else -> error("Tool outside the read-only F04 Settings scope: ${call.name}")
            }
            check.put("in_scope", true)
        }
    }

    override fun finishObservation(): JSONObject? {
        stopRecording()
        synchronized(scrollEvents) { evidence.put("scroll_events", scrollEvents) }
        val root = automation.rootInActiveWindow
        if (title(root) != Target) return null
        val selected = nodes(root).singleOrNull { it.isVisibleToUser && it.isCheckable && it.isChecked &&
            !it.contentDescription.isNullOrBlank() && it.className?.toString() == "android.widget.RadioButton" }
        return JSONObject().put("package", packageName).put("detail_title", Target)
            .put("checked_option", selected?.let { nodes(it).singleOrNull { node -> node.viewIdResourceName == rowTitleId }?.text?.toString() })
            .put("checked_option_description", selected?.contentDescription?.toString())
    }

    private fun title(root: AccessibilityNodeInfo?): String? = if (root?.packageName?.toString() != packageName) null
        else nodes(root).singleOrNull { it.viewIdResourceName == titleId && it.isVisibleToUser }?.text?.toString()

    private fun target(nodes: List<AccessibilityNodeInfo>) =
        nodes.singleOrNull { it.viewIdResourceName == rowTitleId && it.text?.toString() == Target }

    private fun fullyVisible(row: Rect?, visible: List<AccessibilityNodeInfo>): Boolean {
        if (row == null || row.height() != ReferenceRowHeight) return false
        val recycler = visible.singleOrNull { it.viewIdResourceName == recyclerId } ?: return false
        val toolbar = visible.singleOrNull { it.viewIdResourceName == "$packageName:id/bbk_title_view" } ?: return false
        val container = bounds(recycler)
        return container.contains(row) && row.top >= bounds(toolbar).bottom && row.bottom < container.bottom
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else buildList {
        add(root)
        repeat(root.childCount) { addAll(nodes(root.getChild(it))) }
    }

    companion object {
        const val Prompt = "请先从这条聊天按返回，回到上一页的设置「显示、亮度与护眼」。找到「屏幕刷新率」，等这一项完整显示后打开，告诉我当前选中的档位。请保持原设置。"
        private const val DisplayTitle = "显示、亮度与护眼"
        private const val Target = "屏幕刷新率"
        // The full row measured on the specified device at its existing display size.
        private const val ReferenceRowHeight = 161
    }
}
