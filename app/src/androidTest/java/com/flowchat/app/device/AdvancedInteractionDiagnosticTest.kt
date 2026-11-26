package com.flowchat.app.device

import android.app.UiAutomation
import android.content.*
import android.os.*
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.*
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class AdvancedInteractionDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = "com.flowchat.fixture.r3"
    private val gateway by lazy { ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context)) }
    @Volatile private var metrics: JSONObject? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { metrics = JSONObject(intent.getStringExtra("data")!!) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
    private fun report(stage: String) = instrumentation.sendStatus(1, Bundle().apply { putString("R3B_STAGE", stage) })
    private suspend fun control(command: String): JSONObject {
        metrics = null
        context.sendBroadcast(Intent("$fixturePackage.R3B_CONTROL").setPackage(fixturePackage).putExtra("command", command))
        withTimeout(3000) { while (metrics == null) delay(20) }
        delay(180); return requireNotNull(metrics)
    }
    @Before fun prepare() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("runAdvancedInteractionDiagnostics") == "true") { "Explicit diagnostics opt-in required" }
        check(shell("pm path $fixturePackage").startsWith("package:")) { "Install the opt-in phone-fixture APK first" }
        val filter = IntentFilter("${context.packageName}.R3B_RESULT")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (FlowChatAccessibilityService.current() != null) return@runBlocking
        shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
        if (withTimeoutOrNull(2000) { while (FlowChatAccessibilityService.current() == null) delay(100); true } == true) return@runBlocking
        val original = shell("settings get secure enabled_accessibility_services")
        val component = ComponentName(context, FlowChatAccessibilityService::class.java)
        check(original.split(':').any { ComponentName.unflattenFromString(it) == component }) { "Service must already be authorized" }
        val removed = original.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            check(shell("settings get secure enabled_accessibility_services") == original)
            try { check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, removed)); delay(250) }
            finally {
                check(shell("settings get secure enabled_accessibility_services").let { if (it == "null") "" else it } == removed)
                check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original))
            }
        } finally { automation.dropShellPermissionIdentity() }
        withTimeout(10000) { while (FlowChatAccessibilityService.current() == null) delay(100) }
    }
    @After fun finish() = runBlocking {
        try { context.sendBroadcast(Intent("$fixturePackage.R3B_CONTROL").setPackage(fixturePackage).putExtra("command", "finish")); delay(200) }
        finally { context.unregisterReceiver(receiver) }
    }
    private fun DeviceScreenSnapshot.field(description: String) = elements.single { it.contentDescription == description ||
        it.hint == description || it.resourceId?.substringAfterLast('/') == description }
    private suspend fun screen() = requireNotNull(gateway.observeScreen().screen)
    private suspend fun start(kind: String = "", required: String = "r3b_source"): DeviceScreenSnapshot {
        val activity = "com.flowchat.fixture.r3.${kind}FixtureActivity"
        val output = shell("am start -W --display 0 -f 0x10008000 -n $fixturePackage/$activity")
        check(!output.contains("Error:") && !output.contains("Exception")) { output }
        var result: DeviceScreenSnapshot? = null
        try { withTimeout(15000) {
            var previous: DeviceScreenSnapshot? = null; var stableSince = SystemClock.elapsedRealtime()
            while (true) {
                val current = gateway.observeScreen().screen
                if (current?.packageName != fixturePackage || previous?.hasSameUi(current) != true) stableSince = SystemClock.elapsedRealtime()
                if (current?.packageName == fixturePackage && runCatching { current.field(required) }.isSuccess &&
                    SystemClock.elapsedRealtime() - stableSince >= 500) { result = current; break }
                previous = current; delay(100)
            }
        } } catch (error: TimeoutCancellationException) {
            val current = gateway.observeScreen().screen
            throw AssertionError("Fixture $kind did not expose $required: " + if (current?.packageName == fixturePackage) current.asToolContent()
                else "package=${current?.packageName}", error)
        }
        return requireNotNull(result)
    }
    private fun verified(result: DeviceToolResult) { assertTrue(result.asToolContent(), result.success); assertEquals(DeviceActionOutcome.TargetVerified, result.actionOutcome) }
    private fun refused(result: DeviceToolResult, code: String) {
        assertFalse(result.asToolContent(), result.success); assertEquals(code, result.errorCode); assertEquals(DeviceActionOutcome.NotDispatched, result.actionOutcome)
    }

    @Test fun anchoredDragReordersOnceAndLeavesNoPointerDown() = runBlocking {
        val before = start()
        val result = gateway.anchoredGesture(DeviceAnchoredGesture.Drag(before.field("r3b_source").index, before.field("r3b_drop").index), before.screenId)
        assertTrue(result.asToolContent(), result.success); assertEquals(DeviceActionOutcome.ProgressObserved, result.actionOutcome)
        assertTrue(result.screen!!.elements.any { it.text == "Order B,A" })
        val state = control("report"); assertEquals(state.toString(), 1, state.getInt("drops"))
        assertEquals(1, state.getInt("downs")); assertEquals(1, state.getInt("ups")); assertFalse(state.getBoolean("active"))
        report("anchored-order=B,A;one-down-one-up;pointer-released")
    }
    @Test fun doubleTapIsTwoRealTouchesAndCannotReplayAnOldScreen() = runBlocking {
        val before = start()
        val result = gateway.anchoredGesture(DeviceAnchoredGesture.DoubleTap(before.field("r3b_double").index), before.screenId)
        assertTrue(result.asToolContent(), result.success)
        refused(gateway.anchoredGesture(DeviceAnchoredGesture.DoubleTap(before.field("r3b_double").index), before.screenId), "stale_screen")
        delay(350); val state = control("report")
        assertEquals(state.toString(), 1, state.getInt("doubles")); assertEquals(2, state.getInt("doubleDowns")); assertEquals(0, state.getInt("singles"))
    }
    @Test fun cancellingAnActiveDragStopsMovementAndReleasesBeforeReturning() = runBlocking {
        val before = start(); val source = before.field("r3b_source")
        val operation = async { gateway.anchoredGesture(DeviceAnchoredGesture.Drag(source.index, before.field("r3b_drop").index, 600, 1200), before.screenId) }
        withTimeout(7000) { while (metrics?.let { it.optDouble("lastY") > source.bounds.centerY + 20 } != true) delay(20) }
        val started = SystemClock.elapsedRealtime(); operation.cancelAndJoin()
        val state = control("report")
        assertFalse(state.toString(), state.getBoolean("active")); assertEquals(0, state.getInt("drops")); assertEquals(1, state.getInt("downs"))
        assertEquals(1, state.getInt("ups") + state.getInt("cancels")); assertTrue(SystemClock.elapsedRealtime() - started < 1800)
        assertTrue(gateway.observeScreen().success); report("cancelled-mid-drag=release-verified;no-destination-drop")
    }
    @Test fun changedAnchorsAndModalWindowsRejectTheWholeOldPath() = runBlocking {
        var before = start(); val request = DeviceAnchoredGesture.Drag(before.field("r3b_source").index, before.field("r3b_drop").index)
        control("mutate_dest"); refused(gateway.anchoredGesture(request, before.screenId), "stale_screen")
        assertEquals(0, control("report").getInt("downs"))
        before = start(); control("popup")
        refused(gateway.anchoredGesture(DeviceAnchoredGesture.Drag(before.field("r3b_source").index, before.field("r3b_drop").index), before.screenId), "stale_screen")
        assertEquals(0, control("report").getInt("downs"))
    }
    @Test fun exactSliderValuesAndUserChangesHaveSeparateOutcomes() = runBlocking {
        var before = start()
        verified(gateway.adjustRange(DeviceRangeAdjustment(before.field("r3b_slider").index, 7f), before.screenId))
        before = screen(); verified(gateway.adjustRange(DeviceRangeAdjustment(before.field("r3b_slider").index, 7f), before.screenId))
        before = screen(); refused(gateway.adjustRange(DeviceRangeAdjustment(before.field("r3b_slider").index, 11f), before.screenId), "ui_range_unsupported")
        before = screen(); control("range_user")
        refused(gateway.adjustRange(DeviceRangeAdjustment(before.field("r3b_slider").index, 8f), before.screenId), "stale_screen")
        assertEquals(4, control("report").getInt("slider"))
    }
    @Test fun roundedSliderValuesRemainUnverifiedAndAreNotRepeated() = runBlocking {
        val before = start()
        val result = gateway.adjustRange(DeviceRangeAdjustment(before.field("r3b_quantized").index, .74f), before.screenId)
        assertFalse(result.asToolContent(), result.success); assertEquals("ui_range_unverified", result.errorCode)
        assertEquals(.7f, result.screen!!.field("r3b_quantized").range!!.current)
        assertEquals(1, control("report").getInt("rangeWrites"))
    }
    @Test fun composeTextSelectionReplacementAndAppendKeepTheFullValueAndCursor() = runBlocking {
        var before = start("Compose", "r3b_compose_first")
        verified(gateway.setTextSelection(DeviceTextSelection(before.field("r3b_compose_first").index, 1, 3), before.screenId))
        before = screen()
        verified(gateway.editText(DeviceTextEditRequest(before.field("r3b_compose_first").index, "中", DeviceTextEditMode.ReplaceSelection), before.screenId))
        var state = control("report"); assertEquals("甲中乙", state.getString("first")); assertEquals(2, state.getInt("start"))
        before = screen()
        verified(gateway.editText(DeviceTextEditRequest(before.field("r3b_compose_first").index, "😀\n尾部", DeviceTextEditMode.Append), before.screenId))
        state = control("report"); assertEquals("甲中乙😀\n尾部", state.getString("first")); assertEquals("second draft", state.getString("second"))
        report("compose=full-value-selection-and-emoji-verified")
    }
    @Test fun composeUserDraftAndFocusChangesInvalidatePendingEdits() = runBlocking {
        var before = start("Compose", "r3b_compose_first"); control("draft")
        refused(gateway.inputText(before.field("r3b_compose_first").index, "old plan", true, before.screenId), "stale_screen")
        before = screen(); control("focus_second")
        refused(gateway.editText(DeviceTextEditRequest(before.field("r3b_compose_first").index, "X", DeviceTextEditMode.InsertAtCursor), before.screenId), "stale_screen")
        val state = control("report"); assertEquals("用户新草稿", state.getString("first")); assertEquals("second draft", state.getString("second"))
    }
    @Test fun localWebViewInputsVerifyUnicodeAppendAndSelectionCapabilities() = runBlocking {
        var before = start("Web", "r3b_web_first")
        val focused = gateway.tapUiElement(before.field("r3b_web_first").index, false, before.screenId)
        assertTrue(focused.asToolContent(), focused.success)
        // Focusing the field starts an OEM keyboard animation after the tap returns.
        // Only read until the focused field and full window context settle; do not replay the tap/input.
        var settled: DeviceScreenSnapshot? = null
        withTimeout(5_000) {
            var previous: DeviceScreenSnapshot? = null
            var stableSince = SystemClock.elapsedRealtime()
            while (true) {
                val current = screen()
                if (previous?.hasSameUi(current) != true || !current.field("r3b_web_first").focused)
                    stableSince = SystemClock.elapsedRealtime()
                if (current.field("r3b_web_first").focused && SystemClock.elapsedRealtime() - stableSince >= 500) {
                    settled = current
                    break
                }
                previous = current
                delay(100)
            }
        }
        before = requireNotNull(settled)
        verified(gateway.inputText(before.field("r3b_web_first").index, "网页😀", true, before.screenId))
        before = screen(); verified(gateway.inputText(before.field("r3b_web_first").index, "追加", false, before.screenId))
        before = screen(); val field = before.field("r3b_web_first")
        assertEquals("网页😀追加", field.text)
        val selection = gateway.setTextSelection(DeviceTextSelection(field.index, 2, 4), before.screenId)
        if ("set_selection" in field.supportedActions && field.focused) verified(selection)
        else refused(selection, if (!field.focused) "ui_input_focus_required" else "ui_selection_unsupported")
        report("webview=unicode-and-append-verified;selection-actions=${field.supportedActions}")
    }
    @Test fun webViewDraftAndSensitiveSiblingCannotBeBypassed() = runBlocking {
        var before = start("Web", "r3b_web_first"); control("draft")
        refused(gateway.inputText(before.field("r3b_web_first").index, "old plan", true, before.screenId), "stale_screen")
        before = screen(); control("sensitive")
        val blocked = gateway.observeScreen(); assertEquals("sensitive_screen_blocked", blocked.errorCode); assertTrue(blocked.screen!!.elements.isEmpty())
        assertEquals("sensitive_screen_blocked", gateway.inputText(before.field("r3b_web_first").index, "blocked", true, before.screenId).errorCode)
    }
}
