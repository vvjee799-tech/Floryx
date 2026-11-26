package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceTextEditMode
import com.flowchat.app.domain.device.DeviceTextEditRequest
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.tools.AgentToolDefinitions
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in Android accessibility & ChatTaskRuntime acceptance for D2 F05 explicit submit on
 * continuously updating pages. Uses an isolated fixture DB and zero external network calls.
 */
class DynamicSubmitAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private val gateway by lazy {
        ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context))
    }
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }.trim()

    private fun report(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nF05_DYNAMIC $message\n") })
    }

    private fun control(command: String): JSONObject {
        val reply = shell("am broadcast --receiver-foreground -a $fixturePackage.DYNAMIC_SUBMIT_CONTROL -p $fixturePackage --es command $command")
        if (command != "measure") return JSONObject()
        val data = reply.lineSequence().singleOrNull { it.startsWith("Broadcast completed:") }
            ?.substringAfter(", data=\"", "")?.removeSuffix("\"").orEmpty()
        check(data.isNotBlank()) { "Fixture did not return metrics: $reply" }
        return JSONObject(data)
    }

    private suspend fun startFixture(mode: String, noop: Boolean = false, intervalMs: Long = 280L): DeviceScreenSnapshot {
        val output = shell(
            "am start -W --display 0 -f 0x10008000 -n $fixturePackage/${DynamicSubmitFixtureActivity::class.java.name} " +
                "--es mode $mode --ez noop $noop --el interval_ms $intervalMs"
        )
        check(!output.contains("Error:")) { output }
        val settled = withTimeout(12_000) {
            while (true) {
                val screen = gateway.observeScreen().screen
                val fullWindow = screen?.geometry?.let {
                    it.windowBounds.left == 0 && it.windowBounds.top == 0 &&
                        it.windowBounds.right == it.displayWidth && it.windowBounds.bottom == it.displayHeight
                } == true
                if (screen?.packageName == fixturePackage && fullWindow &&
                    screen.elements.any { it.contentDescription == "dyn_input" && it.focused && it.editable }
                ) {
                    break
                }
                delay(100)
            }
            delay(250)
            requireNotNull(gateway.observeScreen().screen)
        }
        return settled
    }

    private suspend fun waitForTickAdvance(baselineTick: Int) {
        withTimeout(5_000) {
            while (control("measure").getInt("tick_count") < baselineTick + 2) {
                delay(60)
            }
        }
        // Exceed Android ViewRootImpl's recurring accessibility event throttle so the node cache refreshes.
        delay(260)
    }

    private fun evidenceDir(): File {
        val run = InstrumentationRegistry.getArguments().getString("dynamicSubmitRun") ?: "manual"
        check(run.matches(Regex("[a-z0-9-]+")))
        return File(context.getExternalFilesDir(null), "f05-dynamic-submit/$run").apply { mkdirs() }
    }

    private fun saveCaseResult(name: String, json: JSONObject) {
        File(evidenceDir(), "$name.json").writeText(json.toString(2))
    }

    private fun captureScreenshot(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            File(evidenceDir(), "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Before
    fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDynamicSubmitDiagnostics") == "true")
        ActionOutcomeServiceReadiness().ensureService()
    }

    @After
    fun finish() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("runDynamicSubmitDiagnostics") == "true") {
            runCatching { control("finish") }
            delay(150)
        }
    }

    @Test
    fun hmsClockContinuousUpdatesAllowImmediateExplicitSubmitAndBlockReplay() = runBlocking {
        val initial = startFixture(mode = "hms_clock", intervalMs = 240L)
        val input = initial.elements.single { it.contentDescription == "dyn_input" }
        val edited = gateway.editText(DeviceTextEditRequest(input.index, "刷机说明", DeviceTextEditMode.Replace), initial.screenId)
        assertTrue(edited.asToolContent(), edited.success)
        val replyScreen = requireNotNull(edited.screen)
        val tickAtReply = control("measure").getInt("tick_count")
        waitForTickAdvance(tickAtReply)

        val editor = replyScreen.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val submit = gateway.performEditorAction(editor.index, requireNotNull(editor.editorActionLabel), replyScreen.screenId)
        val metrics = control("measure")
        saveCaseResult(
            "hms-clock",
            JSONObject()
                .put("case", "hms_clock")
                .put("first_submit_success", submit.success)
                .put("first_submit_error", submit.errorCode)
                .put("first_submit_outcome", submit.actionOutcome?.name)
                .put("metrics", metrics)
        )
        assertTrue("Expected immediate submit past live HH:MM:SS clock: ${submit.asToolContent()}", submit.success)
        assertEquals(DeviceActionOutcome.ProgressObserved, submit.actionOutcome)
        assertEquals(1, metrics.getInt("submit_count"))
        assertEquals("刷机说明", metrics.getString("submitted_query"))
        assertEquals("已提交结果：刷机说明 (#1)", metrics.getString("result_text"))

        val replay = gateway.performEditorAction(editor.index, requireNotNull(editor.editorActionLabel), replyScreen.screenId)
        assertFalse(replay.asToolContent(), replay.success)
        assertEquals("stale_screen", replay.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, replay.actionOutcome)
        assertEquals(1, control("measure").getInt("submit_count"))
        report("hms_clock=PASS submit_count=1 ticks=${metrics.getInt("tick_count")}")
    }

    @Test
    fun nonNumericLiveTickerRefreshesOnceThenDispatchesAndRejectsUnrelatedNoopProgress() = runBlocking {
        val initial = startFixture(mode = "ticker", intervalMs = 300L)
        val input = initial.elements.single { it.contentDescription == "dyn_input" }
        val edited = gateway.editText(DeviceTextEditRequest(input.index, "地铁公告", DeviceTextEditMode.Replace), initial.screenId)
        assertTrue(edited.asToolContent(), edited.success)
        val replyScreen = requireNotNull(edited.screen)
        waitForTickAdvance(control("measure").getInt("tick_count"))

        val editor = replyScreen.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val firstAttempt = gateway.performEditorAction(editor.index, requireNotNull(editor.editorActionLabel), replyScreen.screenId)
        assertFalse("First non-numeric page shift should refresh once like stationary async search", firstAttempt.success)
        assertEquals("stale_screen", firstAttempt.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, firstAttempt.actionOutcome)
        assertEquals(0, control("measure").getInt("submit_count"))

        val refreshed = requireNotNull(firstAttempt.screen)
        waitForTickAdvance(control("measure").getInt("tick_count"))
        val refreshedEditor = refreshed.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val secondAttempt = gateway.performEditorAction(refreshedEditor.index, requireNotNull(refreshedEditor.editorActionLabel), refreshed.screenId)
        val tickerMetrics = control("measure")
        assertTrue("Second attempt on live ticker must dispatch: ${secondAttempt.asToolContent()}", secondAttempt.success)
        assertEquals(DeviceActionOutcome.ProgressObserved, secondAttempt.actionOutcome)
        assertEquals(1, tickerMetrics.getInt("submit_count"))
        assertEquals("地铁公告", tickerMetrics.getString("submitted_query"))

        // Verify that when IME_ENTER is a no-op, a ticking live ticker after dispatch does not fake ProgressObserved.
        val noopInitial = startFixture(mode = "ticker", noop = true, intervalMs = 260L)
        val noopInput = noopInitial.elements.single { it.contentDescription == "dyn_input" }
        val noopEdited = gateway.editText(DeviceTextEditRequest(noopInput.index, "无效提交", DeviceTextEditMode.Replace), noopInitial.screenId)
        assertTrue(noopEdited.asToolContent(), noopEdited.success)
        val noopReply = requireNotNull(noopEdited.screen)
        waitForTickAdvance(control("measure").getInt("tick_count"))
        val noopEditor = noopReply.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val noopFirst = gateway.performEditorAction(noopEditor.index, requireNotNull(noopEditor.editorActionLabel), noopReply.screenId)
        assertEquals("stale_screen", noopFirst.errorCode)

        val noopRefreshed = requireNotNull(noopFirst.screen)
        waitForTickAdvance(control("measure").getInt("tick_count"))
        val noopRefreshedEditor = noopRefreshed.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val noopSecond = gateway.performEditorAction(noopRefreshedEditor.index, requireNotNull(noopRefreshedEditor.editorActionLabel), noopRefreshed.screenId)
        val noopMetrics = control("measure")
        saveCaseResult(
            "ticker-and-noop",
            JSONObject()
                .put("ticker_first_error", firstAttempt.errorCode)
                .put("ticker_second_success", secondAttempt.success)
                .put("ticker_metrics", tickerMetrics)
                .put("noop_second_success", noopSecond.success)
                .put("noop_second_error", noopSecond.errorCode)
                .put("noop_second_outcome", noopSecond.actionOutcome?.name)
                .put("noop_metrics", noopMetrics)
        )
        assertEquals(1, noopMetrics.getInt("noop_attempts"))
        assertFalse("Unrelated live ticker must not prove progress when submission had no effect", noopSecond.success)
        assertEquals("ui_change_unverified", noopSecond.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, noopSecond.actionOutcome)
        report("ticker=PASS noop_unverified=PASS")
    }

    @Test
    fun continuouslyUpdatingFeedContainerAllowsExplicitSubmitWhileUnexpectedBannerStillBlocks() = runBlocking {
        val initial = startFixture(mode = "feed", intervalMs = 300L)
        val input = initial.elements.single { it.contentDescription == "dyn_input" }
        val edited = gateway.editText(DeviceTextEditRequest(input.index, "实时流搜索", DeviceTextEditMode.Replace), initial.screenId)
        assertTrue(edited.asToolContent(), edited.success)
        val replyScreen = requireNotNull(edited.screen)
        waitForTickAdvance(control("measure").getInt("tick_count"))

        val editor = replyScreen.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val firstAttempt = gateway.performEditorAction(editor.index, requireNotNull(editor.editorActionLabel), replyScreen.screenId)
        assertEquals("stale_screen", firstAttempt.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, firstAttempt.actionOutcome)

        val refreshed = requireNotNull(firstAttempt.screen)
        waitForTickAdvance(control("measure").getInt("tick_count"))
        val refreshedEditor = refreshed.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val secondAttempt = gateway.performEditorAction(refreshedEditor.index, requireNotNull(refreshedEditor.editorActionLabel), refreshed.screenId)
        val feedMetrics = control("measure")
        assertTrue("Feed container volatility must allow explicit submit: ${secondAttempt.asToolContent()}", secondAttempt.success)
        assertEquals(1, feedMetrics.getInt("submit_count"))

        // Now add an unexpected non-volatile banner outside the feed container; submit must block again.
        val beforeBanner = requireNotNull(secondAttempt.screen)
        control("popup")
        delay(200)
        val bannerEditor = beforeBanner.elements.single { it.contentDescription == "dyn_input" && it.editable }
        val blockedByBanner = gateway.performEditorAction(bannerEditor.index, requireNotNull(bannerEditor.editorActionLabel), beforeBanner.screenId)
        saveCaseResult(
            "feed-and-banner",
            JSONObject()
                .put("feed_submit_success", secondAttempt.success)
                .put("feed_metrics", feedMetrics)
                .put("banner_blocked_error", blockedByBanner.errorCode)
                .put("banner_blocked_outcome", blockedByBanner.actionOutcome?.name)
                .put("banner_visible_in_reply", blockedByBanner.screen?.elements?.any { it.contentDescription == "dyn_unexpected_banner" } == true)
        )
        assertFalse(blockedByBanner.success)
        assertEquals("stale_screen", blockedByBanner.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, blockedByBanner.actionOutcome)
        assertTrue(blockedByBanner.screen?.elements?.any { it.contentDescription == "dyn_unexpected_banner" } == true)
        assertEquals(1, control("measure").getInt("submit_count"))
        report("feed=PASS unexpected_banner_blocked=PASS")
    }

    @Test
    fun chatRuntimeCompletesExplicitSearchTaskOnContinuouslyUpdatingPageEndToEnd() = runBlocking {
        startFixture(mode = "both", intervalMs = 260L)
        val fixture = AutonomyRuntimeFixture(context, settingsOverride = AppSettings(powerModeEnabled = true, memoryEnabled = false, photoAnswerEnabled = false))
        val targetQuery = "高刷屏设置"
        val trace = JSONArray()
        var submitted = false

        try {
            // MuMu runs foreground Accessibility without KernelSU/Root; wait for Accessibility binding only.
            fixture.real.refreshAccessibilityConnection()
            fixture.await { fixture.real.accessibilityState.value.isConnected }
            captureScreenshot("01-before-submit")
            fixture.responder = { request, round ->
                val latestTool = request.messages.lastOrNull { it.role == MessageRole.Tool.name.lowercase() }?.content.orEmpty()
                trace.put(JSONObject().put("round", round).put("latest_tool_head", latestTool.lines().take(6).joinToString(" | ")))
                if ("已提交结果：$targetQuery" in latestTool) {
                    val resultLine = latestTool.lineSequence().firstOrNull { "已提交结果：$targetQuery" in it }.orEmpty()
                    submitted = true
                    listOf(ChatDelta.Content("已完成动态页搜索提交，界面显示：$resultLine"), ChatDelta.Done)
                } else if (round == 1) {
                    listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("dyn-obs-1", AgentToolDefinitions.ObserveScreenToolName, "{}"))), ChatDelta.Done)
                } else {
                    val screenId = Regex("(?m)^screen_id=([A-Za-z0-9-]+)$").find(latestTool)?.groupValues?.get(1)
                        ?: error("Missing screen_id in tool output:\n$latestTool")
                    val inputLine = latestTool.lineSequence().firstOrNull { "EditText" in it && "editor_action_label=\"搜索\"" in it }
                        ?: error("Missing target EditText in tool output:\n$latestTool")
                    val index = Regex("^\\[(\\d+)] ").find(inputLine)?.groupValues?.get(1)?.toInt()
                        ?: error("Missing index in line: $inputLine")
                    val hasQuery = "\"$targetQuery\"" in inputLine
                    if (!hasQuery) {
                        listOf(
                            ChatDelta.ToolCalls(
                                listOf(
                                    ChatToolCall(
                                        "dyn-edit-$round",
                                        AgentToolDefinitions.InputTextToolName,
                                        JSONObject().put("screen_id", screenId).put("index", index)
                                            .put("text", targetQuery).put("mode", "replace").toString()
                                    )
                                )
                            ),
                            ChatDelta.Done
                        )
                    } else {
                        // Allow the background ticker to advance before the model issues perform_editor_action.
                        delay(350)
                        listOf(
                            ChatDelta.ToolCalls(
                                listOf(
                                    ChatToolCall(
                                        "dyn-submit-$round",
                                        AgentToolDefinitions.PerformEditorActionToolName,
                                        JSONObject().put("screen_id", screenId).put("index", index)
                                            .put("expected_label", "搜索").toString()
                                    )
                                )
                            ),
                            ChatDelta.Done
                        )
                    }
                }
            }
            fixture.create()
            val started = SystemClock.elapsedRealtime()
            fixture.send("请在当前动态页面搜索「$targetQuery」并按搜索键提交，告诉我提交后的结果。")
            val elapsedMs = SystemClock.elapsedRealtime() - started
            captureScreenshot("02-after-submit")
            val metrics = control("measure")
            val run = checkNotNull(fixture.journal.latest(fixture.owner))
            val messages = fixture.chats.getMessages(fixture.owner)
            val answer = messages.lastOrNull { it.role == MessageRole.Assistant && it.status == com.flowchat.app.domain.model.MessageStatus.Complete }?.content.orEmpty()
            saveCaseResult(
                "chat-runtime-e2e",
                JSONObject()
                    .put("passed", submitted && metrics.getInt("submit_count") == 1 && metrics.getString("submitted_query") == targetQuery)
                    .put("elapsed_ms", elapsedMs)
                    .put("model_rounds", fixture.modelCalls.get())
                    .put("confirmations", fixture.confirmations.size)
                    .put("run_state", run.state)
                    .put("answer", answer)
                    .put("metrics", metrics)
                    .put("trace", trace)
            )
            assertTrue(submitted)
            assertEquals("Completed", run.state)
            assertTrue(fixture.confirmations.isEmpty())
            assertEquals(1, metrics.getInt("submit_count"))
            assertEquals(targetQuery, metrics.getString("submitted_query"))
            assertTrue(metrics.getInt("tick_count") >= 2)
            assertTrue(answer, "已提交结果：$targetQuery" in answer)
            report("chat_runtime_e2e=PASS elapsed_ms=$elapsedMs rounds=${fixture.modelCalls.get()} ticks=${metrics.getInt("tick_count")}")
        } finally {
            val dbFile = context.getDatabasePath("${fixture.name}.db")
            fixture.close(passed = dbFile.exists())
            check(!dbFile.exists())
        }
    }
}
