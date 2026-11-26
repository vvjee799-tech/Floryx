package com.flowchat.app.device

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.network.*
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.di.AppModule
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.assistant.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only. Real production HTTP/serialization/runtime; no scripted phone decisions. */
class FlashBaselineDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val directory get() = File(context.getExternalFilesDir(null), "flash-d1").apply { mkdirs() }
    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nFLASH_D1 $text\n") })

    internal class Meter(private val fixture: AutonomyRuntimeFixture, private val actual: ChatCompletionClient,
        private val key: String, private val directory: File,
        private val maxInput: Long = 1_000_000, private val maxOutput: Long = 64_000,
        private val maxRequests: Int = 120,
        private val restrictTools: Boolean = true,
        private val inspectRequest: (ChatRequest, Long, Long) -> Unit = { _, _, _ -> },
        private val inspectToolCalls: (ChatRequest, List<ChatToolCall>) -> Unit = { _, _ -> }
    ) : ChatCompletionClient {
        private val ledgerFile = File(directory, "usage-ledger.json")
        private val ledger = if (ledgerFile.exists()) JSONObject(ledgerFile.readText()) else JSONObject()
            .put("requests", 0).put("input_accounted", 0L).put("output_accounted", 0L).put("requests_detail", JSONArray())
        var sample = "probe"
        var modelMillis = 0L
        var firstToolAt: Long? = null
        private val allowed = setOf("observe_screen", "wait_for_screen", "tap_ui_element", "tap_ui_target", "tap_visual_target",
            "input_text", "set_text_selection", "perform_editor_action", "scroll_container", "swipe_screen", "press_back", "press_home",
            "open_app", "get_device_status", "get_foreground_app", "open_calendar_editor", "task_effect_save_calendar_event", "read_test_value")
        private fun save() = ledgerFile.writeText(ledger.toString(2))

        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
            check(provider == fixture.provider && apiKey == key && request.model == provider.defaultModel)
            check(request.enableThinking && request.thinkingEffort == ThinkingEffort.Auto) { "Approved thinking configuration changed" }
            val sent = request.copy(maxTokens = minOf(request.maxTokens ?: 8192, 8192),
                tools = if (restrictTools) request.tools.filter { it.name in allowed } else request.tools)
            val images = sent.messages.sumOf { it.images.size }
            // UTF-8 bytes overcount text BPE; reserve protocol overhead plus the documented 1024/image ceiling.
            val noImages = sent.copy(messages = sent.messages.map { it.copy(images = emptyList()) })
            val inputReserve = Json.encodeToString(noImages.toOpenAiRequest(provider)).toByteArray().size.toLong() + 4096L + images * 1024L
            val outputReserve = requireNotNull(sent.maxTokens).toLong()
            inspectRequest(sent, inputReserve, outputReserve)
            val requests = ledger.getInt("requests")
            val inputBefore = ledger.getLong("input_accounted")
            val outputBefore = ledger.getLong("output_accounted")
            check(requests < maxRequests && inputBefore + inputReserve <= maxInput && outputBefore + outputReserve <= maxOutput) { "Authorized model usage exhausted before dispatch" }
            ledger.put("requests", requests + 1).put("input_accounted", inputBefore + inputReserve)
                .put("output_accounted", outputBefore + outputReserve)
            val row = JSONObject().put("sample", sample).put("sequence", requests + 1).put("images", images)
                .put("input_reserved", inputReserve).put("output_reserved", outputReserve)
                .put("model", sent.model).put("thinking", sent.thinkingEffort.wireValue)
                .put("previous_reasoning_chars", sent.messages.sumOf { it.reasoningContent.orEmpty().length })
                .put("tools", JSONArray(sent.tools.map { it.name }))
            ledger.getJSONArray("requests_detail").put(row); save()
            val owner = requireNotNull(sent.usageConversationId)
            val old = fixture.database.usageDao().observe(owner).first().map { it.id }.toSet()
            val started = SystemClock.elapsedRealtime()
            try {
                actual.streamChat(sent, provider, apiKey).collect { event ->
                    if (event is ChatDelta.ToolCalls) {
                        check(!restrictTools || event.calls.all { it.name in allowed }) { "Model requested an out-of-scope tool" }
                        if (firstToolAt == null) firstToolAt = SystemClock.elapsedRealtime()
                        row.put("tool_calls", JSONArray(event.calls.map { JSONObject().put("id", it.id).put("name", it.name) }))
                        inspectToolCalls(sent, event.calls)
                    }
                    emit(event)
                }
            } finally {
                val duration = SystemClock.elapsedRealtime() - started
                modelMillis += duration; row.put("elapsed_ms", duration)
                val usage = fixture.database.usageDao().observe(owner).first().singleOrNull { it.id !in old }
                if (usage?.inputTokens != null && usage.outputTokens != null) {
                    ledger.put("input_accounted", ledger.getLong("input_accounted") - inputReserve + usage.inputTokens)
                        .put("output_accounted", ledger.getLong("output_accounted") - outputReserve + usage.outputTokens)
                    row.put("reported_input", usage.inputTokens).put("reported_output", usage.outputTokens).put("reported_cache_hit", usage.cacheHitTokens)
                } else row.put("usage_unknown_reservation_retained", true)
                save()
            }
        }
    }

    @Test fun approvedOfficialFlashProbesAndThreeNaturalLanguageTasks() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runApprovedFlashBaseline") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvider("provider-deepseek"))
        check(java.net.URI(provider.baseUrl).host == "api.deepseek.com" && provider.defaultModel == "deepseek-flash" && provider.isEnabled)
        val key = requireNotNull(data.providers().getApiKey(provider)) { "Approved provider has no usable key" }
        val fixture = AutonomyRuntimeFixture(context, provider, key)
        val http = AppModule.provideHttpClient(AppModule.provideJson())
        val client = KtorOpenAiCompatibleClient(http, AppModule.provideJson()).apply { usageTracker = UsageTracker(fixture.database, fixture.settings) }
        val meter = Meter(fixture, client, key, directory)
        fixture.externalClient = meter
        fixture.settings.setUsageTrackingEnabled(true)
        val results = JSONObject().put("provider_id", provider.id).put("host", "api.deepseek.com").put("model", provider.defaultModel)
            .put("thinking", "enabled/auto").put("isolated_database", fixture.name).put("cases", JSONArray())
        fun save() = File(directory, "baseline-results.json").writeText(results.toString(2))
        suspend fun probe(name: String, block: suspend () -> JSONObject) {
            meter.sample = name
            try { results.put(name, block()); report("$name recorded") }
            catch (error: Exception) { results.put(name, JSONObject().put("passed", false).put("error", error.message)); report("$name failed: ${error.javaClass.simpleName}") }
            save()
        }
        try {
            ActionOutcomeServiceReadiness().ensureService()
            fixture.connect(); fixture.create()
            suspend fun collect(messages: List<ChatRequestMessage>, tools: List<ChatToolDefinition> = emptyList()) =
                meter.streamChat(ChatRequest(provider.defaultModel, messages, 0.7, 1.0, 8192, true,
                    tools = tools, thinkingEffort = ThinkingEffort.Auto, usageConversationId = fixture.owner), provider, key).toList()
            fun answer(events: List<ChatDelta>) = events.filterIsInstance<ChatDelta.Content>().joinToString("") { it.text }
            probe("text") {
                val events = collect(listOf(ChatRequestMessage("user", "请只回复数字 7。")))
                JSONObject().put("passed", answer(events).trim() == "7").put("answer", answer(events))
                    .put("streamed_content_chunks", events.count { it is ChatDelta.Content }).put("reasoning_received", events.any { it is ChatDelta.Reasoning })
                    .put("errors", JSONArray(events.filterIsInstance<ChatDelta.Error>().map { it.message }))
            }
            probe("image") {
                val nonce = UUID.randomUUID().toString().take(6).uppercase()
                val bitmap = Bitmap.createBitmap(420, 240, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(nonce, 48f, 142f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.MONOSPACE }) }
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
                File(directory, "random-image.png").writeBytes(bytes)
                val events = collect(listOf(ChatRequestMessage("user", "请只抄写图片中的六位字符。", images = listOf(ChatImage("image/png", Base64.encodeToString(bytes, Base64.NO_WRAP))))))
                JSONObject().put("passed", answer(events).contains(nonce)).put("expected", nonce).put("answer", answer(events))
                    .put("errors", JSONArray(events.filterIsInstance<ChatDelta.Error>().map { it.message }))
            }
            probe("tool_continuation") {
                val tool = ChatToolDefinition("read_test_value", "Read a random value from the test source.", buildJsonObject { put("type", "object"); putJsonObject("properties") {} })
                val user = ChatRequestMessage("user", "请调用 read_test_value 读取测试值，然后只回复返回的值。")
                val first = collect(listOf(user), listOf(tool))
                val calls = first.filterIsInstance<ChatDelta.ToolCalls>().flatMap { it.calls }
                check(calls.size == 1 && calls.single().name == tool.name) { "No valid native tool call" }
                val value = "TEST-${UUID.randomUUID()}"
                val assistant = ChatRequestMessage("assistant", answer(first),
                    reasoningContent = first.filterIsInstance<ChatDelta.Reasoning>().joinToString("") { it.text }, toolCalls = calls,
                    providerData = first.filterIsInstance<ChatDelta.ProviderData>().lastOrNull()?.blocks)
                val next = collect(listOf(user, assistant, ChatRequestMessage("tool", value, toolCallId = calls.single().id)), listOf(tool))
                JSONObject().put("passed", answer(next).contains(value)).put("tool_id_paired", true)
                    .put("reasoning_continued", !assistant.reasoningContent.isNullOrEmpty()).put("answer", answer(next))
            }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val title = "Floryx 自动验收 D1-${UUID.randomUUID().toString().take(8)}"
            val calendarArgs = buildJsonObject { put("title", title); put("begin_time", "2026-11-16T15:00:00+08:00"); put("end_time", "2026-11-16T15:30:00+08:00"); put("completion", "save") }.toString()
            CalendarSyntheticLedger.record(context, calendarArgs)
            results.put("calendar_identity", JSONObject(calendarArgs)); save()
            val tasks = listOf(
                Triple("F01", "帮我打开设置里的声音与振动，查看当前媒体音量，不要修改。", "com.android.settings"),
                Triple("F02", "在日历中创建并保存一个标题为“$title”的测试日程，开始为北京时间2026年11月16日15:00，结束15:30，不添加参与人。保存后核对标题和起止时间。", "com.bbk.calendar"),
                Triple("F03", "用手机浏览器打开 https://developer.android.google.cn/guide/topics/ui/accessibility/principles?hl=zh-cn ，读完正文，用中文总结三点，并告诉我原文标题。", "com.android.chrome"))
            for ((id, prompt, app) in tasks) {
                meter.sample = id; meter.firstToolAt = null
                val row = JSONObject().put("case_id", id).put("prompt", prompt).put("mode", "phone-real-model")
                    .put("manual_recovery_steps", 0).put("app_confirmations", 0)
                results.getJSONArray("cases").put(row); save()
                try {
                    // Safe starting pages contain no personal tab/event content. The model supplies all subsequent actions.
                    val launch = when (id) {
                        "F01" -> Intent(android.provider.Settings.ACTION_SETTINGS)
                        "F02" -> Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI).setPackage(app)
                        else -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://developer.android.google.cn/"))
                            .setPackage(app).putExtra(android.provider.Browser.EXTRA_CREATE_NEW_TAB, true)
                    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    row.put("initial_state", when (id) { "F01" -> "settings_home"; "F02" -> "fresh_empty_calendar_editor"; else -> "new_public_android_developer_home_tab" })
                    withContext(Dispatchers.Main) { context.startActivity(launch) }
                    delay(700)
                    fixture.owner = fixture.chats.createConversation(provider.id, provider.defaultModel).id
                    withContext(Dispatchers.Main) { fixture.runtime.selectConversation(fixture.owner) }
                    fixture.await { fixture.runtime.uiState.value.currentConversation?.id == fixture.owner }
                    val started = SystemClock.elapsedRealtime(); val modelBefore = meter.modelMillis
                    val accepted = withContext(Dispatchers.Main) { fixture.runtime.sendFromAssistant(fixture.owner, prompt, destination =
                        AssistantTaskDestination(fixture.owner, AssistantModelSelection(provider.id, provider.defaultModel), provider)) }
                    check(accepted)
                    withTimeout(300_000) {
                        while (fixture.owner in fixture.runtime.activeConversations.value) {
                            if (fixture.confirmations.isNotEmpty()) break
                            delay(100)
                        }
                    }
                    row.put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("model_ms", meter.modelMillis - modelBefore)
                        .put("first_tool_ms", meter.firstToolAt?.minus(started)).put("app_confirmations", fixture.confirmations.size)
                    if (fixture.owner in fixture.runtime.activeConversations.value) withContext(Dispatchers.Main) { fixture.runtime.stopFromAssistant(fixture.owner) }
                    val messages = fixture.chats.getMessages(fixture.owner)
                    File(directory, "$id-messages.json").writeText(JSONArray(messages.map { JSONObject().put("role", it.role.name)
                        .put("content", it.content).put("status", it.status.name).put("execution", it.executionContext) }).toString(2))
                    row.put("runtime_state", fixture.journal.latest(fixture.owner)?.state).put("answer", messages.lastOrNull { it.role == MessageRole.Assistant }?.content)
                    val screen = fixture.real.observeScreen()
                    File(directory, "$id-final-screen.txt").writeText(screen.asToolContent())
                    row.put("final_package", screen.screen?.packageName)
                    when (id) {
                        "F01" -> row.put("volume_before", originalVolume).put("volume_after", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                            .put("volume_unchanged", originalVolume == audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                        "F02" -> {
                            val receipt = fixture.real.inspectCalendarEvents(JsonObject(Json.parseToJsonElement(calendarArgs).jsonObject - "completion").toString())
                            row.put("independent_receipt", receipt?.toString())
                            File(directory, "F02-receipt.txt").writeText(receipt.toString())
                        }
                    }
                    row.put("result", "recorded_requires_independent_review"); report("$id finished; evidence saved")
                } catch (error: Exception) {
                    row.put("result", "failed").put("first_failure", error.message).put("category", error.javaClass.simpleName)
                    withContext(Dispatchers.Main) { fixture.runtime.stopFromAssistant(fixture.owner) }
                    withTimeoutOrNull(10_000) { while (fixture.owner in fixture.runtime.activeConversations.value) delay(50) }
                    report("$id failed: ${error.javaClass.simpleName}")
                }
                fixture.confirmations.clear(); save()
            }
        } finally {
            save(); fixture.close(false); http.close()
        }
    }
}
