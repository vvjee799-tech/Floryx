package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.attachment.AttachmentDocumentParser
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.ChatRequestFactory
import com.flowchat.app.domain.chat.TaskRecovery
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.chat.ChatViewModel
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/** Opt-in MuMu regressions. All records and model responses are synthetic and local. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProductReliabilityDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("runProductReliabilityDiagnostics") == "true"
    )

    private fun data() = EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AttachmentDiagnosticsEntryPoint::class.java
    )

    @Test fun bSparseSpreadsheetAndLongAttachmentStayReadable() = runBlocking<Unit> {
        enabled()
        val spreadsheet = zip("xl/worksheets/sheet1.xml" to """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
              <row r="1"><c r="A1" t="inlineStr"><is><t>Alice</t></is></c><c r="C1"><v>100</v></c></row>
            </sheetData></worksheet>
        """.trimIndent())
        val extracted = AttachmentDocumentParser.extract(
            "sparse.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            spreadsheet
        )
        assertTrue(extracted.contains("Alice\t\t100"))

        val tail = "UNIQUE_ATTACHMENT_TAIL_724198"
        val body = "长".repeat(40_000 - tail.length) + tail
        assertEquals(40_000, body.length)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = data()
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value
        val model = AttachmentToolModel(tail)
        val provider = ProviderConfig(id = "long-attachment-${UUID.randomUUID()}", displayName = "Local attachment QA",
            baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "attachment-local")
        var owner: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var collector: Job? = null
        try {
            settings.setPowerModeEnabled(false)
            settings.setCommandExecutionEnabled(false)
            settings.setMemoryEnabled(false)
            data.providers().upsertProvider(provider, null)
            val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
            owner = conversation.id
            data.chats().updateConversationSettings(owner, "Attachment QA", null, false, false, "", 0.0, 1.0, 256)
            val message = data.chats().appendMessage(conversation.id, MessageRole.User, "请查看附件",
                MessageStatus.Sent, provider.defaultModel, attachmentName = "long.txt", attachmentText = body)
            model.expectedMessageId = message.id
            val stored = data.chats().getMessages(conversation.id)
            assertEquals(body, stored.single().attachmentText)
            val request = ChatRequestFactory.create(conversation, stored)
            val inline = request.messages.last { it.role == "user" }.content.orEmpty()
            assertTrue(inline.contains("<attached-file"))
            assertTrue("The inline request must be bounded", inline.length < body.length)
            assertTrue("The inline request must preserve the beginning", inline.contains("长".repeat(100)))

            // Readback must use the original Room attachment, not the bounded model projection.
            val restored = StringBuilder()
            var offset = 0
            var hasMore: Boolean
            do {
                val readArguments = """{"message_id":"${message.id}","offset":$offset,"limit":6000}"""
                val page = Json.parseToJsonElement(
                    com.flowchat.app.domain.chat.AttachmentTools.read(readArguments, conversation.id, stored)
                ).jsonObject
                restored.append(page["text"]!!.jsonPrimitive.content)
                offset = page["next_offset"]?.jsonPrimitive?.intOrNull ?: body.length
                hasMore = page["has_more"]!!.jsonPrimitive.boolean
            } while (hasMore)
            assertEquals("Readback must cover every stored character", body, restored.toString())
            val denied = com.flowchat.app.domain.chat.AttachmentTools.read(
                """{"message_id":"${message.id}"}""", "another-conversation", stored)
            assertTrue(denied.contains("not_found_in_this_conversation"))
            val attached = com.flowchat.app.domain.chat.AttachmentTools.attach(request)
            assertTrue(attached.tools.any { it.name == com.flowchat.app.domain.chat.AttachmentTools.Read })

            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm: ChatViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java] }
            collector = launch(Dispatchers.Default) { vm.uiState.collect { } }
            scenario.onActivity { vm.selectConversation(conversation.id) }
            awaitState(vm) { it.currentConversation?.id == conversation.id && !it.isStreaming }
            scenario.onActivity { vm.updateInput("ATTACH_TOOL_VERIFY"); vm.send() }
            withTimeout(20_000) {
                while (data.chats().getMessages(conversation.id).none { it.role == MessageRole.Assistant &&
                        it.status == MessageStatus.Complete && it.content.contains("ATTACHMENT_TOOL_FINAL_OK") }) {
                    model.failure?.let { throw it }
                    delay(50)
                }
            }
            assertTrue("Model must receive a bounded preview first", model.sawBoundedPreview)
            assertTrue("Model must receive the requested Room tail as a tool result", model.sawTailInToolResult)
            assertEquals(2, model.requests)
            model.failure?.let { throw it }
        } finally {
            collector?.cancel()
            scenario?.close()
            owner?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            settings.setPowerModeEnabled(original.powerModeEnabled)
            settings.setCommandExecutionEnabled(original.commandExecutionEnabled)
            settings.setMemoryEnabled(original.memoryEnabled)
            model.close()
        }
    }

    @Test fun cTwoConversationsKeepDraftsAttachmentsAndRunningTasksIndependent() = runBlocking<Unit> {
        enabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = data()
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value
        val model = GatedModel()
        val provider = ProviderConfig(id = "reliability-${UUID.randomUUID()}", displayName = "Local reliability QA",
            baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "reliability-local")
        val ids = mutableListOf<String>()
        var scenario: ActivityScenario<MainActivity>? = null
        var collector: Job? = null
        try {
            settings.setPowerModeEnabled(false)
            settings.setMemoryEnabled(false)
            settings.setCommandExecutionEnabled(false)
            data.providers().upsertProvider(provider, null)
            val a = data.chats().createConversation(provider.id, provider.defaultModel).also { ids += it.id }
            val b = data.chats().createConversation(provider.id, provider.defaultModel).also { ids += it.id }
            data.chats().updateConversationSettings(a.id, "A", null, false, false, "", 0.0, 1.0, 256)
            data.chats().updateConversationSettings(b.id, "B", null, false, false, "", 0.0, 1.0, 256)
            model.gate("A_RUNNING")
            model.gate("B_STOP")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm: ChatViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java] }
            collector = launch(Dispatchers.Default) { vm.uiState.collect { } }

            scenario.onActivity {
                vm.selectConversation(a.id)
                vm.updateInput("A_RUNNING")
                vm.attachTextFile("A.txt", "A_ATTACHMENT_ONLY")
            }
            awaitState(vm) { it.currentConversation?.id == a.id && it.input == "A_RUNNING" &&
                it.pendingAttachment?.name == "A.txt" }
            scenario.onActivity { vm.selectConversation(b.id) }
            awaitState(vm) { it.currentConversation?.id == b.id && it.input.isEmpty() && it.pendingAttachment == null }
            scenario.onActivity {
                vm.updateInput("B_COMPLETE")
                vm.attachTextFile("B.txt", "B_ATTACHMENT_ONLY")
            }
            scenario.onActivity { vm.selectConversation(a.id) }
            awaitState(vm) { it.currentConversation?.id == a.id && it.input == "A_RUNNING" &&
                it.pendingAttachment?.text == "A_ATTACHMENT_ONLY" }
            scenario.onActivity { vm.send() }
            val aRequest = model.awaitRequest("A_RUNNING")
            assertTrue(aRequest.toString().contains("A_ATTACHMENT_ONLY"))
            assertFalse(aRequest.toString().contains("B_ATTACHMENT_ONLY"))

            scenario.onActivity { vm.selectConversation(b.id) }
            awaitState(vm) { it.currentConversation?.id == b.id && it.input == "B_COMPLETE" &&
                it.pendingAttachment?.text == "B_ATTACHMENT_ONLY" && !it.isStreaming }
            scenario.onActivity { vm.send() }
            val bRequest = model.awaitRequest("B_COMPLETE")
            assertTrue(bRequest.toString().contains("B_ATTACHMENT_ONLY"))
            assertFalse(bRequest.toString().contains("A_ATTACHMENT_ONLY"))
            awaitMessages(b.id) { rows -> rows.any { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } }
            awaitState(vm) { it.currentConversation?.id == b.id && !it.isStreaming }
            scenario.onActivity { vm.selectConversation(a.id) }
            awaitState(vm) { it.currentConversation?.id == a.id && it.isStreaming }

            scenario.onActivity {
                vm.selectConversation(b.id)
                vm.updateInput("B_STOP")
                vm.send()
            }
            model.awaitRequest("B_STOP")
            awaitState(vm) { it.currentConversation?.id == b.id && it.isStreaming }
            scenario.onActivity { vm.stop() }
            awaitMessages(b.id) { rows -> rows.any { it.role == MessageRole.Assistant &&
                it.status in setOf(MessageStatus.Stopped, MessageStatus.Cancelled) } }
            scenario.onActivity { vm.selectConversation(a.id) }
            awaitState(vm) { it.currentConversation?.id == a.id && it.isStreaming }
            model.release("A_RUNNING")
            awaitMessages(a.id) { rows -> rows.any { it.role == MessageRole.Assistant &&
                it.status == MessageStatus.Complete && it.content.contains("A_RUNNING_OK") } }
            awaitState(vm) { it.currentConversation?.id == a.id && !it.isStreaming }
            model.failure?.let { throw it }
        } finally {
            model.release("A_RUNNING")
            model.release("B_STOP")
            settings.setPowerModeEnabled(original.powerModeEnabled)
            settings.setMemoryEnabled(original.memoryEnabled)
            settings.setCommandExecutionEnabled(original.commandExecutionEnabled)
            collector?.cancel()
            scenario?.close()
            ids.forEach { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            model.close()
        }
    }

    @Test fun aOrphanedRoomStreamBecomesRecoverableAfterColdRecovery() = runBlocking<Unit> {
        enabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = data()
        val provider = ProviderConfig(id = "recovery-${UUID.randomUUID()}", displayName = "Local recovery QA",
            baseUrl = "http://127.0.0.1:1/v1", defaultModel = "recovery-local")
        var id: String? = null
        try {
            data.providers().upsertProvider(provider, null)
            val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
            val owner = conversation.id
            id = owner
            val title = "Recovery QA ${UUID.randomUUID().toString().take(8)}"
            data.chats().updateConversationTitle(owner, title)
            data.chats().appendMessage(owner, MessageRole.User, "RECOVER_SYNTHETIC", MessageStatus.Sent, provider.defaultModel)
            delay(5)
            val orphan = data.chats().appendMessage(owner, MessageRole.Assistant, "已完成第一部分。",
                MessageStatus.Streaming, provider.defaultModel)
            assertTrue(owner in data.chats().recoverInterruptedTasks())
            val recovered = data.chats().getMessages(owner).first { it.id == orphan.id }
            assertTrue(recovered.status in setOf(MessageStatus.Stopped, MessageStatus.Cancelled))
            assertTrue(recovered.content.contains("已完成第一部分。"))
            assertFalse(data.chats().getMessages(owner).any { it.status == MessageStatus.Streaming })
            assertTrue(TaskRecovery.isRecoveredInterruption(data.chats().getMessages(owner)))
            val request = ChatRequestFactory.create(data.chats().getConversation(owner)!!, data.chats().getMessages(owner))
            assertTrue(request.messages.any { it.role == "user" && it.content.orEmpty().contains("RECOVER_SYNTHETIC") })
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                lateinit var vm: ChatViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java] }
                val collector = launch(Dispatchers.Default) { vm.uiState.collect { } }
                try {
                    scenario.onActivity { vm.selectConversation(owner) }
                    awaitState(vm) { it.currentConversation?.id == owner && it.interruptedTask && !it.isStreaming }
                    compose.mainClock.advanceTimeBy(1_500)
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithContentDescription(context.getString(R.string.open_navigation_drawer))
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithContentDescription(context.getString(R.string.open_navigation_drawer)).performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText(title).performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(context.getString(R.string.interrupted_task_title))
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText(context.getString(R.string.interrupted_task_title)).assertExists()
                    compose.onNodeWithText(context.getString(R.string.device_task_resume)).assertExists()
                    compose.onNodeWithText(context.getString(R.string.device_task_end)).assertExists()
                    val output = File(context.getExternalFilesDir(null), "optimization/recovery.png")
                    output.parentFile?.mkdirs()
                    val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                        .takeScreenshot()
                    assertTrue("Expected recovery card screenshot", bitmap != null)
                    bitmap!!.useScreenshot(output)
                    compose.onNodeWithText(context.getString(R.string.device_task_end)).performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(context.getString(R.string.interrupted_task_title))
                            .fetchSemanticsNodes().isEmpty()
                    }
                    awaitMessages(owner) { rows -> !TaskRecovery.canResume(rows) && rows.any {
                        it.role == MessageRole.Assistant && it.status == MessageStatus.Complete &&
                            it.content.contains("Existing records and files have been retained")
                    } }
                    awaitState(vm) { it.currentConversation?.id == owner && !it.interruptedTask }
                    assertTrue(data.chats().getMessages(owner).any { it.id == orphan.id &&
                        it.content.contains("已完成第一部分。") && it.status == MessageStatus.Stopped })
                } finally { collector.cancel() }
            } finally { scenario.close() }
        } finally {
            id?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
        }
    }

    private suspend fun awaitState(vm: ChatViewModel, predicate: (com.flowchat.app.presentation.chat.ChatUiState) -> Boolean) {
        withTimeout(15_000) { while (!predicate(vm.uiState.value)) delay(25) }
    }

    private suspend fun awaitMessages(id: String, predicate: (List<Message>) -> Boolean) {
        withTimeout(20_000) { while (!predicate(data().chats().getMessages(id))) delay(50) }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(value.toByteArray())
            zip.closeEntry()
        } }
    }.toByteArray()

    private fun Bitmap.useScreenshot(output: File) {
        try { output.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { recycle() }
    }

    private class AttachmentToolModel(private val tail: String) : AutoCloseable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        @Volatile var expectedMessageId: String? = null
        @Volatile var requests = 0
        @Volatile var sawBoundedPreview = false
        @Volatile var sawTailInToolResult = false
        @Volatile var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "attachment-tool-model") {
            try {
                repeat(2) { index ->
                    server.accept().use { client ->
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
                        check(length in 1..1_000_000)
                        val body = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(body, offset, length - offset)
                            check(count > 0)
                            offset += count
                        }
                        val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                        requests++
                        val response = if (index == 0) {
                            check(request["tools"]!!.jsonArray.any { tool ->
                                tool.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "read_attachment"
                            })
                            check(request["messages"]!!.jsonArray.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }
                                .jsonObject["content"]!!.jsonPrimitive.content.contains("ATTACH_TOOL_VERIFY"))
                            sawBoundedPreview = request.toString().contains("preview_chars") && !request.toString().contains(tail)
                            check(sawBoundedPreview)
                            val messageId = requireNotNull(expectedMessageId)
                            val call = buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
                                putJsonObject("delta") { putJsonArray("tool_calls") { add(buildJsonObject {
                                    put("index", 0); put("id", "attachment-read-1"); put("type", "function")
                                    putJsonObject("function") {
                                        put("name", "read_attachment")
                                        put("arguments", """{"message_id":"$messageId","offset":39800,"limit":200}""")
                                    }
                                }) } }
                                put("finish_reason", JsonNull)
                            }) } }.toString()
                            "data: $call\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                        } else {
                            val tool = request["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "tool" }.jsonObject
                            check(tool["tool_call_id"]!!.jsonPrimitive.content == "attachment-read-1")
                            val result = Json.parseToJsonElement(tool["content"]!!.jsonPrimitive.content).jsonObject
                            sawTailInToolResult = result["text"]!!.jsonPrimitive.content.contains(tail)
                            check(sawTailInToolResult)
                            "data: {\"choices\":[{\"delta\":{\"content\":\"ATTACHMENT_TOOL_FINAL_OK\"},\"finish_reason\":null}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        }
                        val bytes = response.toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) { if (!server.isClosed) failure = error }
        }

        override fun close() { server.close(); worker.join(2_000) }
    }

    private class GatedModel : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        private val requests = LinkedBlockingQueue<Pair<String, JsonObject>>()
        @Volatile var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "reliability-model") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(socket) }.onFailure {
                    if (it !is java.io.IOException || !it.message.orEmpty().contains("Broken pipe", ignoreCase = true)) failure = it
                } }
            }
        }

        fun gate(marker: String) { gates[marker] = CountDownLatch(1) }
        fun release(marker: String) { gates[marker]?.countDown() }
        fun awaitRequest(marker: String): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val next = requests.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (next.first == marker) return next.second
                error("Unexpected model request ${next.first}; expected $marker")
            }
            error("No model request for $marker")
        }

        private fun serve(socket: Socket) {
            socket.use { client ->
                client.soTimeout = 20_000
                val input = client.getInputStream().buffered()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    check(byte >= 0 && header.length < 32_768)
                    header.append(byte.toChar())
                }
                val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                    .find(header)?.groupValues?.get(1)?.toInt() ?: error("Missing body length")
                check(length in 1..1_000_000)
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(body, offset, length - offset)
                    check(count > 0)
                    offset += count
                }
                val request = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                val userText = request["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" }
                    .jsonObject["content"].toString()
                val marker = listOf("A_RUNNING", "B_COMPLETE", "B_STOP").firstOrNull(userText::contains)
                    ?: error("Unknown synthetic request")
                requests.put(marker to request)
                gates[marker]?.await(60, TimeUnit.SECONDS)
                val response = "data: {\"choices\":[{\"delta\":{\"content\":\"${marker}_OK\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                val bytes = response.toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }

        override fun close() { server.close(); worker.join(2_000) }
    }
}
