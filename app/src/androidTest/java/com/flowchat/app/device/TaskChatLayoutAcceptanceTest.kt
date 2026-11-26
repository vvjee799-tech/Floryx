package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.workspace.WorkspaceManager
import com.flowchat.app.domain.chat.TaskPersistenceBoundary
import com.flowchat.app.domain.chat.TaskPersistenceObserver
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** U06 through the normal chat controls, with isolated history, a synthetic document and an in-process model. */
class TaskChatLayoutAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val list get() = compose.onNodeWithTag("chat-message-list")
    private val input get() = compose.onNode(hasSetTextAction())

    private data class ReadingPosition(val viewport: Rect, val messages: Map<String, Float>)

    @Test fun readingPositionAndComposerSurviveToolResultsAndTaskDetails() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val expectedFont = args.getString("u06FontScale")?.toFloatOrNull()
        assumeTrue("Run U06 with the requested system font scale", expectedFont != null)
        val drawerOnly = args.getString("u06DrawerOnly") == "true"
        val run = args.getString("u06RunId") ?: "manual"
        check(run.matches(Regex("[a-z0-9-]+")))
        val runStarted = SystemClock.elapsedRealtime()
        val stages = JSONArray()
        fun stage(name: String) {
            val elapsed = SystemClock.elapsedRealtime() - runStarted
            stages.put(JSONObject().put("stage", name).put("elapsed_ms", elapsed))
            // The host needs to see the active step even when a timeout prevents result.json from being written.
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nU06 stage=$name elapsed_ms=$elapsed\n")
            })
        }
        stage("test_setup_started")
        val directory = File(context.getExternalFilesDir(null), "u06-chat-layout/$run/font-$expectedFont").apply { mkdirs() }
        val samples = JSONArray()
        val drawerSamples = JSONArray()
        val evidence = JSONObject().put("samples", samples).put("drawer_samples", drawerSamples)
            .put("setup_stages", stages).put("external_model_requests", 0)
        val evidenceFile = File(directory, "result.json")
        val workspace = WorkspaceManager(context)
        val fixture = AutonomyRuntimeFixture(context, workspaceOverride = workspace,
            settingsOverride = AppSettings(memoryEnabled = false, photoAnswerEnabled = false))
        val allowSerial = CompletableDeferred<Unit>()
        val allowBatch = CompletableDeferred<Unit>()
        val allowAnswer = CompletableDeferred<Unit>()
        val serialResults = CompletableDeferred<ChatRequest>()
        val batchResults = CompletableDeferred<ChatRequest>()
        val backgroundResults = CompletableDeferred<ChatRequest>()
        val stopReady = CompletableDeferred<Unit>()
        val settingsOpened = CompletableDeferred<Unit>()
        stage("ui_automation_connect_started")
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

        fun capture(name: String) {
            evidenceFile.writeText(evidence.toString(2))
            compose.waitForIdle()
            automation.waitForIdle(350, 5_000)
            val bitmap = checkNotNull(automation.takeScreenshot())
            try {
                File(directory, "$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }

        fun record(name: String, history: Map<String, String>, expected: ReadingPosition? = null): ReadingPosition {
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val jump = compose.onNodeWithTag("jump-to-latest").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val jumpClearance = maxOf(jump.top - viewport.bottom, jump.left - viewport.right)
            val visible = compose.onAllNodes(hasText(HistoryPrefix, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.boundsInRoot.height > 0 && it.boundsInRoot.top >= viewport.top + 2 &&
                    it.boundsInRoot.bottom <= viewport.bottom - 2 }
                .mapNotNull { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.let(history::get)
                        ?.let { id -> id to (node.boundsInRoot.top - viewport.top) }
                }.toMap()
            val position = ReadingPosition(viewport, visible)
            samples.put(JSONObject().put("stage", name).put("viewport_height_px", viewport.height)
                .put("viewport_top_px", viewport.top).put("message_top_px_by_id", JSONObject(visible))
                .put("jump_clearance_px", jumpClearance))
            capture(name)
            assertTrue("Back to latest overlaps the message viewport by ${-jumpClearance} px at $name", jumpClearance >= -1f)
            assertTrue("No fully visible old messages at $name", visible.isNotEmpty())
            if (expected != null) {
                assertEquals("List height changed at $name", expected.viewport.height, viewport.height, 1f)
                assertEquals("List moved at $name", expected.viewport.top, viewport.top, 1f)
                assertEquals("Visible messages changed at $name", expected.messages.keys, visible.keys)
                expected.messages.forEach { (id, top) ->
                    assertEquals("Old message $id moved at $name", top, visible.getValue(id), 1f)
                }
            }
            return position
        }

        val tree = DocumentsContract.buildTreeDocumentUri("${context.packageName}.p0c.documents", "root")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        fun hasPersistedRead() = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        stage("workspace_select_started")
        val hadGrant = hasPersistedRead()
        fun reads(vararg offsets: Int) = listOf(ChatDelta.ToolCalls(offsets.map { offset ->
            ChatToolCall("u06-read-$offset", WorkspaceToolDefinitions.Read,
                """{"path":"stream.txt","byte_offset":$offset,"max_bytes":24}""")
        }), ChatDelta.Done)

        try {
            // This URI is owned by the Dev-only provider and contains only synthetic bytes.
            if (!hadGrant) context.grantUriPermission(context.packageName, tree,
                read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            val location = workspace.select(tree.toString(), read)
            stage("workspace_selected")
            val background = fixture.chats.createConversation(fixture.provider.id, fixture.provider.defaultModel)
            fixture.chats.updateConversationTitle(background.id, BackgroundTitle)
            fixture.chats.updateConversationWorkspace(background.id, location)
            fixture.chats.appendMessage(background.id, MessageRole.User, "Earlier background message.",
                MessageStatus.Complete, fixture.provider.defaultModel)
            stage("background_conversation_ready")
            val backgroundDispatch = CompletableDeferred<Long>()
            val foregroundDispatch = CompletableDeferred<Long>()
            val backgroundReturned = CompletableDeferred<Long>()
            val foregroundReturned = CompletableDeferred<Long>()
            val dispatchReads = CompletableDeferred<Unit>()
            val commitResults = CompletableDeferred<Unit>()
            fixture.persistenceObserver = TaskPersistenceObserver { boundary ->
                val owner = currentCoroutineContext()[DeviceExecutionTarget]?.ownerConversationId
                val isBackground = owner == background.id
                if (isBackground || owner == fixture.owner && allowBatch.isCompleted) {
                    when (boundary) {
                        TaskPersistenceBoundary.PendingEffectsSaved -> {
                            val signal = if (isBackground) backgroundDispatch else foregroundDispatch
                            if (signal.complete(SystemClock.elapsedRealtime())) dispatchReads.await()
                        }
                        TaskPersistenceBoundary.ActionReturned -> {
                            val signal = if (isBackground) backgroundReturned else foregroundReturned
                            if (signal.complete(SystemClock.elapsedRealtime())) commitResults.await()
                        }
                        else -> Unit
                    }
                }
            }
            val roundsByOwner = mutableMapOf<String, Int>()
            fixture.responder = { request, _ ->
                val owner = checkNotNull(request.usageConversationId)
                val round = roundsByOwner.getOrDefault(owner, 0) + 1
                roundsByOwner[owner] = round
                check(request.tools.any { it.name == WorkspaceToolDefinitions.Read })
                check(request.parallelToolCalls == false)
                when (owner) {
                    background.id -> when (round) {
                        1 -> reads(96)
                        2 -> { backgroundResults.complete(request); listOf(ChatDelta.Content(BackgroundAnswer), ChatDelta.Done) }
                        else -> error("Unexpected background model round $round")
                    }
                    fixture.owner -> when (round) {
                        1 -> { allowSerial.await(); reads(0) }
                        2 -> { serialResults.complete(request); allowBatch.await(); reads(32, 64) }
                        3 -> { batchResults.complete(request); allowAnswer.await(); listOf(ChatDelta.Content(Answer), ChatDelta.Done) }
                        4 -> { stopReady.complete(Unit); awaitCancellation() }
                        else -> error("Unexpected foreground model round $round")
                    }
                    else -> error("Unexpected conversation $owner")
                }
            }
            stage("foreground_conversation_create_started")
            fixture.create()
            fixture.chats.updateConversationTitle(fixture.owner, ForegroundTitle)
            fixture.chats.updateConversationWorkspace(fixture.owner, location)
            stage("history_seed_started")
            val history = (1..24).associate { index ->
                val content = "$HistoryPrefix$index"
                val role = if (index % 2 == 0) MessageRole.Assistant else MessageRole.User
                val message = fixture.chats.appendMessage(fixture.owner, role, content, MessageStatus.Complete, fixture.provider.defaultModel)
                content to message.id
            }
            stage("history_state_wait_started")
            fixture.await { fixture.runtime.uiState.value.messages.size == history.size &&
                fixture.runtime.uiState.value.currentConversation?.workspace == location }
            evidence.put("history_message_count", history.size)
            stage("history_ready")
            stage("chat_activity_launch_started")
            // OriginOS can leave Instrumentation.startActivitySync waiting. Use the system launch path.
            val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}"
            )).bufferedReader().use { it.readText() }
            check(launchOutput.contains("Status: ok")) { launchOutput }
            stage("chat_system_launch_ok")
            val activity = withTimeout(15_000) {
                var resumed: MainActivity?
                do {
                    resumed = withContext(Dispatchers.Main) {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing && !it.isDestroyed }
                    }
                    if (resumed == null) delay(50)
                } while (resumed == null)
                checkNotNull(resumed)
            }
            try {
                stage("chat_activity_launched")
                withContext(Dispatchers.Main) {
                    val model = ChatViewModel(fixture.runtime)
                    activity.setContent {
                        FlowChatTheme(AppAppearance.Dark) {
                            ChatScreen({}, { settingsOpened.complete(Unit) }, {}, AppAppearance.Dark, {}, model)
                        }
                    }
                }
                stage("chat_content_attached")
                val configuration = activity.resources.configuration
                evidence.put("system_font_scale", configuration.fontScale).put("orientation", configuration.orientation)
                    .put("screen_width_dp", configuration.screenWidthDp).put("screen_height_dp", configuration.screenHeightDp)
                assertEquals("Chat activity font scale", checkNotNull(expectedFont), configuration.fontScale, 0.01f)
                list.assertIsDisplayed()
                input.assertIsDisplayed()
                stage("chat_controls_ready")
                fun string(id: Int) = activity.getString(id)
                val drawerButton = compose.onNodeWithContentDescription(string(R.string.open_navigation_drawer))
                val drawerHistory = compose.onNode(hasScrollToIndexAction() and !hasTestTag("chat-message-list"))
                fun send(text: String) {
                    input.performTextInput(text)
                    compose.onNodeWithContentDescription(string(R.string.send)).assertIsEnabled().performTouchInput { click(center) }
                }
                suspend fun selectChat(title: String, owner: String, screenshot: String) {
                    val selectionStarted = SystemClock.elapsedRealtime()
                    val imeBefore = if (drawerOnly) imeInset(activity) else 0
                    drawerButton.performTouchInput { click(center) }
                    val row = compose.onNode(hasText(title) and hasClickAction())
                    if (!row.isDisplayed()) drawerHistory.performScrollToNode(hasText(title))
                    row.performScrollTo()
                    val sample = if (drawerOnly) {
                        val viewport = drawerHistory.fetchSemanticsNode().boundsInRoot
                        val node = row.fetchSemanticsNode()
                        val imeDuring = imeInset(activity)
                        val imeTop = activity.window.decorView.height - imeDuring
                        val usable = (minOf(viewport.bottom, imeTop.toFloat()) - viewport.top).coerceAtLeast(0f)
                        val rowBounds = node.boundsInRoot
                        val rowVisiblePx = (minOf(rowBounds.bottom, imeTop.toFloat()) - rowBounds.top).coerceAtLeast(0f)
                        val measurement = JSONObject().put("title", title).put("ime_before_px", imeBefore)
                            .put("ime_during_px", imeDuring).put("viewport_height_px", viewport.height)
                            .put("usable_viewport_height_px", usable).put("row_height_px", node.size.height)
                            .put("visible_row_height_px", rowVisiblePx).put("displayed", row.isDisplayed())
                        drawerSamples.put(measurement)
                        capture(screenshot)
                        measurement
                    } else null
                    row.assertIsDisplayed().performTouchInput { click(center) }
                    fixture.await { fixture.runtime.uiState.value.currentConversation?.id == owner }
                    sample?.put("selection_elapsed_ms", SystemClock.elapsedRealtime() - selectionStarted)
                }
                if (drawerOnly) awaitKeyboard(activity, shown = false)
                stage("background_chat_select_started")
                selectChat(BackgroundTitle, background.id, "00a-drawer-without-keyboard")
                stage("background_task_send_started")
                send(BackgroundPrompt)
                stage("background_tool_wait_started")
                val backgroundReadyAt = withTimeout(15_000) { backgroundDispatch.await() }
                stage("background_tool_ready")
                if (drawerOnly) awaitKeyboard(activity, shown = true)
                stage("foreground_chat_select_started")
                selectChat(ForegroundTitle, fixture.owner, "00b-drawer-after-keyboard")
                if (drawerOnly) {
                    repeat(drawerSamples.length()) { index ->
                        val sample = drawerSamples.getJSONObject(index)
                        assertTrue("Conversation row is clipped: $sample", sample.getDouble("visible_row_height_px") >=
                            sample.getInt("row_height_px") - 1)
                    }
                    assertEquals(setOf(background.id), fixture.runtime.activeConversations.value)
                    dispatchReads.complete(Unit)
                    commitResults.complete(Unit)
                    assertReadResults(withTimeout(15_000) { backgroundResults.await() }, 96)
                    fixture.await { background.id !in fixture.runtime.activeConversations.value &&
                        fixture.journal.latest(background.id)?.state == "Completed" }
                    assertEquals(fixture.owner, fixture.runtime.uiState.value.currentConversation?.id)
                    assertEquals(1, fixture.chats.getMessages(background.id).count {
                        it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.content == BackgroundAnswer
                    })
                    assertTrue(fixture.confirmations.isEmpty())
                    stage("drawer_secondary_controls_started")
                    drawerButton.performTouchInput { click(center) }
                    val archive = compose.onNodeWithTag("history-toggle-archive")
                    if (!archive.isDisplayed()) drawerHistory.performScrollToNode(hasTestTag("history-toggle-archive"))
                    archive.assertIsDisplayed().performTouchInput { click(center) }
                    archive.assert(hasContentDescription(string(R.string.active_conversations)))
                    evidence.put("drawer_archive_reached", true)
                    val settingsDescription = string(R.string.settings)
                    val settings = compose.onNodeWithContentDescription(settingsDescription)
                    if (!settings.isDisplayed()) drawerHistory.performScrollToNode(hasContentDescription(settingsDescription))
                    val settingsNode = settings.fetchSemanticsNode()
                    if (settingsNode.boundsInRoot.height < settingsNode.size.height - 1) settings.performScrollTo()
                    capture("00c-drawer-settings")
                    settings.assertIsDisplayed().performTouchInput { click(center) }
                    compose.waitUntil(5_000) { settingsOpened.isCompleted }
                    evidence.put("background_completed_run_id", checkNotNull(fixture.journal.latest(background.id)).id)
                        .put("app_confirmations", fixture.confirmations.size).put("drawer_settings_reached", true)
                        .put("passed", true)
                    return@runBlocking
                }
                stage("foreground_task_send_started")
                send("Read three ranges from the synthetic workspace document.")
                val started = SystemClock.elapsedRealtime()
                stage("foreground_model_wait_started")
                fixture.await { fixture.modelCalls.get() == 2 }
                stage("keyboard_hide_started")
                withContext(Dispatchers.Main) {
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                }
                awaitKeyboard(activity, shown = false)
                stage("old_messages_scroll_started")
                repeat(2) { list.performTouchInput { swipeDown(durationMillis = 480) } }
                compose.onNodeWithTag("jump-to-latest").assertIsDisplayed()
                stage("first_reading_snapshot_started")
                val beforeTools = record("01-reading-before-tools", history)
                stage("first_reading_snapshot_ready")

                allowSerial.complete(Unit)
                val serialRequest = withTimeout(15_000) { serialResults.await() }
                assertReadResults(serialRequest, 0)
                fixture.await { fixture.runtime.uiState.value.messages.count { it.role == MessageRole.Tool && it.status == MessageStatus.Complete } == 1 }
                record("02-after-single-tool", history, beforeTools)

                allowBatch.complete(Unit)
                val foregroundReadyAt = withTimeout(15_000) { foregroundDispatch.await() }
                val owners = setOf(fixture.owner, background.id)
                assertEquals(owners, fixture.runtime.activeConversations.value)
                val dispatchAt = SystemClock.elapsedRealtime()
                dispatchReads.complete(Unit)
                val returnedAt = withTimeout(15_000) { listOf(foregroundReturned.await(), backgroundReturned.await()) }
                assertEquals(owners, fixture.runtime.activeConversations.value)
                assertTrue(returnedAt.all { it >= dispatchAt })
                capture("03b-both-tool-results-returned")
                val commitAt = SystemClock.elapsedRealtime()
                evidence.put("overlapping_tool_completions", JSONObject()
                    .put("foreground_dispatch_ready_ms", foregroundReadyAt).put("background_dispatch_ready_ms", backgroundReadyAt)
                    .put("dispatch_released_ms", dispatchAt).put("foreground_returned_ms", returnedAt[0])
                    .put("background_returned_ms", returnedAt[1]).put("commit_released_ms", commitAt))
                commitResults.complete(Unit)

                assertReadResults(withTimeout(15_000) { batchResults.await() }, 0, 32, 64)
                assertReadResults(withTimeout(15_000) { backgroundResults.await() }, 96)
                fixture.await { fixture.runtime.uiState.value.messages.count { it.role == MessageRole.Tool && it.status == MessageStatus.Complete } == 3 }
                fixture.await { background.id !in fixture.runtime.activeConversations.value &&
                    fixture.journal.latest(background.id)?.state == "Completed" }
                val backgroundRun = checkNotNull(fixture.journal.latest(background.id))
                val backgroundMessages = fixture.chats.getMessages(background.id)
                assertEquals(1, TaskRunMetadata.decode(backgroundRun.metadataJson)?.toolCallCount)
                assertEquals(1, backgroundMessages.count { it.role == MessageRole.User && it.content == BackgroundPrompt })
                assertEquals(1, backgroundMessages.count { it.role == MessageRole.Tool && it.status == MessageStatus.Complete })
                assertEquals(1, backgroundMessages.count {
                    it.role == MessageRole.Assistant && it.content == BackgroundAnswer && it.status == MessageStatus.Complete
                })
                assertTrue(backgroundMessages.none { it.content == Answer })
                assertEquals(setOf(fixture.owner), fixture.runtime.activeConversations.value)
                evidence.put("background_completed_run_id", backgroundRun.id)
                compose.waitUntil(5_000) { activity.hasWindowFocus() }
                record("04-after-batched-tools-and-details", history, beforeTools)

                input.performTouchInput { click(center) }
                capture("05-keyboard-request")
                val imeTop = awaitKeyboard(activity, shown = true)
                useComposer(imeTop, string(R.string.stop), samples)
                val beforeAnswer = record("05-reading-with-keyboard", history)
                allowAnswer.complete(Unit)
                fixture.await { fixture.owner !in fixture.runtime.activeConversations.value &&
                    fixture.journal.latest(fixture.owner)?.state == "Completed" }
                fixture.await { !fixture.runtime.uiState.value.isStreaming }
                evidence.put("send_to_completed_ms", SystemClock.elapsedRealtime() - started)
                record("06-completed-with-keyboard", history, beforeAnswer)
                useComposer(imeTop, string(R.string.send), samples)
                compose.onNodeWithTag("jump-to-latest").performTouchInput { click(center) }
                compose.onNodeWithText(Answer, useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("task-status").assertDoesNotExist()
                capture("07-final-answer")
                if (configuration.screenHeightDp < 480) {
                    compose.onNodeWithTag("composer-options").assertIsDisplayed().performTouchInput { click(center) }
                    compose.onNodeWithTag("thinking-toggle").assertIsDisplayed()
                    compose.onNodeWithTag("context_window_indicator").assertIsDisplayed()
                    compose.onNodeWithTag("model-switch").assertIsDisplayed()
                    capture("07b-compact-options")
                    compose.onNodeWithTag("composer-options-close").assertIsDisplayed().performTouchInput { click(center) }
                    compose.onNodeWithTag("composer-options-close").assertDoesNotExist()
                    evidence.put("compact_options_reachable", true)
                }

                val finished = checkNotNull(fixture.journal.latest(fixture.owner))
                val messages = fixture.chats.getMessages(fixture.owner)
                assertEquals(1, messages.count { it.role == MessageRole.Assistant && it.content == Answer && it.status == MessageStatus.Complete })
                assertTrue(messages.none { it.content == BackgroundAnswer || it.content == BackgroundPrompt })
                assertNotEquals(backgroundRun.id, finished.id)
                assertEquals(3, TaskRunMetadata.decode(finished.metadataJson)?.toolCallCount)
                assertTrue(fixture.confirmations.isEmpty())
                evidence.put("completed_run_id", finished.id).put("completed_tool_count", 3)

                send("Start a second synthetic task so the Stop button can be tested.")
                withTimeout(15_000) { stopReady.await() }
                useComposer(awaitKeyboard(activity, shown = true), string(R.string.stop), samples)
                capture("08-stop-with-keyboard")
                val stopStarted = SystemClock.elapsedRealtime()
                compose.onNodeWithContentDescription(string(R.string.stop)).assertIsEnabled().performTouchInput { click(center) }
                fixture.await { fixture.owner !in fixture.runtime.activeConversations.value &&
                    fixture.journal.latest(fixture.owner)?.state == "Stopped" }
                val stopped = checkNotNull(fixture.journal.latest(fixture.owner))
                assertNotEquals(finished.id, stopped.id)
                assertEquals("user_stopped", TaskRunMetadata.decode(stopped.metadataJson)?.stopReason)
                assertEquals(6, fixture.modelCalls.get())
                assertEquals(0, fixture.summaryCalls.get())
                assertTrue(fixture.confirmations.isEmpty())
                evidence.put("stopped_run_id", stopped.id).put("stop_to_stopped_ms", SystemClock.elapsedRealtime() - stopStarted)
                    .put("app_confirmations", fixture.confirmations.size).put("scripted_model_rounds", fixture.modelCalls.get())
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { activity.finish() }
            }
            evidence.put("passed", true)
        } catch (failure: Throwable) {
            evidence.put("passed", false).put("failure", failure.toString())
                .put("failed_stage", stages.getJSONObject(stages.length() - 1).getString("stage"))
            throw failure
        } finally {
            stage("cleanup_started")
            val testDatabase = context.getDatabasePath("${fixture.name}.db")
            try {
                fixture.close(passed = testDatabase.exists())
                check(!testDatabase.exists())
                evidence.put("isolated_database_removed", true)
            } finally {
                if (!hadGrant) {
                    if (hasPersistedRead()) context.contentResolver.releasePersistableUriPermission(tree, read)
                    context.revokeUriPermission(context.packageName, tree, read)
                    check(!hasPersistedRead())
                }
                evidence.put("original_document_permission_restored", hasPersistedRead() == hadGrant)
                evidenceFile.writeText(evidence.toString(2))
            }
        }
    }

    private fun assertReadResults(request: ChatRequest, vararg offsets: Int) {
        val results = request.messages.filter { it.role == "tool" && it.toolCallId?.startsWith("u06-read-") == true }
        assertEquals(offsets.map { "u06-read-$it" }, results.map { it.toolCallId })
        results.zip(offsets.toList()).forEach { (message, offset) ->
            val result = JSONObject(checkNotNull(message.content))
            assertFalse("Range $offset failed: $result", result.has("error"))
            assertEquals(offset, result.getInt("byte_start"))
            assertEquals("x".repeat(24), result.getString("text"))
        }
    }

    private suspend fun imeInset(activity: MainActivity): Int = withContext(Dispatchers.Main) {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
    }

    private fun awaitKeyboard(activity: MainActivity, shown: Boolean): Float {
        var top = 0f
        var imeBottom = -1
        var imeVisible = false
        var stableSince = 0L
        try {
            compose.waitUntil(8_000) {
                instrumentation.runOnMainSync {
                    val decor = activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(decor)
                    val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: -1
                    imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    val settledTop = if (imeVisible == shown && (if (shown) bottom > 0 else bottom == 0))
                        (decor.height - bottom).toFloat() else 0f
                    if (bottom != imeBottom || top != settledTop) stableSince = SystemClock.elapsedRealtime()
                    imeBottom = bottom
                    top = settledTop
                }
                top > 0f && SystemClock.elapsedRealtime() - stableSince >= 200
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("IME expected visible=$shown, observed visible=$imeVisible, bottom=$imeBottom", failure)
        }
        compose.waitForIdle()
        return top
    }

    private fun useComposer(imeTop: Float, action: String, samples: JSONArray) {
        val panel = compose.onNodeWithTag("message_composer_panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val field = input.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithContentDescription(action).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        samples.put(JSONObject().put("stage", "composer-with-keyboard").put("action", action).put("ime_top_px", imeTop)
            .put("panel_bottom_px", panel.bottom).put("input_bottom_px", field.bottom).put("action_bottom_px", button.bottom))
        assertTrue("Composer overlaps the IME: $panel vs $imeTop", panel.bottom <= imeTop + 1f)
        assertTrue("Input or action is clipped by the composer: $field / $button vs $panel",
            field.left >= panel.left - 1f && field.right <= panel.right + 1f &&
                field.top >= panel.top - 1f && field.bottom <= panel.bottom + 1f &&
                button.left >= panel.left - 1f && button.right <= panel.right + 1f &&
                button.top >= panel.top - 1f && button.bottom <= panel.bottom + 1f &&
                (field.bottom <= button.top + 1f || field.right <= button.left + 1f))
    }

    private companion object {
        const val HistoryPrefix = "U06 previous message "
        const val ForegroundTitle = "U06 foreground"
        const val BackgroundTitle = "U06 background"
        const val Answer = "Read three ranges of the synthetic document. All three contained the expected text."
        const val BackgroundPrompt = "Read the fourth range from the synthetic workspace document."
        const val BackgroundAnswer = "Read the fourth range. It contained the expected text."
    }
}
