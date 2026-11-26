package com.flowchat.app.device

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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

/** Synthetic model with exact known selectors; real runtime, full scans and independent UI ledger. */
class UiLargeNavigationAcceptanceTest : ScrollFixtureDiagnostics() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun scope(page: Int) = DeviceObservationScope(DeviceUiSelector(description = "nav_page_$page"))
    private fun call(name: String, body: JsonObject) = ChatToolCall(UUID.randomUUID().toString(), name, body.toString())
    private fun observe(page: Int? = null) = call(AgentToolDefinitions.ObserveScreenToolName, buildJsonObject {
        put("include_image", false)
        if (page != null) put("observation_scope", buildJsonObject { put("root", buildJsonObject { put("description", "nav_page_$page") }) })
    })
    private fun tap(screen: DeviceScreenSnapshot, page: Int) = call(AgentToolDefinitions.TapUiTargetToolName, buildJsonObject {
        put("screen_id", screen.screenId); put("timeout_ms", 5000)
        put("target", buildJsonObject { put("description", if (page == 99) "nav_return" else "nav_next_${page + 1}") })
        put("observation_scope", buildJsonObject { put("root", buildJsonObject { put("description", "nav_page_$page") }) })
    })
    private fun goal() = call(AgentToolDefinitions.WaitForScreenToolName, buildJsonObject {
        put("include_image", false); put("timeout_ms", 1000)
        put("condition", buildJsonObject {
            put("package_name", fixturePackage); put("element", buildJsonObject { put("text", "Result: ORCHID-42") })
        })
    })
    private fun response(next: ChatToolCall?) = if (next == null) listOf(ChatDelta.Content("已按原目标读取准确结果，已核对当前页面。"), ChatDelta.Done)
        else listOf(ChatDelta.ToolCalls(listOf(next)), ChatDelta.Done)
    private fun launch(start: Int, rearranged: Boolean = false, titleFirst: Boolean = true, stay: Boolean = false) {
        check(!shell("am start -W -f 0x10008000 -n $fixturePackage/com.flowchat.app.device.NavigationFixtureActivity " +
            "--ei start_page $start --ez wide_page true --ez title_first $titleFirst --ez rearranged $rearranged --ez stay_on_page $stay").contains("Error:"))
    }

    @Test fun fiveLargePageGoalsContinueAndReadTheResultWithoutExpandingTheControlPreview() = runBlocking {
        for ((start, rearranged, titleFirst) in listOf(Triple(0, false, true), Triple(0, true, true), Triple(3, false, true), Triple(99, true, true), Triple(7, false, false))) {
            val fixture = AutonomyRuntimeFixture(app); var passed = false
            try {
                fixture.connect(); fixture.create(); launch(start, rearranged, titleFirst)
                val initialPreview = requireNotNull(fixture.real.observeScreen().screen)
                assertTrue(initialPreview.truncated); assertEquals(160, initialPreview.elements.size)
                assertTrue(initialPreview.elements.none { it.contentDescription?.startsWith("nav_page_") == true })
                var first = true; var actions = 0; var verified = false; var unchangedPreviewVerified = false
                val rawEvidence = linkedMapOf<String, String>()
                var referencedRequests = 0; var savedRequestCharacters = 0L
                val transitions = mutableSetOf<String>()
                fixture.responder = { request, _ ->
                    assertTrue(request.messages.any { it.content?.contains("ORCHID-42") == true })
                    val observations = request.messages.filter { it.screenControls != null }
                    observations.lastOrNull()?.let { assertEquals(it.screenControls!!.originalContent, it.content) }
                    for (message in observations) {
                        val source = requireNotNull(message.screenControls)
                        rawEvidence[source.sourceMessageId] = source.originalContent
                        savedRequestCharacters += source.originalContent.length - message.content.orEmpty().length
                    }
                    if (observations.any { it.content?.contains("identical passive control rows") == true }) referencedRequests++
                    val next = if (first) { first = false; observe(if (titleFirst) null else start) } else {
                        val latest = requireNotNull(fixture.lastResult)
                        val screen = requireNotNull(latest.screen)
                        latest.pageTransition?.let { transition ->
                            assertTrue(latest.success); assertTrue(screen.truncated); assertEquals(160, screen.elements.size)
                            assertNotNull(screen.windowContentSha256); transitions += transition.toPageSha256
                            if (!titleFirst) {
                                assertEquals(initialPreview.progressKey(), screen.progressKey())
                                assertNotEquals(initialPreview.windowContentSha256, screen.windowContentSha256)
                                unchangedPreviewVerified = true
                            }
                        }
                        if (screen.elements.any { it.text == "Result: ORCHID-42" }) { verified = true; null }
                        else if (!titleFirst && actions > 0) goal()
                        else {
                            // The local QA driver knows exact intent selectors. It chooses
                            // the current page from the visible header, never old indices.
                            val page = if (!titleFirst) start else requireNotNull(screen.elements.singleOrNull {
                                it.contentDescription?.startsWith("nav_current_page:") == true
                            }?.contentDescription?.substringAfter(':')?.toInt()) { screen.asToolContent() }
                            if (page == 8) goal() else { actions++; tap(screen, page) }
                        }
                    }
                    response(next)
                }
                AutomationTimings.clear()
                val started = SystemClock.elapsedRealtime()
                fixture.send("沿当前测试入口找到并读取 Result: ORCHID-42；明确目标后自动接续，不点击无关选项。")
                val elapsed = SystemClock.elapsedRealtime() - started
                val expected = if (start == 99) 9 else 8 - start
                val actual = requireNotNull(fixture.real.observeScreen(scope(8)).screen)
                assertEquals("page=8;clicks=$expected;wrong=0", actual.elements.single { it.contentDescription == "nav_ledger" }.text)
                assertTrue(verified); assertEquals(expected, actions); assertEquals(expected, fixture.uiTapOutcomes.size)
                assertEquals(expected, transitions.size); assertEquals(0, fixture.confirmations.size)
                if (!titleFirst) assertTrue(unchangedPreviewVerified)
                val metadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
                assertTrue(metadata.pendingEffects.isEmpty()); assertEquals(0, metadata.verifiedStepCount)
                assertEquals(TaskGoalStatus.Unverified, metadata.goalStatus)
                assertTrue(requireNotNull(metadata.budget).stageProgressCount >= expected)
                val saved = fixture.chats.getMessages(fixture.owner).mapNotNull { it.executionContext }.joinToString("\n")
                assertEquals(expected, "page_transition=".toRegex().findAll(saved).count())
                for (message in fixture.chats.getMessages(fixture.owner)) {
                    val raw = rawEvidence[message.id] ?: continue
                    val step = Json.decodeFromString<List<ExecutionStep>>(requireNotNull(message.executionContext)).single()
                    assertEquals(TaskExecutionHistory.completed(step, raw).result, step.result)
                    assertFalse(step.result.contains("identical passive control rows"))
                }
                val stages = AutomationTimings.snapshot().groupBy { it.stage }.mapValues { (_, values) ->
                    "${values.size}:${values.sumOf { it.durationMillis }.toLong()}"
                }
                report("batch2e start=$start rearranged=$rearranged title_first=$titleFirst observation_calls=${fixture.observationCalls.get()} " +
                    "wait_calls=${fixture.waitCalls.get()} model_ms=${fixture.modelMillis} summary_ms=${fixture.summaryMillis} " +
                    "model_request_tokens=${fixture.modelRequestTokens.joinToString(",")} request_chars=${fixture.modelRequestChars.joinToString(",")} " +
                    "referenced_requests=$referencedRequests saved_request_chars=$savedRequestCharacters stages=$stages")
                if (expected >= 5) {
                    assertTrue("Repeated previews should reduce the active request", referencedRequests > 0)
                    assertTrue(savedRequestCharacters > 50_000)
                    assertTrue("Summary requests must decrease below the previous one per click", fixture.summaryCalls.get() < expected)
                }
                report("batch2d start=$start rearranged=$rearranged title_first=$titleFirst completed=true actual_clicks=$actions wrong_clicks=0 " +
                    "preview_controls=160 full_preview_still_truncated=true unchanged_preview_verified=$unchangedPreviewVerified " +
                    "extra_confirmations=0 manual_steps=0 saved_navigation_steps=$expected model_calls=${fixture.modelCalls.get()} summary_calls=${fixture.summaryCalls.get()} elapsed_ms=$elapsed")
                passed = true
            } finally { shell("am force-stop $fixturePackage"); fixture.close(passed) }
        }
    }

    @Test fun outsideChangesAndLostReceiptsCannotAuthorizeAnotherTap() = runBlocking {
        for (scenario in listOf("outside_only", "lost_receipt")) {
            val fixture = AutonomyRuntimeFixture(app); var passed = false
            try {
                fixture.connect(); fixture.create(); launch(0, stay = scenario == "outside_only")
                if (scenario == "lost_receipt") fixture.calendarTapTransform = {
                    it.copy(success = false, errorCode = "qa_lost_navigation_receipt", actionOutcome = DeviceActionOutcome.Unverified, pageTransition = null)
                }
                var stage = 0
                val afterPage = if (scenario == "outside_only") 0 else 1
                fixture.responder = { _, _ -> when (stage++) {
                    0 -> response(observe(0))
                    1 -> response(tap(requireNotNull(fixture.lastResult?.screen), 0))
                    2 -> {
                        assertEquals(DeviceActionOutcome.Unverified, fixture.lastResult?.actionOutcome)
                        assertNull(fixture.lastResult?.pageTransition)
                        response(observe(afterPage))
                    }
                    3 -> response(tap(requireNotNull(fixture.lastResult?.screen), afterPage))
                    else -> listOf(ChatDelta.Error("Controlled unknown-result stop"))
                } }
                fixture.send("仅在大页面中明确执行一次；结果未知时不重复或替换动作。", "Failed")
                val ledger = requireNotNull(fixture.real.observeScreen(DeviceObservationScope(DeviceUiSelector(description = "nav_ledger"))).screen)
                assertEquals("page=$afterPage;clicks=1;wrong=0", ledger.elements.single { it.contentDescription == "nav_ledger" }.text)
                assertEquals(1, fixture.uiTapOutcomes.size); assertEquals(0, fixture.confirmations.size)
                val metadata = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
                assertEquals(1, metadata.pendingEffects.size); assertEquals(0, metadata.verifiedStepCount)
                report("batch2d guard=$scenario passed=true actual_clicks=1 next_click_blocked=true pending_effect_preserved=true")
                passed = true
            } finally { shell("am force-stop $fixturePackage"); fixture.close(passed) }
        }
    }

    private fun physicalWindow(): String {
        val primary = shell("dumpsys window displays").substringAfter("Display: mDisplayId=0 ").substringBefore("Display: mDisplayId=")
        val token = requireNotNull(primary.lineSequence().firstOrNull { it.trim().startsWith("mTopFullscreenOpaqueWindowState=Window{") }?.substringAfter('=')?.trim())
        check(token.contains("com.android.settings"))
        val record = shell("dumpsys window windows").split(Regex("\\n\\s*Window #")).single { it.substringBefore('\n').contains(token.substringBefore(' ')) }
        check(record.contains("mDisplayId=0") && record.contains("mHasSurface=true") && record.contains("isOnScreen=true"))
        return token
    }

    @Test fun largeBackgroundPagesStayOnTheOwnedDisplayAndLeaveThePrimaryWindowVisible() = runBlocking {
        val tasks = DeviceTaskControl(); val root = RootDeviceConnection(app)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root, backgroundExecutionEnabled = true))
        val background = ShizukuDeviceAssistantGateway(app, settings, root, tasks)
        var task: DeviceTaskControl.Task? = null
        try {
            root.refresh(requestPermission = true); background.refreshConnection()
            withTimeout(15_000) { while (!root.state.value.isConnected) delay(100) }
            task = tasks.begin(); tasks.arm(task)
            check(!shell("am start -W --display 0 -a android.settings.SETTINGS").contains("Error:"))
            FlowChatAccessibilityService.current()!!.observeSettledScreen()
            val primary = physicalWindow()
            assertTrue(background.launchApp(fixturePackage, WideNavigationFixtureActivity::class.java.name).success)
            val preview = requireNotNull(background.observeScreen().screen)
            assertTrue(preview.truncated); assertEquals(160, preview.elements.size)
            val display = requireNotNull(preview.geometry).displayId; assertTrue(display > 0)
            repeat(2) { step ->
                val before = requireNotNull(background.observeScreen(scope(step)).screen)
                val outcome = UiTargetActionController(background).tap(DeviceUiTargetRequest(before.screenId, DeviceUiSelector(description = "nav_next_${step + 1}"), 5000), before)
                assertTrue(outcome.asToolContent(), outcome.success); assertNotNull(outcome.pageTransition)
                assertTrue(requireNotNull(outcome.screen).truncated); assertEquals(display, outcome.screen?.geometry?.displayId)
                assertEquals(primary, physicalWindow())
            }
            val ledger = requireNotNull(background.observeScreen(scope(2)).screen)
            assertEquals("page=2;clicks=2;wrong=0", ledger.elements.single { it.contentDescription == "nav_ledger" }.text)
            assertEquals(primary, physicalWindow())
            report("batch2d background_completed=true actual_clicks=2 wrong_clicks=0 preview_controls=160 display=$display primary_surface_visible=true physical_window_unchanged=true")
        } finally {
            tasks.finish(task); withTimeout(15_000) { while (root.serviceInfo()?.activeDisplays != 0) delay(100) }; root.release()
        }
    }
}
