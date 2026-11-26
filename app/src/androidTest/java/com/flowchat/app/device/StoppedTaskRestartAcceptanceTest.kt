package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.CalendarEventSave
import com.flowchat.app.domain.device.PublicAppActions
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run each method in its own instrumentation process with the same fresh namespace. */
class StoppedTaskRestartAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var launchedActivity: MainActivity? = null
    private val instruction = "先保存 18:00 的验收日程，再保存 19:00 的验收日程。"
    private val stoppedReply = "已按你的要求停止。已有内容已保留，后续步骤未继续执行。"
    private val namespace: String get() {
        val token = requireNotNull(InstrumentationRegistry.getArguments().getString("m04StopNamespace"))
        check(UUID.fromString(token).toString() == token)
        return "autonomyqa-m04-stop-$token"
    }
    private val marker get() = context.getFileStreamPath("$namespace.json")
    private val saves get() = context.getFileStreamPath("$namespace-saves.txt")

    @Test fun stopFromChatAfterFirstSave() = runBlocking<Unit> {
        enabled()
        check(!marker.exists() && !saves.exists() && !context.getDatabasePath("$namespace.db").exists())
        val calendar = calendar()
        val fixture = AutonomyRuntimeFixture(context, gatewayOverride = calendar, name = namespace)
        val waitingForSecondEvent = CompletableDeferred<Unit>()
        val releaseReply = CompletableDeferred<Unit>()
        val returnedLateReply = CompletableDeferred<Unit>()
        fun open(hour: Int) = ChatToolCall("open-$hour", PublicAppActions.Calendar,
            calendar.arguments(hour).dropLast(1) + ",\"completion\":\"save\"}")
        fun tool(call: ChatToolCall) = listOf(ChatDelta.ToolCalls(listOf(call)), ChatDelta.Done)
        try {
            fixture.responder = { request, round ->
                when (round) {
                    1 -> tool(open(18))
                    2 -> tool(ChatToolCall("save-first", CalendarEventSave.ToolName, calendar.arguments(18)))
                    3 -> {
                        check(request.messages.last { it.role == "tool" }.content.orEmpty().contains("Applied"))
                        withContext(NonCancellable) {
                            waitingForSecondEvent.complete(Unit)
                            releaseReply.await()
                            returnedLateReply.complete(Unit)
                            tool(open(19))
                        }
                    }
                    else -> error("Unexpected model request after stop")
                }
            }
            fixture.create()
            showChat(fixture)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("message_composer_panel"))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("message_composer_panel")))
                .performTextInput(instruction)
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            withTimeout(15_000) { waitingForSecondEvent.await() }
            val running = requireNotNull(fixture.journal.latest(fixture.owner))
            assertEquals("Running", running.state)
            assertEquals(listOf(calendar.request(18)), calendar.savedRequests)
            val stop = compose.onNode(hasContentDescription(context.getString(R.string.stop)) and
                hasAnyAncestor(hasTestTag("message_composer_panel")))
            stop.assertIsDisplayed()
            val started = SystemClock.elapsedRealtime()
            stop.performClick()
            releaseReply.complete(Unit)
            withTimeout(5_000) { returnedLateReply.await() }
            fixture.await {
                fixture.journal.get(running.id)?.state == "Stopped" &&
                    fixture.chats.getMessages(fixture.owner).any { it.status == MessageStatus.Stopped && stoppedReply in it.content }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val stopped = requireNotNull(fixture.journal.latest(fixture.owner))
            val metadata = requireNotNull(TaskRunMetadata.decode(stopped.metadataJson))
            assertEquals(running.id, stopped.id)
            assertEquals("user_stopped", metadata.stopReason)
            assertEquals(TaskEffectResolution.Applied, metadata.effects.single().proof?.resolution)
            assertTrue(metadata.pendingEffects.isEmpty())
            assertTrue(fixture.runtime.activeConversations.value.isEmpty())
            assertTrue(fixture.confirmations.isEmpty())
            assertEquals(3, fixture.modelCalls.get())
            assertEquals(listOf(CalendarEventSave.fingerprint(calendar.request(18))), saves.readLines())
            marker.writeText(JSONObject().put("seedPid", Process.myPid()).put("owner", fixture.owner)
                .put("runId", stopped.id).put("metadata", stopped.metadataJson)
                .put("messages", messageEvidence(fixture)).toString())
            report("stopped pid=${Process.myPid()} run=${stopped.id} first_saves=1 second_saves=0 " +
                "model_calls=3 confirmations=0 elapsed_ms=$elapsed")
        } finally {
            releaseReply.complete(Unit)
            withContext(Dispatchers.Main) { launchedActivity?.finish(); launchedActivity = null }
            fixture.close(false)
        }
    }

    @Test fun reopenedChatKeepsStopAndDoesNotReplay() = runBlocking<Unit> {
        enabled()
        val saved = JSONObject(marker.readText())
        assertNotEquals("Cold verification must run in a new process", saved.getInt("seedPid"), Process.myPid())
        val calendar = calendar()
        val fixture = AutonomyRuntimeFixture(context, gatewayOverride = calendar, name = namespace)
        val started = SystemClock.elapsedRealtime()
        try {
            fixture.reopen(saved.getString("owner"))
            showChat(fixture)
            val stoppedStatus = context.getString(R.string.task_status_user_stopped)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(stoppedStatus).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(stoppedStatus).assertIsDisplayed()
            compose.onNodeWithText(stoppedReply, substring = true, useUnmergedTree = true).assertIsDisplayed()
            val savedCalendarLabel = context.getString(R.string.tool_action_complete,
                context.getString(R.string.tool_action_save_calendar_event))
            compose.onNodeWithText(CalendarEventSave.ToolName, substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText(savedCalendarLabel, useUnmergedTree = true).assertIsDisplayed()
            val elapsed = SystemClock.elapsedRealtime() - started
            val image = File(requireNotNull(context.getExternalFilesDir(null)), "$namespace.png")
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val screenshot = requireNotNull(automation.takeScreenshot())
            image.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshot.recycle()
            compose.onNodeWithText(savedCalendarLabel, useUnmergedTree = true).performClick()
            compose.onNodeWithText("验收活动 · 2026-11-20 18:00–18:10", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("task-status").assertIsDisplayed()
            listOf(R.string.device_task_resume, R.string.task_status_check_again, R.string.task_status_skip_uncertain)
                .forEach { compose.onNodeWithText(context.getString(it)).assertDoesNotExist() }
            // Observe after the restored conversation and its controls have settled.
            delay(750)
            val stopped = requireNotNull(fixture.journal.latest(fixture.owner))
            assertEquals(saved.getString("runId"), stopped.id)
            assertEquals("Stopped", stopped.state)
            assertEquals(saved.getString("metadata"), stopped.metadataJson)
            assertEquals(saved.getJSONArray("messages").toString(), messageEvidence(fixture).toString())
            assertTrue(fixture.runtime.activeConversations.value.isEmpty())
            assertEquals(0, fixture.modelCalls.get())
            assertEquals(0, fixture.summaryCalls.get())
            assertEquals(listOf(CalendarEventSave.fingerprint(calendar.request(18))), saves.readLines())
            assertTrue(calendar.savedRequests.isEmpty())
            report("cold pid=${Process.myPid()} seed_pid=${saved.getInt("seedPid")} run=${stopped.id} " +
                "state=Stopped stop_reason=user_stopped model_calls=0 duplicate_saves=0 second_saves=0 elapsed_ms=$elapsed")
        } finally {
            withContext(Dispatchers.Main) { launchedActivity?.finish(); launchedActivity = null }
            fixture.close(false)
        }
    }

    @Test fun deleteIsolatedEvidence() {
        enabled()
        AutonomyRuntimeFixture.deleteIsolatedDatabase(context, namespace)
        listOf(marker, saves, File(requireNotNull(context.getExternalFilesDir(null)), "$namespace.png")).forEach {
            if (it.exists()) assertTrue(it.delete())
        }
        report("isolated_evidence_deleted")
    }

    private fun calendar() = CalendarTimeCorrectionAcceptanceTest.CalendarFixture { request ->
        saves.appendText(CalendarEventSave.fingerprint(request) + "\n")
    }

    private suspend fun messageEvidence(fixture: AutonomyRuntimeFixture): JSONArray =
        JSONArray(fixture.chats.getMessages(fixture.owner).map { message ->
            JSONObject().put("id", message.id).put("status", message.status.name).put("content", message.content)
                .put("executionContext", message.executionContext)
        })

    private suspend fun showChat(fixture: AutonomyRuntimeFixture) {
        val activity = launchM03Chat(context, automation)
        launchedActivity = activity
        withContext(Dispatchers.Main) {
            val viewModel = ChatViewModel(fixture.runtime)
            activity.setContent {
                FlowChatTheme(AppAppearance.Dark) {
                    ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel)
                }
            }
        }
    }

    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runM04StopRestart") == "true")

    private fun report(result: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nM04_STOP $result\n") })
}
