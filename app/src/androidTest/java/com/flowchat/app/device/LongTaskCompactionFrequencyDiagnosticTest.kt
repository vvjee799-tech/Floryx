package com.flowchat.app.device

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.ContextNote
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskContextTools
import com.flowchat.app.domain.chat.TaskSummary
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ContextWindowUsage
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.presentation.chat.ChatViewModel
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in MuMu diagnostic: only synthetic Room records and a loopback model endpoint. */
class LongTaskCompactionFrequencyDiagnosticTest {
    @Test fun realVmCountsCompactionAcrossLongAndSmallTranscripts() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runContextFrequencyDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value
        val model = FrequencyModel()
        val provider = ProviderConfig(id = "context-frequency-${UUID.randomUUID()}", displayName = "Local context frequency QA",
            baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "deepseek-flash")
        val owners = mutableListOf<String>()
        var scenario: ActivityScenario<MainActivity>? = null
        var collector: Job? = null
        var longRoomSteps = -1
        var smallTurnsReported = 0
        val meterSamples = java.util.Collections.synchronizedList(mutableListOf<ContextWindowUsage>())
        val report = File(context.getExternalFilesDir(null), "context-frequency/result.json")
        report.parentFile?.mkdirs()
        try {
            settings.setPowerModeEnabled(false)
            settings.setMemoryEnabled(false)
            // This raises the VM's per-turn cap to 80. The fixture exposes only read_task_history calls.
            settings.setCommandExecutionEnabled(true)
            data.providers().upsertProvider(provider, null)
            val longChat = data.chats().createConversation(provider.id, provider.defaultModel).also { owners += it.id }
            data.chats().updateConversationSettings(longChat.id, "Frequency QA", null, false, true, "", 0.0, 1.0, 256)
            model.repository = data.chats()
            model.owner = longChat.id
            model.mode = FrequencyModel.Mode.Long
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm: ChatViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java] }
            collector = launch(Dispatchers.Main.immediate) { vm.uiState.collect { state ->
                state.contextWindow?.takeIf { state.currentConversation?.id == longChat.id && state.isStreaming }
                    ?.let { meterSamples += it }
            } }
            scenario.onActivity { vm.selectConversation(longChat.id) }
            awaitState(vm, longChat.id)
            scenario.onActivity { vm.updateInput("FREQUENCY_RUN: inspect synthetic records; OLD_EVIDENCE_61942 must remain readable."); vm.send() }
            withTimeout(150_000) {
                while (data.chats().getMessages(longChat.id).none { it.role == MessageRole.Assistant &&
                        it.status == MessageStatus.Complete && it.content == LongFinal }) {
                    model.failure?.let { throw it }
                    val failed = data.chats().getMessages(longChat.id).lastOrNull { it.role == MessageRole.Assistant && it.status == MessageStatus.Failed }
                    check(failed == null) { "Long task failed: ${failed?.content}" }
                    delay(100)
                }
            }
            val longRows = data.chats().getMessages(longChat.id)
            val returned = longRows.sumOf { row -> row.executionContext?.let { stored ->
                Json.decodeFromString<List<ExecutionStep>>(stored).count { it.tool == TaskContextTools.Read && it.outcome == "returned" }
            } ?: 0 }
            longRoomSteps = returned
            val checkpoint = data.chats().getContextCheckpoint(longChat.id)
            assertNotNull("Long task must create a durable checkpoint", checkpoint)
            assertTrue(checkpoint!!.matches(longRows))
            assertEquals(ToolCalls, model.callsEmitted)
            assertEquals(ToolCalls, model.uniqueArguments.size)
            assertEquals(ToolCalls, returned)
            assertEquals(ToolCalls + 1, model.longModelRequests)
            assertTrue("Expected one or more compaction requests", model.summaryRequests > 0)
            assertTrue("Compaction must be less frequent than tool execution", model.summaryRequests < ToolCalls)
            assertTrue("28 synthetic steps must compact in batches, not every step: ${model.summaryRequests}", model.summaryRequests <= 8)
            assertTrue("Compacted request must retain a checkpoint", model.sawHistoricalCheckpoint)
            assertTrue("Protected native reasoning and call/result groups must survive compaction", model.sawProtectedPairAfterSummary)
            assertTrue("Model must read old evidence after compaction", model.sawOldEvidenceReadback)
            val samples = synchronized(meterSamples) { meterSamples.toList() }
            assertTrue("The meter must expose compaction progress", samples.any { it.isCompacting })
            assertTrue("The context meter must decrease after a committed checkpoint",
                samples.zipWithNext().any { (before, after) -> before.isCompacting && !after.isCompacting &&
                    after.usedTokens < before.usedTokens })
            assertTrue(samples.all { it.totalTokens == 32_000 })
            val oldest = longRows.first { it.role == MessageRole.Assistant && !it.executionContext.isNullOrBlank() }
            val readback = TaskContextTools.read("""{"message_ids":["${oldest.id}"]}""", longChat.id, longRows)
            assertTrue(readback.contains("OLD_EVIDENCE_61942"))
            collector?.cancel(); collector = null
            scenario.close(); scenario = null
            settings.setCommandExecutionEnabled(false)
            val smallChat = data.chats().createConversation(provider.id, provider.defaultModel).also { owners += it.id }
            data.chats().updateConversationSettings(smallChat.id, "Small history QA", null, false, false, "", 0.0, 1.0, 256)
            repeat(SmallTurns) { index ->
                data.chats().appendMessage(smallChat.id, MessageRole.User, "Small user turn $index", MessageStatus.Sent, provider.defaultModel)
                delay(2)
                data.chats().appendMessage(smallChat.id, MessageRole.Assistant, "Small answer $index", MessageStatus.Complete, provider.defaultModel)
                delay(2)
            }
            model.owner = smallChat.id
            model.mode = FrequencyModel.Mode.Small
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java] }
            collector = launch(Dispatchers.Default) { vm.uiState.collect { } }
            scenario.onActivity { vm.selectConversation(smallChat.id) }
            awaitState(vm, smallChat.id)
            scenario.onActivity { vm.updateInput("SMALL_HISTORY_FINAL_QUERY"); vm.send() }
            withTimeout(30_000) {
                while (data.chats().getMessages(smallChat.id).none { it.role == MessageRole.Assistant &&
                        it.status == MessageStatus.Complete && it.content == SmallFinal }) {
                    model.failure?.let { throw it }
                    delay(50)
                }
            }
            assertEquals(1, model.smallModelRequests)
            assertEquals("A small 23-turn chat must not be summarized", 0, model.smallSummaryRequests)
            assertNull(data.chats().getContextCheckpoint(smallChat.id))
            smallTurnsReported = SmallTurns + 1
            model.failure?.let { throw it }
        } finally {
            report.writeText(model.report(longRoomSteps = longRoomSteps, smallTurns = smallTurnsReported,
                smallSummaries = model.smallSummaryRequests))
            File(context.getExternalFilesDir(null), "context-window/compaction-meter.json").apply {
                parentFile?.mkdirs()
                val samples = synchronized(meterSamples) { meterSamples.toList() }
                writeText(buildJsonArray { samples.forEach { usage -> add(buildJsonObject {
                    put("usedTokens", usage.usedTokens); put("totalTokens", usage.totalTokens)
                    put("compacting", usage.isCompacting)
                }) } }.toString())
            }
            collector?.cancel()
            scenario?.close()
            settings.setCommandExecutionEnabled(original.commandExecutionEnabled)
            settings.setPowerModeEnabled(original.powerModeEnabled)
            settings.setMemoryEnabled(original.memoryEnabled)
            owners.forEach { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            model.close()
        }
    }

    private suspend fun awaitState(vm: ChatViewModel, owner: String) = withTimeout(15_000) {
        while (vm.uiState.value.currentConversation?.id != owner || vm.uiState.value.isStreaming) delay(25)
    }

    private class FrequencyModel : AutoCloseable {
        enum class Mode { Long, Small }
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        @Volatile var repository: ChatRepository? = null
        @Volatile var owner = ""
        @Volatile var mode = Mode.Long
        @Volatile var failure: Throwable? = null
        @Volatile var longModelRequests = 0
        @Volatile var smallModelRequests = 0
        @Volatile var summaryRequests = 0
        @Volatile var smallSummaryRequests = 0
        @Volatile var callsEmitted = 0
        @Volatile var sawHistoricalCheckpoint = false
        @Volatile var sawProtectedPairAfterSummary = false
        @Volatile var sawOldEvidenceReadback = false
        val uniqueArguments = linkedSetOf<String>()
        private var firstEvidenceId: String? = null
        private val worker = thread(isDaemon = true, name = "context-frequency-model") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                try { socket.use(::serve) } catch (error: Throwable) { failure = error }
            }
        }

        private fun serve(client: Socket) {
            client.soTimeout = 20_000
            val input = client.getInputStream().buffered()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0 && header.length < 32_768)
                header.append(byte.toChar())
            }
            val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.toInt() ?: error("Missing request length")
            check(length in 1..2_000_000)
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                check(count > 0)
                offset += count
            }
            val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
            val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
            val summary = messages.first()["content"]!!.jsonPrimitive.content.contains("# Floryx context checkpoint writer")
            val event: JsonObject
            val finish: String
            if (summary) {
                if (mode == Mode.Small) smallSummaryRequests++ else summaryRequests++
                val source = Json.parseToJsonElement(messages.last()["content"]!!.jsonPrimitive.content).jsonObject
                val previous = source["previous_checkpoint"]?.let { CheckpointCodec.json.decodeFromJsonElement<com.flowchat.app.domain.chat.TaskCheckpoint>(it) }
                val id = source["new_records"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
                val objective = previous?.summary?.objective ?: ContextNote("Retain synthetic task evidence", listOf(id))
                val output = CheckpointCodec.json.encodeToString(TaskSummary(objective))
                event = buildJsonObject { put("content", output) }
                finish = "stop"
            } else if (mode == Mode.Small) {
                smallModelRequests++
                check(messages.last { it["role"]!!.jsonPrimitive.content == "user" }["content"]!!.jsonPrimitive.content
                    .contains("SMALL_HISTORY_FINAL_QUERY"))
                event = buildJsonObject { put("content", SmallFinal) }
                finish = "stop"
            } else {
                longModelRequests++
                check(request["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == TaskContextTools.Read })
                if (summaryRequests > 0 && messages.any { it["content"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("Conversation task checkpoint") }) {
                    sawHistoricalCheckpoint = true
                }
                val calls = messages.withIndex().filter { it.value["role"]?.jsonPrimitive?.contentOrNull == "assistant" &&
                    (it.value["tool_calls"] as? JsonArray)?.isNotEmpty() == true }
                if (summaryRequests > 0 && calls.size >= 2) {
                    val recent = calls.takeLast(2)
                    sawProtectedPairAfterSummary = recent.all { (index, assistant) ->
                        assistant["reasoning_content"]?.jsonPrimitive?.contentOrNull?.contains("NATIVE_REASONING_") == true &&
                            assistant["tool_calls"]!!.jsonArray.any { call ->
                                val id = call.jsonObject["id"]!!.jsonPrimitive.content
                                messages.drop(index + 1).any { it["role"]?.jsonPrimitive?.contentOrNull == "tool" &&
                                    it["tool_call_id"]?.jsonPrimitive?.contentOrNull == id }
                            }
                    } || sawProtectedPairAfterSummary
                }
                if (callsEmitted > 0) {
                    val result = messages.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "tool" }
                        ?: error("Missing result for read_task_history #$callsEmitted")
                    check(result["tool_call_id"]!!.jsonPrimitive.content == "frequency-read-$callsEmitted")
                    check(result["content"]!!.jsonPrimitive.content.contains("historical_data_only"))
                    if (callsEmitted == ToolCalls) {
                        sawOldEvidenceReadback = result["content"]!!.jsonPrimitive.content.contains("OLD_EVIDENCE_61942")
                        check(sawOldEvidenceReadback)
                    }
                }
                if (callsEmitted == ToolCalls) {
                    event = buildJsonObject { put("content", LongFinal); put("reasoning_content", "NATIVE_FINAL_REASONING") }
                    finish = "stop"
                } else {
                    val rows = runBlocking { requireNotNull(repository).getMessages(owner) }
                    val source = rows.lastOrNull { it.role == MessageRole.Assistant && !it.executionContext.isNullOrBlank() }
                        ?: rows.last { it.role == MessageRole.User }
                    if (firstEvidenceId == null && source.role == MessageRole.Assistant) firstEvidenceId = source.id
                    val ids = if (callsEmitted == ToolCalls - 1) listOf(requireNotNull(firstEvidenceId), source.id)
                        else listOf(source.id)
                    val arguments = buildJsonObject { putJsonArray("message_ids") { ids.forEach { add(it) } } }.toString()
                    check(uniqueArguments.add(arguments)) { "Repeated read_task_history arguments: $arguments" }
                    callsEmitted++
                    event = buildJsonObject {
                        put("content", "Synthetic progress $callsEmitted")
                        put("reasoning_content", "NATIVE_REASONING_${callsEmitted}_".repeat(120))
                        putJsonArray("tool_calls") { add(buildJsonObject {
                            put("index", 0); put("id", "frequency-read-$callsEmitted"); put("type", "function")
                            putJsonObject("function") { put("name", TaskContextTools.Read); put("arguments", arguments) }
                        }) }
                    }
                    finish = "tool_calls"
                }
            }
            val chunk = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("delta", event); put("finish_reason", JsonNull) }) } }
            val response = "data: $chunk\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n"
            val bytes = response.toByteArray()
            client.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }

        fun report(longRoomSteps: Int, smallTurns: Int, smallSummaries: Int): String = buildJsonObject {
            put("long_tool_calls", callsEmitted)
            put("long_unique_arguments", uniqueArguments.size)
            put("long_room_returned_steps", longRoomSteps)
            put("long_model_requests", longModelRequests)
            put("long_summary_requests", summaryRequests)
            put("checkpoint_seen", sawHistoricalCheckpoint)
            put("protected_native_pair_seen_after_summary", sawProtectedPairAfterSummary)
            put("old_evidence_readback", sawOldEvidenceReadback)
            put("small_turns", smallTurns)
            put("small_model_requests", smallModelRequests)
            put("small_summary_requests", smallSummaries)
            failure?.let { put("model_failure", it.toString()) }
        }.toString()

        override fun close() { server.close(); worker.join(2_000) }
    }

    private companion object {
        const val ToolCalls = 28
        const val SmallTurns = 22
        const val LongFinal = "FREQUENCY_LONG_FINAL_OK"
        const val SmallFinal = "FREQUENCY_SMALL_FINAL_OK"
    }
}
