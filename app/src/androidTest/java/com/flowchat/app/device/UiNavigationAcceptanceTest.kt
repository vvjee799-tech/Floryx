package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.*
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.tools.AgentToolDefinitions
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real task runtime and controls; the local model chooses each step from the latest page. */
class UiNavigationAcceptanceTest : ScrollFixtureDiagnostics() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun call(name: String, body: JsonObject = buildJsonObject {}) = ChatToolCall(UUID.randomUUID().toString(), name, body.toString())
    private fun observe() = call(AgentToolDefinitions.ObserveScreenToolName, buildJsonObject { put("include_image", false) })
    private fun tap(screen: DeviceScreenSnapshot, selector: DeviceUiSelector, pane: DeviceUiSelector? = null) = call(
        AgentToolDefinitions.TapUiTargetToolName, buildJsonObject {
            put("screen_id", screen.screenId); put("timeout_ms", 5000)
            put("target", buildJsonObject { selector.text?.let { put("text", it) }; selector.description?.let { put("description", it) } })
            pane?.let { put("observation_scope", buildJsonObject { put("root", buildJsonObject { put("description", it.description) }) }) }
        })
    private fun response(next: ChatToolCall?) = if (next == null) listOf(ChatDelta.Content("已按原目标读取结果并核对当前页面。"), ChatDelta.Done)
        else listOf(ChatDelta.ToolCalls(listOf(next)), ChatDelta.Done)
    private fun launch(start: Int, rearranged: Boolean = false) {
        check(!shell("am start -W -f 0x10008000 -n $fixturePackage/com.flowchat.app.device.NavigationFixtureActivity --ei start_page $start --ez rearranged $rearranged").contains("Error:"))
    }

    private fun physicalSettingsWindow(): String {
        val displays = shell("dumpsys window displays")
        val primary = displays.substringAfter("Display: mDisplayId=0 ").substringBefore("Display: mDisplayId=")
        val token = requireNotNull(primary.lineSequence().firstOrNull { it.trim().startsWith("mTopFullscreenOpaqueWindowState=Window{") }
            ?.substringAfter('=')?.trim()) { "No primary fullscreen window" }
        check(token.contains("com.android.settings")) { token }
        val windows = shell("dumpsys window windows")
        val record = windows.split(Regex("\\n\\s*Window #")).single { it.substringBefore('\n').contains(token.substringBefore(' ')) }
        check(record.contains("mDisplayId=0") && record.contains("mHasSurface=true") && record.contains("isOnScreen=true")) { "Primary settings window is not visible" }
        return token
    }

    @Test fun changedRootsAndDifferentStartsPreserveTheGoalAndAllCompletedSteps() = runBlocking {
        for ((start, rearranged) in listOf(0 to false, 0 to true, 3 to false, 99 to true)) {
            val fixture = AutonomyRuntimeFixture(app); var passed = false
            try {
                fixture.connect(); fixture.create(); launch(start, rearranged)
                var first = true; var verified = false; var actions = 0
                val instruction = "沿当前页面入口找到并读取 ORCHID-42；保留原目标，按实际页面换路，不点击无关选项，不修改任何设置。"
                fixture.responder = { request, _ ->
                    assertTrue("Original goal must remain in the model context", request.messages.any { it.content?.contains("ORCHID-42") == true })
                    if (actions > 0) {
                        val replay = request.messages.joinToString("\n") { it.content.orEmpty() }
                        val completed = fixture.chats.getMessages(fixture.owner).mapNotNull { message ->
                            val digest = Regex("to_page_sha256=([0-9a-f]{64})").find(message.executionContext.orEmpty())?.groupValues?.get(1)
                            digest?.let { message.id to it }
                        }
                        assertEquals("Every completed navigation must be durably recorded",
                            fixture.uiTapOutcomes.count { it == DeviceActionOutcome.ProgressObserved }, completed.size)
                        for ((messageId, digest) in completed) {
                            val nativePair = request.messages.any { it.role == "tool" && it.content.orEmpty().contains("to_page_sha256=$digest") }
                            assertTrue("A completed step must remain in a native result or a sourced checkpoint: $messageId",
                                nativePair || replay.contains(messageId) && replay.contains("to_page_sha256=$digest"))
                        }
                        assertNotEquals("An unknown dispatch must never be followed by another tap",
                            DeviceActionOutcome.Unverified, fixture.lastResult?.actionOutcome)
                    }
                    val next = if (first) { first = false; observe() } else {
                        val screen = requireNotNull(fixture.lastResult?.screen)
                        val result = screen.elements.singleOrNull { it.contentDescription == "nav_result" }
                        if (result != null) {
                            assertEquals("Result: ORCHID-42", result.text); verified = true; null
                        } else {
                            val pane = screen.elements.single { it.contentDescription?.startsWith("nav_page_") == true }
                            val target = screen.elements.single { it.contentDescription?.startsWith("nav_next_") == true || it.contentDescription == "nav_return" }
                            actions++; check(actions <= 12) { "Read-only navigation exceeded its bounded request count" }
                            tap(screen, DeviceUiSelector(description = target.contentDescription), DeviceUiSelector(description = pane.contentDescription))
                        }
                    }
                    response(next)
                }
                val started = SystemClock.elapsedRealtime(); fixture.send(instruction)
                val elapsed = SystemClock.elapsedRealtime() - started
                val current = requireNotNull(fixture.real.observeScreen().screen)
                val expected = if (start == 99) 9 else 8 - start
                assertTrue(verified); assertTrue(actions in expected..12)
                assertEquals("page=8;clicks=$expected;wrong=0", current.elements.single { it.contentDescription == "nav_ledger" }.text)
                assertEquals(expected, fixture.uiTapOutcomes.count { it == DeviceActionOutcome.ProgressObserved })
                assertTrue(fixture.uiTapOutcomes.all { it in setOf(DeviceActionOutcome.ProgressObserved, DeviceActionOutcome.NotDispatched) })
                assertEquals(0, fixture.confirmations.size)
                val metadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
                assertTrue(metadata.pendingEffects.isEmpty()); assertEquals(0, metadata.verifiedStepCount)
                assertEquals(TaskGoalStatus.Unverified, metadata.goalStatus)
                assertTrue(requireNotNull(metadata.budget).stageProgressCount >= expected)
                val saved = fixture.chats.getMessages(fixture.owner).mapNotNull { it.executionContext }.joinToString("\n")
                assertEquals(expected, "page_transition=".toRegex().findAll(saved).count())
                report("batch2c start=$start rearranged=$rearranged completed=true actual_clicks=$expected navigation_requests=$actions wrong_clicks=0 extra_confirmations=0 manual_steps=0 " +
                    "goal_preserved=true saved_navigation_steps=$expected submit_verified=false model_calls=${fixture.modelCalls.get()} summary_calls=${fixture.summaryCalls.get()} elapsed_ms=$elapsed")
                passed = true
            } finally { shell("am force-stop $fixturePackage"); fixture.close(passed) }
        }
    }

    @Test fun losingNavigationEvidenceCannotClearAnUnknownEffectOrAuthorizeAnotherClick() = runBlocking {
        val fixture = AutonomyRuntimeFixture(app); var passed = false
        try {
            fixture.connect(); fixture.create(); launch(0)
            fixture.calendarTapTransform = { it.copy(success = false, errorCode = "qa_lost_navigation_receipt", actionOutcome = DeviceActionOutcome.Unverified, pageTransition = null) }
            var stage = 0
            fixture.responder = { _, _ -> when (stage++) {
                0 -> response(observe())
                1 -> response(tap(requireNotNull(fixture.lastResult?.screen), DeviceUiSelector(description = "nav_next_1"), DeviceUiSelector(description = "nav_page_0")))
                2 -> response(observe())
                3 -> response(tap(requireNotNull(fixture.lastResult?.screen), DeviceUiSelector(description = "nav_next_2"), DeviceUiSelector(description = "nav_page_1")))
                else -> listOf(ChatDelta.Error("Controlled unknown-result stop"))
            } }
            fixture.send("在只读夹具中导航到 ORCHID-42；回执不明时不要重复或替换动作。", "Failed")
            val current = requireNotNull(fixture.real.observeScreen().screen)
            assertEquals("page=1;clicks=1;wrong=0", current.elements.single { it.contentDescription == "nav_ledger" }.text)
            assertEquals(1, fixture.uiTapOutcomes.size)
            val metadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
            assertEquals(1, metadata.pendingEffects.size); assertEquals(0, metadata.verifiedStepCount)
            report("batch2c guard=lost_receipt passed=true actual_clicks=1 next_click_blocked=true observation_did_not_clear_uncertainty=true")
            passed = true
        } finally { shell("am force-stop $fixturePackage"); fixture.close(passed) }
    }

    @Test fun ownedBackgroundPagesContinueWithoutMovingThePhysicalWindow() = runBlocking {
        val tasks = DeviceTaskControl(); val root = RootDeviceConnection(app)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root, backgroundExecutionEnabled = true))
        val background = ShizukuDeviceAssistantGateway(app, settings, root, tasks)
        var task: DeviceTaskControl.Task? = null
        try {
            root.refresh(requestPermission = true); background.refreshConnection()
            withTimeout(15_000) { while (!root.state.value.isConnected) delay(100) }
            task = tasks.begin(); tasks.arm(task)
            // A separate system app provides an independent, visible primary witness.
            check(!shell("am start -W --display 0 -a android.settings.SETTINGS").contains("Error:"))
            val physicalBefore = FlowChatAccessibilityService.current()!!.observeSettledScreen()
            physicalBefore?.let { assertEquals("com.android.settings", it.packageName); assertEquals(0, it.geometry?.displayId) }
            val primaryToken = physicalSettingsWindow()
            assertTrue(background.launchApp(fixturePackage, NavigationFixtureActivity::class.java.name).success)
            var current = requireNotNull(background.observeScreen().screen)
            val display = requireNotNull(current.geometry).displayId; assertTrue(display > 0)
            repeat(2) { step ->
                val scope = DeviceObservationScope(DeviceUiSelector(description = "nav_page_$step"))
                current = requireNotNull(background.observeScreen(scope).screen)
                val result = UiTargetActionController(background).tap(DeviceUiTargetRequest(current.screenId, DeviceUiSelector(description = "nav_next_${step + 1}"), 5000), current)
                assertTrue(result.asToolContent(), result.success); assertNotNull(result.pageTransition)
                current = requireNotNull(result.screen); assertEquals(display, current.geometry?.displayId)
                assertEquals(primaryToken, physicalSettingsWindow())
            }
            assertEquals("page=2;clicks=2;wrong=0", current.elements.single { it.contentDescription == "nav_ledger" }.text)
            var physicalAfter: DeviceScreenSnapshot? = null
            repeat(5) { if (physicalAfter == null) { physicalAfter = FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0); if (physicalAfter == null) delay(100) } }
            if (physicalBefore != null) physicalAfter?.let { assertEquals(physicalBefore.packageName, it.packageName); assertEquals(physicalBefore.windowId, it.windowId) }
            // A null accessibility read never equals a successful window check: the
            // independent WindowManager token, surface and visibility must all agree.
            assertEquals(primaryToken, physicalSettingsWindow())
            report("batch2c background_completed=true actual_clicks=2 wrong_clicks=0 display=$display physical_window_unchanged=true " +
                "independent_primary_surface_visible=true primary_controls_readable=${physicalBefore != null && physicalAfter != null}")
        } finally {
            tasks.finish(task); withTimeout(15_000) { while (root.serviceInfo()?.activeDisplays != 0) delay(100) }; root.release()
        }
    }

    @Test fun realSettingsReachTheSameReadOnlyGoalFromThreeDifferentPages() = runBlocking {
        for (entry in listOf(Settings.ACTION_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SOUND_SETTINGS)) {
            val fixture = AutonomyRuntimeFixture(app); var passed = false
            try {
                fixture.connect(); fixture.create()
                withContext(Dispatchers.Main) { app.startActivity(Intent(entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                var first = true; var verified = false; var steps = 0; var entryVerified = false
                val route = mutableListOf<String>()
                fixture.responder = { _, _ ->
                    val next = if (first) { first = false; observe() } else {
                        val screen = requireNotNull(fixture.lastResult?.screen)
                        check(screen.packageName == "com.android.settings") { screen.asToolContent() }
                        if (!entryVerified) {
                            when (entry) {
                                Settings.ACTION_DISPLAY_SETTINGS -> check(screen.elements.any { it.displayText.contains("亮度") || it.displayText.contains("深色") }) { screen.asToolContent() }
                                Settings.ACTION_SOUND_SETTINGS -> check(screen.elements.any { it.displayText.contains("媒体") }) { screen.asToolContent() }
                            }
                            entryVerified = true
                        }
                        when {
                            screen.elements.any { it.displayText.contains("媒体") } -> { verified = true; null }
                            screen.elements.any { it.text == "声音与振动" } -> tap(screen, DeviceUiSelector(text = "声音与振动"))
                            screen.elements.any { it.displayText.contains("亮度") || it.displayText.contains("深色") } && entry == Settings.ACTION_DISPLAY_SETTINGS && steps == 0 -> call(AgentToolDefinitions.PressBackToolName)
                            else -> {
                                val list = screen.elements.filter { it.scrollable }.maxBy { it.bounds.width.toLong() * it.bounds.height }
                                call(AgentToolDefinitions.ScrollContainerToolName, buildJsonObject {
                                    put("screen_id", screen.screenId); put("index", list.index); put("direction", "down")
                                    put("target", buildJsonObject { put("text", "声音与振动") })
                                })
                            }
                        }.also { if (it != null) { steps++; route += it.name; check(steps <= 12) } }
                    }
                    response(next)
                }
                val started = SystemClock.elapsedRealtime()
                fixture.send("从当前设置页找到声音与振动，读取媒体音量所在页面并核对；不要修改音量和其他设置。")
                assertTrue(verified); assertTrue(entryVerified); assertEquals(0, fixture.confirmations.size)
                assertTrue(requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson)).pendingEffects.isEmpty())
                report("batch2c real_settings_entry=$entry completed=true navigation_steps=$steps actual_clicks=${fixture.uiTapOutcomes.size} " +
                    "entry_verified=true route=${route.joinToString(",")} extra_confirmations=0 manual_steps=0 settings_values_changed=false elapsed_ms=${SystemClock.elapsedRealtime() - started}")
                passed = true
            } finally { fixture.close(passed) }
        }
    }
}
