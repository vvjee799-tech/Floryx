package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.AdaptiveScrollController
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.*
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * B3: real Settings app list, no model request and no synthetic gain.
 *
 * The controller is driven directly with the same gesture/revalidation primitives as
 * ShizukuDeviceAssistantGateway. Every dispatch path is recorded before it is sent, while the
 * visible rows are read independently through UiAutomation. The app list is only scrolled and
 * an app-info page is opened read-only.
 */
class RealAppOvershootDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val settingsPackage = "com.android.settings"
    private val gateway by lazy {
        ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context))
    }
    private var accessibility: FlowChatAccessibilityService? = null
    private var originalSystemAppsShown = false
    private val scrollEvents = mutableListOf<JSONObject>()
    private val runStarted = System.currentTimeMillis()

    private data class Row(val label: String, val bounds: Rect)
    private data class UiSnapshot(
        val packageName: String,
        val container: Rect?,
        val rows: List<Row>,
        val screenHeight: Int,
        val texts: Set<String>
    ) {
        fun row(label: String): Row? = rows.firstOrNull { it.label == label }
        fun has(label: String): Boolean = label in texts || row(label) != null
    }

    private data class Case(
        val id: String,
        val start: String,
        val anchor: String?,
        val target: String,
        val direction: DeviceScrollDirection,
        val durationMillis: Int? = null
    )

    private data class Gesture(
        val number: Int,
        val call: Int,
        val iteration: Int,
        val fingerPixels: Int,
        val durationMillis: Int,
        val contentPixels: Int?,
        val observedGain: Double?,
        val targetBefore: Boolean,
        val targetAfter: Boolean,
        val targetReadyAfter: Boolean,
        val overshoot: Boolean,
        val reverse: Boolean,
        val events: List<JSONObject>
    )

    private val rowOrder = listOf(
        "浏览器", "媒体选择工具", "密钥链", "瀑布刘海屏", "权限控制器", "日历存储", "软件包安装程序",
        "设置", "设置存储", "设置建议", "声音", "双刘海屏", "图库", "外部存储设备", "网络管理器",
        "文件", "系统 WLAN 资源", "系统界面", "系统网络连接资源", "下载", "下载管理器", "相机",
        "虚拟身份管理", "一体化位置信息", "应用分身", "游戏中心", "长型刘海屏", "照片和视频",
        "证书安装程序", "Android 系统", "Android Services Library", "Android Shared Library",
        "Android System WebView", "com.android.appsearch.apk", "com.android.cts.ctsshim",
        "com.android.cts.priv.ctsshim", "com.android.federatedcompute.services",
        "com.android.localtransport",
        "com.android.ondevicepersonalization.services.OnDevicePersonalizationApplication",
        "com.android.sdksandbox", "com.android.server.deviceconfig.resources",
        "com.android.wallpapercropper", "com.android.wifi.dialog", "Credential Manager", "Floryx Dev",
        "Gestural Navigation Bar", "HTML 查看程序", "IntentResolver", "KernelSU", "Lawnchair",
        "Module Metadata", "MuMuSDK", "nlp", "Noto Serif / Source Sans Pro", "OsuLogin",
        "RemoteProvisioner", "Shell", "SogouIME-chuizi", "Tethering", "VpnDialogs"
    )
    private val rowIndex = rowOrder.withIndex().associate { it.value to it.index }

    private val cases = listOf(
        Case("top-name", "top", null, "设置存储", DeviceScrollDirection.Down),
        Case("top-far", "top", null, "系统界面", DeviceScrollDirection.Down),
        Case("middle-webview", "middle", "下载", "Android System WebView", DeviceScrollDirection.Down),
        Case("middle-floryx", "middle", "下载", "Floryx Dev", DeviceScrollDirection.Down),
        Case("lower-mumusdk", "lower", "Floryx Dev", "MuMuSDK", DeviceScrollDirection.Down),
        Case("lower-sogou", "lower", "Floryx Dev", "SogouIME-chuizi", DeviceScrollDirection.Down),
        Case("reverse-system-ui", "reverse", "Floryx Dev", "系统界面", DeviceScrollDirection.Up)
    )

    @Before
    fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runB3RealOvershoot") == "true")
        check(android.os.Build.MODEL == "SM-A5360") { "B3 diagnostic is pinned to the MuMu #1 image" }
        accessibility = withTimeoutOrNull(4_000) {
            var current = FlowChatAccessibilityService.current()
            while (current == null) { delay(100); current = FlowChatAccessibilityService.current() }
            current
        }
        if (accessibility == null) {
            val component = android.content.ComponentName(context, FlowChatAccessibilityService::class.java)
            shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val components = enabled.split(':').filter { it.isNotBlank() }
                check(components.any { android.content.ComponentName.unflattenFromString(it) == component }) {
                    "Floryx accessibility is not selected; refusing to change the user's choice"
                }
                check(android.provider.Settings.Secure.putInt(
                    context.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1))
                delay(250)
                val remaining = components.filterNot {
                    android.content.ComponentName.unflattenFromString(it) == component
                }.joinToString(":")
                check(android.provider.Settings.Secure.putString(
                    context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    remaining))
                delay(300)
                check(android.provider.Settings.Secure.putString(
                    context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    enabled))
            } finally { automation.dropShellPermissionIdentity() }
            accessibility = withTimeoutOrNull(20_000) {
                var current = FlowChatAccessibilityService.current()
                while (current == null) { delay(100); current = FlowChatAccessibilityService.current() }
                current
            }
        }
        check(accessibility != null) { "Floryx accessibility could not be rebound" }
        automation.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() == settingsPackage &&
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                synchronized(scrollEvents) {
                    scrollEvents += JSONObject()
                        .put("event_ms", event.eventTime)
                        .put("class", event.className?.toString() ?: JSONObject.NULL)
                        .put("scroll_y", event.scrollY)
                        .put("delta_y", event.scrollDeltaY)
                        .put("from_index", event.fromIndex)
                        .put("to_index", event.toIndex)
                }
            }
        }
    }

    @After
    fun finish() {
        automation.setOnAccessibilityEventListener(null)
    }

    @Test
    fun realSettingsOvershootMatrix(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("b3Run") ?: "mumu-b3"
        check(label.matches(Regex("[a-z0-9-]{1,48}")))
        val evidence = JSONObject()
            .put("run", label)
            .put("started_at", ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).toString())
            .put("device_model", android.os.Build.MODEL)
            .put("android_release", android.os.Build.VERSION.RELEASE)
            .put("package", settingsPackage)
            .put("mode", "real_settings_no_model")
            .put("model_requests", 0)
            .put("row_order", JSONArray(rowOrder))
        val results = JSONArray()
        evidence.put("cases", results)
        var failure: Throwable? = null
        try {
            launchSettingsList()
            originalSystemAppsShown = ui().has("媒体选择工具")
            evidence.put("system_apps_originally_shown", originalSystemAppsShown)
            ensureSystemApps()
            val selectedCases = if (args.getString("b3FastFling") == "true") cases + listOf(
                Case("fast-fling-top", "top", null, "软件包安装程序", DeviceScrollDirection.Down, durationMillis = 180)
            ) else cases
            for (case in selectedCases) {
                val caseStarted = SystemClock.elapsedRealtime()
                try {
                    val result = withTimeout(90_000) { runCase(case) }
                    results.put(result.put("elapsed_ms", SystemClock.elapsedRealtime() - caseStarted))
                    report("B3 ${case.id} PASS target=${case.target} calls=${result.optInt("calls")} " +
                        "gestures=${result.optInt("gestures")} corrections=${result.optInt("corrections")} " +
                        "overshoot=${result.optBoolean("overshoot")} first_seen=${result.optLong("first_seen_ms")}ms")
                } catch (error: Throwable) {
                    val record = JSONObject()
                        .put("case_id", case.id).put("start", case.start).put("anchor", case.anchor ?: JSONObject.NULL)
                        .put("target", case.target).put("target_index", rowIndex[case.target] ?: -1)
                        .put("direction", case.direction.name).put("passed", false)
                        .put("error", error.toString()).put("elapsed_ms", SystemClock.elapsedRealtime() - caseStarted)
                    results.put(record)
                    report("B3 ${case.id} FAIL target=${case.target} error=$error")
                    try { recoverSettingsList() } catch (recovery: Throwable) {
                        report("B3 recovery failed after ${case.id}: $recovery")
                    }
                }
            }
            val passed = (0 until results.length()).all { results.getJSONObject(it).optBoolean("passed", false) }
            evidence.put("summary", JSONObject()
                .put("cases", results.length()).put("passed", (0 until results.length()).count {
                    results.getJSONObject(it).optBoolean("passed", false) })
                .put("failed", (0 until results.length()).count { !results.getJSONObject(it).optBoolean("passed", false) })
                .put("all_passed", passed)
                .put("state_restored", true))
            if (!passed) failure = AssertionError("B3 real-app matrix has failing cases")
        } catch (error: Throwable) {
            failure = error
            evidence.put("fatal_error", error.toString())
        } finally {
            try { restoreSystemAppsState() } catch (error: Throwable) {
                evidence.put("restore_error", error.toString())
            }
            try { shell("input keyevent 3") } catch (_: Throwable) {}
            evidence.put("finished_at", ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).toString())
            evidence.put("elapsed_ms", System.currentTimeMillis() - runStarted)
            evidence.put("scroll_events", JSONArray(scrollEvents))
            val directory = File(context.getExternalFilesDir(null), "b3-d2-overshoot/$label").also { it.mkdirs() }
            File(directory, "result.json").writeText(evidence.toString(2))
            report("B3 evidence=${directory.absolutePath}/result.json summary=${evidence.optJSONObject("summary")}")
        }
        failure?.let { throw it }
    }

    private suspend fun runCase(case: Case): JSONObject {
        returnToListAndTop()
        advanceToAnchor(case.anchor)
        val before = ui()
        check(before.packageName == settingsPackage && before.container != null) { "Settings list is not current" }
        check(before.has("所有应用")) { "The real app list title is not visible" }
        check(!before.has(case.target)) { "Target ${case.target} must start outside the first screen" }
        val targetIndex = requireNotNull(rowIndex[case.target])
        val visibleIndices = before.rows.mapNotNull { rowIndex[it.label] }.sorted()
        val targetAway = when (case.direction) {
            DeviceScrollDirection.Down -> visibleIndices.maxOrNull()?.let { it < targetIndex } == true
            DeviceScrollDirection.Up -> visibleIndices.minOrNull()?.let { it > targetIndex } == true
            else -> false
        }
        check(targetAway) {
            "Target ${case.target} (#$targetIndex) is not ahead in ${case.direction}; visible=$visibleIndices"
        }
        val record = JSONObject()
            .put("case_id", case.id).put("start", case.start)
            .put("anchor", case.anchor ?: JSONObject.NULL).put("target", case.target)
            .put("target_index", targetIndex).put("direction", case.direction.name)
            .put("target_fully_visible_at_start", false)
            .put("before_rows", JSONArray(before.rows.map { it.label }))
            .put("before_container", before.container?.flattenToString() ?: JSONObject.NULL)
            .put("passed", false)
        val calls = JSONArray()
        val gestures = mutableListOf<Gesture>()
        val caseStarted = SystemClock.elapsedRealtime()
        var firstSeen = -1L
        var firstSeenGesture = -1
        var lastMotion: DeviceScrollMotion? = null
        var callsCount = 0
        var corrections = 0
        var ready = false
        var failureCode: String? = null
        var lastState = "not_visible"

        repeat(12) { callIndex ->
            if (ready || failureCode != null) return@repeat
            val initial = requireNotNull(accessibility?.observeScreen(forceRefresh = true)) {
                "No accessibility snapshot before call"
            }
            check(initial.packageName == settingsPackage) { "Left Settings during ${case.id}" }
            val list = initial.elements.filter { it.scrollable && it.enabled }
                .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
                ?: error("No scrollable Settings container during ${case.id}")
            val request = DeviceScrollRequest(
                direction = if (case.direction == DeviceScrollDirection.Down) DeviceSwipeDirection.Up
                else DeviceSwipeDirection.Down,
                screenId = initial.screenId,
                containerIndex = list.index,
                durationMillis = case.durationMillis,
                target = DeviceUiSelector(text = case.target)
            )
            val areaBefore = requireNotNull(DeviceAdaptiveScroll.area(initial, request)) {
                "Settings scroll area is unavailable"
            }
            val originalContainer = areaBefore.container
            val callStarted = SystemClock.elapsedRealtime()
            val callGestures = mutableListOf<Gesture>()
            var gestureNumber = gestures.size
            var iteration = 0
            val controller = caseController
            val result = controller.scroll(
                initial, request, "b3-real-${case.id}",
                ViewConfiguration.get(context).scaledTouchSlop + 8,
                read = { accessibility?.observeScreen(forceRefresh = true) },
                capture = { gateway.captureScreen() },
                dispatch = { expected, path, durationMillis ->
                    iteration++
                    val eventsBefore = synchronized(scrollEvents) { scrollEvents.size }
                    val targetBefore = expected.elements.any { it.text == case.target }
                    val finger = abs(path.startY - path.endY)
                    val dispatched = accessibility!!.controlledScroll(
                        expected, path, durationMillis, originalContainer, request
                    ) { }
                    val after = withTimeoutOrNull(1_500) {
                        delay(320)
                        accessibility?.observeScreen(forceRefresh = true)
                    }
                    val areaAfter = after?.let { DeviceAdaptiveScroll.area(it, request, originalContainer) }
                    val motion = if (after != null && areaAfter != null)
                        DeviceAdaptiveScroll.measure(expected, after, areaBefore, request.vertical) else null
                    val targetAfter = after?.elements?.any { it.text == case.target } == true
                    val targetReadyAfter = if (after != null && areaAfter != null) {
                        after.elements.singleOrNull { it.text == case.target }?.let {
                            DeviceAdaptiveScroll.targetReady(after, areaAfter, it, request.vertical) ||
                                DeviceAdaptiveScroll.shownTargetReady(after, areaAfter, it, request.vertical)
                        } == true
                    } else false
                    if (firstSeenGesture < 0 && targetAfter) {
                        firstSeenGesture = gestureNumber
                        firstSeen = SystemClock.elapsedRealtime() - caseStarted
                        record.put("first_seen_ms", firstSeen)
                            .put("first_seen_calls", callIndex + 1)
                            .put("first_seen_gesture", firstSeenGesture)
                    }
                    val events = synchronized(scrollEvents) {
                        scrollEvents.drop(eventsBefore).map { JSONObject(it.toString()) }
                    }
                    val visibleAfterIndices = after?.elements.orEmpty()
                        .mapNotNull { rowIndex[it.text] }.sorted()
                    val skipped = when {
                        targetBefore || targetAfter -> false
                        case.direction == DeviceScrollDirection.Down ->
                            visibleAfterIndices.minOrNull()?.let { it > rowIndex.getValue(case.target) } == true
                        else -> visibleAfterIndices.maxOrNull()?.let { it < rowIndex.getValue(case.target) } == true
                    }
                    val lostVisibleTarget = targetBefore && !targetAfter && motion != null &&
                        abs(motion.contentPixels) > 0
                    val content = motion?.contentPixels
                    val observedGain = content?.takeIf { finger > 0 }?.let { abs(it).toDouble() / finger }
                    val previousGesture = callGestures.lastOrNull() ?: gestures.lastOrNull()
                    val reverse = gestureNumber > 0 && previousGesture?.let { previous ->
                        val previousDirection = previous.contentPixels?.let { if (it > 0) 1 else -1 }
                        val nowDirection = content?.let { if (it > 0) 1 else -1 }
                        previousDirection != null && nowDirection != null && previousDirection != nowDirection
                    } == true
                    val gesture = Gesture(
                        number = gestureNumber, call = callIndex, iteration = iteration,
                        fingerPixels = finger, durationMillis = durationMillis,
                        contentPixels = content, observedGain = observedGain,
                        targetBefore = targetBefore, targetAfter = targetAfter,
                        targetReadyAfter = targetReadyAfter,
                        overshoot = skipped || lostVisibleTarget, reverse = reverse,
                        events = events
                    )
                    callGestures += gesture
                    gestureNumber++
                    lastMotion = motion
                    dispatched
                },
                check = { }
            )
            callsCount++
            val feedback = result.scrollFeedback
            lastState = feedback?.targetState ?: "unknown"
            corrections += feedback?.corrections ?: 0
            gestures += callGestures
            val callRecord = JSONObject()
                .put("call", callIndex).put("actions", feedback?.actions ?: 0)
                .put("corrections", feedback?.corrections ?: 0)
                .put("state", lastState).put("success", result.success)
                .put("error", result.errorCode ?: JSONObject.NULL)
                .put("finger_pixels", feedback?.gesturePixels ?: 0)
                .put("content_pixels", feedback?.motion?.contentPixels ?: JSONObject.NULL)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - callStarted)
                .put("gestures", JSONArray(callGestures.map(::gestureJson)))
            calls.put(callRecord)
            val nowUi = ui()
            if (firstSeenGesture >= 0 && !record.has("first_seen_bounds") && nowUi.has(case.target)) {
                record.put("first_seen_bounds", nowUi.row(case.target)?.bounds?.flattenToString())
            }
            if (feedback?.targetState == "ready") {
                if (!nowUi.has(case.target) || !targetFullyVisible(nowUi, case.target)) {
                    failureCode = "ready_without_independent_full_visibility"
                } else {
                    ready = true
                }
            } else if (result.errorCode in setOf(
                    "ui_scroll_no_progress", "ui_scroll_context_changed", "ui_scroll_container_changed",
                    "ui_scroll_container_moved", "ui_scroll_path_blocked", "ui_scroll_target_ambiguous",
                    "lost_unverified", "ui_scroll_motion_unverified")) {
                failureCode = result.errorCode
            } else if (!result.success && result.actionOutcome == DeviceActionOutcome.NotDispatched &&
                result.errorCode != null) {
                failureCode = result.errorCode
            }
            if (callsCount >= 12 && !ready) failureCode = "bounded_calls_exhausted"
        }

        val finalUi = ui()
        record.put("calls", callsCount)
            .put("gestures", gestures.size)
            .put("corrections", corrections)
            .put("final_state", lastState)
            .put("failure_code", failureCode ?: JSONObject.NULL)
            .put("overshoot", gestures.any { it.overshoot })
            .put("overshoot_gestures", JSONArray(gestures.filter { it.overshoot }.map(::gestureJson)))
            .put("reverse_gestures", JSONArray(gestures.filter { it.reverse }.map(::gestureJson)))
            .put("gesture_records", JSONArray(gestures.map(::gestureJson)))
            .put("calls_records", calls)
            .put("last_motion", lastMotion?.let { JSONObject().put("content_pixels", it.contentPixels)
                .put("source", it.source).put("anchors", it.anchors) } ?: JSONObject.NULL)
            .put("final_rows", JSONArray(finalUi.rows.map { it.label }))
            .put("target_visible_at_end", finalUi.has(case.target))
            .put("target_fully_visible_at_end", targetFullyVisible(finalUi, case.target))
        val scrollsAfterFirstVisible = if (firstSeenGesture >= 0) gestures.size - firstSeenGesture - 1 else -1
        record.put("scrolls_after_first_visible", scrollsAfterFirstVisible)
            .put("extra_scroll_after_ready", 0)
        if (!ready || failureCode != null) {
            record.put("passed", false)
            throw IllegalStateException("${case.id}: ${failureCode ?: lastState}; ${record.toString(2)}")
        }

        val fresh = requireNotNull(gateway.observeScreen().screen) { "No fresh screen before opening target" }
        val targetElement = fresh.elements.singleOrNull { it.text == case.target }
            ?: error("Target ${case.target} disappeared before opening")
        var clickable = targetElement
        val byIndex = fresh.elements.associateBy { it.index }
        var guard = 0
        while (!clickable.clickable && clickable.parentIndex != null && guard++ < 12) {
            clickable = byIndex[clickable.parentIndex] ?: break
        }
        check(clickable.clickable) { "Target row has no clickable ancestor" }
        val opened = gateway.tapUiElement(clickable.index, false, fresh.screenId)
        record.put("tap_success", opened.success).put("tap_error", opened.errorCode ?: JSONObject.NULL)
        val appInfoShown = withTimeoutOrNull(8_000) {
            var found: UiSnapshot? = null
            while (found == null) {
                val current = ui()
                if (current.packageName == settingsPackage &&
                    (current.has("应用信息") || current.has("强行停止") || current.has("卸载"))) found = current
                else delay(120)
            }
            found
        }
        check(appInfoShown != null) { "App info did not open for ${case.target}" }
        record.put("opened", true).put("opened_ms", SystemClock.elapsedRealtime() - caseStarted)
            .put("app_info_rows", JSONArray(appInfoShown!!.rows.map { it.label }))
        shell("input keyevent 4")
        withTimeoutOrNull(5_000) { while (!ui().has("所有应用")) delay(120) }
        check(ui().has("所有应用")) { "Back did not return to the Settings list" }
        record.put("passed", true)
        record.put("elapsed_ms", SystemClock.elapsedRealtime() - caseStarted)
        return record
    }

    private val caseController by lazy { AdaptiveScrollController(SystemClock::elapsedRealtime) }

    private fun gestureJson(g: Gesture) = JSONObject()
        .put("gesture", g.number).put("call", g.call).put("iteration", g.iteration)
        .put("finger_pixels", g.fingerPixels).put("duration_ms", g.durationMillis)
        .put("content_pixels", g.contentPixels ?: JSONObject.NULL)
        .put("observed_gain", g.observedGain ?: JSONObject.NULL)
        .put("target_before", g.targetBefore).put("target_after", g.targetAfter)
        .put("target_ready_after", g.targetReadyAfter)
        .put("overshoot", g.overshoot).put("reverse", g.reverse)
        .put("scroll_events", JSONArray(g.events))

    private suspend fun launchSettingsList() {
        shell("am force-stop $settingsPackage")
        val launched = shell("am start -W -a android.settings.MANAGE_APPLICATIONS_SETTINGS")
        report("B3 launch output=" + launched.replace("\n", ";"))
        val deadline = SystemClock.elapsedRealtime() + 12_000
        var lastLog = 0L
        var last: UiSnapshot? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val screen = ui()
            last = screen
            if (screen.packageName == settingsPackage && screen.has("所有应用")) {
                delay(500)
                return
            }
            if (SystemClock.elapsedRealtime() - lastLog > 1_500) {
                lastLog = SystemClock.elapsedRealtime()
                report("B3 launch waiting pkg='${screen.packageName}' rows=${screen.rows.map { it.label }} " +
                    "top=${shell("dumpsys activity activities | grep topResumedActivity")}")
            }
            delay(150)
        }
        error("Settings app list did not appear; last pkg='${last?.packageName}' rows=${last?.rows?.map { it.label }}")
    }

    private suspend fun ensureSystemApps() {
        if (ui().has("媒体选择工具")) return
        clickDescription("更多选项")
        waitForText("显示系统应用")
        clickText("显示系统应用")
        withTimeout(8_000) {
            while (!ui().has("媒体选择工具")) delay(150)
        }
        delay(300)
    }

    private suspend fun restoreSystemAppsState() {
        repeat(3) {
            if (ui().has("所有应用")) return@repeat
            shell("input keyevent 4")
            delay(300)
        }
        if (!originalSystemAppsShown && ui().has("所有应用") && ui().has("媒体选择工具")) {
            clickDescription("更多选项")
            if (waitForTextOrNull("隐藏系统应用", 2_000) != null) {
                clickText("隐藏系统应用")
                delay(300)
            } else {
                shell("input keyevent 4")
            }
        }
    }

    private suspend fun returnToListAndTop() {
        // A fresh Settings process makes the all-apps list start at row 0 again. Swiping back is
        // not deterministic on this Compose list because the collapsing header changes geometry.
        recoverSettingsList()
    }

    private suspend fun recoverSettingsList() {
        launchSettingsList()
        ensureSystemApps()
    }

    private suspend fun resetToTop() {
        repeat(24) {
            val screen = ui()
            val first = screen.row("浏览器")
            if (first != null && screen.container != null && first.bounds.top <= screen.container.top + 100) {
                delay(250)
                return
            }
            shell("input swipe 540 1000 540 1800 1200")
            delay(300)
        }
        error("Settings app list did not return to the top")
    }

    private suspend fun advanceToAnchor(anchor: String?) {
        if (anchor == null) return
        repeat(20) {
            val screen = ui()
            if (screen.has(anchor)) { delay(300); return }
            shell("input swipe 540 1600 540 800 1200")
            delay(350)
        }
        error("Could not reach start anchor $anchor")
    }

    private fun ui(): UiSnapshot {
        val root = automation.rootInActiveWindow ?: return UiSnapshot("", null, emptyList(), 0, emptySet())
        val packageName = root.packageName?.toString().orEmpty()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            nodes += node
            repeat(node.childCount) { collect(node.getChild(it)) }
        }
        collect(root)
        val scrollables = nodes.filter { it.isScrollable && it.isVisibleToUser }
        val container = scrollables.maxByOrNull {
            val b = Rect().also(it::getBoundsInScreen); b.width().toLong() * b.height()
        }?.let { Rect().also(it::getBoundsInScreen) }
        val visibleTexts = nodes.filter { it.isVisibleToUser && !it.text.isNullOrBlank() }
            .map { it.text.toString() }.toSet()
        val rows = nodes.filter { it.isVisibleToUser && !it.text.isNullOrBlank() }
            .mapNotNull { node ->
                val label = node.text.toString()
                if (label !in rowIndex) null else Row(label, Rect().also(node::getBoundsInScreen))
            }
            .distinctBy { it.label }
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        return UiSnapshot(packageName, container, rows, bounds.height(), visibleTexts)
    }

    private fun targetFullyVisible(screen: UiSnapshot, target: String): Boolean {
        val row = screen.row(target) ?: return false
        val container = screen.container ?: return false
        val safeBottom = container.bottom
        return row.bounds.top >= container.top + 4 && row.bounds.bottom <= safeBottom - 4 &&
            row.bounds.centerY() in (container.top + 4)..(safeBottom - 4)
    }

    private suspend fun clickDescription(description: String) {
        val root = automation.rootInActiveWindow ?: error("No active window for $description")
        val node = find(root) { it.contentDescription?.toString() == description && it.isVisibleToUser }
            ?: error("Missing UI description $description")
        val bounds = Rect().also(node::getBoundsInScreen)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        delay(350)
    }

    private suspend fun clickText(text: String) {
        val node = waitForText(text)
        val bounds = Rect().also(node::getBoundsInScreen)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        delay(400)
    }

    private fun find(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        repeat(root.childCount) { index ->
            find(root.getChild(index) ?: return@repeat, predicate)?.let { return it }
        }
        return null
    }

    private suspend fun waitForText(text: String): AccessibilityNodeInfo {
        return waitForTextOrNull(text, 8_000) ?: error("Missing visible text $text")
    }

    private suspend fun waitForTextOrNull(text: String, timeoutMs: Long): AccessibilityNodeInfo? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val root = automation.rootInActiveWindow
            val node = root?.let { find(it) { candidate -> candidate.isVisibleToUser && candidate.text?.toString() == text } }
            if (node != null) return@withTimeoutOrNull node
            delay(120)
        }
        null
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }.trim()
    }

    private fun report(message: String) {
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\nB3_REAL_APP $message\n")
        })
    }
}