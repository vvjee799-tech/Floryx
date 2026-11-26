package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real chat entry, runtime, journal and calendar save adapter, with an isolated calendar source. */
class CalendarTimeCorrectionAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun inFlightSaveReplyCannotSaveTheSupersededTime() = runBlocking {
        exerciseCorrection(stopAfterCorrection = false)
    }

    @Test fun stoppingAfterCorrectionCannotRestartOrSaveEitherTime() = runBlocking {
        exerciseCorrection(stopAfterCorrection = true)
    }

    @Test fun savedTimeCorrectionDoesNotCreateASecondEvent() = runBlocking {
        exerciseSavedCorrection(canEdit = false, loseCallback = false)
    }

    @Test fun savedTimeCorrectionUpdatesTheOriginalEvent() = runBlocking {
        exerciseSavedCorrection(canEdit = true, loseCallback = false)
    }

    @Test fun uncertainEditIsReadBackAfterRebuildWithoutAnotherWrite() = runBlocking {
        exerciseSavedCorrection(canEdit = true, loseCallback = true)
    }

    private suspend fun exerciseSavedCorrection(canEdit: Boolean, loseCallback: Boolean) {
        val calendar = CalendarFixture(canEdit, loseCallback)
        val fixture = AutonomyRuntimeFixture(context, gatewayOverride = calendar)
        val original = "创建并保存验收活动，11月20日18:00到18:10。"
        val correction = "把刚才的日程改到同一天18:05到18:16。"
        val anotherHour = if (canEdit) 20 else 19
        val createAnother = "另外新建一条同名验收活动，11月20日$anotherHour:00到$anotherHour:10，保留前一条。"
        val label = if (loseCallback) "recovery" else if (canEdit) "applied" else "unsupported"
        val output = File(context.getExternalFilesDir(null), "d3-m01-chat-edit/$label").apply { mkdirs() }

        fun mountChat() = compose.activityRule.scenario.onActivity { activity ->
            val viewModel = ChatViewModel(fixture.runtime)
            activity.setContent {
                FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) }
            }
        }
        suspend fun send(text: String) {
            val previous = fixture.journal.latest(fixture.owner)?.id
            withContext(Dispatchers.Main) { fixture.runtime.updateInput(text) }
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            fixture.await {
                val run = fixture.journal.latest(fixture.owner)
                run != null && run.id != previous && run.state !in setOf("Running", "WaitingForUser") &&
                    fixture.owner !in fixture.runtime.activeConversations.value
            }
        }
        fun replies(vararg deltas: ChatDelta) = deltas.toList() + ChatDelta.Done
        fun open(hour: Int) = ChatToolCall("open-$hour", PublicAppActions.Calendar,
            calendar.arguments(hour).dropLast(1) + ",\"completion\":\"save\"}")
        fun save(hour: Int) = ChatToolCall("save-$hour", CalendarEventSave.ToolName, calendar.arguments(hour))

        try {
            fixture.create()
            mountChat()
            fixture.responder = { request, round ->
                if (request.messages.last { it.role == "user" }.content == original) when (round) {
                    1 -> replies(ChatDelta.ToolCalls(listOf(open(18))))
                    2 -> replies(ChatDelta.ToolCalls(listOf(save(18))))
                    3 -> replies(ChatDelta.Content("验收活动已保存，时间为18:00至18:10。"))
                    else -> error("Unexpected initial calendar request $round")
                } else when (round) {
                    4 -> replies(ChatDelta.ToolCalls(listOf(open(anotherHour))))
                    5 -> replies(ChatDelta.ToolCalls(listOf(save(anotherHour))))
                    6 -> replies(ChatDelta.Content("已安排验收活动，时间为$anotherHour:00至$anotherHour:10。"))
                    else -> error("Unexpected follow-up calendar request $round")
                }
            }
            send(original)
            val prior = requireNotNull(fixture.journal.latest(fixture.owner))
            assertEquals("Completed", prior.state)
            val priorProof = requireNotNull(TaskRunMetadata.decode(prior.metadataJson)).effects.single().proof
            assertEquals(TaskEffectResolution.Applied, priorProof?.resolution)
            val originalRow = calendar.sourceReceipt(calendar.arguments(18)).rows.single()
            assertEquals("calendar:${originalRow.calendarId}:event:${originalRow.id}", priorProof?.receiptId)
            fixture.rebuildRuntime()
            mountChat()

            val started = SystemClock.elapsedRealtime()
            send(correction)
            val elapsed = SystemClock.elapsedRealtime() - started
            val afterCorrection = requireNotNull(fixture.journal.latest(fixture.owner))
            val answer = fixture.chats.getMessages(fixture.owner).last().content
            val old = calendar.sourceReceipt(calendar.arguments(18)).rows
            val revised = calendar.sourceReceipt(calendar.arguments(18, "05", "16")).rows
            val result = JSONObject().put("original_receipt", priorProof?.receiptId).put("elapsed_ms", elapsed)
                .put("record_count_after_correction", calendar.savedRequests.size)
                .put("old_ids", org.json.JSONArray(old.map { it.id })).put("new_ids", org.json.JSONArray(revised.map { it.id }))
                .put("model_calls_after_initial_save", fixture.modelCalls.get() - 3)
                .put("confirmations", fixture.confirmations.size).put("state", afterCorrection.state).put("answer", answer)
                .put("edit_dispatches", calendar.editDispatches).put("manual_completion_steps", 0)
            File(output, "result.json").writeText(result.toString(2))
            compose.onNodeWithText(answer, useUnmergedTree = true).assertIsDisplayed()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val screenshot = requireNotNull(automation.takeScreenshot())
            try {
                File(output, "correction.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            report("saved_correction records=${calendar.savedRequests.size} old_ids=${old.map { it.id }} new_ids=${revised.map { it.id }} elapsed_ms=$elapsed")
            assertEquals("A correction of an already saved event must never create a second row", 1, calendar.savedRequests.size)
            assertEquals(if (canEdit) 1 else 0, calendar.editDispatches)
            assertEquals(if (canEdit) emptyList<CalendarEventRow>() else listOf(originalRow), old)
            assertEquals(if (canEdit) listOf(originalRow.id) else emptyList<Long>(), revised.map { it.id })
            assertEquals(if (!canEdit || loseCallback) "Paused" else "Completed", afterCorrection.state)
            val metadata = requireNotNull(TaskRunMetadata.decode(afterCorrection.metadataJson))
            assertEquals(priorProof, metadata.effects.first().proof)
            assertEquals(loseCallback, metadata.pendingEffects.isNotEmpty())
            if (canEdit && !loseCallback) assertEquals(TaskEffectResolution.Applied, metadata.effects.last().proof?.resolution)
            assertEquals(3, fixture.modelCalls.get())
            assertTrue(fixture.confirmations.isEmpty())

            var savedRequest = if (canEdit) CalendarEventSave.request(calendar.arguments(18, "05", "16")) else calendar.request(18)
            if (!canEdit || loseCallback) {
                calendar.withholdEditedReceipt = false
                fixture.rebuildRuntime()
                mountChat()
                val retryStart = SystemClock.elapsedRealtime()
                send("继续")
                val recovered = requireNotNull(fixture.journal.latest(fixture.owner))
                assertEquals(listOf(savedRequest), calendar.savedRequests)
                assertEquals(if (loseCallback) "Completed" else "Paused", recovered.state)
                assertEquals(if (loseCallback) 1 else 0, calendar.editDispatches)
                assertEquals(3, fixture.modelCalls.get())
                val resolved = requireNotNull(TaskRunMetadata.decode(recovered.metadataJson))
                assertTrue(resolved.pendingEffects.isEmpty())
                if (loseCallback) assertEquals(TaskEffectResolution.Applied, resolved.effects.last().proof?.resolution)
                result.put("retry_state", recovered.state).put("retry_elapsed_ms", SystemClock.elapsedRealtime() - retryStart)
            }
            if (canEdit && !loseCallback) {
                send("把《验收活动》改到同一天18:06到18:17。")
                val repeated = requireNotNull(fixture.journal.latest(fixture.owner))
                val repeatedArgs = calendar.arguments(18, "06", "17")
                savedRequest = CalendarEventSave.request(repeatedArgs)
                assertEquals("Completed", repeated.state)
                assertEquals(listOf(savedRequest), calendar.savedRequests)
                assertEquals(originalRow.id, calendar.sourceReceipt(repeatedArgs).rows.single().id)
                assertEquals(2, calendar.editDispatches)
                assertEquals(3, fixture.modelCalls.get())
                result.put("second_edit_same_id", true)
            }

            send(createAnother)
            assertEquals(listOf(savedRequest, calendar.request(anotherHour)), calendar.savedRequests)
            assertEquals("Completed", fixture.journal.latest(fixture.owner)?.state)
            assertEquals(6, fixture.modelCalls.get())
            result.put("retry_created_no_event", true).put("explicit_separate_creation_preserved", true).put("passed", true)
            File(output, "result.json").writeText(result.toString(2))
        } finally {
            fixture.close(true)
        }
    }

    private suspend fun exerciseCorrection(stopAfterCorrection: Boolean) {
        val calendar = CalendarFixture()
        val fixture = AutonomyRuntimeFixture(context, gatewayOverride = calendar)
        val oldRequestWaiting = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        val original = "创建并保存验收活动，11月20日18:00到18:10。"
        val correction = "把刚才的日程改到同一天19:00到19:10。"
        val answer = "验收活动已保存，时间为19:00至19:10。"
        fun actions(vararg calls: ChatToolCall) = listOf(ChatDelta.ToolCalls(calls.toList()), ChatDelta.Done)
        fun open(id: String, hour: Int) = ChatToolCall(id, PublicAppActions.Calendar,
            calendar.arguments(hour).dropLast(1) + ",\"completion\":\"save\"}")
        fun save(id: String, hour: Int) = ChatToolCall(id, CalendarEventSave.ToolName, calendar.arguments(hour))
        fun reply(text: String) = listOf(ChatDelta.Content(text), ChatDelta.Done)

        try {
            fixture.create()
            compose.activityRule.scenario.onActivity { activity ->
                val viewModel = ChatViewModel(fixture.runtime)
                activity.setContent {
                    FlowChatTheme(AppAppearance.Dark) {
                        ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel)
                    }
                }
            }
            fixture.responder = { request, round ->
                when (round) {
                    1 -> {
                        check(request.tools.any { it.name == PublicAppActions.Calendar })
                        actions(open("open-original", 18))
                    }
                    2 -> {
                        withContext(NonCancellable) {
                            oldRequestWaiting.complete(Unit)
                            releaseOldRequest.await()
                        }
                        actions(save("late-old-response", 18))
                    }
                    3 -> if (request.messages.any { it.role == "user" && it.content == correction }) {
                        actions(open("stale-prefill-repeated", 18))
                    } else reply("验收活动已保存，时间为18:00至18:10。")
                    4 -> {
                        check(request.messages.last { it.role == "tool" }.content.orEmpty()
                            .contains("calendar_correction_requires_updated_draft"))
                        actions(open("revised-editor", 19))
                    }
                    5 -> actions(save("stale-save-repeated", 18))
                    6 -> {
                        check(request.messages.last { it.role == "tool" }.content.orEmpty()
                            .contains("calendar_correction_requires_updated_draft"))
                        actions(save("save-new-time", 19))
                    }
                    7 -> {
                        check(request.messages.last { it.role == "tool" }.content.orEmpty().contains("Applied"))
                        reply(answer)
                    }
                    else -> error("Unexpected round $round")
                }
            }
            withContext(Dispatchers.Main) {
                fixture.runtime.updateInput(original)
                fixture.runtime.send()
            }
            withTimeout(10_000) { oldRequestWaiting.await() }
            val initialRun = requireNotNull(fixture.journal.latest(fixture.owner))
            val correctionButton = hasContentDescription("改日程时间") or hasContentDescription("Change calendar time")
            withContext(Dispatchers.Main) { fixture.runtime.updateInput(correction) }
            compose.waitForIdle()
            val started = SystemClock.elapsedRealtime()
            val hasCorrection = compose.onAllNodes(correctionButton).fetchSemanticsNodes().isNotEmpty()
            if (hasCorrection) compose.onNode(correctionButton).performClick()
            else withContext(Dispatchers.Main) { fixture.runtime.send() }
            if (stopAfterCorrection) {
                assertTrue("The test must first submit the correction", hasCorrection)
                val stop = (hasContentDescription("停止") or hasContentDescription("Stop")) and
                    hasAnyAncestor(hasTestTag("message_composer_panel"))
                compose.onNode(stop).performClick()
            }
            releaseOldRequest.complete(Unit)
            fixture.await { fixture.owner !in fixture.runtime.activeConversations.value &&
                (!stopAfterCorrection || fixture.journal.latest(fixture.owner)?.state == "Stopped") }
            val elapsed = SystemClock.elapsedRealtime() - started
            val rows = fixture.chats.getMessages(fixture.owner)
            val saved = calendar.savedRequests.toList()
            report("correction_control=$hasCorrection old_saves=${saved.count { it == calendar.request(18) }} " +
                "new_saves=${saved.count { it == calendar.request(19) }} total=${saved.size} " +
                "stopped=$stopAfterCorrection elapsed_ms=$elapsed")
            if (stopAfterCorrection) {
                val stopped = requireNotNull(fixture.journal.latest(fixture.owner))
                assertEquals(initialRun.id, stopped.id)
                assertEquals("user_stopped", TaskRunMetadata.decode(stopped.metadataJson)?.stopReason)
                assertTrue(saved.isEmpty())
                assertEquals(2, fixture.modelCalls.get())
                fixture.rebuildRuntime()
                assertTrue(fixture.runtime.activeConversations.value.isEmpty())
                assertEquals(2, fixture.modelCalls.get())
                return
            }
            assertEquals("Only the corrected event may exist", listOf(calendar.request(19)), saved)
            assertEquals(listOf(original, correction), rows.filter { it.role == MessageRole.User }.map { it.content })
            assertEquals(answer, rows.last().content)
            val completed = requireNotNull(fixture.journal.latest(fixture.owner))
            assertEquals(initialRun.id, completed.id)
            assertEquals("Completed", completed.state)
            val metadata = requireNotNull(TaskRunMetadata.decode(completed.metadataJson))
            assertEquals(1, metadata.effects.count { it.proof?.resolution == TaskEffectResolution.Applied })
            assertTrue(metadata.pendingEffects.isEmpty())
            assertTrue(fixture.confirmations.isEmpty())
            compose.onNodeWithText(answer, useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(correctionButton).assertDoesNotExist()
            compose.onAllNodesWithText("随后交给你操作", substring = true).assertCountEquals(0)
            val evidenceDirectory = if (InstrumentationRegistry.getArguments().getString("m01SavedRegression") == "true")
                "d3-m01-saved-regression" else "d3-m01"
            val screenshot = File(context.getExternalFilesDir(null), "$evidenceDirectory/result.png")
            screenshot.parentFile?.mkdirs()
            screenshot.outputStream().use {
                val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                assertTrue(requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            releaseOldRequest.complete(Unit)
            fixture.close(true)
        }
    }

    private fun report(result: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nM01 $result\n") })

    internal class CalendarFixture(
        private val canEdit: Boolean = false,
        private val loseCallback: Boolean = false,
        private val recordSave: (PublicAppActions.Request) -> Unit = {}
    ) : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
        override val connectionState = kotlinx.coroutines.flow.MutableStateFlow(ShizukuConnectionState(ShizukuConnectionStatus.ConnectedRoot))
        override val accessibilityState = kotlinx.coroutines.flow.MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Connected))
        override val safetyState = kotlinx.coroutines.flow.MutableStateFlow(DeviceSafetyState(screenLocked = false, screenInteractive = true))
        private var editor: PublicAppActions.Request? = null
        private var screen: DeviceScreenSnapshot? = null
        private var editingIndex: Int? = null
        private var editingStart: Boolean? = null
        var editDispatches = 0
            private set
        var withholdEditedReceipt = loseCallback
        val savedRequests = mutableListOf<PublicAppActions.Request>()
        fun arguments(hour: Int, beginMinute: String = "00", endMinute: String = "10") =
            """{"title":"验收活动","begin_time":"2026-11-20T$hour:$beginMinute:00+08:00","end_time":"2026-11-20T$hour:$endMinute:00+08:00"}"""
        fun request(hour: Int) = CalendarEventSave.request(arguments(hour))

        override suspend fun openPublicAppAction(tool: String, arguments: String): DeviceToolResult {
            val request = PublicAppActions.parse(tool, arguments)
            editor = request
            screen = snapshot(request)
            return DeviceToolResult(true, "Exact synthetic calendar editor verified, not saved.", screen = screen,
                actionOutcome = DeviceActionOutcome.ProgressObserved)
        }

        override suspend fun openSavedCalendarEvent(edit: CalendarTimeEdit): Boolean {
            if (!canEdit) return false
            val original = CalendarEventSave.request(edit.original)
            val index = savedRequests.indexOf(original)
            check(edit.calendarId == 1L && index >= 0 && edit.eventId == index + 1L)
            editingIndex = index
            editor = original
            screen = snapshot(original)
            return true
        }

        private fun snapshot(request: PublicAppActions.Request): DeviceScreenSnapshot {
            val bounds = DeviceBounds(0, 0, 400, 800)
            fun node(index: Int, id: String, text: String?, parent: Int? = null,
                     editable: Boolean = false, checked: Boolean? = null, tap: Boolean = false, picker: Boolean = false) =
                DeviceUiElement(index, if (picker) "android.widget.SeekBar" else if (checked == null) "TextView" else "Switch", text, null,
                    "${request.registration.packageName}:id/$id", bounds, tap, editable, false, true,
                    checked = checked, parentIndex = parent, supportedActions = if (tap) listOf("click") else emptyList())
            val formatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())
            fun date(millis: Long?) = formatter.format(Instant.ofEpochMilli(requireNotNull(millis)))
            val heading = if (editingIndex == null) "新建日程" else "编辑"
            val nodes = listOf(node(1, "title", request.title, editable = true), node(2, "all_day_row", null, checked = false),
                node(3, "heading", heading), node(4, "title", "开始", parent = 20),
                node(5, "summary", date(request.beginTimeMillis), parent = 20),
                node(6, "title", "结束", parent = 21), node(7, "summary", date(request.endTimeMillis), parent = 21),
                node(8, "save", "完成", tap = true), node(20, "display_row", null, tap = true), node(21, "display_row", null, tap = true)) +
                editingStart?.let { start ->
                    val active = Instant.ofEpochMilli(requireNotNull(if (start) request.beginTimeMillis else request.endTimeMillis)).atZone(ZoneId.systemDefault())
                    listOf(node(22, "not_allday_col3", active.hour.toString(), picker = true),
                        node(23, "not_allday_col4", active.minute.toString(), picker = true))
                }.orEmpty()
            val snapshot = DeviceScreenSnapshot(request.registration.packageName, request.registration.component, nodes,
                windowId = 1, geometry = DeviceScreenGeometry(bounds, 400, 800, 0),
                interactionContext = DeviceInteractionContext(listOf(
                    DeviceWindowRegion(1, DeviceWindowKind.Application, 0, bounds, listOf(bounds), focused = true, active = true))))
            check(PublicAppActions.prefillMatches(request, snapshot, 0, ZoneId.systemDefault(), heading))
            return snapshot
        }

        override suspend fun observeScreen() = DeviceToolResult(true, "Calendar fixture", screen = screen)

        override suspend fun commitCalendarTimeEdit(index: Int, screenId: String): DeviceToolResult {
            check(editingIndex != null && DeviceUiTargetPolicy.resolve(requireNotNull(screen), CalendarTimeEdit.SaveControl)?.index == index)
            return tapUiElement(index, false, screenId)
        }

        override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult {
            check(screenId == screen?.screenId && !longPress)
            val saved = requireNotNull(editor)
            if (index == 20 || index == 21) {
                check(editingIndex != null)
                editingStart = index == 20
                screen = snapshot(saved)
                return DeviceToolResult(true, "Synthetic picker opened", actionOutcome = DeviceActionOutcome.ProgressObserved)
            }
            check(index == 8)
            val existing = editingIndex
            if (existing == null) {
                recordSave(saved)
                savedRequests += saved
            } else {
                savedRequests[existing] = saved
                editDispatches++
            }
            editingIndex = null
            editingStart = null
            editor = null
            screen = null
            if (existing != null && loseCallback) error("Synthetic calendar save callback lost")
            return DeviceToolResult(true, "Synthetic calendar save dispatched", actionOutcome = DeviceActionOutcome.ProgressObserved)
        }

        override suspend fun moveCalendarTimeWheel(index: Int, steps: Int, screenId: String): DeviceToolResult {
            check(screenId == screen?.screenId && index in 22..23)
            val current = requireNotNull(editor)
            val offset = steps * if (index == 22) 3_600_000L else 60_000L
            val adjusted = if (requireNotNull(editingStart)) current.copy(beginTimeMillis = current.beginTimeMillis!! + offset,
                endTimeMillis = current.endTimeMillis!! + offset) else current.copy(endTimeMillis = current.endTimeMillis!! + offset)
            editor = adjusted
            screen = snapshot(adjusted)
            return DeviceToolResult(true, "Synthetic picker moved", actionOutcome = DeviceActionOutcome.ProgressObserved)
        }

        override suspend fun inspectCalendarEvents(arguments: String): CalendarEventReceipt? =
            if (withholdEditedReceipt && editDispatches > 0) null else sourceReceipt(arguments)

        fun sourceReceipt(arguments: String): CalendarEventReceipt {
            val request = CalendarEventSave.request(arguments)
            val rows = savedRequests.mapIndexedNotNull { index, saved ->
                if (saved == request) CalendarEventRow(index + 1L, 1L, saved.beginTimeMillis!!, saved.endTimeMillis!!) else null
            }
            return CalendarEventReceipt(CalendarEventSave.SourceId, CalendarEventSave.fingerprint(request), System.currentTimeMillis(), rows)
        }
    }
}
