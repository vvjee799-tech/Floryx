package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.chat.ContextLimits
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.data.workspace.WorkspaceManager
import com.flowchat.app.di.AppModule
import com.flowchat.app.domain.chat.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

private val sampleRanges = listOf(
    TaskInformationRange(0, 9_000), TaskInformationRange(9_000, 18_000),
    TaskInformationRange(8_710, 8_838), TaskInformationRange(199_997, 200_012)
)
private const val instruction = "帮我对工作区里的 stream.txt（200012 字节）做只读抽检。按顺序分别读取四段，字节偏移从 0 开始，右端不含。" +
    "先读 [0,9000) 核对填充内容，再读 [9000,18000) 看是否一致，然后单独复读 [8710,8838) 作为抽查，最后读 [199997,200012)。" +
    "每次只读取一段，收到当前段的工具结果后才读取下一段。" +
    "请实际读取这四段，不要执行或改动文件。告诉我前三段的填充字符和文件末尾的中文原文。" +
    "请说明第二段实际返回了多少字节，以及该段内部有没有未返回的字节。"

/** The real model chooses how to complete a read-only audit against the existing pipe fixture. */
class WorkspaceCompactionFlashAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test fun offlinePreflight() = runBlocking<Unit> {
        assumeTrue(args.getString("m03Sample") != null)
        exercise(false)
    }

    @Test fun officialFlash() = runBlocking<Unit> {
        assumeTrue(args.getString("runM03OfficialFlash") == "true")
        exercise(true)
    }

    private suspend fun exercise(official: Boolean) {
        val sample = requireNotNull(args.getString("m03Sample"))
        check(UUID.fromString(sample).toString() == sample)
        val mode = if (official) "flash" else "dry"
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "d3-m03-range-$mode-$sample")
        check(output.mkdir())
        val result = JSONObject().put("sample", sample).put("official", official).put("passed", false)
        val (provider, key) = if (official) loadOfficialFlashProvider(context) else ProviderConfig(id = "m03-range-offline",
            displayName = "M03 offline fixture", baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash") to "mock-only"
        val authority = "${context.packageName}.p0c.documents"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "stream")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        fun hasGrant() = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        val originallyGranted = hasGrant()
        val workspace = WorkspaceManager(context)
        val fixture = AutonomyRuntimeFixture(context, providerOverride = provider, apiKeyOverride = key, workspaceOverride = workspace,
            contextLimits = ContextLimits(softTokens = 6_500, targetTokens = 5_000, minGrowthTokens = 500, summaryTimeoutMillis = 120_000),
            gatewayOverride = UnavailableDeviceAssistantGateway, name = "autonomyqa-m03-range-$mode-$sample")
        val json = AppModule.provideJson()
        val http = if (official) AppModule.provideHttpClient(json) else AppModule.createProviderHttpClient(json, RangeMockEngine())
        val requestEvidence = WorkspaceRequestEvidence()
        val wire = FlashLedger(http, output, sample, official, requestEvidence::describe, maxRequests = 11)
        val observations = mutableListOf<ReadObservation>()
        var activity: MainActivity? = null
        var sourceFingerprint: String? = null
        try {
            fixture.settings.setPowerModeEnabled(false)
            fixture.settings.setUsageTrackingEnabled(true)
            val actual = KtorOpenAiCompatibleClient(http, json).apply { usageTracker = UsageTracker(fixture.database, fixture.settings) }
            fixture.externalClient = wire.track(actual, fixture)
            fixture.persistenceObserver = TaskPersistenceObserver { boundary ->
                if (boundary == TaskPersistenceBoundary.ResultMetadataSaved) {
                    val latest = savedSteps(fixture).lastOrNull()
                    if (latest?.tool == WorkspaceToolDefinitions.Read) {
                        val payload = runCatching { JSONObject(latest.result) }.getOrNull()
                        if (payload?.has("byte_start") == true && payload.has("byte_end_exclusive")) {
                            observations.add(ReadObservation(
                                TaskInformationRange(payload.getLong("byte_start"), payload.getLong("byte_end_exclusive")),
                                metadata(fixture), fixture.chats.getContextCheckpoint(fixture.owner)?.sourceHash))
                        }
                    }
                }
            }
            if (!originallyGranted) context.grantUriPermission(context.packageName, tree,
                read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            val location = workspace.select(tree.toString(), read)
            sourceFingerprint = documentFingerprint(document)
            result.put("source_sha256", sourceFingerprint).put("source_bytes", 200_012)
            fixture.create()
            fixture.chats.updateConversationWorkspace(fixture.owner, location)
            fixture.await { fixture.runtime.uiState.value.currentConversation?.workspace == location }
            report("launching_chat official=$official")
            val chat = launchM03Chat(context, automation)
            activity = chat
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                chat.setContent {
                    FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) }
                }
                fixture.runtime.updateInput(instruction)
            }
            report("chat_ready official=$official")
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
            fixture.await {
                val run = fixture.journal.latest(fixture.owner)
                run != null && run.state !in setOf("Running", "WaitingForUser") && fixture.owner !in fixture.runtime.activeConversations.value
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val run = checkNotNull(fixture.journal.latest(fixture.owner))
            val rows = fixture.chats.getMessages(fixture.owner)
            val answer = rows.last()
            val checkpoint = fixture.chats.getContextCheckpoint(fixture.owner)
            val steps = savedSteps(fixture)
            result.put("elapsed_ms", elapsed).put("answer", answer.content).put("state", run.state)
                .put("checkpoint", checkpoint?.sourceHash).put("tools", JSONArray(steps.map { it.tool }))
                .put("early_range_archived", requestEvidence.earlyRangeArchived)
                .put("second_range_in_request", requestEvidence.secondRange)
                .put("confirmation_count", fixture.confirmations.size).put("manual_recovery_steps", 0)
            assertEquals(answer.content, "Completed", run.state)
            assertEquals(MessageStatus.Complete, answer.status)
            assertTrue("The final answer must report the observed padding and Chinese ending",
                "中文末尾" in answer.content && 'x' in answer.content.lowercase())
            assertTrue("The final answer must report the second range's byte count", Regex("9[,， ]?000").containsMatchIn(answer.content))
            val visible = compose.onAllNodesWithText("中文末尾", substring = true, useUnmergedTree = true)
            assertTrue(visible.fetchSemanticsNodes().indices.any { visible[it].isDisplayed() })
            val screenshot = requireNotNull(automation.takeScreenshot())
            try {
                File(output, "answer.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                screenshot.recycle()
            }
            assertTrue(checkpoint != null && checkpoint.matches(rows))
            val firstRead = rows.indexOfFirst { row -> row.executionContext?.contains(WorkspaceToolDefinitions.Read) == true }
            assertTrue(firstRead >= 0 && rows.indexOfFirst { it.id == checkpoint!!.throughMessageId } >= firstRead)
            assertTrue("No outbound archived window dropped the early native read", requestEvidence.earlyRangeArchived)
            val reads = steps.filter { it.tool == WorkspaceToolDefinitions.Read }.map { JSONObject(it.result) }
            assertEquals(sampleRanges, observations.map { it.range })
            assertTrue(reads.take(3).all { it.getString("text").let { text -> text.isNotEmpty() && text.all { it == 'x' } } })
            val second = checkNotNull(requestEvidence.secondRange)
            assertEquals("contiguous", second.getString("range_coverage"))
            assertEquals(9_000L, second.getLong("bytes_returned"))
            assertEquals(9_000L, second.getLong("text_utf8_bytes"))
            assertEquals(8_997L, second.getLong("skipped_bytes"))
            assertEquals(9_004L, second.getLong("source_bytes_read"))
            assertEquals(18_000L, second.getLong("next_byte_offset"))
            assertEquals(182_012L, second.getLong("source_bytes_after_range"))
            assertTrue(second.getBoolean("truncated"))
            assertEquals("xxx中文末尾", reads.last().getString("text"))
            assertTrue(reads.last().getBoolean("eof"))
            assertTrue(steps.all { it.tool in WorkspaceToolDefinitions.ReadOnlyNames && it.outcome == "returned" })
            val head = listOf(TaskInformationRange(0, 18_000))
            val before = observations[1].metadata
            val overlap = observations[2]
            val tail = observations[3].metadata
            assertEquals(head, before.informationProgress.sources.single().ranges)
            assertNotNull("Overlap must run after a real checkpoint", overlap.checkpoint)
            assertEquals(before.informationProgress, overlap.metadata.informationProgress)
            assertEquals(before.lastBudgetEvidenceRef, overlap.metadata.lastBudgetEvidenceRef)
            assertEquals(requireNotNull(before.budget).noProgressCount + 1, requireNotNull(overlap.metadata.budget).noProgressCount)
            val expectedFinal = head + sampleRanges.last()
            val source = tail.informationProgress.sources.single()
            assertEquals(expectedFinal, source.ranges)
            assertEquals(before.informationProgress.sources.single().versionSha256, source.versionSha256)
            assertEquals(0, requireNotNull(tail.budget).noProgressCount)
            assertEquals(tail.informationProgress, metadata(fixture).informationProgress)
            assertTrue(tail.effects.isEmpty() && tail.pendingEffects.isEmpty() && fixture.confirmations.isEmpty())
            result.put("passed", true).put("business_writes", 0)
            report("passed official=$official elapsed_ms=$elapsed")
        } finally {
            try {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { activity?.finish() }
                    fixture.close(true)
                }
                if (sourceFingerprint != null) {
                    val unchanged = sourceFingerprint == documentFingerprint(document)
                    result.put("source_unchanged", unchanged)
                    assertTrue("The synthetic document changed during the audit", unchanged)
                }
            } finally {
                if (!originallyGranted) {
                    if (hasGrant()) context.contentResolver.releasePersistableUriPermission(tree, read)
                    context.revokeUriPermission(context.packageName, tree, read)
                }
                result.put("original_grant_restored", originallyGranted == hasGrant())
                    .put("reads", JSONArray(observations.map { it.toJson() }))
                    .put("official_requests", if (official) wire.count else 0).put("mock_requests", if (official) 0 else wire.count)
                http.close()
                File(output, "result.json").writeText(result.toString(2))
                assertEquals(originallyGranted, hasGrant())
            }
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

    private fun report(stage: String) =
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nM03_FLASH $stage\n") })
}

private data class ReadObservation(val range: TaskInformationRange, val metadata: TaskRunMetadata, val checkpoint: String?) {
    private val source get() = metadata.informationProgress.sources.singleOrNull()
    fun toJson(): JSONObject = JSONObject().put("range", range.json()).put("checkpoint", checkpoint ?: JSONObject.NULL)
        .put("coverage", JSONArray(source?.ranges.orEmpty().map { it.json() }))
        .put("version", source?.versionSha256 ?: JSONObject.NULL)
        .put("last_progress", metadata.lastBudgetEvidenceRef ?: JSONObject.NULL)
        .put("no_progress", metadata.budget?.noProgressCount)
}

private fun TaskInformationRange.json() = JSONArray(listOf(start, end))

private class WorkspaceRequestEvidence {
    private var earlyRangeSent = false
    var earlyRangeArchived = false; private set
    var secondRange: JSONObject? = null; private set

    fun describe(payload: JSONObject, writer: Boolean): JSONObject {
        val messages = payload.getJSONArray("messages").objects()
        val checkpoint = messages.any { "Conversation task checkpoint" in it.optString("content") }
        val ranges = messages.filter { it.optString("role") == "tool" }.mapNotNull { tool ->
            val content = runCatching { JSONObject(tool.getString("content")) }.getOrNull() ?: return@mapNotNull null
            if (!content.has("byte_start") || !content.has("byte_end_exclusive")) return@mapNotNull null
            val range = TaskInformationRange(content.getLong("byte_start"), content.getLong("byte_end_exclusive"))
            if (!writer && secondRange == null && range == sampleRanges[1]) {
                secondRange = JSONObject(content, arrayOf("byte_start", "byte_end_exclusive", "bytes_returned", "range_coverage",
                    "skipped_bytes", "source_bytes_read", "next_byte_offset", "source_bytes_after_range", "truncated"))
                    .put("text_utf8_bytes", content.getString("text").encodeToByteArray().size)
            }
            range
        }
        if (!writer) {
            val includesEarly = sampleRanges.first() in ranges
            if (checkpoint && earlyRangeSent && !includesEarly) earlyRangeArchived = true
            earlyRangeSent = earlyRangeSent || includesEarly
        }
        return JSONObject().put("stage", if (writer) "checkpoint" else "audit")
            .put("checkpoint_in_payload", checkpoint).put("native_byte_ranges", JSONArray(ranges.map { it.json() }))
    }
}

private class RangeMockEngine : HttpClientEngineFactory<MockEngineConfig> {
    private var round = 0
    override fun create(block: MockEngineConfig.() -> Unit): MockEngine = MockEngine {
        val messages = JSONObject((it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            .getJSONArray("messages").objects()
        val delta = when {
            messages.first().getString("content").startsWith("# Floryx context checkpoint writer") -> {
                val input = JSONObject(messages.last().getString("content"))
                val archived = input.getJSONArray("new_records").objects()
                val source = archived.firstOrNull { it.optString("role") == "User" }?.getString("id")
                    ?: input.optJSONObject("previous_checkpoint")?.getJSONObject("summary")
                        ?.getJSONObject("objective")?.getJSONArray("sources")?.getString(0)
                    ?: archived.first().getString("id")
                JSONObject().put("content", CheckpointCodec.json.encodeToString(TaskSummary(
                    ContextNote("只读抽检隔离文件的四段内容", listOf(source)))))
            }
            round < sampleRanges.size -> {
                val range = sampleRanges[round++]
                val arguments = JSONObject().put("path", "stream.txt").put("byte_offset", range.start)
                    .put("max_bytes", range.end - range.start)
                val call = JSONObject().put("index", 0).put("id", "m03-range-$round").put("type", "function")
                    .put("function", JSONObject().put("name", WorkspaceToolDefinitions.Read).put("arguments", arguments.toString()))
                JSONObject().put("reasoning_content", "Read the next audit range.").put("tool_calls", JSONArray(listOf(call)))
            }
            else -> {
                val ending = JSONObject(messages.single { it.optString("tool_call_id") == "m03-range-4" }.getString("content"))
                    .getString("text").removePrefix("xxx")
                val second = messages.singleOrNull { it.optString("tool_call_id") == "m03-range-2" }
                val range = second?.let { JSONObject(it.getString("content")) }
                val bytes = range?.getLong("bytes_returned") ?: (sampleRanges[1].end - sampleRanges[1].start)
                JSONObject().put("content", "前三段都是 x，第二段返回 $bytes 字节，区间内没有缺口。文件末尾是xxx$ending。")
            }
        }
        val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", if (delta.has("tool_calls")) "tool_calls" else "stop")))
            .put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 10))
        respond("data: $event\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
}
