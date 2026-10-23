package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.ChatRequestFactory
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in MuMu test. Synthetic records and a loopback endpoint; no real accounts or external API. */
class TaskContinuationDiagnosticTest {
    @Test fun stoppedTaskEvidenceSurvivesRoomAndReachesTheActualProviderRequest() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runTaskContinuationDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val provider = ProviderConfig(id = "continuation-qa-${UUID.randomUUID()}", displayName = "Local continuity QA",
            baseUrl = "http://127.0.0.1:${server.localPort}", defaultModel = "continuation-test")
        var failure: Throwable? = null
        var received: JsonObject? = null
        val worker = thread(isDaemon = true, name = "continuation-qa") {
            try {
                server.accept().use { client ->
                    client.soTimeout = 10_000
                    val input = client.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32_768); header.append(b.toChar()) }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                    check(length in 1..500_000)
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val n = input.read(body, offset, length - offset); check(n > 0); offset += n }
                    received = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                    val response = "data: {\"choices\":[{\"delta\":{\"content\":\"CONTINUITY_OK\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    val bytes = response.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (error: Throwable) { failure = error }
        }
        var conversationId: String? = null
        try {
            data.providers().upsertProvider(provider, null)
            val conversation = data.chats().createConversation(provider.id, provider.defaultModel).copy(enableThinking = false)
            conversationId = conversation.id
            val repository = data.chats()
            repository.appendMessage(conversation.id, MessageRole.User, "检查组件并设置测试项", MessageStatus.Sent, provider.defaultModel)
            val segment = repository.appendMessage(conversation.id, MessageRole.Assistant, "已确认组件存在，测试项已关闭。", MessageStatus.Complete, provider.defaultModel)
            repository.updateMessage(segment.id, segment.content, MessageStatus.Complete, "PRIVATE_NOT_FOR_HISTORY", replyPhase = ReplyPhase.Commentary)
            val steps = listOf(
                TaskExecutionHistory.completed(TaskExecutionHistory.pending(ChatToolCall("probe", "run_command", """{"command":"synthetic-read-only-probe"}""")), "exit=0; COMPONENT_ALREADY_PRESENT; toggle=false"),
                TaskExecutionHistory.interrupted(TaskExecutionHistory.pending(ChatToolCall("tap", "tap_ui_element", """{"index":2,"screen_id":"old"}""")))
            )
            repository.updateExecutionContext(segment.id, TaskExecutionHistory.encode(steps))
            repository.appendMessage(conversation.id, MessageRole.Assistant, "已停止本轮处理。", MessageStatus.Stopped, provider.defaultModel)
            repository.appendMessage(conversation.id, MessageRole.User, "不要重复检查组件，只处理剩余部分", MessageStatus.Sent, provider.defaultModel)
            val saved = repository.getMessages(conversation.id)
            assertEquals(TaskExecutionHistory.encode(steps), saved.first { it.id == segment.id }.executionContext)
            val request = ChatRequestFactory.create(conversation, saved)
            val output = StringBuilder()
            data.client().streamChat(request, provider, null).collect { delta ->
                when (delta) {
                    is ChatDelta.Content -> output.append(delta.text)
                    is ChatDelta.FullResponse -> output.append(delta.contentText)
                    is ChatDelta.Error -> error(delta.message)
                    else -> Unit
                }
            }
            worker.join(2_000)
            failure?.let { throw it }
            assertEquals("CONTINUITY_OK", output.toString())
            val wireMessages = requireNotNull(received)["messages"]!!.jsonArray
            val text = wireMessages.joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content.orEmpty() }
            assertTrue(text.contains("COMPONENT_ALREADY_PRESENT"))
            assertTrue(text.contains("synthetic-read-only-probe"))
            assertTrue(text.contains("toggle=false"))
            assertTrue(text.contains("interrupted_unconfirmed"))
            assertTrue(text.contains("Segment status: Stopped"))
            assertFalse(text.contains("PRIVATE_NOT_FOR_HISTORY"))
            assertTrue(wireMessages.none { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" || it.jsonObject.containsKey("tool_calls") })
            assertEquals("不要重复检查组件，只处理剩余部分", wireMessages.last().jsonObject["content"]!!.jsonPrimitive.content)
        } finally {
            server.close()
            worker.join(1_000)
            conversationId?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
        }
    }
}
