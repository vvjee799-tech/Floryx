package com.flowchat.app.device

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.chat.ContextBudget
import com.flowchat.app.data.chat.TaskContextManager
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.DeepSeekContextDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.presentation.chat.ChatViewModel
import dagger.hilt.android.EntryPointAccessors
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Official identity is resolved before a test-only HTTP transport redirects to loopback.
 * No credential is supplied, no production URL rule is relaxed, and no paid API is called.
 */
class DeepSeekLongContextDiagnosticTest {
    @Test fun officialBudgetAcrossRoomVmWireCompactionStopAndRecreation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDeepSeekContextDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val deps = EntryPointAccessors.fromApplication(context, DeepSeekContextDiagnosticsEntryPoint::class.java)
        val model = LocalModel()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val http = HttpClient(CIO) { install(HttpTimeout); install(ContentNegotiation) { json(json) } }
        var redirected = 0
        http.plugin(HttpSend).intercept { request ->
            check(request.url.host == "api.deepseek.com")
            check(request.headers["Authorization"] == null)
            redirected++
            request.url.protocol = URLProtocol.HTTP
            request.url.host = "127.0.0.1"
            request.url.port = model.port
            execute(request)
        }
        val client = KtorOpenAiCompatibleClient(http, json)
        val provider = ProviderConfig(id = "deepseek-context-${UUID.randomUUID()}", displayName = "Synthetic DeepSeek context QA",
            baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash")
        val manager = TaskContextManager(client, data.chats())
        var owner: String? = null
        var vm: ChatViewModel? = null
        var collector: Job? = null
        val samples = java.util.Collections.synchronizedList(mutableListOf<ContextWindowUsage>())
        val measurements = mutableListOf<JsonObject>()
        val report = File(context.getExternalFilesDir(null), "deepseek-context/result.json")
        report.parentFile?.mkdirs()
        suspend fun dispose() {
            collector?.cancelAndJoin(); collector = null
            vm?.let { current -> withContext(Dispatchers.Main) { current.viewModelScope.cancel() } }
            vm = null
        }
        suspend fun open(id: String) {
            vm = withContext(Dispatchers.Main) {
                ChatViewModel(data.chats(), data.providers(), client, deps.appUsage(), deps.launcher(),
                    promptProfileRepository = deps.profiles(), memoryRepository = deps.memories(),
                    appSettingsStore = AppSettingsStore(AppSettings(powerModeEnabled = false, memoryEnabled = false,
                        commandExecutionEnabled = false, installId = "deepseek-context-qa")), taskContextManager = manager)
            }
            val current = requireNotNull(vm)
            collector = launch(Dispatchers.Main) { current.uiState.collect { state ->
                if (state.currentConversation?.id == id) state.contextWindow?.let { samples += it }
            } }
            withContext(Dispatchers.Main) { current.selectConversation(id) }
            withTimeout(20_000) { while (current.uiState.value.currentConversation?.id != id || current.uiState.value.contextWindow == null) delay(25) }
        }
        suspend fun send(text: String, expected: String = "SYNTHETIC_DONE") {
            val current = requireNotNull(vm)
            val id = requireNotNull(owner)
            val before = data.chats().getMessages(id).count { it.role == MessageRole.Assistant && it.content == expected }
            withContext(Dispatchers.Main) { current.updateInput(text); current.send() }
            withTimeout(150_000) {
                while (true) {
                    model.failure?.let { throw it }
                    val rows = data.chats().getMessages(id)
                    check(rows.lastOrNull { it.role == MessageRole.Assistant }?.status != MessageStatus.Failed) {
                        "Synthetic turn failed: ${rows.lastOrNull { it.role == MessageRole.Assistant }?.content}"
                    }
                    if (rows.count { it.role == MessageRole.Assistant && it.content == expected && it.status == MessageStatus.Complete } > before &&
                        !current.uiState.value.isStreaming) break
                    delay(50)
                }
            }
        }
        try {
            data.providers().upsertProvider(provider, null)
            val chat = data.chats().createConversation(provider.id, provider.defaultModel)
            owner = chat.id
            data.chats().updateConversationSettings(chat.id, "Context QA", null, false, true, "", 0.0, 1.0, null)
            repeat(20) { index ->
                val row = data.chats().appendMessage(chat.id, MessageRole.User,
                    if (index == 0) "OLD_EVIDENCE_61942: already installed" else "Synthetic question $index", MessageStatus.Sent, provider.defaultModel)
                if (index == 0) model.evidenceId = row.id
                data.chats().appendMessage(chat.id, MessageRole.Assistant, "x".repeat(15_000), MessageStatus.Complete, provider.defaultModel)
            }
            open(chat.id)
            assertEquals(1_000_000, vm!!.uiState.value.contextWindow!!.modelWindowTokens)
            assertEquals(884_464, vm!!.uiState.value.contextWindow!!.totalTokens)
            assertTrue(vm!!.uiState.value.contextWindow!!.usedTokens > 100_000)
            model.readNext = true
            send("LOCAL_100K: use the original synthetic evidence")
            assertEquals(0, model.summaryRequests)
            assertTrue(model.sawEvidence)
            assertTrue(model.largestAnswerBytes > 300_000)
            assertTrue(model.sawNativePair)

            // Test cancellation of an in-flight HTTP response, then resume using the saved history.
            model.holdNext = true
            val previousRequests = model.answerRequests
            withContext(Dispatchers.Main) { vm!!.updateInput("LOCAL_STOP"); vm!!.send() }
            withTimeout(20_000) { while (model.answerRequests == previousRequests || !model.holding) delay(25) }
            withContext(Dispatchers.Main) { vm!!.stop() }
            model.release.countDown()
            withTimeout(20_000) { while (vm!!.uiState.value.isStreaming) delay(25) }
            send("LOCAL_RESUME: continue from the synthetic saved records")
            assertTrue(data.chats().getMessages(chat.id).any { it.status == MessageStatus.Stopped })

            dispose()
            repeat(100) { index ->
                data.chats().appendMessage(chat.id, MessageRole.User, "Large synthetic $index " + "u".repeat(15_000), MessageStatus.Sent, provider.defaultModel)
                data.chats().appendMessage(chat.id, MessageRole.Assistant, "a".repeat(15_000), MessageStatus.Complete, provider.defaultModel)
            }
            open(chat.id)
            model.readNext = true
            model.sawEvidence = false
            send("LOCAL_COMPACT: preserve and read original evidence")
            val rows = data.chats().getMessages(chat.id)
            val checkpoint = requireNotNull(data.chats().getContextCheckpoint(chat.id))
            assertTrue(checkpoint.matches(rows))
            assertTrue(model.summaryRequests in 1..5)
            assertTrue(model.sawCheckpoint)
            assertTrue(model.sawEvidence)
            assertTrue(model.summaryMaxBytes > 60_000)
            assertTrue(TaskContextTools.read("""{"message_ids":["${model.evidenceId}"]}""", chat.id, rows).contains("OLD_EVIDENCE_61942"))
            val summaries = model.summaryRequests
            dispose()
            open(chat.id)
            send("LOCAL_REOPEN: use the committed checkpoint")
            assertEquals(summaries, model.summaryRequests)

            withContext(Dispatchers.Default) {
                val estimator = ContextBudget.Estimator()
                for (tokens in listOf(100_000, 600_000, 880_000)) {
                    val prefix = List(80) { ChatRequestMessage("user", "x".repeat((tokens * 3) / 80)) }
                    val request = ChatRequest(provider.defaultModel, prefix, 0.0, 1.0, null, usageConversationId = "perf")
                    val start = SystemClock.elapsedRealtimeNanos()
                    val cold = estimator.request(request, provider)
                    val coldMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    val stream = request.copy(messages = prefix + ChatRequestMessage("assistant", "small live fragment"))
                    val warmStart = SystemClock.elapsedRealtimeNanos()
                    val warm = estimator.request(stream, provider)
                    val warmMs = (SystemClock.elapsedRealtimeNanos() - warmStart) / 1_000_000.0
                    assertEquals(ContextBudget.request(stream, provider), warm)
                    assertTrue(warm > cold)
                    measurements += buildJsonObject { put("target_tokens", tokens); put("estimated_tokens", cold)
                        put("cold_ms", coldMs); put("prefix_reuse_ms", warmMs)
                        put("heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) }
                }
            }
            model.failure?.let { throw it }
            assertTrue(redirected == model.answerRequests + model.summaryRequests)
            report.writeText(buildJsonObject { put("result", "PASS"); put("model_capacity", 1_000_000)
                put("input_budget", 884_464); put("answer_requests", model.answerRequests); put("summary_requests", model.summaryRequests)
                put("largest_answer_request_bytes", model.largestAnswerBytes); put("summary_max_bytes", model.summaryMaxBytes)
                put("native_pair", model.sawNativePair); put("early_evidence_read", model.sawEvidence)
                put("reopened_without_new_summary", true); put("stop_resume", true)
                put("compaction_meter_seen", synchronized(samples) { samples.any { it.isCompacting } })
                put("performance", JsonArray(measurements)) }.toString())
        } finally {
            model.release.countDown()
            dispose()
            owner?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            http.close(); model.close()
        }
    }

    private class LocalModel : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        @Volatile var evidenceId = ""
        @Volatile var failure: Throwable? = null
        @Volatile var answerRequests = 0
        @Volatile var summaryRequests = 0
        @Volatile var largestAnswerBytes = 0
        @Volatile var summaryMaxBytes = 0
        @Volatile var readNext = false
        @Volatile var holdNext = false
        @Volatile var holding = false
        @Volatile var sawNativePair = false
        @Volatile var sawCheckpoint = false
        @Volatile var sawEvidence = false
        val release = CountDownLatch(1)
        private val worker = thread(isDaemon = true, name = "deepseek-context-local-model") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { try { serve(it) } catch (error: Throwable) { failure = error } }
            }
        }
        private fun serve(socket: Socket) {
            socket.soTimeout = 20_000
            val input = socket.getInputStream().buffered()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32_768); header.append(b.toChar()) }
            val size = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
            check(size in 1..8_000_000)
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) { val n = input.read(bytes, offset, size - offset); check(n > 0); offset += n }
            val body = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
            val summary = messages.first()["content"]!!.jsonPrimitive.content.contains("# Floryx context checkpoint writer")
            val delta: JsonObject
            val finish: String
            if (summary) {
                summaryRequests++
                summaryMaxBytes = maxOf(summaryMaxBytes, size)
                check(body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content == "disabled")
                val source = Json.parseToJsonElement(messages.last()["content"]!!.jsonPrimitive.content).jsonObject
                val previous = source["previous_checkpoint"]?.let { CheckpointCodec.json.decodeFromJsonElement<TaskCheckpoint>(it) }
                val id = source["new_records"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
                val result = TaskSummary(previous?.summary?.objective ?: ContextNote("Preserve synthetic task evidence", listOf(id)))
                delta = buildJsonObject { put("content", CheckpointCodec.json.encodeToString(result)) }
                finish = "stop"
            } else {
                answerRequests++
                largestAnswerBytes = maxOf(largestAnswerBytes, size)
                check(body["max_tokens"]!!.jsonPrimitive.int == 65_536)
                messages.filter { it["role"]!!.jsonPrimitive.content == "assistant" }.forEach { assistant ->
                    check(!assistant["reasoning_content"]?.jsonPrimitive?.content.isNullOrBlank())
                    assistant["tool_calls"]?.jsonArray?.forEach { call ->
                        val id = call.jsonObject["id"]!!.jsonPrimitive.content
                        check(messages.any { it["role"]!!.jsonPrimitive.content == "tool" && it["tool_call_id"]?.jsonPrimitive?.content == id })
                        sawNativePair = true
                    }
                }
                sawCheckpoint = sawCheckpoint || messages.any { it["content"]?.jsonPrimitive?.content.orEmpty().contains("Conversation task checkpoint") }
                if (messages.last()["role"]!!.jsonPrimitive.content == "tool") {
                    check(messages.last()["content"]!!.jsonPrimitive.content.contains("OLD_EVIDENCE_61942"))
                    sawEvidence = true
                }
                if (holdNext) {
                    holdNext = false; holding = true
                    check(release.await(20, TimeUnit.SECONDS))
                    holding = false
                    // Cancellation closes this socket; finish quietly instead of recording a server fault.
                    runCatching { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
                    return
                }
                if (readNext) {
                    readNext = false
                    delta = buildJsonObject { put("reasoning_content", "Synthetic native state")
                        putJsonArray("tool_calls") { add(buildJsonObject { put("index", 0); put("id", "read-${answerRequests}"); put("type", "function")
                            putJsonObject("function") { put("name", TaskContextTools.Read); put("arguments", """{"message_ids":["$evidenceId"]}""") } }) } }
                    finish = "tool_calls"
                } else {
                    delta = buildJsonObject { put("reasoning_content", "Synthetic native state"); put("content", "SYNTHETIC_DONE") }
                    finish = "stop"
                }
            }
            val response = "data: ${buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("delta", delta); put("finish_reason", JsonNull) }) } }}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n"
            val output = response.toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${output.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(output); flush()
            }
        }
        override fun close() { server.close(); worker.join(1_000) }
    }
}
