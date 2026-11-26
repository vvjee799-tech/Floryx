package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.di.AppModule
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.domain.device.CalendarEventReceipt
import com.flowchat.app.domain.device.CalendarEventRow
import com.flowchat.app.domain.device.CalendarEventSave
import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceInteractionContext
import com.flowchat.app.domain.device.DeviceSafetyState
import com.flowchat.app.domain.device.DeviceScreenGeometry
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.DeviceWindowKind
import com.flowchat.app.domain.device.DeviceWindowRegion
import com.flowchat.app.domain.device.PublicAppActions
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.device.UnavailableDeviceAssistantGateway
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * S1 F02 3-variant acceptance tests (launcher_home, calendar_list, unrelated_draft_editor).
 * Supports both R2 verified iQOO Official Flash (`s1F02Mode=real`) and R1 0-cost MuMu loopback rehearsal (`s1F02Mode=local`).
 */
class S1CalendarFlashAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private enum class Variant(
        val id: String,
        val initialState: String
    ) {
        LauncherHome("F02-1", "launcher_home"),
        CalendarList("F02-2", "calendar_list"),
        UnrelatedDraft("F02-3", "unrelated_draft_editor")
    }

    @Test fun f02LauncherHomeCreatesAndVerifiesUniqueEvent() = runBlocking<Unit> {
        runF02Variant(Variant.LauncherHome)
    }

    @Test fun f02CalendarListCreatesAndVerifiesUniqueEvent() = runBlocking<Unit> {
        runF02Variant(Variant.CalendarList)
    }

    @Test fun f02UnrelatedDraftPreservedWhileCreatingNewEvent() = runBlocking<Unit> {
        runF02Variant(Variant.UnrelatedDraft)
    }

    private suspend fun runF02Variant(variant: Variant) {
        assumeTrue(args.getString("runS1F02") == "true")
        val mode = args.getString("s1F02Mode", "local")
        check(mode in setOf("real", "local")) { "Unsupported s1F02Mode: $mode" }
        val runLabel = args.getString("s1F02Run", "default").also {
            check(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid s1F02Run: $it" }
        }
        val sampleSeed = args.getString("s1F02Sample")
        val shortId = if (!sampleSeed.isNullOrBlank()) {
            UUID.fromString(sampleSeed).toString().take(8)
        } else {
            UUID.randomUUID().toString().take(8)
        }

        val shanghaiZone = ZoneId.of("Asia/Shanghai")
        val shanghaiOffset = ZoneOffset.ofHours(8)
        val tomorrow = LocalDate.now(shanghaiZone).plusDays(1)
        val targetBegin = tomorrow.atTime(15, 0).atOffset(shanghaiOffset)
        val targetEnd = tomorrow.atTime(15, 30).atOffset(shanghaiOffset)
        val targetBeginIso = targetBegin.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val targetEndIso = targetEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val targetTitle = "Floryx_S1_$shortId"
        val targetArgs = buildJsonObject {
            put("title", targetTitle)
            put("begin_time", targetBeginIso)
            put("end_time", targetEndIso)
        }.toString()
        val targetRequest = CalendarEventSave.request(targetArgs)
        val prompt = "在日历添加明天下午三点到三点半的‘$targetTitle’，保存好。不要额外询问。"

        val draftTitle = "Floryx_Draft_${UUID.randomUUID().toString().take(6)}"
        val draftDate = tomorrow.plusDays(2)
        val draftBegin = draftDate.atTime(10, 0).atOffset(shanghaiOffset)
        val draftEnd = draftDate.atTime(10, 30).atOffset(shanghaiOffset)
        val draftArgs = buildJsonObject {
            put("title", draftTitle)
            put("begin_time", draftBegin.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("end_time", draftEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }.toString()

        val outputDir = File(requireNotNull(context.getExternalFilesDir(null)), "s1-r2-f02/$runLabel/${variant.id}").apply {
            check(mkdirs() || isDirectory)
        }
        val result = JSONObject()
            .put("case_id", variant.id)
            .put("run", runLabel)
            .put("mode", if (mode == "real") "phone-real-model" else "mumu-local-loopback")
            .put("initial_state", variant.initialState)
            .put("prompt", prompt)
            .put("target_title", targetTitle)
            .put("target_args", JSONObject(targetArgs))
            .put("passed", false)
            .put("official_requests", 0)
        if (variant == Variant.UnrelatedDraft) {
            result.put("unrelated_draft_title", draftTitle).put("unrelated_draft_args", JSONObject(draftArgs))
        }
        fun saveResult() = File(outputDir, "result.json").writeText(result.toString(2))
        saveResult()

        if (mode == "real") {
            runRealVariant(variant, outputDir, result, ::saveResult, prompt, targetTitle, targetArgs, targetRequest, draftTitle, draftBegin, draftEnd, draftArgs)
        } else {
            runLocalVariant(variant, outputDir, result, ::saveResult, prompt, targetTitle, targetArgs, targetRequest, draftTitle, draftArgs)
        }
    }

    private suspend fun runRealVariant(
        variant: Variant,
        outputDir: File,
        result: JSONObject,
        saveResult: () -> Unit,
        prompt: String,
        targetTitle: String,
        targetArgs: String,
        targetRequest: PublicAppActions.Request,
        draftTitle: String,
        draftBegin: OffsetDateTime,
        draftEnd: OffsetDateTime,
        draftArgs: String
    ) {
        check(Build.MODEL == "V2302A" && Build.MANUFACTURER == "vivo") {
            "Real F02 mode requires verified iQOO V2302A/vivo; got ${Build.MODEL}/${Build.MANUFACTURER}"
        }
        val (provider, key) = loadOfficialFlashProvider(context)
        val json = AppModule.provideJson()
        val http = AppModule.provideHttpClient(json)
        val maxRequests = args.getString("s1F02MaxRequests")?.toInt() ?: 12
        val maxInput = args.getString("s1F02MaxInput")?.toInt() ?: 400_000
        val maxOutput = args.getString("s1F02MaxOutput")?.toInt() ?: 96_000
        val wire = FlashLedger(
            http = http,
            output = outputDir,
            sample = variant.id,
            official = true,
            describe = { payload, writer ->
                val names = (payload.optJSONArray("tools") ?: JSONArray()).objects().map {
                    it.getJSONObject("function").getString("name")
                }
                JSONObject().put("stage", if (writer) "checkpoint" else variant.id.lowercase(Locale.ROOT))
                    .put("tool_count", names.size)
            },
            maxRequests = maxRequests,
            maxInputReserved = maxInput,
            maxOutputReserved = maxOutput
        )
        val fixture = AutonomyRuntimeFixture(
            context = context,
            providerOverride = provider,
            apiKeyOverride = key,
            name = "autonomyqa-${variant.id.lowercase(Locale.ROOT)}-${UUID.randomUUID().toString().take(8)}"
        )
        var activity: MainActivity? = null
        var createdRow: CalendarEventRow? = null
        var passed = false
        try {
            ActionOutcomeServiceReadiness().ensureService()
            fixture.connect()
            fixture.settings.setPowerModeEnabled(true)
            fixture.settings.setUsageTrackingEnabled(true)
            val actual = KtorOpenAiCompatibleClient(http, json).apply {
                usageTracker = UsageTracker(fixture.database, fixture.settings)
            }
            fixture.externalClient = wire.track(actual, fixture)

            val preTarget = fixture.real.inspectCalendarEvents(targetArgs)
            assertNotNull("Root calendar inspection must be available before starting", preTarget)
            assertTrue("Target time slot must start empty", preTarget!!.rows.isEmpty())
            assertEquals("Target title must start absent", 0, activeTitleRowLines(targetTitle).size)
            if (variant == Variant.UnrelatedDraft) {
                assertEquals("Unrelated draft title must start absent", 0, activeTitleRowLines(draftTitle).size)
            }

            stageRealInitialState(variant, draftTitle, draftBegin, draftEnd, outputDir)

            fixture.create()
            activity = launchM03Chat(context, automation)
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                activity!!.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
            }
            compose.waitForIdle()

            val previous = fixture.journal.latest(fixture.owner)?.id
            withContext(Dispatchers.Main) { fixture.runtime.updateInput(prompt) }
            compose.waitForIdle()
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            withTimeout(150_000L) {
                while (true) {
                    val run = fixture.journal.latest(fixture.owner)
                    if (run != null && run.id != previous && run.state !in setOf("Running", "WaitingForUser") &&
                        fixture.owner !in fixture.runtime.activeConversations.value
                    ) break
                    delay(50)
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val run = requireNotNull(fixture.journal.latest(fixture.owner))
            val messages = fixture.chats.getMessages(fixture.owner)
            File(outputDir, "messages.json").writeText(JSONArray(messages.map {
                JSONObject().put("role", it.role.name).put("status", it.status.name)
                    .put("content", it.content).put("execution", it.executionContext)
            }).toString(2))

            val targetReceipt = fixture.real.inspectCalendarEvents(targetArgs)
            val rows = targetReceipt?.rows.orEmpty()
            createdRow = rows.singleOrNull()
            val activeTargetRows = activeTitleRowLines(targetTitle)
            val activeDraftRows = if (variant == Variant.UnrelatedDraft) activeTitleRowLines(draftTitle) else emptyList()
            val answer = messages.lastOrNull { it.role == MessageRole.Assistant }?.content.orEmpty()

            result.put("elapsed_ms", elapsedMs)
                .put("runtime_state", run.state)
                .put("official_requests", wire.count)
                .put("app_confirmations", fixture.confirmations.size)
                .put("answer", answer)
                .put("saved_row_count", rows.size)
                .put("active_target_rows", JSONArray(activeTargetRows))
                .put("active_draft_rows", JSONArray(activeDraftRows))
            createdRow?.let { row ->
                result.put("event_id", row.id)
                    .put("calendar_id", row.calendarId)
                    .put("receipt", "calendar:${row.calendarId}:event:${row.id}")
            }
            saveResult()

            assertEquals("F02 task must complete: $answer", "Completed", run.state)
            assertTrue("No user confirmation permitted", fixture.confirmations.isEmpty())
            assertEquals("Exactly one new target calendar event must exist", 1, rows.size)
            assertEquals(targetRequest.beginTimeMillis, createdRow!!.beginTimeMillis)
            assertEquals(targetRequest.endTimeMillis, createdRow!!.endTimeMillis)
            assertEquals("Row: 0 _id=${createdRow!!.calendarId}, account_type=LOCAL", queryCalendarAccount(createdRow!!.calendarId))
            assertEquals(1, activeTargetRows.size)
            if (variant == Variant.UnrelatedDraft) {
                assertTrue("Unrelated draft must never be saved accidentally: $activeDraftRows", activeDraftRows.isEmpty())
                assertTrue(fixture.real.inspectCalendarEvents(draftArgs)?.rows.orEmpty().isEmpty())
            }
            passed = true
            result.put("passed", true)
            report("real ${variant.id} passed requests=${wire.count} elapsed_ms=$elapsedMs event_id=${createdRow!!.id}")
        } finally {
            withContext(NonCancellable) {
                val cleanupWhere = if (createdRow != null) "_id=${createdRow!!.id}" else "title='$targetTitle'"
                val deleteOut = shell("content delete --user 0 --uri content://com.android.calendar/events --where $cleanupWhere")
                delay(200)
                val remainingTarget = activeTitleRowLines(targetTitle)
                val remainingReceipt = runCatching { fixture.real.inspectCalendarEvents(targetArgs)?.rows.orEmpty() }.getOrElse { emptyList() }
                val remainingDraft = if (variant == Variant.UnrelatedDraft) activeTitleRowLines(draftTitle) else emptyList()
                if (variant == Variant.UnrelatedDraft) {
                    shell("am force-stop com.bbk.calendar")
                    shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                }
                withContext(Dispatchers.Main) { activity?.finish() }
                fixture.close(true)
                http.close()
                val dbDeleted = !context.getDatabasePath("${fixture.name}.db").exists()
                val cleanupPassed = remainingTarget.isEmpty() && remainingReceipt.isEmpty() && remainingDraft.isEmpty() && dbDeleted
                result.put(
                    "cleanup",
                    JSONObject()
                        .put("delete_output", deleteOut)
                        .put("remaining_target_rows", JSONArray(remainingTarget))
                        .put("remaining_target_receipt_count", remainingReceipt.size)
                        .put("remaining_draft_rows", JSONArray(remainingDraft))
                        .put("isolated_db_deleted", dbDeleted)
                        .put("cleanup_passed", cleanupPassed)
                )
                result.put("passed", passed && cleanupPassed)
                saveResult()
                assertTrue("F02 zero-side-effect cleanup failed: ${result.optJSONObject("cleanup")}", cleanupPassed)
            }
        }
    }

    private suspend fun stageRealInitialState(
        variant: Variant,
        draftTitle: String,
        draftBegin: OffsetDateTime,
        draftEnd: OffsetDateTime,
        outputDir: File
    ) {
        when (variant) {
            Variant.LauncherHome -> {
                shell("am start -W --user 0 --display 0 -a android.intent.action.MAIN -c android.intent.category.HOME")
                delay(700)
            }
            Variant.CalendarList -> {
                shell("am force-stop com.bbk.calendar")
                val out = shell("am start -W --user 0 --display 0 -f 0x10008000 -p com.bbk.calendar -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
                check(out.contains("Status: ok")) { "Failed to launch calendar list: $out" }
                withTimeout(10_000) {
                    while (true) {
                        val root = automation.rootInActiveWindow
                        if (root?.packageName?.toString() == "com.bbk.calendar" &&
                            nodes(root).none { it.viewIdResourceName == "com.bbk.calendar:id/all_day_row" }
                        ) break
                        delay(100)
                    }
                }
                captureScreenshot(File(outputDir, "00-staged-calendar-list.png"))
            }
            Variant.UnrelatedDraft -> {
                shell("am force-stop com.bbk.calendar")
                val cmd = "am start -W --user 0 --display 0 -f 0x18080000 " +
                    "-a android.intent.action.INSERT -t vnd.android.cursor.item/event " +
                    "-n com.bbk.calendar/.event.EditEventActivity " +
                    "--es title $draftTitle " +
                    "--el beginTime ${draftBegin.toInstant().toEpochMilli()} " +
                    "--el endTime ${draftEnd.toInstant().toEpochMilli()} " +
                    "--ez allDay false"
                val out = shell(cmd)
                check(out.contains("Status: ok")) { "Failed to stage unrelated draft: $out" }
                withTimeout(10_000) {
                    while (true) {
                        val root = automation.rootInActiveWindow
                        if (root?.packageName?.toString() == "com.bbk.calendar" &&
                            nodes(root).any { it.viewIdResourceName == "com.bbk.calendar:id/title" && it.isEditable && it.text?.toString() == draftTitle }
                        ) break
                        delay(100)
                    }
                }
                captureScreenshot(File(outputDir, "00-staged-draft.png"))
            }
        }
    }

    private suspend fun runLocalVariant(
        variant: Variant,
        outputDir: File,
        result: JSONObject,
        saveResult: () -> Unit,
        prompt: String,
        targetTitle: String,
        targetArgs: String,
        targetRequest: PublicAppActions.Request,
        draftTitle: String,
        draftArgs: String
    ) {
        val localGateway = S1LocalCalendarGateway(
            initialState = variant.initialState,
            unrelatedDraft = if (variant == Variant.UnrelatedDraft) CalendarEventSave.request(draftArgs) else null
        )
        val fixture = AutonomyRuntimeFixture(
            context = context,
            gatewayOverride = localGateway,
            name = "autonomyqa-local-${variant.id.lowercase(Locale.ROOT)}-${UUID.randomUUID().toString().take(8)}"
        )
        var activity: MainActivity? = null
        var createdRow: CalendarEventRow? = null
        var passed = false
        try {
            fixture.create()
            activity = launchM03Chat(context, automation)
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                activity!!.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
            }
            compose.waitForIdle()

            val preTarget = localGateway.inspectCalendarEvents(targetArgs)
            assertTrue("Target slot must start empty", preTarget.rows.isEmpty())
            if (variant == Variant.UnrelatedDraft) {
                assertTrue("Draft must start unsaved", localGateway.inspectCalendarEvents(draftArgs).rows.isEmpty())
                assertTrue("Unrelated draft must start active on editor", localGateway.unrelatedDraftPreserved)
            }

            fixture.responder = { request, round ->
                when (round) {
                    1 -> {
                        check(request.tools.any { it.name == PublicAppActions.Calendar })
                        listOf(
                            ChatDelta.ToolCalls(
                                listOf(
                                    ChatToolCall(
                                        "open-${variant.id}",
                                        PublicAppActions.Calendar,
                                        targetArgs.dropLast(1) + ",\"completion\":\"save\"}"
                                    )
                                )
                            ),
                            ChatDelta.Done
                        )
                    }
                    2 -> {
                        check(request.tools.any { it.name == CalendarEventSave.ToolName })
                        listOf(
                            ChatDelta.ToolCalls(
                                listOf(
                                    ChatToolCall(
                                        "save-${variant.id}",
                                        CalendarEventSave.ToolName,
                                        targetArgs
                                    )
                                )
                            ),
                            ChatDelta.Done
                        )
                    }
                    3 -> {
                        val toolResult = request.messages.last { it.role == "tool" }.content.orEmpty()
                        check("Applied" in toolResult) { "Expected Applied save receipt, got: $toolResult" }
                        listOf(
                            ChatDelta.Content("已保存日程「$targetTitle」（${targetRequest.beginTimeMillis}–${targetRequest.endTimeMillis}），并独立核对记录一致。"),
                            ChatDelta.Done
                        )
                    }
                    else -> error("Unexpected local rehearsal round $round")
                }
            }

            val previous = fixture.journal.latest(fixture.owner)?.id
            withContext(Dispatchers.Main) { fixture.runtime.updateInput(prompt) }
            compose.waitForIdle()
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            withTimeout(60_000L) {
                while (true) {
                    val run = fixture.journal.latest(fixture.owner)
                    if (run != null && run.id != previous && run.state !in setOf("Running", "WaitingForUser") &&
                        fixture.owner !in fixture.runtime.activeConversations.value
                    ) break
                    delay(50)
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val run = requireNotNull(fixture.journal.latest(fixture.owner))
            val meta = requireNotNull(TaskRunMetadata.decode(run.metadataJson))
            val saveEffect = meta.effects.singleOrNull { it.tool == CalendarEventSave.ToolName }
            val savedRows = localGateway.inspectCalendarEvents(targetArgs).rows
            createdRow = savedRows.singleOrNull()
            val messages = fixture.chats.getMessages(fixture.owner)
            val answer = messages.lastOrNull { it.role == MessageRole.Assistant }?.content.orEmpty()

            result.put("elapsed_ms", elapsedMs)
                .put("runtime_state", run.state)
                .put("official_requests", 0)
                .put("local_model_calls", fixture.modelCalls.get())
                .put("app_confirmations", fixture.confirmations.size)
                .put("saved_row_count", savedRows.size)
                .put("receipt", saveEffect?.proof?.receiptId)
                .put("answer", answer)
            if (variant == Variant.UnrelatedDraft) {
                result.put("unrelated_draft_preserved", localGateway.unrelatedDraftPreserved)
                    .put("unrelated_draft_saved", localGateway.unrelatedDraftSaved)
            }
            saveResult()

            assertEquals("Completed", run.state)
            assertTrue(fixture.confirmations.isEmpty())
            assertEquals(1, savedRows.size)
            assertEquals(targetRequest.beginTimeMillis, createdRow!!.beginTimeMillis)
            assertEquals(targetRequest.endTimeMillis, createdRow!!.endTimeMillis)
            assertEquals(TaskEffectResolution.Applied, saveEffect?.proof?.resolution)
            assertEquals("calendar:1:event:${createdRow!!.id}", saveEffect?.proof?.receiptId)
            if (variant == Variant.UnrelatedDraft) {
                assertTrue("Unrelated draft must stay preserved in its separate document", localGateway.unrelatedDraftPreserved)
                assertFalse("Unrelated draft must never be saved", localGateway.unrelatedDraftSaved)
                assertTrue(localGateway.inspectCalendarEvents(draftArgs).rows.isEmpty())
            }
            passed = true
            result.put("passed", true)
            report("local ${variant.id} passed model_calls=${fixture.modelCalls.get()} official_requests=0 elapsed_ms=$elapsedMs")
        } finally {
            withContext(NonCancellable) {
                createdRow?.let { localGateway.deleteSavedEvent(it.id) }
                localGateway.discardUnrelatedDraft()
                val remainingRows = localGateway.inspectCalendarEvents(targetArgs).rows
                val remainingDraft = if (variant == Variant.UnrelatedDraft) localGateway.inspectCalendarEvents(draftArgs).rows else emptyList()
                withContext(Dispatchers.Main) { activity?.finish() }
                fixture.close(true)
                val dbDeleted = !context.getDatabasePath("${fixture.name}.db").exists()
                val cleanupPassed = remainingRows.isEmpty() && remainingDraft.isEmpty() && dbDeleted && !localGateway.unrelatedDraftActive
                result.put(
                    "cleanup",
                    JSONObject()
                        .put("remaining_target_rows", remainingRows.size)
                        .put("remaining_draft_rows", remainingDraft.size)
                        .put("unrelated_draft_active", localGateway.unrelatedDraftActive)
                        .put("isolated_db_deleted", dbDeleted)
                        .put("cleanup_passed", cleanupPassed)
                )
                result.put("passed", passed && cleanupPassed)
                saveResult()
                assertTrue("Local F02 cleanup failed: ${result.optJSONObject("cleanup")}", cleanupPassed)
            }
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }.trim()

    private fun activeTitleRowLines(title: String): List<String> {
        val safeTitle = title.replace("'", "")
        val output = shell(
            "content query --user 0 --uri content://com.android.calendar/events " +
                "--projection _id:calendar_id:dtstart:dtend:deleted --where title='$safeTitle'"
        )
        check(!output.contains("SQLiteException") && !output.contains("Unsupported argument")) { output }
        if (output == "No result found.") return emptyList()
        return output.lines().map { it.trim() }.filter { it.startsWith("Row: ") && it.contains("deleted=0") }
    }

    private fun queryCalendarAccount(calendarId: Long): String = shell(
        "content query --user 0 --uri content://com.android.calendar/calendars " +
            "--projection _id:account_type --where _id=$calendarId"
    )

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else buildList {
            add(root)
            repeat(root.childCount) { addAll(nodes(root.getChild(it))) }
        }

    private fun captureScreenshot(file: File) {
        val screenshot = requireNotNull(automation.takeScreenshot())
        try {
            file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun report(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nS1_F02 $message\n") })
    }

    private class S1LocalCalendarGateway(
        private val initialState: String,
        private val unrelatedDraft: PublicAppActions.Request?
    ) : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
        override val connectionState = MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedRoot))
        override val accessibilityState = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Connected))
        override val safetyState = MutableStateFlow(DeviceSafetyState(screenLocked = false, screenInteractive = true))

        private var activeEditor: PublicAppActions.Request? = unrelatedDraft
        private var currentScreen: DeviceScreenSnapshot? = unrelatedDraft?.let { buildEditorSnapshot(it) } ?: buildNonEditorSnapshot(initialState)
        private val savedEvents = mutableListOf<Pair<Long, PublicAppActions.Request>>()
        private var nextEventId = 1L

        var unrelatedDraftPreserved = unrelatedDraft != null
            private set
        var unrelatedDraftSaved = false
            private set
        var unrelatedDraftActive = unrelatedDraft != null
            private set

        override suspend fun openPublicAppAction(tool: String, arguments: String): DeviceToolResult {
            val request = PublicAppActions.parse(tool, arguments)
            check(unrelatedDraft == null || request.title != unrelatedDraft.title) {
                "Target request must be distinct from the unrelated draft"
            }
            // Opening a fresh document preserves any separate pre-existing unrelated draft without overwriting or saving it.
            activeEditor = request
            currentScreen = buildEditorSnapshot(request)
            return DeviceToolResult(
                success = true,
                summary = "Exact synthetic calendar editor verified, not saved.",
                screen = currentScreen,
                actionOutcome = DeviceActionOutcome.ProgressObserved
            )
        }

        override suspend fun observeScreen(): DeviceToolResult =
            DeviceToolResult(true, "Local S1 calendar fixture ($initialState)", screen = currentScreen)

        override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult {
            check(!longPress && screenId == currentScreen?.screenId && index == 8) {
                "Unexpected tapUiElement($index, $screenId) on S1LocalCalendarGateway"
            }
            val current = requireNotNull(activeEditor) { "No active calendar editor" }
            if (unrelatedDraft != null && current.title == unrelatedDraft.title) {
                unrelatedDraftSaved = true
            }
            savedEvents += nextEventId++ to current
            activeEditor = null
            currentScreen = buildNonEditorSnapshot("calendar_list")
            return DeviceToolResult(
                success = true,
                summary = "Synthetic calendar save dispatched",
                actionOutcome = DeviceActionOutcome.ProgressObserved
            )
        }

        override suspend fun inspectCalendarEvents(arguments: String): CalendarEventReceipt {
            val request = CalendarEventSave.request(arguments)
            val rows = savedEvents.mapNotNull { (id, saved) ->
                if (saved.title == request.title &&
                    saved.beginTimeMillis == request.beginTimeMillis &&
                    saved.endTimeMillis == request.endTimeMillis
                ) {
                    CalendarEventRow(id, 1L, saved.beginTimeMillis!!, saved.endTimeMillis!!)
                } else null
            }
            return CalendarEventReceipt(
                sourceId = CalendarEventSave.SourceId,
                requestSha256 = CalendarEventSave.fingerprint(request),
                observedAtMillis = System.currentTimeMillis(),
                rows = rows
            )
        }

        fun deleteSavedEvent(eventId: Long) {
            savedEvents.removeAll { it.first == eventId }
        }

        fun discardUnrelatedDraft() {
            unrelatedDraftActive = false
            if (activeEditor?.title == unrelatedDraft?.title) {
                activeEditor = null
                currentScreen = null
            }
        }

        private fun buildNonEditorSnapshot(state: String): DeviceScreenSnapshot {
            val bounds = DeviceBounds(0, 0, 400, 800)
            val pkg = if (state == "launcher_home") "app.lawnchair" else PublicAppActions.Event.packageName
            return DeviceScreenSnapshot(
                packageName = pkg,
                activityName = if (state == "launcher_home") ".Launcher" else ".AllInOneActivity",
                elements = listOf(
                    DeviceUiElement(1, "android.widget.TextView", state, null, "$pkg:id/stage", bounds, false, false, false, true)
                ),
                windowId = 1,
                geometry = DeviceScreenGeometry(bounds, 400, 800, 0),
                interactionContext = DeviceInteractionContext(
                    listOf(DeviceWindowRegion(1, DeviceWindowKind.Application, 0, bounds, listOf(bounds), focused = true, active = true))
                )
            )
        }

        private fun buildEditorSnapshot(request: PublicAppActions.Request): DeviceScreenSnapshot {
            val bounds = DeviceBounds(0, 0, 400, 800)
            fun node(
                index: Int,
                id: String,
                text: String?,
                parent: Int? = null,
                editable: Boolean = false,
                checked: Boolean? = null,
                tap: Boolean = false
            ) = DeviceUiElement(
                index = index,
                className = if (checked == null) "android.widget.TextView" else "android.widget.Switch",
                text = text,
                contentDescription = null,
                resourceId = "${request.registration.packageName}:id/$id",
                bounds = bounds,
                clickable = tap,
                editable = editable,
                scrollable = false,
                password = false,
                enabled = true,
                checked = checked,
                parentIndex = parent,
                supportedActions = if (tap) listOf("click") else emptyList()
            )
            val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())
            fun date(millis: Long?) = formatter.format(Instant.ofEpochMilli(requireNotNull(millis)))
            val nodes = listOf(
                node(1, "title", request.title, editable = true),
                node(2, "all_day_row", null, checked = false),
                node(3, "heading", "新建日程"),
                node(4, "title", "开始", parent = 20),
                node(5, "summary", date(request.beginTimeMillis), parent = 20),
                node(6, "title", "结束", parent = 21),
                node(7, "summary", date(request.endTimeMillis), parent = 21),
                node(8, "save", "完成", tap = true),
                node(20, "display_row", null, tap = true),
                node(21, "display_row", null, tap = true)
            )
            val snapshot = DeviceScreenSnapshot(
                packageName = request.registration.packageName,
                activityName = request.registration.component,
                elements = nodes,
                windowId = 1,
                geometry = DeviceScreenGeometry(bounds, 400, 800, 0),
                interactionContext = DeviceInteractionContext(
                    listOf(DeviceWindowRegion(1, DeviceWindowKind.Application, 0, bounds, listOf(bounds), focused = true, active = true))
                )
            )
            check(PublicAppActions.prefillMatches(request, snapshot, 0, ZoneId.systemDefault(), "新建日程"))
            return snapshot
        }
    }
}
