package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import com.flowchat.app.data.chat.ContextBudget
import com.flowchat.app.data.chat.TaskContextManager
import com.flowchat.app.data.network.ChatCompletionClient
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.repository.ChatRepository
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in regressions: synthetic transport markers, loopback HTTP, read-only task tools. */
class LongTaskObservationDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun historyProjectionIsAcceptedWithoutInventingReasoning() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLongTaskObservation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val model = Model(strict = true)
        try {
            val provider = provider(model.port)
            val conversation = Conversation(id = "synthetic", providerId = provider.id, modelName = provider.defaultModel, enableThinking = true)
            val messages = listOf(
                Message(id = "u1", conversationId = conversation.id, role = MessageRole.User, content = "合成测试：记住组件已确认存在。"),
                Message(id = "a1", conversationId = conversation.id, role = MessageRole.Assistant, content = "组件已确认存在。",
                    status = MessageStatus.Complete, reasoningContent = "SYNTHETIC_TRANSPORT_MARKER"),
                Message(id = "u2", conversationId = conversation.id, role = MessageRole.User, content = "继续合成测试。")
            )
            val projected = TaskContextTools.attach(ChatRequestFactory.create(conversation, messages)).copy(maxTokens = 64)
            suspend fun call(request: ChatRequest): Pair<String, String> {
                val content = StringBuilder()
                val errors = StringBuilder()
                withTimeout(15_000) { data.client().streamChat(request, provider, null).collect { delta ->
                    when (delta) {
                        is ChatDelta.Content -> content.append(delta.text)
                        is ChatDelta.FullResponse -> content.append(delta.contentText)
                        is ChatDelta.Error -> errors.append(delta.message)
                        else -> Unit
                    }
                } }
                return content.toString() to errors.toString()
            }
            model.answerOnly = true
            val result = call(projected)
            assertEquals(Done, result.first)
            assertEquals("", result.second)
            assertEquals(0, model.rejections)
            assertTrue(model.historyDataRequests > 0)
            val control = projected.copy(messages = projected.messages.map {
                if (it.role == "assistant") it.copy(reasoningContent = "SYNTHETIC_TRANSPORT_MARKER", historical = false) else it
            })
            assertEquals(Done, call(control).first)
            model.failure?.let { throw it }
            report("history", "PASS: stored history is attributed as data, native replay stays intact, strict thinking/tool requests are accepted without invented reasoning.")
        } finally { model.close() }
    }

    @Test fun runningTaskCompactionWithPermissiveEndpoint() = runBlocking { runUi(strict = false) }

    @Test fun runningTaskCompactionWithStrictThinkingEndpoint() = runBlocking { runUi(strict = true) }

    @Test fun runningTaskRepairsInvalidSummaryBeforeContinuing() = runBlocking { runUi(strict = true, repair = true) }

    @Test fun runningTaskRepairsTruncatedSummaryBeforeContinuing() = runBlocking { runUi(strict = true, truncated = true) }

    @Test fun liveDeepSeekContinuesFromStoredHistoryWithTools() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLongTaskObservation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        })
        val key = data.providers().getApiKey(provider)
        require(!key.isNullOrBlank())
        val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
            .copy(enableThinking = true, thinkingEffort = ThinkingEffort.Low)
        try {
            data.chats().appendMessage(conversation.id, MessageRole.User, "合成测试：检查测试组件是否已安装。", MessageStatus.Sent, provider.defaultModel)
            val segment = data.chats().appendMessage(conversation.id, MessageRole.Assistant,
                "工具已经确认测试组件已安装，不需要重新安装。", MessageStatus.Complete, provider.defaultModel)
            val call = ChatToolCall("synthetic-component-probe", "run_command", """{"command":"test -f synthetic-component","environment":"linux"}""")
            data.chats().updateExecutionContext(segment.id, TaskExecutionHistory.encode(listOf(TaskExecutionHistory.completed(
                TaskExecutionHistory.pending(call), """{"status":"Exited","exit_code":0,"output":"SYNTHETIC_COMPONENT_ALREADY_PRESENT; no installation needed"}"""))))
            data.chats().appendMessage(conversation.id, MessageRole.User,
                "这是合成测试，只依据本会话已经保存的工具结果，不要重新检查。现在需要重新安装吗？严格只回答：不需要重新安装。",
                MessageStatus.Sent, provider.defaultModel)
            val request = TaskContextTools.attach(ChatRequestFactory.create(conversation, data.chats().getMessages(conversation.id)))
                .copy(maxTokens = 2_048, toolChoice = "none")
            val answer = StringBuilder()
            withTimeout(60_000) { data.client().streamChat(request, provider, key).collect { delta ->
                when (delta) {
                    is ChatDelta.Content -> answer.append(delta.text)
                    is ChatDelta.FullResponse -> answer.append(delta.contentText)
                    is ChatDelta.Error -> error("Live stored-history request failed; credential omitted")
                    is ChatDelta.ToolCalls, is ChatDelta.ToolCallDelta -> error("History reuse must not repeat an action")
                    else -> Unit
                }
            } }
            report("live-history", "answer=$answer")
            assertEquals("不需要重新安装", answer.toString().trim().removeSuffix("。"))
        } finally { data.chats().deleteConversation(conversation.id) }
    }

    @Test fun largeActiveTaskSummaryWithExistingDeepSeekConfiguration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLongTaskObservation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        }) { "Existing direct DeepSeek configuration required" }
        val key = data.providers().getApiKey(provider)
        require(!key.isNullOrBlank()) { "Existing credential required; omitted from diagnostics" }
        val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
        var protectedId = ""
        val native = mutableListOf<ChatRequestMessage>(ChatRequestMessage("user", "合成测试：连续排查已授权的本地测试问题。"))
        try {
            data.chats().appendMessage(conversation.id, MessageRole.User, native.first().content!!, MessageStatus.Sent, provider.defaultModel)
            for (index in 1..12) {
                val call = ChatToolCall("synthetic-$index", "run_command", """{"command":"printf SYNTHETIC_$index","environment":"android"}""")
                val output = "SYNTHETIC_EVIDENCE_$index\n" + "DNS log synthetic record: 状态已读取，尚未验证修复。\n".repeat(150)
                val result = buildJsonObject { put("status", "Exited"); put("exit_code", 0); put("output", output) }.toString()
                val segment = data.chats().appendMessage(conversation.id, MessageRole.Assistant, "合成步骤 $index 返回。", MessageStatus.Complete, provider.defaultModel)
                data.chats().updateMessage(segment.id, segment.content, MessageStatus.Complete, "SYNTHETIC_REPLAY_$index".repeat(100), replyPhase = ReplyPhase.Commentary)
                data.chats().updateExecutionContext(segment.id, TaskExecutionHistory.encode(listOf(TaskExecutionHistory.completed(TaskExecutionHistory.pending(call), result))))
                data.chats().appendMessage(conversation.id, MessageRole.Tool, result, MessageStatus.Complete, provider.defaultModel)
                native += ChatRequestMessage("assistant", segment.content, "SYNTHETIC_REPLAY_$index".repeat(100), toolCalls = listOf(call), localMessageId = segment.id)
                native += ChatRequestMessage("tool", result, toolCallId = call.id)
                if (index == 11) protectedId = segment.id
            }
            val original = data.chats().getMessages(conversation.id)
            val captures = File(directory(), "large-live-summary.responses.jsonl").apply { writeText("") }
            var summaryRequests = 0
            val probe = object : ChatCompletionClient {
                override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
                    val sequence = ++summaryRequests
                    val output = StringBuilder()
                    try {
                        data.client().streamChat(request, provider, apiKey).collect { delta ->
                            when (delta) {
                                is ChatDelta.Content -> output.append(delta.text)
                                is ChatDelta.FullResponse -> { output.clear(); output.append(delta.contentText) }
                                else -> Unit
                            }
                            emit(delta)
                        }
                    } finally {
                        // Only synthetic summary content, never provider reasoning, keys, or user conversations.
                        captures.appendText(buildJsonObject { put("sequence", sequence); put("content", output.toString()) }.toString() + "\n")
                    }
                }
            }
            val manager = TaskContextManager(probe, data.chats())
            val prepared = withTimeout(100_000) { manager.prepare(conversation, original, provider, key, protectedMessageId = protectedId, force = true) }
            val index = prepared.messages.indexOfFirst { it.id == protectedId }
            val prefix = if (index >= 0) prepared.messages.take(index) else prepared.messages
            val projected = ChatRequestFactory.create(conversation, prefix, checkpoint = prepared.checkpoint)
            val rebuilt = projected.copy(messages = projected.messages + native.dropWhile { it.localMessageId != protectedId })
            val fits = runCatching { manager.requireFits(rebuilt) }.isSuccess
            report("large-live-summary", "checkpoint=${prepared.checkpoint != null}; summary_failure=${manager.lastFailureCode}; " +
                "records_before=${original.size}; records_after=${prepared.messages.size}; request_estimate_after=${ContextBudget.request(rebuilt)}; fits=$fits; summary_requests=$summaryRequests")
            assertEquals(original, data.chats().getMessages(conversation.id))
            assertNotNull("Live active summary failure=${manager.lastFailureCode}", prepared.checkpoint)
            assertTrue("Compacted active task must remain under the local hard threshold", fits)
        } finally { data.chats().deleteConversation(conversation.id) }
    }

    @Test fun recordedStoppedTaskCompactionWithExistingConfiguration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLongTaskObservation") == "true")
        val fixtureFile = File(directory(), "replay-fixture.redacted.json")
        assumeTrue("An explicitly supplied redacted fixture is required", fixtureFile.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        })
        val key = data.providers().getApiKey(provider)
        require(!key.isNullOrBlank())
        val fixture = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
        val conversationId = fixture["conversationId"]!!.jsonPrimitive.content
        val rows = fixture["messages"]!!.jsonArray.map { value ->
            val item = value.jsonObject
            Message(id = item["id"]!!.jsonPrimitive.content, conversationId = conversationId,
                role = MessageRole.valueOf(item["role"]!!.jsonPrimitive.content), content = item["content"]!!.jsonPrimitive.content,
                status = MessageStatus.valueOf(item["status"]!!.jsonPrimitive.content),
                replyPhase = ReplyPhase.valueOf(item["replyPhase"]!!.jsonPrimitive.content),
                executionContext = item["executionContext"]?.jsonPrimitive?.contentOrNull,
                createdAt = item["createdAt"]!!.jsonPrimitive.long)
        }
        var checkpoint = fixture["checkpoint"]?.takeUnless { it == JsonNull }?.let { CheckpointCodec.json.decodeFromJsonElement<TaskCheckpoint>(it) }
        checkpoint = checkpoint?.let { old -> old.copy(sourceHash = CheckpointCodec.fingerprint(rows.takeWhile { it.id != old.throughMessageId } + rows.first { it.id == old.throughMessageId })) }
        val repository = object : ChatRepository by data.chats() {
            override suspend fun getContextCheckpoint(conversationId: String) = checkpoint
            override suspend fun getMessages(conversationId: String) = rows
            override suspend fun saveContextCheckpoint(candidate: TaskCheckpoint, expected: TaskCheckpoint?): Boolean {
                if (checkpoint != expected || !candidate.matches(rows)) return false
                checkpoint = candidate
                return true
            }
        }
        val diagnostics = StringBuilder()
        var requests = 0
        val client = object : ChatCompletionClient {
            override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
                val sequence = ++requests
                val output = StringBuilder()
                try {
                    data.client().streamChat(request, provider, apiKey).collect { delta ->
                        when (delta) {
                            is ChatDelta.Content -> output.append(delta.text)
                            is ChatDelta.FullResponse -> { output.clear(); output.append(delta.contentText) }
                            else -> Unit
                        }
                        emit(delta)
                    }
                } finally {
                    val clean = output.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val summary = runCatching { CheckpointCodec.json.decodeFromString<TaskSummary>(clean) }.getOrNull()
                    val notes = summary?.notes().orEmpty()
                    val known = rows.map { it.id }.toSet()
                    // Diagnostic metadata only: never save private reasoning or summary/source text.
                    diagnostics.appendLine("request=$sequence; max_tokens=${request.maxTokens}; output_chars=${clean.length}; parsed=${summary != null}; notes=${notes.size}; " +
                        "max_note_chars=${notes.maxOfOrNull { it.text.length }}; blank_notes=${notes.count { it.text.isBlank() }}; " +
                        "max_sources=${notes.maxOfOrNull { it.sources.size }}; unsourced_notes=${notes.count { it.sources.isEmpty() }}; " +
                        "unknown_sources=${notes.sumOf { note -> note.sources.count { it !in known } }}")
                    report("recorded-replay", diagnostics.toString())
                }
            }
        }
        val manager = TaskContextManager(client, repository)
        try {
            withTimeout(150_000) { manager.prepare(Conversation(id = conversationId, providerId = provider.id, modelName = provider.defaultModel),
                rows, provider, key, fixture["protectedMessageId"]!!.jsonPrimitive.content, force = true) }
            diagnostics.appendLine("summary_failure=${manager.lastFailureCode}; requests=$requests")
            report("recorded-replay", diagnostics.toString())
            assertNull("Recorded task compaction failure=${manager.lastFailureCode}", manager.lastFailureCode)
        } finally { fixtureFile.delete() }
    }

    private suspend fun runUi(strict: Boolean, repair: Boolean = false, truncated: Boolean = false) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLongTaskObservation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val environment = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = environment.settings().state.value
        val model = Model(strict)
        model.invalidFirstSummary = repair
        model.truncateFirstSummary = truncated
        val provider = provider(model.port)
        var owner: String? = null
        val label = if (truncated) "truncated" else if (repair) "repair" else if (strict) "strict" else "permissive"
        try {
            data.providers().upsertProvider(provider, null)
            val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
            owner = conversation.id
            data.chats().updateThinkingSettings(conversation.id, true, ThinkingEffort.Auto)
            model.repository = data.chats()
            model.owner = conversation.id
            environment.settings().setCommandExecutionEnabled(false)
            environment.settings().setPowerModeEnabled(false)
            environment.settings().setMemoryEnabled(false)
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("合成诊断：连续读取本会话记录，完成后简短报告。")
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            val final = withTimeout(70_000) {
                while (true) {
                    model.failure?.let { throw it }
                    val rows = data.chats().getMessages(conversation.id)
                    rows.lastOrNull { it.role == MessageRole.Assistant &&
                        (it.status == MessageStatus.Failed || it.content == Done) }?.let { return@withTimeout it }
                    delay(100)
                }
                error("unreachable")
            }
            val rows = data.chats().getMessages(conversation.id)
            // read_task_history persists evidence without adding visible Tool rows.
            val tools = rows.sumOf { row -> row.executionContext?.let { stored ->
                Json.decodeFromString<List<ExecutionStep>>(stored).count { it.outcome == "returned" }
            } ?: 0 }
            report(label, "status=${final.status}; tools=$tools; model_requests=${model.answers}; summary_requests=${model.summaries}; " +
                "missing_reasoning_requests=${model.missingReasoningRequests}; protocol_rejections=${model.rejections}; " +
                "compaction_progress_seen=${model.compactionProgressSeen}; final=${final.content}")
            compose.waitForIdle()
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                File(directory(), "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            assertTrue("Compaction must actually occur", model.summaries > 0)
            assertTrue("Attributed historical context must reach the wire", model.historyDataRequests > 0)
            assertEquals(0, model.missingReasoningRequests)
            assertEquals(0, model.rejections)
            assertEquals(Done, final.content)
            assertEquals(8, tools)
            assertTrue("Compaction should not leave a blank assistant placeholder", model.compactionProgressSeen)
            if (repair || truncated) assertTrue(model.summaries >= 2)
        } finally {
            environment.settings().setCommandExecutionEnabled(original.commandExecutionEnabled)
            environment.settings().setPowerModeEnabled(original.powerModeEnabled)
            environment.settings().setMemoryEnabled(original.memoryEnabled)
            owner?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            model.close()
        }
    }

    private fun provider(port: Int) = ProviderConfig(id = "long-task-observation-${UUID.randomUUID()}", displayName = "Local task observation",
        baseUrl = "http://127.0.0.1:$port/v1", defaultModel = "deepseek-flash")

    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "long-task-observation").apply { mkdirs() }
    private fun report(name: String, text: String) { File(directory(), "$name.txt").writeText(text) }

    private class Model(private val strict: Boolean) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var repository: ChatRepository? = null
        @Volatile var owner = ""
        @Volatile var answerOnly = false
        @Volatile var invalidFirstSummary = false
        @Volatile var truncateFirstSummary = false
        @Volatile var compactionProgressSeen = false
        @Volatile var answers = 0
        @Volatile var summaries = 0
        @Volatile var rejections = 0
        @Volatile var missingReasoningRequests = 0
        @Volatile var historyDataRequests = 0
        @Volatile var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "long-task-observation-model") {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use { client -> try {
                    client.soTimeout = 15_000
                    val input = client.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32_768); header.append(b.toChar()) }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                    check(length in 1..2_000_000)
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                    val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
                    val summary = messages.first()["content"]!!.jsonPrimitive.content.contains("# Floryx context checkpoint writer")
                    if (!summary && messages.any { it["role"]!!.jsonPrimitive.content == "user" &&
                        it["content"]?.jsonPrimitive?.contentOrNull.orEmpty().startsWith("[Floryx stored history:") }) historyDataRequests++
                    val missing = !summary && request["tools"] != null && messages.any {
                        it["role"]!!.jsonPrimitive.content == "assistant" && it["reasoning_content"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
                    }
                    if (missing) missingReasoningRequests++
                    if (missing && strict) {
                        rejections++
                        val body = "{\"error\":{\"message\":\"The reasoning_content in the thinking mode must be passed back to the API.\",\"type\":\"invalid_request_error\"}}".toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                        }
                    } else {
                        val event = if (summary) {
                            summaries++
                            runBlocking { repository?.getMessages(owner)?.lastOrNull { it.role == MessageRole.Assistant } }?.let {
                                if (it.content == ReplyPolicy.contextPreparation(true) && it.status == MessageStatus.Streaming) compactionProgressSeen = true
                            }
                            val source = Json.parseToJsonElement(messages.last()["content"]!!.jsonPrimitive.content).jsonObject
                            val previous = source["previous_checkpoint"]?.let { CheckpointCodec.json.decodeFromJsonElement<TaskCheckpoint>(it) }
                            val id = source["new_records"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
                            val objective = previous?.summary?.objective ?: ContextNote("完成合成本地诊断", listOf(id))
                            val result = if (invalidFirstSummary && summaries == 1) TaskSummary(ContextNote("Unsourced synthetic summary", emptyList())) else TaskSummary(objective)
                            val output = CheckpointCodec.json.encodeToString(result)
                            buildJsonObject { put("content", if (truncateFirstSummary && summaries == 1) output.take(40) else output) }
                        } else {
                            answers++
                            if (answerOnly || answers > 8) buildJsonObject { put("content", Done); put("reasoning_content", "SYNTHETIC_FINAL_MARKER") }
                            else {
                                val id = runBlocking { requireNotNull(repository).getMessages(owner).last { it.role == MessageRole.Assistant }.id }
                                buildJsonObject {
                                    // Large artificial replay metadata exercises compaction without copying private deliberation.
                                    put("reasoning_content", "SYNTHETIC_REPLAY_${answers}_".repeat(700))
                                    put("content", "读取合成记录第 $answers 步。")
                                    putJsonArray("tool_calls") { add(buildJsonObject {
                                        put("index", 0); put("id", "synthetic-read-$answers"); put("type", "function")
                                        putJsonObject("function") { put("name", TaskContextTools.Read); put("arguments", """{"message_ids":["$id"]}""") }
                                    }) }
                                }
                            }
                        }
                        val finish = if (summary && truncateFirstSummary && summaries == 1) "length" else if (event.containsKey("tool_calls")) "tool_calls" else "stop"
                        val chunk = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("delta", event); put("finish_reason", JsonNull) }) } }
                        val response = "data: $chunk\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n"
                        val body = response.toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                        }
                    }
                } catch (error: Throwable) { failure = error } }
            }
        }
        override fun close() { socket.close(); worker.join(1_000) }
    }

    private companion object { const val Done = "LONG_TASK_SYNTHETIC_DONE" }
}
