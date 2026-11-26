package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Local model, real task runtime and independent fixture-side click ledger. No accounts or network. */
class UiTargetRecoveryAcceptanceTest : ScrollFixtureDiagnostics() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private val pane = DeviceObservationScope(DeviceUiSelector(description="r2b_scope"))
    private fun control(value: String) { app.sendBroadcast(Intent("$fixturePackage.R2B_CONTROL").setPackage(fixturePackage).putExtra("command",value)) }
    private fun shell(value: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(value)).bufferedReader().use { it.readText() }
    private fun observe(scoped: Boolean) = ChatToolCall(UUID.randomUUID().toString(),AgentToolDefinitions.ObserveScreenToolName,
        buildJsonObject { put("include_image",false);if(scoped) put("observation_scope",buildJsonObject { put("root",buildJsonObject { put("description","r2b_scope") }) }) }.toString())
    private fun tap(screen: DeviceScreenSnapshot, target: DeviceUiSelector? = null, within: DeviceObservationScope? = null): ChatToolCall {
        // Android Button may expose transformed ALL-CAPS text. Use what was actually
        // observed; an off-preview target retains only its independently known exact ID.
        val exact = target ?: DeviceUiSelector(description="r2b_action", text=screen.elements.singleOrNull { it.contentDescription=="r2b_action" }?.text)
        return ChatToolCall(UUID.randomUUID().toString(),AgentToolDefinitions.TapUiTargetToolName,buildJsonObject {
            put("screen_id",screen.screenId);put("target",buildJsonObject {
                exact.description?.let { put("description",it) };exact.text?.let { put("text",it) };exact.resourceId?.let { put("resource_id",it) }
            });put("timeout_ms",5000)
            if(within!=null) put("observation_scope",buildJsonObject { put("root",buildJsonObject { put("description",within.root.description) }) })
        }.toString())
    }
    private fun response(call: ChatToolCall?) = if(call==null) listOf(ChatDelta.Content("已找到准确目标并完成一次操作，已核对实际结果。"),ChatDelta.Done)
        else listOf(ChatDelta.ToolCalls(listOf(call)),ChatDelta.Done)
    private suspend fun ledger(fixture: AutonomyRuntimeFixture): String = withTimeout(8000) {
        while(true) {
            val screen=fixture.real.observeScreen(pane).screen
            val text=screen?.elements?.singleOrNull { it.contentDescription=="r2b_ledger" }?.text
            if(text!=null) return@withTimeout text
            delay(100)
        }
        error("unreachable")
    }
    private fun launchFixture(labelRow: Boolean = false, searchEcho: Boolean = false) { check(!shell("am start -W -f 0x10008000 -n $fixturePackage/com.flowchat.app.device.ScopedObservationFixtureActivity --ez recovery_fixture true --ez label_row $labelRow --ez search_echo $searchEcho").contains("Error:")) }
    private suspend fun connectForeground(fixture: AutonomyRuntimeFixture) {
        if (InstrumentationRegistry.getArguments().getString("allowAccessibilityOnly") == "true") {
            fixture.real.refreshAccessibilityConnection()
            fixture.await { fixture.real.accessibilityState.value.isConnected }
        } else {
            fixture.connect()
        }
    }

    @Test fun backgroundTargetStaysOnItsOwnedDisplayAndLeavesThePhysicalWindowAlone() = runBlocking {
        val tasks=DeviceTaskControl();val root=RootDeviceConnection(app)
        val settings=AppSettingsStore(AppSettings(powerModeEnabled=true,powerConnectionMode=PowerConnectionMode.Root,backgroundExecutionEnabled=true))
        val background=ShizukuDeviceAssistantGateway(app,settings,root,tasks)
        var task:DeviceTaskControl.Task?=null;var actualWrites=-1
        val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { actualWrites=intent.getIntExtra("writes",-1) } }
        if(android.os.Build.VERSION.SDK_INT>=33) app.registerReceiver(receiver,IntentFilter("${app.packageName}.R2B_RESULT"),Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver,IntentFilter("${app.packageName}.R2B_RESULT"))
        try {
            root.refresh(requestPermission=true);background.refreshConnection()
            withTimeout(15_000) { while(!root.state.value.isConnected) delay(100) }
            task=tasks.begin();tasks.arm(task)
            val before=FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            val launched=background.launchApp(fixturePackage,ScopedObservationFixtureActivity::class.java.name)
            assertTrue(launched.asToolContent(),launched.success)
            val initial=requireNotNull(background.observeScreen(pane).screen)
            val display=requireNotNull(initial.geometry).displayId;assertTrue(display>0)
            val exact=initial.elements.single { it.contentDescription=="r2b_action" }
            val result=UiTargetActionController(background).tap(DeviceUiTargetRequest(initial.screenId,DeviceUiSelector(description="r2b_action",text=exact.text),5000),initial)
            assertTrue(result.asToolContent(),result.success);assertEquals(display,result.screen?.geometry?.displayId)
            val after=FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            assertEquals(before?.packageName,after?.packageName);assertEquals(before?.windowId,after?.windowId)
            control("report")
            withTimeout(5000) { while(actualWrites<0) delay(100) }
            assertEquals(1,actualWrites)
            report("batch2b background_target=true completed=true actual_clicks=$actualWrites display=$display physical_window_unchanged=true")
        } finally {
            tasks.finish(task)
            withTimeout(15_000) { while(root.serviceInfo()?.activeDisplays!=0) delay(100) }
            root.release();app.unregisterReceiver(receiver)
        }
    }

    @Test fun sixChangedPagesFinishOneGoalWithOneSemanticActionRequestEach() = runBlocking {
        for(scenario in listOf("relayout","dispatch_race","loading","temporary_popup","beyond_preview","parent_reused")) {
            val fixture=AutonomyRuntimeFixture(app);var passed=false
            try {
                connectForeground(fixture);fixture.create();launchFixture(scenario=="parent_reused")
                var stage=0;var actionRequests=0;var injected=false
                if(scenario in setOf("dispatch_race","parent_reused")) fixture.beforeUiTap={
                    if(!injected) { injected=true;control(if(scenario=="parent_reused") "target_reparent" else "target_relayout");delay(150) }
                }
                fixture.responder={ request,_ ->
                    val call=when(stage++) {
                        0 -> observe(scenario!="beyond_preview")
                        1 -> {
                            val before=requireNotNull(fixture.lastResult?.screen)
                            if(scenario=="beyond_preview") { assertTrue(before.truncated);assertTrue(before.elements.none { it.contentDescription=="r2b_action" }) }
                            when(scenario) { "relayout" -> control("target_relayout");"loading" -> control("target_loading");"temporary_popup" -> control("target_overlay") }
                            if(scenario in setOf("relayout","loading","temporary_popup")) delay(100)
                            actionRequests++;tap(before,within=if(scenario=="beyond_preview") pane else null)
                        }
                        else -> {
                            check(fixture.lastResult?.actionOutcome in setOf(DeviceActionOutcome.ProgressObserved,DeviceActionOutcome.TargetVerified)) {
                                fixture.lastResult?.asToolContent().orEmpty()
                            };null
                        }
                    }
                    if(call!=null) check(request.tools.any { it.name==call.name })
                    response(call)
                }
                val started=SystemClock.elapsedRealtime()
                fixture.send("在当前测试页面找到 Scoped action，页面变化时自行重新定位，执行一次并核对结果。")
                val actual=ledger(fixture)
                assertTrue(actual,actual.startsWith("writes=1;"));assertEquals(1,actionRequests);assertEquals(0,fixture.confirmations.size)
                assertTrue(actual,actual.endsWith("wrong=0"))
                if(scenario in setOf("dispatch_race","parent_reused")) { assertEquals(2,fixture.uiTapOutcomes.size);assertEquals(DeviceActionOutcome.NotDispatched,fixture.uiTapOutcomes.first()) }
                else assertEquals(1,fixture.uiTapOutcomes.size)
                if(scenario=="parent_reused") assertTrue(fixture.uiTapErrorCodes.first() in setOf("ui_target_changed","stale_screen"))
                if(scenario=="loading") assertTrue("The fixture must actually be observed disabled before it becomes ready",
                    fixture.targetWaitResults.any { it.screen?.elements?.any { node -> node.contentDescription=="r2b_action" && !node.enabled }==true })
                val metadata=requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
                assertTrue(metadata.pendingEffects.isEmpty())
                report("batch2b scenario=$scenario completed=true actual_clicks=1 semantic_action_requests=$actionRequests raw_tap_attempts=${fixture.uiTapOutcomes.size} " +
                    "first_rejection=${fixture.uiTapErrorCodes.firstOrNull()} wrong_clicks=0 app_confirmations=0 manual_steps=0 model_calls=${fixture.modelCalls.get()} elapsed_ms=${SystemClock.elapsedRealtime()-started}")
                passed=true
            } finally { control("finish");delay(150);fixture.close(passed) }
        }
    }

    @Test fun ambiguityCannotClickAndUncertainClickCannotBeReplayedByTheModel() = runBlocking {
        for(scenario in listOf("ambiguous","unverified")) {
            val fixture=AutonomyRuntimeFixture(app);var passed=false
            try {
                connectForeground(fixture);fixture.create();launchFixture();var stage=0
                if(scenario=="unverified") fixture.calendarTapTransform={ it.copy(success=false,errorCode="qa_lost_click_receipt",actionOutcome=DeviceActionOutcome.Unverified) }
                fixture.responder={ _,_ -> when(stage++) {
                    0 -> response(observe(true))
                    1 -> {
                        val before=requireNotNull(fixture.lastResult?.screen)
                        if(scenario=="ambiguous") { control("target_duplicate");delay(150) }
                        response(tap(before))
                    }
                    2 -> if(scenario=="unverified") response(tap(requireNotNull(fixture.lastResult?.screen))) else listOf(ChatDelta.Error("Controlled ambiguous-target stop"))
                    else -> listOf(ChatDelta.Error("Controlled unknown-result stop"))
                } }
                fixture.send("对当前测试页面的 Scoped action 操作一次；找不准或结果不明时不得猜测或重复操作。","Failed")
                val actual=ledger(fixture)
                val metadata=requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
                if(scenario=="ambiguous") { assertTrue(actual,actual.startsWith("writes=0;"));assertTrue(fixture.uiTapOutcomes.isEmpty());assertTrue(metadata.pendingEffects.isEmpty()) }
                else { assertTrue(actual,actual.startsWith("writes=1;"));assertEquals(1,fixture.uiTapOutcomes.size);assertEquals(1,metadata.pendingEffects.size) }
                assertEquals(0,fixture.confirmations.size)
                report("batch2b guard=$scenario passed=true actual_clicks=${fixture.uiTapOutcomes.size} unknown_gate_preserved=${metadata.pendingEffects.isNotEmpty()} no_replay=true")
                passed=true
            } finally { control("finish");delay(150);fixture.close(passed) }
        }
    }

    @Test fun realSettingsLabelReachesItsClickableRowFromThreeListPositions() = runBlocking {
        repeat(3) { sample ->
            val fixture=AutonomyRuntimeFixture(app);var passed=false
            try {
                fixture.connect();fixture.create()
                withContext(Dispatchers.Main) { app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                var current=requireNotNull(fixture.real.observeScreen().screen)
                if(sample>0) {
                    val list=current.elements.filter { it.scrollable }.maxBy { it.bounds.width.toLong()*it.bounds.height }
                    current=requireNotNull(fixture.real.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Up,15*sample,600,current.screenId,list.index)).screen)
                }
                // Reuse the first batch's proven search to bring the requested label into view.
                // This batch measures fresh target activation and parent resolution, not new scrolling.
                var targetReady=false
                for(step in 0..9) {
                    val list=current.elements.filter { it.scrollable }.maxBy { it.bounds.width.toLong()*it.bounds.height }
                    val located=fixture.real.scrollContainer(DeviceContainerScrollRequest(list.index,DeviceScrollDirection.Down,current.screenId,target=DeviceUiSelector(text="声音与振动")))
                    assertTrue(located.asToolContent(),located.success);current=requireNotNull(located.screen)
                    if(located.scrollFeedback?.targetState=="ready") { targetReady=true;break }
                }
                assertTrue(targetReady)
                var stage=0;var verified=false
                fixture.responder={ _,_ -> response(when(stage++) {
                    0 -> observe(false)
                    1 -> tap(requireNotNull(fixture.lastResult?.screen),DeviceUiSelector(text="声音与振动"))
                    else -> {
                        val result=requireNotNull(fixture.lastResult);val screen=requireNotNull(result.screen)
                        check(result.success && screen.packageName=="com.android.settings" && screen.elements.any { it.displayText.contains("媒体") }) { result.asToolContent() }
                        verified=true;null
                    }
                }) }
                val started=SystemClock.elapsedRealtime()
                fixture.send("打开声音与振动设置并核对页面，不修改音量或其他设置。")
                assertTrue(verified);assertEquals(1,fixture.uiTapOutcomes.size);assertEquals(0,fixture.confirmations.size)
                report("batch2b real_settings_sample=$sample completed=true actual_clicks=1 app_confirmations=0 manual_steps=0 " +
                    "settings_values_changed=false activation_elapsed_ms=${SystemClock.elapsedRealtime()-started}")
                passed=true
            } finally { fixture.close(passed) }
        }
    }

    @Test fun searchBoxEchoTextResolvesToSingleClickableResultRowAndStillRejectsDuplicateRows() = runBlocking {
        // Part A: Search EditText + single clickable result row both expose text "Scoped action" -> clicks the row, not the EditText.
        run {
            val fixture = AutonomyRuntimeFixture(app); var passed = false
            try {
                connectForeground(fixture); fixture.create(); launchFixture(labelRow = true, searchEcho = true)
                var stage = 0; var actionRequests = 0
                fixture.responder = { request, _ ->
                    val call = when (stage++) {
                        0 -> observe(true)
                        1 -> {
                            val before = requireNotNull(fixture.lastResult?.screen)
                            actionRequests++
                            tap(before, DeviceUiSelector(text = "Scoped action"), within = pane)
                        }
                        else -> {
                            check(fixture.lastResult?.actionOutcome in setOf(DeviceActionOutcome.ProgressObserved, DeviceActionOutcome.TargetVerified)) {
                                fixture.lastResult?.asToolContent().orEmpty()
                            }
                            null
                        }
                    }
                    if (call != null) check(request.tools.any { it.name == call.name })
                    response(call)
                }
                fixture.send("在当前测试页面打开与搜索词同名的唯一结果行并核对完成状态。")
                val actual = ledger(fixture)
                assertTrue(actual, actual.startsWith("writes=1;"))
                assertTrue(actual, actual.endsWith("wrong=0"))
                assertEquals(1, actionRequests)
                assertEquals(1, fixture.uiTapOutcomes.size)
                assertEquals(0, fixture.confirmations.size)
                val metadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
                assertTrue(metadata.pendingEffects.isEmpty())
                passed = true
            } finally { control("finish"); delay(150); fixture.close(passed) }
        }
        // Part B: Search EditText + two clickable result rows exposing text "Scoped action" -> strictly rejects as ambiguous.
        run {
            val fixture = AutonomyRuntimeFixture(app); var passed = false
            var ambiguousToolContent = ""
            try {
                connectForeground(fixture); fixture.create(); launchFixture(labelRow = true, searchEcho = true)
                var stage = 0
                fixture.responder = { request, _ ->
                    when (stage++) {
                        0 -> response(observe(true))
                        1 -> {
                            val before = requireNotNull(fixture.lastResult?.screen)
                            control("target_duplicate_row"); delay(150)
                            response(tap(before, DeviceUiSelector(text = "Scoped action"), within = pane))
                        }
                        else -> {
                            ambiguousToolContent = request.messages.last { it.role == "tool" }.content.orEmpty()
                            listOf(ChatDelta.Error("Controlled ambiguous-target stop"))
                        }
                    }
                }
                fixture.send("对当前测试页面的 Scoped action 操作一次；有多个可选结果行时不得猜测。", "Failed")
                val actual = ledger(fixture)
                assertTrue(actual, actual.startsWith("writes=0;"))
                assertTrue(actual, actual.endsWith("wrong=0"))
                assertTrue(fixture.uiTapOutcomes.isEmpty())
                assertTrue(ambiguousToolContent, ambiguousToolContent.contains("error_code: ui_target_ambiguous"))
                assertTrue(ambiguousToolContent, ambiguousToolContent.contains("action_outcome=NotDispatched"))
                assertTrue(ambiguousToolContent, ambiguousToolContent.contains("resource_id"))
                val metadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
                assertTrue(metadata.pendingEffects.isEmpty())
                passed = true
            } finally { control("finish"); delay(150); fixture.close(passed) }
        }
    }
}
