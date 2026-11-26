package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AtomicFile
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.chat.ContextLimits
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.di.AppModule
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.ContextNote
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskContextTools
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.chat.TaskSummary
import com.flowchat.app.domain.device.UnavailableDeviceAssistantGateway
import com.flowchat.app.domain.model.*
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.EntryPointAccessors
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic source and isolated conversation. The official case leaves all model decisions live. */
class ArchivedDocumentFlashAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val question = "刚才那份公告的许可编号是什么？"

    @Test fun offlinePreflight() = runBlocking<Unit> {
        assumeTrue(args.getString("m03Sample") != null)
        exercise(official = false)
    }

    @Test fun officialFlash() = runBlocking<Unit> {
        assumeTrue(args.getString("runM03OfficialFlash") == "true")
        exercise(official = true)
    }

    private suspend fun exercise(official: Boolean) {
        val sample = requireNotNull(args.getString("m03Sample"))
        check(UUID.fromString(sample).toString() == sample)
        val permit = "北岸-${sample.take(8)}"
        val original = "请用一句话概述下面这份公告的开放时段。\n" +
            "开放时间：周二至周日 09:00–17:00，周一闭馆。\n" +
            archivedAnnouncement(permit).substringAfter('\n')
        val factOffset = original.indexOf(permit)
        check(original.indexOf(permit, factOffset + 1) == -1 && factOffset in 4_000 until 8_000 &&
            permit !in CheckpointCodec.excerpt(original, 6_000))
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "d3-m03-${if (official) "flash" else "dry"}-$sample")
        check(output.mkdir()) { "This sample directory already exists" }
        val result = JSONObject().put("sample", sample).put("official", official).put("passed", false)
            .put("source_chars", original.length).put("expected_fact_offset", factOffset)
        val (provider, key) = if (official) loadOfficialFlashProvider(context) else ProviderConfig(id = "m03-offline",
            displayName = "M03 offline fixture", baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash") to "mock-only"
        val fixture = AutonomyRuntimeFixture(context, providerOverride = provider, apiKeyOverride = key,
            contextLimits = ContextLimits(triggerTurns = 1, keepTurns = 1, summaryTimeoutMillis = 120_000),
            gatewayOverride = UnavailableDeviceAssistantGateway, name = "autonomyqa-m03-${if (official) "flash" else "dry"}-$sample")
        val json = AppModule.provideJson()
        val http = if (official) AppModule.provideHttpClient(json) else AppModule.createProviderHttpClient(json, ArchiveMockEngine())
        val requestEvidence = ArchiveRequestEvidence(question, permit)
        val wire = FlashLedger(http, output, sample, official, requestEvidence::describe)
        var activity: MainActivity? = null
        try {
            fixture.settings.setPowerModeEnabled(false)
            fixture.settings.setUsageTrackingEnabled(true)
            val actual = KtorOpenAiCompatibleClient(http, json).apply {
                usageTracker = UsageTracker(fixture.database, fixture.settings)
            }
            fixture.externalClient = wire.track(actual, fixture)
            fixture.create()
            report("launching_chat official=$official")
            val chat = launchM03Chat(context, automation)
            activity = chat
            withContext(Dispatchers.Main) {
                val viewModel = ChatViewModel(fixture.runtime)
                chat.setContent {
                    FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel) }
                }
            }
            report("chat_ready official=$official")
            val summaryMillis = sendUsingChat(fixture, original)
            val summary = fixture.chats.getMessages(fixture.owner).last().content
            result.put("first_summary", summary).put("summary_ms", summaryMillis)
            assertFalse("The opening-hours summary must omit the unrelated identifier", permit in summary)
            assertTrue("The summary must give both opening and closing times",
                Regex("(?<!\\d)(?:0?9|九)\\s*[：:点时]").containsMatchIn(summary) &&
                    Regex("(?<!\\d)(?:17|十七|下午\\s*[5五])\\s*[：:点时]").containsMatchIn(summary))
            val elapsed = sendUsingChat(fixture, question)
            val rows = fixture.chats.getMessages(fixture.owner)
            val source = rows.first { it.role == MessageRole.User }
            val answer = rows.last()
            val checkpoint = requireNotNull(fixture.chats.getContextCheckpoint(fixture.owner))
            val trace = rows.flatMap { row -> row.executionContext?.let { Json.decodeFromString<List<ExecutionStep>>(it) }.orEmpty() }
            val pages = trace.filter { it.tool == TaskContextTools.Read }.flatMap { step ->
                val response = runCatching { JSONObject(step.result) }.getOrNull() ?: return@flatMap emptyList()
                val records = response.getJSONArray("records")
                (0 until records.length()).map { records.getJSONObject(it) }
            }
            val originalPages = pages.filter { it.optString("id") == source.id }
            val factPage = originalPages.singleOrNull { permit in it.optString("content") }
            result.put("followup_ms", elapsed).put("answer", answer.content)
                .put("source_id", source.id).put("checkpoint_source_sha256", checkpoint.sourceHash)
                .put("original_read_offsets", JSONArray(originalPages.map { it.opt("content_offset") ?: JSONObject.NULL }))
                .put("fact_page_offset", factPage?.optInt("content_offset", -1) ?: -1)
                .put("tools", JSONArray(trace.map { it.tool })).put("confirmation_count", fixture.confirmations.size)
                .put("manual_recovery_steps", 0)
            assertTrue(checkpoint.matches(rows) && source.id in checkpoint.originalRequest.sources)
            assertFalse("The compacted checkpoint must omit the fact", permit in checkpoint.asContext())
            assertEquals(original, source.content)
            assertEquals(listOf(original, question), rows.filter { it.role == MessageRole.User }.map { it.content })
            assertEquals(MessageStatus.Complete, answer.status)
            assertTrue("Answer did not contain the archived identifier", permit in answer.content)
            assertNotNull("The original paged source did not contain the reported fact", factPage)
            assertTrue(factPage!!.has("content_offset") && factPage.getInt("content_offset") <= factOffset)
            assertTrue(trace.isNotEmpty() && trace.all { it.tool == TaskContextTools.Read && it.outcome == "returned" })
            assertTrue(fixture.confirmations.isEmpty())
            val run = checkNotNull(fixture.journal.latest(fixture.owner))
            assertEquals("Completed", run.state)
            val metadata = checkNotNull(TaskRunMetadata.decode(run.metadataJson))
            assertTrue(metadata.effects.isEmpty() && metadata.pendingEffects.isEmpty())
            assertTrue(requestEvidence.checkpointSent && requestEvidence.firstFollowUpStarted && requestEvidence.sawFactInToolResult)
            if (!official) assertEquals(5, wire.count)
            val answerNodes = compose.onAllNodesWithText(permit, substring = true, useUnmergedTree = true)
            assertTrue(answerNodes.fetchSemanticsNodes().indices.any { answerNodes[it].isDisplayed() })
            val screenshot = requireNotNull(automation.takeScreenshot())
            File(output, "answer.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshot.recycle()
            result.put("passed", true).put("business_writes", 0)
            report("passed official=$official followup_ms=$elapsed")
        } finally {
            try {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { activity?.finish() }
                    fixture.close(true)
                }
            } finally {
                http.close()
                result.put("official_requests", if (official) wire.count else 0)
                    .put("mock_requests", if (official) 0 else wire.count)
                File(output, "result.json").writeText(result.toString(2))
            }
        }
    }

    private fun report(stage: String) =
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nM03_FLASH $stage\n") })

    private suspend fun sendUsingChat(fixture: AutonomyRuntimeFixture, text: String): Long {
        val previous = fixture.journal.latest(fixture.owner)?.id
        withContext(Dispatchers.Main) { fixture.runtime.updateInput(text) }
        val started = SystemClock.elapsedRealtime()
        compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
        fixture.await {
            val run = fixture.journal.latest(fixture.owner)
            run != null && run.id != previous && run.state !in setOf("Running", "WaitingForUser") &&
                fixture.owner !in fixture.runtime.activeConversations.value
        }
        val run = checkNotNull(fixture.journal.latest(fixture.owner))
        assertEquals(fixture.chats.getMessages(fixture.owner).lastOrNull()?.content, "Completed", run.state)
        return SystemClock.elapsedRealtime() - started
    }

    private class ArchiveMockEngine : HttpClientEngineFactory<MockEngineConfig> {
        private var round = 0
        override fun create(block: MockEngineConfig.() -> Unit): MockEngine = MockEngine {
            val body = (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val messages = JSONObject(body).getJSONArray("messages").objects()
            fun answer(text: String) = JSONObject().put("content", text)
            fun read(id: String, source: String, offset: Int) = JSONObject().put("reasoning_content", "Read the synthetic source page.")
                .put("tool_calls", JSONArray().put(
                JSONObject().put("index", 0).put("id", id).put("type", "function").put("function",
                    JSONObject().put("name", TaskContextTools.Read).put("arguments",
                        JSONObject().put("message_ids", JSONArray(listOf(source))).put("content_offset", offset).toString()))))
            fun page(id: String) = JSONObject(messages.single { it.optString("tool_call_id") == id }.getString("content"))
                .getJSONArray("records").getJSONObject(0)
            val delta = when (++round) {
                1 -> answer("场馆每周二至周日 09:00–17:00 开放，周一闭馆。")
                2 -> {
                    val userRecord = JSONObject(messages.last().getString("content")).getJSONArray("new_records")
                        .objects().first { it.getString("role") == "User" }
                    val objective = ContextNote("概括场馆开放时段", listOf(userRecord.getString("id")))
                    answer(CheckpointCodec.json.encodeToString(TaskSummary(objective)))
                }
                3 -> {
                    val window = messages.first { it.optString("content").contains("Conversation task checkpoint") }
                    val source = checkNotNull(CheckpointCodec.decode(window.getString("content").substringAfterLast('\n')))
                        .originalRequest.sources.single()
                    read("page-0", source, 0)
                }
                4 -> page("page-0").let { read("page-next", it.getString("id"), it.getInt("next_content_offset")) }
                5 -> {
                    val fact = checkNotNull(Regex("许可编号为([^。]+)。").find(page("page-next").getString("content")))
                    answer("许可编号是${fact.groupValues[1]}。")
                }
                else -> error("Unexpected simulated request $round")
            }
            val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", if (delta.has("tool_calls")) "tool_calls" else "stop")))
                .put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 10))
            respond("data: $event\n\ndata: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
    }
}

internal suspend fun launchM03Chat(context: Context, automation: UiAutomation): MainActivity {
    // OriginOS can leave Instrumentation.startActivitySync waiting for an activity that never resumes.
    val launch = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
        "am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}"
    )).bufferedReader().use { it.readText() }
    check(launch.contains("Status: ok")) { launch }
    return withTimeout(15_000) {
        var resumed: MainActivity?
        do {
            resumed = withContext(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing && !it.isDestroyed }
            }
            if (resumed == null) delay(50)
        } while (resumed == null)
        requireNotNull(resumed)
    }
}

internal suspend fun loadOfficialFlashProvider(context: Context): Pair<ProviderConfig, String> {
    val settings = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java).settings()
    val selection = checkNotNull(settings.state.value.assistantModelSelection)
    check(selection.providerId == "provider-deepseek" && selection.modelName == "deepseek-flash")
    val providers = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java).providers()
    val provider = checkNotNull(providers.getProvider(selection.providerId))
    val endpoint = URI(provider.baseUrl)
    check(provider.isEnabled && endpoint.scheme == "https" && endpoint.host == "api.deepseek.com")
    val key = checkNotNull(providers.getApiKey(provider)).also { check(it.isNotBlank()) }
    return provider.copy(defaultModel = selection.modelName) to key
}

internal fun JSONArray.objects() = (0 until length()).map(::getJSONObject)

private class ArchiveRequestEvidence(private val question: String, private val permit: String) {
    var checkpointSent = false; private set
    var firstFollowUpStarted = false; private set
    var sawFactInToolResult = false; private set

    fun describe(payload: JSONObject, writer: Boolean): JSONObject {
        val rows = payload.getJSONArray("messages").objects()
        val names = (payload.optJSONArray("tools") ?: JSONArray()).objects().map { it.getJSONObject("function").getString("name") }
        val latestUser = rows.lastOrNull { it.getString("role") == "user" }?.optString("content").orEmpty()
        val followingUp = latestUser == question
        val toolFact = rows.any { it.getString("role") == "tool" && permit in it.optString("content") }
        val factInPayload = permit in payload.toString()
        if (writer) {
            check(!factInPayload && names.isEmpty())
            checkpointSent = true
        } else if (followingUp && !firstFollowUpStarted) {
            check(checkpointSent && !factInPayload && TaskContextTools.Read in names &&
                rows.any { it.optString("content").contains("Conversation task checkpoint") })
            firstFollowUpStarted = true
        }
        sawFactInToolResult = sawFactInToolResult || (followingUp && toolFact)
        val stage = if (writer) "checkpoint" else if (followingUp) "followup" else "initial_summary"
        return JSONObject().put("stage", stage).put("fact_in_tool_result", toolFact).put("fact_in_payload", factInPayload)
    }
}

internal class FlashLedger(http: HttpClient, output: File, sample: String, official: Boolean,
                          describe: (JSONObject, Boolean) -> JSONObject, maxRequests: Int = 8,
                          onRequestSaved: (Int, Boolean) -> Unit = { _, _ -> },
                          maxInputReserved: Int = 260_000, maxOutputReserved: Int = 524_288) {
    private val disk = AtomicFile(File(output, if (official) "usage-ledger.json" else "mock-wire-ledger.json"))
    private val entries = JSONArray()
    private val record = JSONObject().put("sample", sample).put("host", "api.deepseek.com").put("model", "deepseek-flash")
        .put("max_requests", maxRequests).put("max_input_reserved", maxInputReserved).put("max_output_reserved", maxOutputReserved)
        .put("transport", if (official) "official_https" else "mock_engine_no_network")
        .put("requests_detail", entries)
    val count: Int get() = entries.length()

    init {
        save()
        http.plugin(HttpSend).intercept { call ->
            check(call.url.protocol == URLProtocol.HTTPS && call.url.host == "api.deepseek.com" &&
                call.url.build().encodedPath.endsWith("/chat/completions"))
            val body = (call.body as TextContent).text
            val payload = JSONObject(body)
            check(payload.getString("model") == "deepseek-flash")
            val rows = payload.getJSONArray("messages").objects()
            val tools = payload.optJSONArray("tools") ?: JSONArray()
            val names = tools.objects().map { it.getJSONObject("function").getString("name") }
            val writer = rows.first().optString("content").startsWith("# Floryx context checkpoint writer")
            val thinking = payload.getJSONObject("thinking").getString("type")
            val reserve = body.toByteArray(Charsets.UTF_8).size + 4_096
            val outputReserve = payload.getInt("max_tokens")
            check(payload.getJSONObject("stream_options").getBoolean("include_usage"))
            check(thinking == if (writer) "disabled" else "enabled")
            check(count < record.getInt("max_requests"))
            check(total("input_accounted") + reserve <= record.getInt("max_input_reserved"))
            check(outputReserve in 1..65_536 && total("output_accounted") + outputReserve <= record.getInt("max_output_reserved"))
            entries.put(describe(payload, writer).put("sequence", count + 1).put("tools", JSONArray(names))
                .put("thinking", thinking)
                .put("input_reserved", reserve).put("output_reserved", outputReserve)
                .put("input_accounted", reserve).put("output_accounted", outputReserve))
            save()
            onRequestSaved(count, writer)
            execute(call)
        }
    }

    fun track(actual: ChatCompletionClient, fixture: AutonomyRuntimeFixture) = object : ChatCompletionClient {
        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
            check(request.model == "deepseek-flash" && provider == fixture.provider && !apiKey.isNullOrBlank())
            val before = fixture.database.usageDao().observe(fixture.owner).first().map { it.id }.toSet()
            val previous = count
            try {
                emitAll(actual.streamChat(request, provider, apiKey))
            } finally {
                withContext(NonCancellable) {
                    if (count > previous) {
                        val usage = fixture.database.usageDao().observe(fixture.owner).first().singleOrNull { it.id !in before }
                        val entry = entries.getJSONObject(count - 1)
                        if (usage?.inputTokens != null && usage.outputTokens != null) {
                            entry.put("reported_input", usage.inputTokens).put("reported_output", usage.outputTokens)
                                .put("input_accounted", usage.inputTokens).put("output_accounted", usage.outputTokens)
                        }
                        save()
                    }
                }
            }
        }
    }

    private fun total(field: String) = (0 until count).sumOf { entries.getJSONObject(it).getInt(field) }

    private fun save() {
        record.put("requests", count).put("input_accounted", total("input_accounted"))
            .put("output_accounted", total("output_accounted"))
        val stream = disk.startWrite()
        try {
            stream.write(record.toString(2).toByteArray(Charsets.UTF_8))
            disk.finishWrite(stream)
        } catch (error: Throwable) {
            disk.failWrite(stream)
            throw error
        }
    }
}
