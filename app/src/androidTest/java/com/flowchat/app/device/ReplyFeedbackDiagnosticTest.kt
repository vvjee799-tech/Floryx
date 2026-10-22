package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in emulator test: loopback model, real Linux command, real chat UI, no external account calls. */
class ReplyFeedbackDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun liveDeepSeekUnderstandsProgressAndEvidenceWithSyntheticData() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveReplyFeedbackDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        }
        assumeTrue("No existing direct DeepSeek provider is configured", provider != null)
        val selected = requireNotNull(provider)
        val key = data.providers().getApiKey(selected)
        assumeTrue("No existing DeepSeek credential is available", !key.isNullOrBlank())
        val conversation = Conversation(id = "synthetic-reply-qa", providerId = selected.id,
            modelName = selected.defaultModel, enableThinking = false, temperature = 0.2, maxTokens = 1200)
        var request = com.flowchat.app.domain.chat.ChatRequestFactory.create(conversation,
            listOf(Message(conversationId = conversation.id, role = MessageRole.User,
                content = "请读取测试账目并计算合计。处理前简短说明，最后先给出合计金额。"))).copy(
            tools = listOf(ChatToolDefinition("read_test_file", "Read a synthetic, read-only test ledger. No personal files are accessed.",
                buildJsonObject { put("type", "object"); putJsonObject("properties") {}; putJsonArray("required") {}; put("additionalProperties", false) })),
            toolChoice = "auto", parallelToolCalls = false)
        val progress = mutableListOf<String>()
        var answer = ""
        var requests = 0
        var reads = 0
        withTimeout(150_000) {
            for (round in 0 until 3) {
                requests++
                val content = StringBuilder()
                val reasoning = StringBuilder()
                var providerData: JsonArray? = null
                var calls = emptyList<ChatToolCall>()
                data.client().streamChat(request, selected, key).collect { delta ->
                    when (delta) {
                        is ChatDelta.Content -> content.append(delta.text)
                        is ChatDelta.Reasoning -> reasoning.append(delta.text)
                        is ChatDelta.FullResponse -> { content.append(delta.contentText); reasoning.append(delta.reasoningText) }
                        is ChatDelta.ToolCalls -> calls = delta.calls
                        is ChatDelta.ProviderData -> providerData = delta.blocks
                        is ChatDelta.Error -> error("Live provider request failed; credential omitted from diagnostics")
                        else -> Unit
                    }
                }
                if (calls.isEmpty()) { answer = content.toString(); break }
                check(calls.size == 1 && calls.single().name == "read_test_file" && reads == 0)
                reads++
                progress += content.toString()
                request = request.copy(messages = request.messages + ChatRequestMessage("assistant", content.toString(),
                    reasoningContent = reasoning.toString(), toolCalls = calls, providerData = providerData) +
                    ChatRequestMessage("tool", """{"source":"synthetic test ledger","currency":"CNY","items":[{"name":"A","amount":17},{"name":"B","amount":25}]}""", toolCallId = calls.single().id))
            }
        }
        assertEquals(1, reads)
        assertTrue(answer.contains("42"))
        assertTrue(progress.any { it.isNotBlank() })
        File(context.getExternalFilesDir(null), "reply-feedback").apply { mkdirs() }
        File(context.getExternalFilesDir(null), "reply-feedback/live-results.txt").writeText(
            "PASS: existing DeepSeek configuration, synthetic ledger only, model=${selected.defaultModel}, requests=$requests, reads=$reads\n" +
                "Progress: ${progress.joinToString("\n")}\nFinal: $answer")
    }

    @Test fun progressConclusionAndInterruptedReplyRemainDistinct() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runReplyFeedbackDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val environment = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = environment.settings().state.value
        val model = LocalModel()
        val provider = ProviderConfig(id = "reply-qa-${UUID.randomUUID()}", displayName = "Reply QA local",
            baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "reply-qa")
        var conversationId: String? = null
        fun capture(name: String) {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            val directory = File(context.getExternalFilesDir(null), "reply-feedback").apply { mkdirs() }
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun send(text: String) {
            compose.onNode(hasSetTextAction()).performTextInput(text)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription(context.getString(R.string.send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        }
        suspend fun awaitMessage(predicate: (Message) -> Boolean): Message = withTimeout(40_000) {
            while (true) {
                model.failure?.let { error(it) }
                data.chats().getMessages(requireNotNull(conversationId)).firstOrNull(predicate)?.let { return@withTimeout it }
                delay(100)
            }
            error("unreachable")
        }
        try {
            data.providers().upsertProvider(provider, null)
            conversationId = data.chats().createConversation(provider.id, provider.defaultModel).id
            environment.settings().setCommandExecutionEnabled(true)
            environment.settings().setPowerModeEnabled(false)
            environment.settings().setMemoryEnabled(false)
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Compose tests control the coroutine/frame clock; advance past the launch overlay before screenshots.
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            send("FEEDBACK_QA 运行本地测试并告诉我结果。")
            awaitMessage { it.role == MessageRole.Tool && it.status == MessageStatus.Streaming && it.content.contains("elapsed=1s") }
            compose.onNodeWithText(Preface).assertExists()
            compose.onNodeWithText(Reasoning).assertDoesNotExist()
            assertEquals(1, model.requests)
            capture("running")
            val final = awaitMessage { it.content == Answer && it.status == MessageStatus.Complete }
            assertEquals(ReplyPhase.Answer, final.replyPhase)
            assertEquals(2, model.requests)
            val messages = data.chats().getMessages(conversationId!!)
            assertEquals(ReplyPhase.Commentary, messages.first { it.content == Preface }.replyPhase)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.reply_activity)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(Preface).assertDoesNotExist()
            compose.onNodeWithText(Reasoning).assertDoesNotExist()
            compose.onNodeWithText(Answer).assertExists()
            capture("completed")
            compose.onNodeWithText(context.getString(R.string.reply_activity)).performClick()
            compose.onNodeWithText(Preface).assertExists()
            capture("expanded")
            compose.onNodeWithText(context.getString(R.string.reply_activity)).performClick()
            send("INTERRUPTED_QA 测试回复中断。")
            val interrupted = awaitMessage { it.status == MessageStatus.Failed && it.content.startsWith("已读取第一部分") }
            assertTrue(interrupted.content.contains("回复中断"))
            assertEquals(3, model.requests)
            assertTrue(model.sawPolicy)
            capture("interrupted")
            File(context.getExternalFilesDir(null), "reply-feedback/results.txt").writeText(
                "PASS: loopback provider, real Linux command, live progress, persisted commentary/answer phases, " +
                    "collapsed activity and reasoning, expandable history, evidence-based final response, " +
                    "truncated stream preserves partial text and is failed without retry. Exactly 3 HTTP requests.")
        } finally {
            conversationId?.let { owner ->
                environment.terminal().state.value.commands.filter { it.isRunning && environment.terminal().owns(owner, it.id) }
                    .forEach { environment.terminal().cancel(owner, it.id) }
            }
            environment.settings().setCommandExecutionEnabled(original.commandExecutionEnabled)
            environment.settings().setPowerModeEnabled(original.powerModeEnabled)
            environment.settings().setMemoryEnabled(original.memoryEnabled)
            conversationId?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            model.close()
        }
    }

    private class LocalModel : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var requests = 0
        @Volatile var failure: String? = null
        @Volatile var sawPolicy = false
        private val worker = thread(isDaemon = true, name = "reply-feedback-model") {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use { client ->
                    try {
                        client.soTimeout = 20_000
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val b = input.read(); check(b >= 0 && header.length < 32_768); header.append(b.toChar())
                        }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                        check(length in 1..1_000_000)
                        val bytes = ByteArray(length)
                        var offset = 0
                        while (offset < length) { val size = input.read(bytes, offset, length - offset); check(size > 0); offset += size }
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        val messages = request["messages"]!!.jsonArray
                        sawPolicy = messages.any { it.jsonObject["content"]?.jsonPrimitive?.content.orEmpty().contains("# FlowChat response behavior") }
                        requests++
                        val lastTool = messages.lastOrNull { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }
                        val event = when (requests) {
                            1 -> buildJsonObject {
                                put("reasoning_content", Reasoning); put("content", Preface)
                                putJsonArray("tool_calls") { add(buildJsonObject {
                                    put("index", 0); put("id", "feedback-command"); put("type", "function")
                                    putJsonObject("function") {
                                        put("name", "run_command")
                                        put("arguments", """{"environment":"linux","command":"printf FEEDBACK_BEGIN; sleep 5; printf FEEDBACK_OK","timeout_seconds":30}""")
                                    }
                                }) }
                            }
                            2 -> {
                                val result = Json.parseToJsonElement(lastTool!!.jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                                check(result["exit_code"]!!.jsonPrimitive.int == 0)
                                check(result["output"]!!.jsonPrimitive.content.contains("FEEDBACK_OK"))
                                buildJsonObject { put("content", Answer) }
                            }
                            3 -> buildJsonObject { put("content", "已读取第一部分") }
                            else -> error("Unexpected extra model request")
                        }
                        val chunk = buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                            put("delta", event)
                            if (requests != 3) put("finish_reason", if (requests == 1) "tool_calls" else "stop")
                        }) } }
                        val body = "data: $chunk\n\n".toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    } catch (error: Throwable) { failure = error.toString() }
                }
            }
        }
        override fun close() { socket.close(); worker.join(1_000) }
    }

    private companion object {
        const val Preface = "先运行一条本地测试命令，完成后检查输出。"
        const val Reasoning = "检查本地测试环境，再根据实际输出核对结果。"
        const val Answer = "本地命令执行成功，输出 FEEDBACK_OK，退出码为 0。"
    }
}
