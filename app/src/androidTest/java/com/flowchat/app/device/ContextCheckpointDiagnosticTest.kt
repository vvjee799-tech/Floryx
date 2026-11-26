package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.chat.ContextLimits
import com.flowchat.app.data.chat.TaskContextManager
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.repository.ChatRepository
import dagger.hilt.android.EntryPointAccessors
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic records only. Local mode exercises the real HTTP adapter; live mode is separately opt-in. */
class ContextCheckpointDiagnosticTest {
    private val limits = ContextLimits(softTokens = 5_000, hardTokens = 16_000, triggerTurns = 3, keepTurns = 2)

    private fun data() = EntryPointAccessors.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext,
        AttachmentDiagnosticsEntryPoint::class.java)

    private suspend fun seed(repository: ChatRepository, provider: ProviderConfig): Pair<Conversation, String> {
        val conversation = repository.createConversation(provider.id, provider.defaultModel).copy(enableThinking = false)
        var evidenceId = ""
        for (turn in 1..8) {
            repository.appendMessage(conversation.id, MessageRole.User,
                if (turn == 3) "不要再检查组件，它已确认存在，只处理剩余步骤。" else "合成测试任务第 $turn 步",
                MessageStatus.Sent, provider.defaultModel)
            val segment = repository.appendMessage(conversation.id, MessageRole.Assistant, "测试步骤 $turn 已返回。", MessageStatus.Complete, provider.defaultModel)
            repository.updateMessage(segment.id, segment.content, MessageStatus.Complete, "PRIVATE_NOT_FOR_CHECKPOINT", replyPhase = ReplyPhase.Commentary)
            repository.updateExecutionContext(segment.id, TaskExecutionHistory.encode(listOf(TaskExecutionHistory.completed(
                TaskExecutionHistory.pending(ChatToolCall("synthetic-$turn", "read_test", """{"source":"synthetic"}""")),
                if (turn == 1) "exit=0; SYNTHETIC_COMPONENT_ALREADY_PRESENT; no installation needed" else "exit=0; SYNTHETIC_STEP_$turn"))))
            if (turn == 1) evidenceId = segment.id
        }
        repository.appendMessage(conversation.id, MessageRole.Assistant, "已停止本轮处理。", MessageStatus.Stopped, provider.defaultModel)
        repository.appendMessage(conversation.id, MessageRole.User, "根据已确认的结果，现在需要重新检查组件吗？只回答：不需要重查。", MessageStatus.Sent, provider.defaultModel)
        return conversation to evidenceId
    }

    @Test fun checkpointPersistsAndOriginalEvidenceCanBeReadThroughTheRealWireProtocol() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runContextCheckpointDiagnostics") == "true")
        val data = data()
        val model = LocalModel()
        val provider = ProviderConfig(id = "checkpoint-qa-${UUID.randomUUID()}", displayName = "Local checkpoint QA",
            baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "checkpoint-test")
        var conversationId: String? = null
        try {
            data.providers().upsertProvider(provider, null)
            val (conversation, evidenceId) = seed(data.chats(), provider)
            conversationId = conversation.id
            model.evidenceId = evidenceId
            val original = data.chats().getMessages(conversation.id)
            val manager = TaskContextManager(data.client(), data.chats(), limits)
            val prepared = manager.prepare(conversation, original, provider, null)
            val checkpoint = requireNotNull(prepared.checkpoint)
            assertEquals(checkpoint, data.chats().getContextCheckpoint(conversation.id))
            assertTrue(checkpoint.matches(original))
            assertTrue(checkpoint.summary.corrections.any { it.text.contains("不要再检查") })
            val summaryRequests = model.summaryRequests
            val reopened = TaskContextManager(data.client(), data.chats(), limits).prepare(conversation, original, provider, null)
            assertEquals(checkpoint, reopened.checkpoint)
            assertEquals(summaryRequests, model.summaryRequests)
            var request = TaskContextTools.attach(ChatRequestFactory.create(conversation, reopened.messages, checkpoint = checkpoint))
            val calls = mutableListOf<ChatToolCall>()
            withTimeout(20_000) { data.client().streamChat(request, provider, null).collect { delta ->
                when (delta) {
                    is ChatDelta.ToolCalls -> calls += delta.calls
                    is ChatDelta.Error -> error(delta.message)
                    else -> Unit
                }
            } }
            assertEquals(TaskContextTools.Read, calls.single().name)
            val evidence = TaskContextTools.read(calls.single().arguments, conversation.id, data.chats().getMessages(conversation.id))
            assertTrue(evidence.contains("SYNTHETIC_COMPONENT_ALREADY_PRESENT"))
            request = request.copy(messages = request.messages + ChatRequestMessage("assistant", "Read recorded evidence", toolCalls = calls) +
                ChatRequestMessage("tool", evidence, toolCallId = calls.single().id))
            val output = StringBuilder()
            withTimeout(20_000) { data.client().streamChat(request, provider, null).collect { delta ->
                when (delta) {
                    is ChatDelta.Content -> output.append(delta.text)
                    is ChatDelta.FullResponse -> output.append(delta.contentText)
                    is ChatDelta.Error -> error(delta.message)
                    else -> Unit
                }
            } }
            assertEquals("不需要重查。", output.toString())
            assertEquals(2, model.answerRequests)
            assertEquals(original, data.chats().getMessages(conversation.id))
            model.failure?.let { throw it }
        } finally {
            conversationId?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            model.close()
        }
        Unit
    }

    @Test fun liveDeepSeekSummarizesSyntheticTaskHistoryAndUsesTheCheckpoint() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveContextCheckpointDiagnostics") == "true")
        val data = data()
        val provider = data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        }
        assumeTrue("An existing direct DeepSeek provider is required", provider != null)
        val selected = requireNotNull(provider)
        val key = data.providers().getApiKey(selected)
        assumeTrue("An existing credential is required", !key.isNullOrBlank())
        var conversationId: String? = null
        try {
            val (conversation, _) = seed(data.chats(), selected)
            conversationId = conversation.id
            val original = data.chats().getMessages(conversation.id)
            val syntheticOutput = StringBuilder()
            var summaryRequests = 0
            val probe = object : com.flowchat.app.data.network.ChatCompletionClient {
                override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
                    summaryRequests++
                    data.client().streamChat(request, provider, apiKey).collect { delta ->
                        when (delta) {
                            is ChatDelta.Content -> syntheticOutput.append(delta.text)
                            is ChatDelta.FullResponse -> syntheticOutput.append(delta.contentText)
                            else -> Unit
                        }
                        emit(delta)
                    }
                }
            }
            val manager = TaskContextManager(probe, data.chats(), limits)
            val prepared = withTimeout(75_000) {
                manager.prepare(conversation, original, selected, key)
            }
            if (prepared.checkpoint == null) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                java.io.File(context.getExternalFilesDir(null), "synthetic-checkpoint-summary.txt")
                    .writeText("failure=${manager.lastFailureCode}\n${syntheticOutput.take(18_000)}")
            }
            val checkpoint = requireNotNull(prepared.checkpoint) { "Live summary did not produce a valid sourced checkpoint: ${manager.lastFailureCode}" }
            assertTrue(checkpoint.matches(original))
            val request = ChatRequestFactory.create(conversation, prepared.messages, checkpoint = checkpoint).copy(maxTokens = 512)
            val output = StringBuilder()
            withTimeout(60_000) { data.client().streamChat(request, selected, key).collect { delta ->
                when (delta) {
                    is ChatDelta.Content -> output.append(delta.text)
                    is ChatDelta.FullResponse -> output.append(delta.contentText)
                    is ChatDelta.Error -> error("Live checkpoint answer failed; credential omitted")
                    else -> Unit
                }
            } }
            assertTrue(output.toString().contains("不需要重查"))
            assertEquals(original, data.chats().getMessages(conversation.id))
            assertTrue(summaryRequests in 1..2)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.getExternalFilesDir(null), "synthetic-checkpoint-summary.txt").delete()
            java.io.File(context.getExternalFilesDir(null), "context-checkpoint-live-result.txt")
                .writeText("PASS: sourced checkpoint, unchanged synthetic transcript, answer uses prior evidence; summary_requests=$summaryRequests; answer_requests=1; answer=${output.toString().take(80)}")
        } finally { conversationId?.let { data.chats().deleteConversation(it) } }
    }

    private class LocalModel : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var evidenceId = ""
        @Volatile var summaryRequests = 0
        @Volatile var answerRequests = 0
        @Volatile var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "checkpoint-model") {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use { client -> try {
                    client.soTimeout = 10_000
                    val input = client.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32_768); header.append(b.toChar()) }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                    check(length in 1..500_000)
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val size = input.read(bytes, offset, length - offset); check(size > 0); offset += size }
                    val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val messages = request["messages"]!!.jsonArray
                    val system = messages.first().jsonObject["content"]!!.jsonPrimitive.content
                    val delta = if (system.contains("# Floryx context checkpoint writer")) {
                        summaryRequests++
                        val source = Json.parseToJsonElement(messages.last().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                        check(!source.toString().contains("PRIVATE_NOT_FOR_CHECKPOINT"))
                        val records = source["new_records"]!!.jsonArray.map { it.jsonObject }
                        val previous = source["previous_checkpoint"]?.let { CheckpointCodec.json.decodeFromJsonElement<TaskCheckpoint>(it) }
                        val first = records.first()
                        val objective = previous?.summary?.objective ?: ContextNote("完成合成测试任务", listOf(first["id"]!!.jsonPrimitive.content))
                        val corrections = previous?.summary?.corrections.orEmpty() + records.filter { it["content"]!!.jsonPrimitive.content.contains("不要再检查") }
                            .map { ContextNote("不要再检查组件，只处理剩余步骤。", listOf(it["id"]!!.jsonPrimitive.content)) }
                        val completed = previous?.summary?.completed.orEmpty() + records.filter { it["tool_evidence"]?.jsonPrimitive?.content.orEmpty().contains("COMPONENT_ALREADY_PRESENT") }
                            .map { ContextNote("组件存在已由工具确认，无需重新检查。", listOf(it["id"]!!.jsonPrimitive.content)) }
                        buildJsonObject { put("content", CheckpointCodec.json.encodeToString(TaskSummary(objective, corrections = corrections, completed = completed))) }
                    } else {
                        answerRequests++
                        check(messages.any { it.jsonObject["content"]?.jsonPrimitive?.content.orEmpty().startsWith("Conversation task checkpoint") })
                        if (answerRequests == 1) buildJsonObject { putJsonArray("tool_calls") { add(buildJsonObject {
                            put("index", 0); put("id", "read-original"); put("type", "function")
                            putJsonObject("function") { put("name", TaskContextTools.Read); put("arguments", """{"message_ids":["$evidenceId"]}""") }
                        }) } } else {
                            check(messages.last().jsonObject["role"]!!.jsonPrimitive.content == "tool")
                            check(messages.last().jsonObject["content"]!!.jsonPrimitive.content.contains("SYNTHETIC_COMPONENT_ALREADY_PRESENT"))
                            buildJsonObject { put("content", "不需要重查。") }
                        }
                    }
                    val finish = if (delta.containsKey("tool_calls")) "tool_calls" else "stop"
                    val response = "data: ${buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("delta", delta); put("finish_reason", JsonNull) }) } }}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n"
                    val body = response.toByteArray()
                    client.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                } catch (error: Throwable) { failure = error } }
            }
        }
        override fun close() { socket.close(); worker.join(1_000) }
    }
}
