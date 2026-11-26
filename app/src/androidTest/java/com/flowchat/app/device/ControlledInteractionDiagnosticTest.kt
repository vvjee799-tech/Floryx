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

class ControlledInteractionDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val gateway by lazy { ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context)) }
    @Volatile private var metrics: JSONObject? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { metrics = JSONObject(intent.getStringExtra("data")!!) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
    private fun report(stage: String) = instrumentation.sendStatus(1, Bundle().apply { putString("R3_STAGE", stage) })
    private suspend fun control(command: String): JSONObject {
        metrics = null
        context.sendBroadcast(Intent("$fixturePackage.R3_CONTROL").setPackage(fixturePackage).putExtra("command", command))
        withTimeout(3000) { while (metrics == null) delay(20) }
        delay(150)
        return requireNotNull(metrics)
    }

    @Before fun prepare() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("runControlledInteractionDiagnostics") == "true") { "Explicit diagnostics opt-in required" }
        val filter = IntentFilter("${context.packageName}.R3_RESULT")
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
        try {
            context.sendBroadcast(Intent("$fixturePackage.R3_CONTROL").setPackage(fixturePackage).putExtra("command", "finish"))
            delay(150)
        } finally { context.unregisterReceiver(receiver) }
    }

    private suspend fun start() {
        val output = shell("am start -W --display 0 -f 0x10008000 -n $fixturePackage/${ControlledInteractionFixtureActivity::class.java.name}")
        check(!output.contains("Error:")) { output }
        withTimeout(12000) {
            var previous: DeviceScreenSnapshot? = null
            var stableSince = SystemClock.elapsedRealtime()
            while (true) {
                val current = gateway.observeScreen().screen
                if (current?.packageName != fixturePackage || previous?.hasSameUi(current) != true) stableSince = SystemClock.elapsedRealtime()
                if (current?.packageName == fixturePackage && current.elements.any { it.contentDescription == "r3_first" && it.focused } &&
                    SystemClock.elapsedRealtime() - stableSince >= 500) break
                previous = current; delay(100)
            }
        }
        assertEquals(0, control("report").getInt("writes"))
    }
    private suspend fun screen(scope: DeviceObservationScope? = null) = requireNotNull(
        (scope?.let { gateway.observeScreen(it) } ?: gateway.observeScreen()).screen)
    private fun DeviceScreenSnapshot.field(description: String = "r3_first") = elements.single { it.contentDescription == description }
    private suspend fun edit(text: String, mode: DeviceTextEditMode = DeviceTextEditMode.Replace, description: String = "r3_first"): DeviceToolResult {
        val before = screen()
        return gateway.editText(DeviceTextEditRequest(before.field(description).index, text, mode), before.screenId)
    }
    private fun verified(result: DeviceToolResult) {
        assertTrue(result.asToolContent(), result.success); assertEquals(DeviceActionOutcome.TargetVerified, result.actionOutcome)
    }
    private fun notDispatched(result: DeviceToolResult, code: String) {
        assertFalse(result.asToolContent(), result.success); assertEquals(code, result.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, result.actionOutcome)
    }

    @Test fun replaceAppendClearAndUnicodeNewlinesVerifyTheCompleteValue() = runBlocking {
        start()
        verified(edit("中文😀\n第二行")); verified(edit("尾部", DeviceTextEditMode.Append))
        assertEquals("中文😀\n第二行尾部", control("report").getString("first"))
        assertEquals("second draft", control("report").getString("second"))
        verified(edit("")); assertEquals("", control("report").getString("first"))
        report("replace-append-clear-unicode-newline=verified")
    }

    @Test fun selectionReplacementAndCursorInsertionPreserveTheRestOfTheDraft() = runBlocking {
        start(); var before = screen()
        verified(gateway.setTextSelection(DeviceTextSelection(before.field().index, 1, 3), before.screenId))
        verified(edit("中", DeviceTextEditMode.ReplaceSelection))
        var state = control("report"); assertEquals("甲中乙", state.getString("first")); assertEquals(2, state.getInt("start")); assertEquals(2, state.getInt("end"))
        verified(edit("😀\n", DeviceTextEditMode.InsertAtCursor))
        state = control("report"); assertEquals("甲中😀\n乙", state.getString("first")); assertEquals(5, state.getInt("start"))
        before = screen()
        notDispatched(gateway.setTextSelection(DeviceTextSelection(before.field().index, 3, 3), before.screenId), "ui_selection_invalid")
        assertEquals("甲中😀\n乙", control("report").getString("first"))
        report("selection-cursor-restoration-and-emoji-boundary=verified")
    }

    @Test fun userDraftChangesCannotBeOverwrittenByAnOldPlan() = runBlocking {
        start(); val before = screen(); control("draft")
        notDispatched(gateway.inputText(before.field().index, "obsolete plan", true, before.screenId), "stale_screen")
        val state = control("report"); assertEquals("用户新草稿", state.getString("first")); assertEquals(1, state.getInt("writes"))
    }

    @Test fun cursorAndFocusChangesInvalidateOldPlansWithoutTouchingAnotherInput() = runBlocking {
        start(); var before = screen(); control("cursor")
        notDispatched(gateway.editText(DeviceTextEditRequest(before.field().index, "X", DeviceTextEditMode.InsertAtCursor), before.screenId), "stale_screen")
        before = screen(); control("focus_second")
        notDispatched(gateway.inputText(before.field().index, "wrong focus", true, before.screenId), "stale_screen")
        val fresh = screen()
        notDispatched(gateway.setTextSelection(DeviceTextSelection(fresh.field().index, 0, 0), fresh.screenId), "ui_input_focus_required")
        assertEquals(0, control("report").getInt("writes")); assertEquals("second draft", control("report").getString("second"))
    }

    @Test fun advertisedLengthAndMissingSetTextAreRejectedBeforeWriting() = runBlocking {
        start()
        notDispatched(edit("x".repeat(4001)), "ui_text_too_long")
        notDispatched(edit("abcdef", description = "r3_limited"), "ui_text_too_long")
        notDispatched(edit("new", description = "r3_unsupported"), "ui_input_unsupported")
        assertEquals(0, control("report").getInt("writes"))
        verified(edit("😀".repeat(2000)))
        notDispatched(edit("x", DeviceTextEditMode.Append), "ui_text_too_long")
        assertEquals(4000, control("report").getString("first").length)
        report("4000-utf16-final-limit-and-capability-refusal=verified")
    }

    @Test fun unknownTruncationAndFormattingRemainUnverifiedWithoutAutomaticRewrite() = runBlocking {
        start()
        val truncated = edit("abcdef", description = "r3_truncated")
        assertFalse(truncated.asToolContent(), truncated.success); assertEquals("ui_input_unverified", truncated.errorCode)
        assertEquals("abcde", truncated.screen!!.field("r3_truncated").text)
        val formatted = edit("abc", description = "r3_formatted")
        assertFalse(formatted.asToolContent(), formatted.success); assertEquals("ui_input_unverified", formatted.errorCode)
        assertEquals("ABC", formatted.screen!!.field("r3_formatted").text)
        assertEquals(2, control("report").getInt("writes"))
    }

    @Test fun labeledSearchNextAndDoneAreSeparateSingleDispatchesWithNoReplay() = runBlocking {
        start()
        for ((command, label) in listOf("report" to "搜索", "next" to "下一项", "done" to "完成")) {
            control(command); val before = screen(); val field = before.field()
            assertEquals(before.asToolContent(), label, field.editorActionLabel)
            notDispatched(gateway.performEditorAction(field.index, "Wrong label", before.screenId), "ui_editor_action_changed")
            val fresh = screen()
            val result = gateway.performEditorAction(fresh.field().index, label, fresh.screenId)
            assertTrue(result.asToolContent(), result.success); assertEquals(DeviceActionOutcome.ProgressObserved, result.actionOutcome)
            notDispatched(gateway.performEditorAction(fresh.field().index, label, fresh.screenId), "stale_screen")
        }
        assertEquals(3, control("report").getInt("editorActions")); assertEquals(0, control("report").getInt("writes"))
        control("unlabeled"); val before = screen()
        notDispatched(gateway.performEditorAction(before.field().index, "搜索", before.screenId), "ui_editor_action_unsupported")
        assertEquals(3, control("report").getInt("editorActions"))
        report("search-next-done=three-dispatches;unlabeled-and-replay=blocked")
    }

    @Test fun scrollingTheNestedContainerLeavesItsParentAndSiblingUnchanged() = runBlocking {
        start(); control("lists")
        val scope = DeviceObservationScope(DeviceUiSelector(description = "r3_inner"), DeviceObservationScope.Kind.ScrollContainer)
        val before = screen(scope)
        val result = gateway.scrollContainer(before.field("r3_inner").index, DeviceScrollDirection.Forward, before.screenId)
        assertTrue(result.asToolContent(), result.success); assertEquals(DeviceActionOutcome.ProgressObserved, result.actionOutcome)
        assertEquals(scope, result.screen!!.observationScope)
        var state = control("report"); assertTrue(state.toString(), state.getInt("innerY") > 0)
        assertEquals(0, state.getInt("outerY")); assertEquals(0, state.getInt("siblingY"))
        val siblingScope = scope.copy(root = DeviceUiSelector(description = "r3_sibling"))
        val sibling = screen(siblingScope)
        val moved = gateway.scrollContainer(sibling.field("r3_sibling").index, DeviceScrollDirection.Down, sibling.screenId)
        assertTrue(moved.asToolContent(), moved.success)
        state = control("report"); assertTrue(state.toString(), state.getInt("siblingY") > 0); assertEquals(0, state.getInt("outerY"))
        report("nested-and-sibling-scroll=isolated")
    }

    @Test fun unsupportedAndStaleContainerActionsNeverFallBackToWholeScreenSwipes() = runBlocking {
        start(); val original = screen()
        notDispatched(gateway.scrollContainer(original.field().index, DeviceScrollDirection.Forward, original.screenId), "ui_scroll_unsupported")
        control("lists"); val before = screen(); val root = before.field("r3_inner")
        notDispatched(gateway.scrollContainer(root.index, DeviceScrollDirection.Left, before.screenId), "ui_scroll_unsupported")
        val fresh = screen(); val moved = gateway.scrollContainer(fresh.field("r3_inner").index, DeviceScrollDirection.Forward, fresh.screenId)
        assertTrue(moved.asToolContent(), moved.success)
        notDispatched(gateway.scrollContainer(root.index, DeviceScrollDirection.Forward, before.screenId), "stale_screen")
        val state = control("report"); assertEquals(0, state.getInt("outerY")); assertEquals(0, state.getInt("siblingY"))
    }

    @Test fun unrelatedStatusChangeCannotProveAnOpaqueContainerScrolled() = runBlocking {
        start(); control("lists"); val before = screen()
        val result = gateway.scrollContainer(before.field("r3_opaque").index, DeviceScrollDirection.Forward, before.screenId)
        assertFalse(result.asToolContent(), result.success); assertEquals("ui_scroll_unverified", result.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome)
        assertEquals(1, control("report").getInt("opaqueActions")); report("opaque-container=unverified-once")
    }
}
