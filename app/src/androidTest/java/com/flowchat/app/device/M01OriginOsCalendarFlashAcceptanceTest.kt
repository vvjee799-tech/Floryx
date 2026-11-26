package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.di.AppModule
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.CalendarEventRow
import com.flowchat.app.domain.device.CalendarEventSave
import com.flowchat.app.domain.device.CalendarTimeEdit
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * M01 real iQOO OriginOS calendar closure via normal chat entry and official deepseek-flash:
 * create + save one isolated LOCAL event, rebuild runtime, edit the same event's times in place,
 * verify source readback (same ID, old interval empty, count 1, single commit), then clean up.
 */
class M01OriginOsCalendarFlashAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test fun reproduceSavedCalendarEditLocally() = runBlocking<Unit> {
        assumeTrue(args.getString("runM01LocalRepro") == "true")
        val sample = validatedSample()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "d3-m01-repro-$sample").apply {
            check(mkdirs() || isDirectory)
        }
        val title = "Floryx_M01_Repro_${sample.take(8)}"
        val originalArgs = """{"title":"$title","begin_time":"2026-12-22T18:10:00+08:00","end_time":"2026-12-22T18:40:00+08:00"}"""
        val revisedArgs = """{"title":"$title","begin_time":"2026-12-22T18:15:00+08:00","end_time":"2026-12-22T18:45:00+08:00"}"""
        val traceEvents = org.json.JSONArray()
        val t0 = SystemClock.elapsedRealtime()
        fun logTrace(op: String, detail: String = "") {
            traceEvents.put(JSONObject().put("t_ms", SystemClock.elapsedRealtime() - t0).put("op", op).put("detail", detail))
        }
        val fixture = AutonomyRuntimeFixture(context, name = "autonomyqa-m01-repro-$sample", wrapCalendarEdit = { delegate ->
            object : com.flowchat.app.domain.chat.TaskEffectAdapter by delegate {
                override suspend fun prepare(record: com.flowchat.app.domain.chat.TaskEffectRecord, arguments: String): String? {
                    logTrace("prepare.start")
                    return try { delegate.prepare(record, arguments).also { logTrace("prepare.end") } }
                    catch (t: Throwable) { logTrace("prepare.err", "${t.javaClass.name}:${t.message}\n${t.stackTraceToString().take(2000)}"); throw t }
                }
                override suspend fun dispatch(effectId: String, arguments: String) {
                    logTrace("dispatch.start")
                    try { delegate.dispatch(effectId, arguments); logTrace("dispatch.end") }
                    catch (t: Throwable) { logTrace("dispatch.err", "${t.javaClass.name}:${t.message}\n${t.stackTraceToString().take(2000)}"); throw t }
                }
                override suspend fun inspect(record: com.flowchat.app.domain.chat.TaskEffectRecord): com.flowchat.app.domain.chat.TaskEffectProof? {
                    logTrace("inspect.start")
                    return try { delegate.inspect(record).also { logTrace("inspect.end", "${it?.resolution}") } }
                    catch (t: Throwable) { logTrace("inspect.err", "${t.javaClass.name}:${t.message}\n${t.stackTraceToString().take(2000)}"); throw t }
                }
            }
        })
        var activity: MainActivity? = null
        var createdRow: CalendarEventRow? = null
        fun mount(targetActivity: MainActivity) {
            val viewModel = ChatViewModel(fixture.runtime)
            targetActivity.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
        }
        val hiltSafetyLog = org.json.JSONArray()
        var safetyJob: kotlinx.coroutines.Job? = null
        try {
            val hiltEntry = dagger.hilt.android.EntryPointAccessors.fromApplication(
                context,
                com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint::class.java
            )
            val hiltSafetyBefore = hiltEntry.safetyMonitor().state.value.toString()
            val hiltConnBefore = hiltEntry.deviceGateway().connectionState.value.toString()
            safetyJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                hiltEntry.safetyMonitor().state.collect { s ->
                    val settingsVal = runCatching {
                        android.provider.Settings.Secure.getString(
                            context.contentResolver,
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        )
                    }.getOrElse { "err:${it.message}" }
                    hiltSafetyLog.put(
                        JSONObject()
                            .put("t_ms", SystemClock.elapsedRealtime() - t0)
                            .put("safety", s.toString())
                            .put("svc_connected", com.flowchat.app.data.device.FlowChatAccessibilityService.connected.value)
                            .put("svc_current", com.flowchat.app.data.device.FlowChatAccessibilityService.current() != null)
                            .put("hilt_acc", hiltEntry.deviceGateway().accessibilityState.value.toString())
                            .put("settings_secure", settingsVal ?: "null")
                    )
                }
            }
            ActionOutcomeServiceReadiness().ensureService()
            logTrace("after_ensureService", hiltEntry.safetyMonitor().state.value.toString())
            fixture.connect()
            logTrace("after_fixture_connect", hiltEntry.safetyMonitor().state.value.toString())
            fixture.responder = { _, round ->
                when (round) {
                    1 -> listOf(
                        com.flowchat.app.domain.model.ChatDelta.ToolCalls(
                            listOf(
                                com.flowchat.app.domain.model.ChatToolCall(
                                    "open-1",
                                    com.flowchat.app.domain.device.PublicAppActions.Calendar,
                                    originalArgs.dropLast(1) + ",\"completion\":\"save\"}"
                                )
                            )
                        ),
                        com.flowchat.app.domain.model.ChatDelta.Done
                    )
                    2 -> listOf(
                        com.flowchat.app.domain.model.ChatDelta.ToolCalls(
                            listOf(
                                com.flowchat.app.domain.model.ChatToolCall(
                                    "save-1",
                                    CalendarEventSave.ToolName,
                                    originalArgs
                                )
                            )
                        ),
                        com.flowchat.app.domain.model.ChatDelta.Done
                    )
                    3 -> listOf(com.flowchat.app.domain.model.ChatDelta.Content("已保存测试日程。"), com.flowchat.app.domain.model.ChatDelta.Done)
                    else -> error("Unexpected round $round")
                }
            }
            fixture.create()
            activity = launchM03Chat(context, automation)
            withContext(Dispatchers.Main) { mount(activity!!) }
            fixture.send("创建并保存日程：$title")
            createdRow = fixture.real.inspectCalendarEvents(originalArgs)?.rows?.singleOrNull()
            assertNotNull(createdRow)
            withContext(Dispatchers.Main) { activity?.finish() }
            activity = launchM03Chat(context, automation)
            fixture.rebuildRuntime()
            withContext(Dispatchers.Main) { mount(activity!!) }
            compose.waitForIdle()

            // Test ChatTaskRuntime saved calendar edit end-to-end with zero external model calls.
            val diag = JSONObject()
            val prevId = fixture.journal.latest(fixture.owner)?.id
            val editStart = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { fixture.runtime.updateInput("把刚才的日程改到同一天18:15到18:45。") }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            withTimeout(120_000L) {
                while (true) {
                    val run = fixture.journal.latest(fixture.owner)
                    if (run != null && run.id != prevId && run.state !in setOf("Running", "WaitingForUser") &&
                        fixture.owner !in fixture.runtime.activeConversations.value) break
                    delay(50)
                }
            }
            val after = requireNotNull(fixture.journal.latest(fixture.owner))
            val msg = fixture.chats.getMessages(fixture.owner).lastOrNull()?.content.orEmpty()
            diag.put("elapsed_ms", SystemClock.elapsedRealtime() - editStart)
                .put("state", after.state)
                .put("hilt_safety_before", hiltSafetyBefore)
                .put("hilt_conn_before", hiltConnBefore)
                .put("hilt_safety_after", hiltEntry.safetyMonitor().state.value.toString())
                .put("hilt_conn_after", hiltEntry.deviceGateway().connectionState.value.toString())
                .put("hilt_power_mode", hiltEntry.settings().state.value.powerModeEnabled)
                .put("hilt_conn_mode", hiltEntry.settings().state.value.powerConnectionMode.name)
                .put("metadata_json", after.metadataJson)
                .put("last_message", msg)
                .put("ui_error", fixture.runtime.uiState.value.errorMessage)
                .put("device_task_paused", fixture.runtime.uiState.value.deviceTaskPaused)
                .put("safety_state", fixture.real.safetyState.value.toString())
                .put("accessibility_state", fixture.real.accessibilityState.value.toString())
                .put("root_state", fixture.real.connectionState.value.toString())
                .put("settings_version", fixture.settings.state.value.deviceConfigurationVersion)
                .put("revised_rows", fixture.real.inspectCalendarEvents(revisedArgs)?.rows?.size ?: -1)
                .put("hilt_safety_log", hiltSafetyLog)
                .put("trace", traceEvents)
            File(output, "diag.json").writeText(diag.toString(2))
            assertEquals("Expected Completed: $diag", "Completed", after.state)
        } finally {
            safetyJob?.cancel()
            withContext(NonCancellable) {
                val cleanupWhere = if (createdRow != null) "_id=${createdRow!!.id}" else "title='$title'"
                shell("content delete --user 0 --uri content://com.android.calendar/events --where $cleanupWhere")
                withContext(Dispatchers.Main) { activity?.finish() }
                fixture.close(true)
            }
        }
    }

    @Test fun createSaveAndEditIsolatedCalendarEventWithOfficialFlash() = runBlocking<Unit> {
        assumeTrue(args.getString("runM01OfficialFlash") == "true")
        val sample = validatedSample()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "d3-m01-flash-$sample").apply {
            check(mkdirs() || isDirectory)
        }
        val title = "Floryx_M01_${sample.take(8)}"
        val originalArgs = """{"title":"$title","begin_time":"2026-12-22T18:10:00+08:00","end_time":"2026-12-22T18:40:00+08:00"}"""
        val revisedArgs = """{"title":"$title","begin_time":"2026-12-22T18:15:00+08:00","end_time":"2026-12-22T18:45:00+08:00"}"""
        val originalRequest = CalendarEventSave.request(originalArgs)
        val revisedRequest = CalendarEventSave.request(revisedArgs)

        val (provider, key) = loadOfficialFlashProvider(context)
        val json = AppModule.provideJson()
        val http = AppModule.provideHttpClient(json)
        val wire = FlashLedger(http, output, sample, official = true, describe = { payload, writer ->
            val names = (payload.optJSONArray("tools") ?: JSONArray()).objects().map { it.getJSONObject("function").getString("name") }
            JSONObject().put("stage", if (writer) "checkpoint" else "m01_calendar").put("tool_count", names.size)
        }, maxRequests = 12)
        val fixture = AutonomyRuntimeFixture(
            context = context,
            providerOverride = provider,
            apiKeyOverride = key,
            name = "autonomyqa-m01-flash-$sample"
        )
        val result = JSONObject()
            .put("sample", sample)
            .put("title", title)
            .put("original_args", JSONObject(originalArgs))
            .put("revised_args", JSONObject(revisedArgs))
            .put("passed", false)
        var activity: MainActivity? = null
        var createdRow: CalendarEventRow? = null

        fun mount(targetActivity: MainActivity) {
            val viewModel = ChatViewModel(fixture.runtime)
            targetActivity.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
        }

        suspend fun sendViaChat(text: String): Long {
            val previous = fixture.journal.latest(fixture.owner)?.id
            withContext(Dispatchers.Main) { fixture.runtime.updateInput(text) }
            compose.waitForIdle()
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            withTimeout(120_000L) {
                while (true) {
                    val run = fixture.journal.latest(fixture.owner)
                    if (run != null && run.id != previous && run.state !in setOf("Running", "WaitingForUser") &&
                        fixture.owner !in fixture.runtime.activeConversations.value) break
                    delay(50)
                }
            }
            return SystemClock.elapsedRealtime() - started
        }

        try {
            ActionOutcomeServiceReadiness().ensureService()
            fixture.connect()
            fixture.settings.setPowerModeEnabled(true)
            fixture.settings.setUsageTrackingEnabled(true)
            val actual = KtorOpenAiCompatibleClient(http, json).apply {
                usageTracker = UsageTracker(fixture.database, fixture.settings)
            }
            fixture.externalClient = wire.track(actual, fixture)

            val preOriginal = fixture.real.inspectCalendarEvents(originalArgs)
            val preRevised = fixture.real.inspectCalendarEvents(revisedArgs)
            assertNotNull("Root calendar inspection must be available before starting", preOriginal)
            assertNotNull("Root calendar inspection must be available before starting", preRevised)
            assertTrue("Original slot must start empty", preOriginal!!.rows.isEmpty())
            assertTrue("Revised slot must start empty", preRevised!!.rows.isEmpty())
            assertEquals("Active rows for test title must start empty", 0, activeTitleRowLines(title).size)

            fixture.create()
            activity = launchM03Chat(context, automation)
            withContext(Dispatchers.Main) { mount(activity!!) }

            val createInstruction = "请打开日历创建并保存一条测试日程：标题「$title」，开始时间 2026-12-22T18:10:00+08:00，结束时间 2026-12-22T18:40:00+08:00。保存后核对实际记录，不要额外询问。"
            val createElapsedMs = sendViaChat(createInstruction)
            val prior = requireNotNull(fixture.journal.latest(fixture.owner))
            val priorMeta = requireNotNull(TaskRunMetadata.decode(prior.metadataJson))
            val saveEffect = priorMeta.effects.singleOrNull { it.tool == CalendarEventSave.ToolName }
            val savedRow = fixture.real.inspectCalendarEvents(originalArgs)?.rows?.singleOrNull()
            createdRow = savedRow
            result.put("create_elapsed_ms", createElapsedMs)
                .put("create_state", prior.state)
                .put("create_requests", wire.count)
                .put("create_confirmations", fixture.confirmations.size)
                .put("create_answer", fixture.chats.getMessages(fixture.owner).lastOrNull()?.content.orEmpty())
            assertEquals("Create turn must complete: ${result.optString("create_answer")}", "Completed", prior.state)
            assertTrue("No user confirmation may be requested during create", fixture.confirmations.isEmpty())
            assertNotNull("Save effect record missing", saveEffect)
            assertEquals(TaskEffectResolution.Applied, saveEffect!!.proof?.resolution)
            assertNotNull("Created row missing in calendar provider", savedRow)
            assertEquals("calendar:${savedRow!!.calendarId}:event:${savedRow.id}", saveEffect.proof?.receiptId)
            assertEquals("Row: 0 _id=${savedRow.calendarId}, account_type=LOCAL", queryCalendarAccount(savedRow.calendarId))
            result.put("event_id", savedRow.id).put("calendar_id", savedRow.calendarId).put("original_receipt", saveEffect.proof?.receiptId)

            // Capture Turn 1 completion on chat screen, then rebuild runtime for Turn 2 edit.
            withContext(Dispatchers.Main) { activity?.finish() }
            activity = launchM03Chat(context, automation)
            withContext(Dispatchers.Main) { mount(activity!!) }
            compose.waitForIdle()
            captureScreenshot(File(output, "01-chat-saved.png"))

            val requestsBeforeEdit = wire.count
            val editInstruction = "把刚才的日程改到同一天18:15到18:45。"
            fixture.rebuildRuntime()
            withContext(Dispatchers.Main) { mount(activity!!) }
            compose.waitForIdle()
            val editElapsedMs = sendViaChat(editInstruction)

            val afterEdit = requireNotNull(fixture.journal.latest(fixture.owner))
            val afterMeta = requireNotNull(TaskRunMetadata.decode(afterEdit.metadataJson))
            val editEffects = afterMeta.effects.filter { it.tool == CalendarTimeEdit.ToolName }
            val editEffect = editEffects.singleOrNull()
            val oldAfter = fixture.real.inspectCalendarEvents(originalArgs)?.rows
            val revisedAfter = fixture.real.inspectCalendarEvents(revisedArgs)?.rows
            val activeRows = activeTitleRowLines(title)
            val editAnswer = fixture.chats.getMessages(fixture.owner).lastOrNull { it.role == MessageRole.Assistant }?.content.orEmpty()
            val editRequests = wire.count - requestsBeforeEdit

            result.put("edit_elapsed_ms", editElapsedMs)
                .put("edit_state", afterEdit.state)
                .put("edit_model_requests", editRequests)
                .put("edit_dispatches", editEffects.size)
                .put("edit_confirmations", fixture.confirmations.size)
                .put("edit_answer", editAnswer)
                .put("old_interval_count", oldAfter?.size ?: -1)
                .put("revised_interval_rows", JSONArray(revisedAfter.orEmpty().map {
                    JSONObject().put("id", it.id).put("calendar_id", it.calendarId)
                        .put("begin", it.beginTimeMillis).put("end", it.endTimeMillis)
                }))
                .put("active_title_rows", JSONArray(activeRows))

            assertEquals("Edit turn must complete: $editAnswer", "Completed", afterEdit.state)
            assertTrue("No confirmation allowed during edit", fixture.confirmations.isEmpty())
            assertEquals("Direct saved edit should not initiate extra model calls", 0, editRequests)
            assertEquals("Exactly one edit dispatch expected", 1, editEffects.size)
            assertNotNull(editEffect)
            assertEquals(TaskEffectResolution.Applied, editEffect!!.proof?.resolution)
            assertEquals("calendar-edit:${editEffect.effectId}", editEffect.proof?.receiptId)
            assertTrue(afterMeta.pendingEffects.isEmpty())
            assertEquals("Old interval must be empty after edit", emptyList<CalendarEventRow>(), oldAfter)
            assertEquals(
                "Revised interval must hold the exact original event ID and calendar ID",
                listOf(CalendarEventRow(savedRow.id, savedRow.calendarId, revisedRequest.beginTimeMillis!!, revisedRequest.endTimeMillis!!)),
                revisedAfter
            )
            assertEquals(1, activeRows.size)

            withContext(Dispatchers.Main) { activity?.finish() }
            activity = launchM03Chat(context, automation)
            withContext(Dispatchers.Main) { mount(activity!!) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("已修改原日程", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(editAnswer, useUnmergedTree = true).assertIsDisplayed()
            captureScreenshot(File(output, "02-chat-edited.png"))

            result.put("passed", true)
            report("passed sample=$sample event_id=${savedRow.id} calendar_id=${savedRow.calendarId} create_ms=$createElapsedMs edit_ms=$editElapsedMs create_requests=$requestsBeforeEdit edit_requests=$editRequests")
        } finally {
            withContext(NonCancellable) {
                val cleanupWhere = if (createdRow != null) {
                    "_id=${createdRow!!.id}"
                } else {
                    "title='$title'"
                }
                val deleteOutput = shell("content delete --user 0 --uri content://com.android.calendar/events --where $cleanupWhere")
                delay(200)
                val remainingRows = activeTitleRowLines(title)
                val cleanupPassed = remainingRows.isEmpty()
                result.put("cleanup_delete_output", deleteOutput)
                    .put("cleanup_remaining_rows", JSONArray(remainingRows))
                    .put("cleanup_passed", cleanupPassed)
                withContext(Dispatchers.Main) { activity?.finish() }
                fixture.close(true)
                http.close()
                result.put("official_requests", wire.count)
                File(output, "result.json").writeText(result.toString(2))
                assertTrue("Synthetic M01 event must be cleanly deleted: $remainingRows", cleanupPassed)
            }
        }
    }

    private fun validatedSample(): String {
        val sample = requireNotNull(args.getString("m01Sample")) { "Pass m01Sample" }
        check(UUID.fromString(sample).toString() == sample) { "Invalid UUID sample: $sample" }
        return sample
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }.trim()

    private fun activeTitleRowLines(title: String): List<String> {
        val output = shell(
            "content query --user 0 --uri content://com.android.calendar/events " +
                "--projection _id:calendar_id:dtstart:dtend:deleted --where title='$title'"
        )
        check(!output.contains("SQLiteException") && !output.contains("Unsupported argument")) { output }
        if (output == "No result found.") return emptyList()
        return output.lines().map { it.trim() }.filter { it.startsWith("Row: ") && it.contains("deleted=0") }
    }

    private fun queryCalendarAccount(calendarId: Long): String = shell(
        "content query --user 0 --uri content://com.android.calendar/calendars " +
            "--projection _id:account_type --where _id=$calendarId"
    )

    private fun captureScreenshot(file: File) {
        val screenshot = requireNotNull(automation.takeScreenshot())
        try {
            file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun report(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nM01_FLASH $message\n") })
    }
}
