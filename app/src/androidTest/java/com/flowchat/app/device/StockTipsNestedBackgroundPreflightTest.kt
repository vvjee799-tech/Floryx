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
import com.flowchat.app.domain.device.DeviceContainerScrollRequest
import com.flowchat.app.domain.device.DeviceScrollDirection
import com.flowchat.app.domain.device.DeviceScrollRequest
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.domain.device.DeviceUiSelector
import com.flowchat.app.domain.device.PowerConnectionMode
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Zero-model-request preflight for F04 nested real-app scrolling on the owned background display.
 * Opens native Vivo Tips, confirms multi-container ambiguity guard, scrolls the inner StaggeredGridLayoutManager
 * inside the horizontal ViewPager to an off-screen card, opens its public article, and verifies display 0 never changed.
 */
class StockTipsNestedBackgroundPreflightTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pkg = "com.vivo.Tips"
    private fun id(name: String) = "$pkg:id/$name"
    private val recyclerId = id("recycler_view")
    private val viewPagerId = id("view_pager")
    private val targetText = "悦目护眼"

    private fun stage(name: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBG_TIPS_NESTED $name\n") })

    @Test fun nestedRealAppTipsScrollsInnerContainerAndOpensOffscreenTargetInBackground() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runBgTipsNestedPreflight") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        val label = args.getString("bgTipsNestedRun").orEmpty()
        check(label.matches(Regex("[a-z0-9-]{1,64}")))
        val directory = File(context.getExternalFilesDir(null), "bg-f04/$label").also { check(it.mkdirs()) }
        val record = JSONObject().put("run", label).put("mode", "background_nested_preflight_no_network")
        fun save() = File(directory, "result.json").writeText(record.toString(2))

        val tasks = DeviceTaskControl()
        val root = RootDeviceConnection(context)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root,
            backgroundExecutionEnabled = true))
        val gateway = ShizukuDeviceAssistantGateway(context, settings, root, tasks)
        val launcher = AndroidAppLauncher(context, gateway, tasks)
        var task: DeviceTaskControl.Task? = null

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
            var screen = withTimeout(10_000) {
                while (true) {
                    val observed = gateway.observeScreen().screen
                    if (observed != null && observed.packageName == pkg && observed.elements.any { it.resourceId == recyclerId }) {
                        return@withTimeout observed
                    }
                    delay(150)
                }
                error("Unreachable")
            }
            val displayId = requireNotNull(screen.geometry).displayId
            check(displayId > 0) { "Tips did not open on an owned background display" }
            check(screen.elements.none { targetText in it.displayText }) { "Target '$targetText' was already visible before scrolling" }
            val scrollables = screen.elements.filter { it.scrollable }
            check(scrollables.any { it.resourceId == viewPagerId } && scrollables.any { it.resourceId == recyclerId }) {
                "Expected nested view_pager + recycler_view: $scrollables"
            }
            record.put("display_id", displayId).put("initial_scrollable_count", scrollables.size)
                .put("initial_scrollables", JSONArray(scrollables.map { "${it.resourceId}:${it.className}:${it.nodePath}" }))

            stage("checking_ambiguous_guard")
            val ambiguous = gateway.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Up, 30, screenId = screen.screenId))
            check(!ambiguous.success && ambiguous.errorCode == "ui_scroll_container_ambiguous" &&
                ambiguous.actionOutcome == DeviceActionOutcome.NotDispatched) {
                "Unqualified swipe on multi-container Tips must be rejected: ${ambiguous.errorCode} ${ambiguous.actionOutcome}"
            }
            screen = requireNotNull(ambiguous.screen)
            record.put("ambiguous_rejected", true)
            save()

            // Wait 4.5s across banner auto-rotation so planning delay against an auto-rotating child ViewPager is exercised.
            delay(4_500)
            stage("scrolling_inner_container")
            val scrollSteps = JSONArray()
            var totalActions = 0
            var reached = false
            repeat(6) { step ->
                val container = screen.elements.single { it.resourceId == recyclerId && it.scrollable }
                val res = gateway.scrollContainer(DeviceContainerScrollRequest(
                    container.index, DeviceScrollDirection.Down, screen.screenId,
                    target = DeviceUiSelector(text = targetText)
                ))
                val fb = res.scrollFeedback
                totalActions += fb?.actions ?: 0
                scrollSteps.put(JSONObject().put("step", step + 1).put("success", res.success).put("error", res.errorCode)
                    .put("outcome", res.actionOutcome?.name).put("target_state", fb?.targetState)
                    .put("actions", fb?.actions).put("input_mode", fb?.inputMode)
                    .put("content_delta", fb?.motion?.contentPixels))
                check(res.success) { "Background Tips scroll failed at step ${step + 1}: ${res.errorCode} ${res.summary}" }
                screen = requireNotNull(res.screen)
                if (fb?.targetState == "ready") {
                    reached = true
                    return@repeat
                }
            }
            check(reached) { "Did not reach '$targetText' within 6 scroll steps" }
            record.put("scroll_steps", scrollSteps).put("total_actions", totalActions)
            save()

            stage("opening_target_article")
            val targetNode = screen.elements.single { it.text == targetText }
            val clickableCard = screen.elements.singleOrNull { el ->
                el.clickable && el.bounds.left <= targetNode.bounds.left && el.bounds.right >= targetNode.bounds.right &&
                    el.bounds.top <= targetNode.bounds.top && el.bounds.bottom >= targetNode.bounds.bottom
            } ?: targetNode
            val tapped = gateway.tapUiElement(clickableCard.index, false, screen.screenId)
            check(tapped.success) { "Failed to tap '$targetText': ${tapped.errorCode}" }
            val article = withTimeout(10_000) {
                while (true) {
                    val observed = gateway.observeScreen().screen
                    if (observed != null && observed.packageName == pkg &&
                        observed.elements.none { it.resourceId == recyclerId } &&
                        observed.elements.any { it.displayText.contains(targetText) } &&
                        observed.elements.any { it.displayText.length >= 12 && it.displayText != targetText }) {
                        return@withTimeout observed
                    }
                    delay(150)
                }
                error("Unreachable")
            }
            val articleTexts = article.elements.mapNotNull { it.text?.takeIf(String::isNotBlank) }.distinct()
            record.put("article", JSONObject().put("display_id", article.geometry?.displayId)
                .put("texts", JSONArray(articleTexts)))

            val physicalAfter = service.observeScreenOnDisplay(0)
            record.put("physical_after", JSONObject().put("package", physicalAfter?.packageName).put("window", physicalAfter?.windowId))
            check(physicalBefore?.packageName == physicalAfter?.packageName && physicalBefore?.windowId == physicalAfter?.windowId) {
                "Physical screen changed while Tips ran on background display"
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
}