package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.data.workspace.WorkspaceManager
import com.flowchat.app.di.AppModule
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskInformationRange
import com.flowchat.app.domain.chat.TaskPersistenceBoundary
import com.flowchat.app.domain.chat.TaskPersistenceObserver
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.UnavailableDeviceAssistantGateway
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** M04 OriginOS background/screen-off continuation and mid-task OS process-kill recovery with real Flash. */
class M04OriginOsFlashAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private val screenOffInstruction = "请对工作区里的 stream.txt（200012 字节）做只读抽检：" +
        "读取开头 [0,9000) 和文件末尾 [199997,200012)，不要执行或改动文件。最后告诉我填充字符和文件末尾的中文原文。"
    private val twoStepInstruction = "请对工作区里的 stream.txt（200012 字节）做两步只读抽检：" +
        "第一步先只调用一次 workspace_read 读取开头 [0,9000)，不要同时读取其他段；收到结果后再读取文件末尾 [199997,200012)。" +
        "不要执行或改动文件。最后告诉我填充字符和文件末尾的中文原文。"

    @Test fun screenOffOfflinePreflight() = runBlocking<Unit> {
        assumeTrue(args.getString("m04Sample") != null)
        exerciseScreenOff(official = false, togglePhysicalScreen = args.getString("m04ScreenOff") == "true")
    }

    @Test fun screenOffOfficialFlash() = runBlocking<Unit> {
        assumeTrue(args.getString("runM04OfficialFlash") == "true")
        exerciseScreenOff(official = true, togglePhysicalScreen = args.getString("m04ScreenOff") == "true")
    }

    @Test fun seedInterruptedOfflinePreflight() = runBlocking<Unit> {
        assumeTrue(args.getString("m04KillPhase") == "seed" && args.getString("m04OfficialFlash") != "true")
        seedKillMidTask(official = false)
    }

    @Test fun resumeInterruptedOfflinePreflight() = runBlocking<Unit> {
        assumeTrue(args.getString("m04KillPhase") == "resume" && args.getString("m04OfficialFlash") != "true")
        resumeAfterKill(official = false)
    }

    @Test fun seedInterruptedOfficialFlash() = runBlocking<Unit> {
        assumeTrue(args.getString("m04KillPhase") == "seed" && args.getString("m04OfficialFlash") == "true")
        seedKillMidTask(official = true)
    }

    @Test fun resumeInterruptedOfficialFlash() = runBlocking<Unit> {
        assumeTrue(args.getString("runM04OfficialFlash") == "true" && args.getString("m04KillPhase") == "resume")
        resumeAfterKill(official = true)
    }

    @Test fun deleteIsolatedKillEvidence() {
        assumeTrue(args.getString("m04KillCleanup") == "true")
        val sample = validatedSample()
        val official = args.getString("m04OfficialFlash") == "true"
        val mode = if (official) "flash" else "dry"
        val dbName = "autonomyqa-m04-kill-$mode-$sample"
        val marker = context.getFileStreamPath("$dbName.json")
        if (marker.exists()) {
            val saved = JSONObject(marker.readText())
            if (!saved.optBoolean("originally_granted", true)) {
                val tree = DocumentsContract.buildTreeDocumentUri("${context.packageName}.p0c.documents", "root")
                val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (hasPersistedReadGrant(tree)) context.contentResolver.releasePersistableUriPermission(tree, read)
                context.revokeUriPermission(context.packageName, tree, read)
            }
            assertTrue(marker.delete())
        }
        AutonomyRuntimeFixture.deleteIsolatedDatabase(context, dbName)
        report("kill_cleanup_done sample=$sample")
    }

    private suspend fun exerciseScreenOff(official: Boolean, togglePhysicalScreen: Boolean) {
        val sample = validatedSample()
        val mode = if (official) "flash" else "dry"
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "d3-m04-screenoff-$mode-$sample")
        check(output.mkdir())
        val result = JSONObject().put("sample", sample).put("official", official)
            .put("screen_off_requested", togglePhysicalScreen).put("passed", false)
        val (provider, key) = if (official) loadOfficialFlashProvider(context) else offlineProvider()
        val tree = DocumentsContract.buildTreeDocumentUri("${context.packageName}.p0c.documents", "root")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "stream")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val originallyGranted = hasPersistedReadGrant(tree)
        val workspace = WorkspaceManager(context)
        val fixture = AutonomyRuntimeFixture(context, providerOverride = provider, apiKeyOverride = key,
            workspaceOverride = workspace, gatewayOverride = UnavailableDeviceAssistantGateway,
            name = "autonomyqa-m04-screenoff-$mode-$sample")
        val json = AppModule.provideJson()
        val engine = if (official) null else M04MockEngine(delayBeforeFinalReplyMs = 600L)
        val http = if (official) AppModule.provideHttpClient(json) else AppModule.createProviderHttpClient(json, engine!!)
        val firstRequestSent = CompletableDeferred<Unit>()
        val wire = FlashLedger(http, output, sample, official, { payload, writer ->
            val names = (payload.optJSONArray("tools") ?: JSONArray()).objects().map { it.getJSONObject("function").getString("name") }
            JSONObject().put("stage", if (writer) "checkpoint" else "screenoff_bg").put("tool_count", names.size)
        }, maxRequests = 8, onRequestSaved = { seq, writer ->
            if (!writer && seq >= 1) firstRequestSent.complete(Unit)
        })
        val power = context.getSystemService(PowerManager::class.java)
        var activity: MainActivity? = null
        var sourceFingerprint: String? = null
        try {
            fixture.settings.setPowerModeEnabled(false)
            fixture.settings.setUsageTrackingEnabled(true)
            val actual = KtorOpenAiCompatibleClient(http, json).apply { usageTracker = UsageTracker(fixture.database, fixture.settings) }
            fixture.externalClient = wire.track(actual, fixture)
            if (!originallyGranted) context.grantUriPermission(context.packageName, tree,
                read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            val location = workspace.select(tree.toString(), read)
            sourceFingerprint = documentFingerprint(document)
            result.put("source_sha256", sourceFingerprint).put("source_bytes", 200_012)
            fixture.create()
            fixture.chats.updateConversationWorkspace(fixture.owner, location)
            fixture.await { fixture.runtime.uiState.value.currentConversation?.workspace == location }
            val chat = launchM03Chat(context, automation)
            activity = chat
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                chat.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
                fixture.runtime.updateInput(screenOffInstruction)
            }
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            withTimeout(15_000) { firstRequestSent.await() }
            shell("input keyevent KEYCODE_HOME")
            delay(350)
            if (togglePhysicalScreen) {
                shell("input keyevent 223")
                withTimeout(8_000) { while (power.isInteractive) delay(50) }
            }
            val screenWasOffAtBackgroundStart = !power.isInteractive
            result.put("backgrounded_screen_off", screenWasOffAtBackgroundStart)
            report("backgrounded official=$official screen_off=$screenWasOffAtBackgroundStart")
            withTimeout(90_000) {
                while (true) {
                    val run = fixture.journal.latest(fixture.owner)
                    if (run != null && run.state !in setOf("Running", "WaitingForUser") &&
                        fixture.owner !in fixture.runtime.activeConversations.value) break
                    delay(80)
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val completedWhileScreenOff = !power.isInteractive
            result.put("completed_while_screen_off", completedWhileScreenOff)
            if (togglePhysicalScreen) assertTrue("Task must finish while screen is still off", completedWhileScreenOff)
            if (togglePhysicalScreen || !power.isInteractive) {
                shell("input keyevent 224")
                withTimeout(8_000) { while (!power.isInteractive) delay(50) }
                shell("wm dismiss-keyguard")
            }
            activity = launchM03Chat(context, automation)
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                activity!!.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
            }
            val run = requireNotNull(fixture.journal.latest(fixture.owner))
            val rows = fixture.chats.getMessages(fixture.owner)
            val answer = rows.last()
            val steps = savedSteps(fixture)
            assertEquals(answer.content, "Completed", run.state)
            assertEquals(MessageStatus.Complete, answer.status)
            assertTrue("Expected Chinese tail and x padding in: ${answer.content}",
                "中文末尾" in answer.content && 'x' in answer.content.lowercase())
            assertTrue(steps.any { it.tool == WorkspaceToolDefinitions.Read })
            assertTrue(steps.all { it.tool in WorkspaceToolDefinitions.ReadOnlyNames && it.outcome == "returned" })
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("中文末尾", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val visible = compose.onAllNodesWithText("中文末尾", substring = true, useUnmergedTree = true)
            assertTrue(visible.fetchSemanticsNodes().indices.any { visible[it].isDisplayed() })
            captureScreenshot(File(output, "answer.png"))
            val meta = metadata(fixture)
            assertTrue(meta.effects.isEmpty() && meta.pendingEffects.isEmpty() && fixture.confirmations.isEmpty())
            result.put("passed", true).put("elapsed_ms", elapsed).put("state", run.state)
                .put("answer", answer.content).put("tools", JSONArray(steps.map { it.tool }))
                .put("confirmation_count", fixture.confirmations.size).put("business_writes", 0)
            report("screenoff_passed official=$official elapsed_ms=$elapsed completed_off=$completedWhileScreenOff")
        } finally {
            if (togglePhysicalScreen && !power.isInteractive) {
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
            }
            try {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { activity?.finish() }
                    fixture.close(true)
                }
                if (sourceFingerprint != null) {
                    val unchanged = sourceFingerprint == documentFingerprint(document)
                    result.put("source_unchanged", unchanged)
                    assertTrue("The synthetic document changed", unchanged)
                }
            } finally {
                restoreGrant(tree, originallyGranted)
                result.put("original_grant_restored", originallyGranted == hasPersistedReadGrant(tree))
                    .put("official_requests", if (official) wire.count else 0)
                    .put("mock_requests", if (official) 0 else wire.count)
                http.close()
                File(output, "result.json").writeText(result.toString(2))
                assertEquals(originallyGranted, hasPersistedReadGrant(tree))
            }
        }
    }

    private suspend fun seedKillMidTask(official: Boolean) {
        val sample = validatedSample()
        val mode = if (official) "flash" else "dry"
        val dbName = "autonomyqa-m04-kill-$mode-$sample"
        val marker = context.getFileStreamPath("$dbName.json")
        check(!marker.exists() && !context.getDatabasePath("$dbName.db").exists())
        val baseDir = File(requireNotNull(context.getExternalFilesDir(null)), "d3-m04-kill-$mode-$sample")
        val seedOut = File(baseDir, "seed").apply { check(mkdirs()) }
        val (provider, key) = if (official) loadOfficialFlashProvider(context) else offlineProvider()
        val tree = DocumentsContract.buildTreeDocumentUri("${context.packageName}.p0c.documents", "root")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "stream")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val originallyGranted = hasPersistedReadGrant(tree)
        val workspace = WorkspaceManager(context)
        val fixture = AutonomyRuntimeFixture(context, providerOverride = provider, apiKeyOverride = key,
            workspaceOverride = workspace, gatewayOverride = UnavailableDeviceAssistantGateway, name = dbName)
        val json = AppModule.provideJson()
        val engine = if (official) null else M04MockEngine(delayAfterHeadReadMs = 30_000L)
        val http = if (official) AppModule.provideHttpClient(json) else AppModule.createProviderHttpClient(json, engine!!)
        val headSaved = CompletableDeferred<TaskInformationRange>()
        var roundAtHeadSaved = Int.MAX_VALUE
        val nextRoundEntered = CompletableDeferred<Int>()
        val wire = FlashLedger(http, seedOut, sample, official, { payload, writer ->
            val rows = payload.getJSONArray("messages").objects()
            val ranges = rows.filter { it.optString("role") == "tool" }.mapNotNull { tool ->
                val obj = runCatching { JSONObject(tool.getString("content")) }.getOrNull() ?: return@mapNotNull null
                if (obj.has("byte_start") && obj.has("byte_end_exclusive")) {
                    JSONArray(listOf(obj.getLong("byte_start"), obj.getLong("byte_end_exclusive")))
                } else null
            }
            JSONObject().put("stage", if (writer) "checkpoint" else "kill_seed").put("tool_ranges", JSONArray(ranges))
        }, maxRequests = 8, onRequestSaved = { seq, writer ->
            if (!writer && headSaved.isCompleted && seq > roundAtHeadSaved) nextRoundEntered.complete(seq)
        })
        fixture.settings.setPowerModeEnabled(false)
        fixture.settings.setUsageTrackingEnabled(true)
        val actual = KtorOpenAiCompatibleClient(http, json).apply { usageTracker = UsageTracker(fixture.database, fixture.settings) }
        fixture.externalClient = wire.track(actual, fixture)
        fixture.persistenceObserver = TaskPersistenceObserver { boundary ->
            if (boundary == TaskPersistenceBoundary.ResultMetadataSaved && !headSaved.isCompleted) {
                val ranges = metadata(fixture).informationProgress.sources.singleOrNull()?.ranges.orEmpty()
                val head = ranges.firstOrNull { it.start == 0L && it.end >= 1_000L }
                if (head != null) {
                    roundAtHeadSaved = wire.count
                    headSaved.complete(head)
                }
            }
        }
        if (!originallyGranted) context.grantUriPermission(context.packageName, tree,
            read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        val location = workspace.select(tree.toString(), read)
        val sourceFingerprint = documentFingerprint(document)
        fixture.create()
        fixture.chats.updateConversationWorkspace(fixture.owner, location)
        fixture.await { fixture.runtime.uiState.value.currentConversation?.workspace == location }
        val chat = launchM03Chat(context, automation)
        withContext(Dispatchers.Main) {
            val viewModel = ChatViewModel(fixture.runtime)
            chat.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
            fixture.runtime.updateInput(twoStepInstruction)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
        val headRange = withTimeout(45_000) { headSaved.await() }
        val killAtRequest = withTimeout(15_000) { nextRoundEntered.await() }
        // Wait briefly so chatRepository.appendMessage(Streaming) for the next assistant message commits to Room.
        withTimeout(5_000) {
            while (fixture.chats.getMessages(fixture.owner).lastOrNull()?.status != MessageStatus.Streaming) delay(25)
        }
        val run = requireNotNull(fixture.journal.latest(fixture.owner))
        val meta = metadata(fixture)
        assertEquals("Running", run.state)
        marker.writeText(JSONObject()
            .put("seed_pid", Process.myPid())
            .put("owner", fixture.owner)
            .put("run_id", run.id)
            .put("logical_task_id", meta.logicalTaskId)
            .put("head_start", headRange.start)
            .put("head_end", headRange.end)
            .put("kill_at_request", killAtRequest)
            .put("source_sha256", sourceFingerprint)
            .put("originally_granted", originallyGranted)
            .toString(2))
        shell("input keyevent KEYCODE_HOME")
        delay(200)
        report("seed_killed pid=${Process.myPid()} run=${run.id} head=[${headRange.start},${headRange.end}) req=$killAtRequest")
        Runtime.getRuntime().halt(1)
    }

    private suspend fun resumeAfterKill(official: Boolean) {
        val sample = validatedSample()
        val mode = if (official) "flash" else "dry"
        val dbName = "autonomyqa-m04-kill-$mode-$sample"
        val marker = context.getFileStreamPath("$dbName.json")
        val saved = JSONObject(marker.readText())
        val seedPid = saved.getInt("seed_pid")
        assertNotEquals("Resume must run in a cold process", seedPid, Process.myPid())
        val owner = saved.getString("owner")
        val seedRunId = saved.getString("run_id")
        val seedLogicalId = saved.getString("logical_task_id")
        val headRange = TaskInformationRange(saved.getLong("head_start"), saved.getLong("head_end"))
        val baseDir = File(requireNotNull(context.getExternalFilesDir(null)), "d3-m04-kill-$mode-$sample")
        val resumeOut = File(baseDir, "resume").apply { check(mkdirs()) }
        val result = JSONObject().put("sample", sample).put("official", official)
            .put("seed_pid", seedPid).put("resume_pid", Process.myPid()).put("passed", false)
        val (provider, key) = if (official) loadOfficialFlashProvider(context) else offlineProvider()
        val tree = DocumentsContract.buildTreeDocumentUri("${context.packageName}.p0c.documents", "root")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "stream")
        val workspace = WorkspaceManager(context)
        val fixture = AutonomyRuntimeFixture(context, providerOverride = provider, apiKeyOverride = key,
            workspaceOverride = workspace, gatewayOverride = UnavailableDeviceAssistantGateway, name = dbName)
        val json = AppModule.provideJson()
        val engine = if (official) null else M04MockEngine(startAfterHeadRead = true)
        val http = if (official) AppModule.provideHttpClient(json) else AppModule.createProviderHttpClient(json, engine!!)
        val resumePayloads = mutableListOf<JSONObject>()
        val wire = FlashLedger(http, resumeOut, sample, official, { payload, writer ->
            val rows = payload.getJSONArray("messages").objects()
            val hasPriorHistory = rows.any { it.optString("content").contains("Previous task activity") }
            val info = JSONObject().put("stage", if (writer) "checkpoint" else "kill_resume").put("has_prior_history", hasPriorHistory)
            if (!writer) resumePayloads += info
            info
        }, maxRequests = 8)
        var activity: MainActivity? = null
        try {
            fixture.settings.setPowerModeEnabled(false)
            fixture.settings.setUsageTrackingEnabled(true)
            val actual = KtorOpenAiCompatibleClient(http, json).apply { usageTracker = UsageTracker(fixture.database, fixture.settings) }
            fixture.externalClient = wire.track(actual, fixture)
            fixture.reopen(owner)
            fixture.await {
                fixture.journal.get(seedRunId)?.state == "Interrupted" &&
                    fixture.runtime.uiState.value.interruptedTask
            }
            val chat = launchM03Chat(context, automation)
            activity = chat
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                chat.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) } }
            }
            val interruptedLabel = context.getString(R.string.task_status_interrupted)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(interruptedLabel).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat-message-list").performScrollToNode(hasTestTag("task-status"))
            compose.onNodeWithText(interruptedLabel).assertIsDisplayed()
            delay(1_000)
            assertEquals("Reopening an interrupted task must not auto-dispatch model requests", 0, wire.count)
            captureScreenshot(File(resumeOut, "interrupted.png"))

            val started = SystemClock.elapsedRealtime()
            val resumeButton = context.getString(R.string.device_task_resume)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(resumeButton).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(resumeButton).performClick()

            withTimeout(90_000) {
                while (true) {
                    val latest = fixture.journal.latest(owner)
                    if (latest != null && latest.id != seedRunId &&
                        latest.state !in setOf("Running", "WaitingForUser") &&
                        owner !in fixture.runtime.activeConversations.value) break
                    delay(80)
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val resumedRun = requireNotNull(fixture.journal.latest(owner))
            val resumedMeta = metadata(fixture)
            val rows = fixture.chats.getMessages(owner)
            val answer = rows.last()
            assertEquals(answer.content, "Completed", resumedRun.state)
            assertEquals(MessageStatus.Complete, answer.status)
            assertEquals(seedLogicalId, resumedMeta.logicalTaskId)
            assertEquals(seedRunId, resumedMeta.continuedFromRunId)
            assertTrue("Prior coverage must survive process kill and resume",
                resumedMeta.informationProgress.sources.single().ranges.any { it.start <= headRange.start && it.end >= headRange.end })
            assertTrue("Tail coverage must be recorded after resume",
                resumedMeta.informationProgress.sources.single().ranges.any { it.end >= 200_012L })
            assertTrue("Expected Chinese tail and x padding in: ${answer.content}",
                "中文末尾" in answer.content && 'x' in answer.content.lowercase())
            val resumeSteps = rows.takeLast(3).flatMap { row ->
                row.executionContext?.let { Json.decodeFromString<List<ExecutionStep>>(it) }.orEmpty()
            }
            val repeatedHeadInResume = resumeSteps.count { step ->
                step.tool == WorkspaceToolDefinitions.Read && runCatching {
                    val obj = JSONObject(step.result)
                    obj.getLong("byte_start") == 0L && obj.getLong("byte_end_exclusive") == 9_000L
                }.getOrDefault(false)
            }
            assertTrue(resumePayloads.first().getBoolean("has_prior_history"))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("中文末尾", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val visible = compose.onAllNodesWithText("中文末尾", substring = true, useUnmergedTree = true)
            assertTrue(visible.fetchSemanticsNodes().indices.any { visible[it].isDisplayed() })
            captureScreenshot(File(resumeOut, "answer.png"))
            assertTrue(resumedMeta.effects.isEmpty() && resumedMeta.pendingEffects.isEmpty() && fixture.confirmations.isEmpty())
            val unchanged = saved.getString("source_sha256") == documentFingerprint(document)
            assertTrue("The synthetic document changed", unchanged)
            result.put("passed", true).put("elapsed_ms", elapsed).put("seed_run_id", seedRunId)
                .put("resumed_run_id", resumedRun.id).put("logical_task_id", resumedMeta.logicalTaskId)
                .put("final_ranges", JSONArray(resumedMeta.informationProgress.sources.single().ranges.map { JSONArray(listOf(it.start, it.end)) }))
                .put("repeated_head_reads_after_resume", repeatedHeadInResume)
                .put("answer", answer.content).put("confirmation_count", fixture.confirmations.size)
                .put("business_writes", 0).put("source_unchanged", unchanged)
                .put("official_requests", if (official) wire.count else 0)
                .put("mock_requests", if (official) 0 else wire.count)
            report("resume_passed official=$official seed_pid=$seedPid resume_pid=${Process.myPid()} repeated_head=$repeatedHeadInResume elapsed_ms=$elapsed")
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { activity?.finish() }
                fixture.close(false)
            }
            http.close()
            File(resumeOut, "result.json").writeText(result.toString(2))
        }
    }

    private fun validatedSample(): String = requireNotNull(args.getString("m04Sample")).also {
        check(UUID.fromString(it).toString() == it)
    }

    private fun offlineProvider() = ProviderConfig(id = "m04-offline", displayName = "M04 offline fixture",
        baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash") to "mock-only"

    private fun hasPersistedReadGrant(tree: android.net.Uri) =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    private fun restoreGrant(tree: android.net.Uri, originallyGranted: Boolean) {
        if (!originallyGranted) {
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (hasPersistedReadGrant(tree)) context.contentResolver.releasePersistableUriPermission(tree, read)
            context.revokeUriPermission(context.packageName, tree, read)
        }
    }

    private fun documentFingerprint(document: android.net.Uri): String {
        val bytes = requireNotNull(context.contentResolver.openInputStream(document)).use { it.readBytes() }
        assertEquals(200_012, bytes.size)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private suspend fun metadata(fixture: AutonomyRuntimeFixture) =
        requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))

    private suspend fun savedSteps(fixture: AutonomyRuntimeFixture) = fixture.chats.getMessages(fixture.owner)
        .flatMap { row -> row.executionContext?.let { Json.decodeFromString<List<ExecutionStep>>(it) }.orEmpty() }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun captureScreenshot(file: File) {
        val screenshot = requireNotNull(automation.takeScreenshot())
        try {
            file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun report(stage: String) =
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nM04_FLASH $stage\n") })
}

private class M04MockEngine(
    private val delayBeforeFinalReplyMs: Long = 0L,
    private val delayAfterHeadReadMs: Long = 0L,
    private val startAfterHeadRead: Boolean = false
) : HttpClientEngineFactory<MockEngineConfig> {
    private var step = if (startAfterHeadRead) 1 else 0
    override fun create(block: MockEngineConfig.() -> Unit): MockEngine = MockEngine {
        val messages = JSONObject((it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            .getJSONArray("messages").objects()
        fun readCall(id: String, start: Long, size: Int) = JSONObject()
            .put("reasoning_content", "Read the requested byte range.")
            .put("tool_calls", JSONArray().put(
                JSONObject().put("index", 0).put("id", id).put("type", "function").put("function",
                    JSONObject().put("name", WorkspaceToolDefinitions.Read)
                        .put("arguments", """{"path":"stream.txt","byte_offset":$start,"max_bytes":$size}"""))))
        val delta = when (step++) {
            0 -> readCall("m04-head", 0, 9_000)
            1 -> {
                if (delayAfterHeadReadMs > 0L) delay(delayAfterHeadReadMs)
                readCall("m04-tail", 199_997, 32)
            }
            else -> {
                if (delayBeforeFinalReplyMs > 0L) delay(delayBeforeFinalReplyMs)
                val tailText = JSONObject(messages.single { m -> m.optString("tool_call_id") == "m04-tail" }.getString("content"))
                    .getString("text")
                JSONObject().put("content", "只读检查完成：开头为字符 x 填充，文件末尾为 $tailText。")
            }
        }
        val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", if (delta.has("tool_calls")) "tool_calls" else "stop")))
            .put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 10))
        respond("data: $event\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
}
