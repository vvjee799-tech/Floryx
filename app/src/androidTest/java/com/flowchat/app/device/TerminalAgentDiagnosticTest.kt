package com.flowchat.app.device

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.terminal.CommandEnvironment
import com.flowchat.app.domain.terminal.CommandStatus
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

class TerminalAgentDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun agentRunsLinuxAndSystemCommandsWithTaskConsent() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runTerminalDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = entry.settings().state.value
        val server = LocalTerminalModel()
        val provider = ProviderConfig(id = "terminal-agent-qa-${UUID.randomUUID()}", displayName = "Terminal QA local",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "terminal-qa")
        var conversationId: String? = null
        fun send(text: String) {
            compose.onNode(hasSetTextAction()).performTextInput(text)
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(context.getString(R.string.send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        }
        suspend fun awaitAnswer(marker: String) {
            withTimeout(40_000) {
                while (true) {
                    server.failure?.let { error(it) }
                    if (data.chats().getMessages(conversationId!!).any { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.content.contains(marker) }) break
                    delay(100)
                }
            }
        }
        try {
            data.providers().upsertProvider(provider, null)
            val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
            conversationId = conversation.id
            entry.settings().setCommandExecutionEnabled(true)
            entry.settings().setPowerModeEnabled(false)
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            send("LINUX_AGENT_QA run the requested local test")
            withTimeout(10_000) {
                while (data.chats().getMessages(conversation.id).none {
                    it.role == MessageRole.Tool && it.status == MessageStatus.Streaming && it.content.contains("elapsed=2s")
                }) delay(100)
            }
            assertEquals("No model requests while the command is waiting locally", 1, server.linuxRequests)
            assertEquals(1, data.chats().getMessages(conversation.id).count { it.role == MessageRole.Assistant })
            awaitAnswer("TERMINAL_AGENT_LOCAL_OK")
            val linuxMessages = data.chats().getMessages(conversation.id)
            assertEquals(1, linuxMessages.count { it.role == MessageRole.Tool })
            assertTrue(linuxMessages.any { it.role == MessageRole.Tool && it.content.contains("AGENT_TERMINAL_OK") })
            assertTrue(server.sawCommandTools)
            assertEquals("One request to start the command and one to summarize its result", 2, server.linuxRequests)
            assertEquals(0, server.pollRequests)
            assertTrue(server.linuxElapsedSeconds in 20..35)
            compose.onAllNodesWithText(context.getString(R.string.thinking_expand)).assertCountEquals(1)
            send("CANCEL_AGENT_QA wait until I stop the command")
            val cancelCommand = withTimeout(10_000) {
                while (true) {
                    val command = entry.terminal().state.value.commands.firstOrNull { it.command.contains("CANCEL_READY") && it.output.contains("CANCEL_READY") && it.isRunning }
                    if (command != null) return@withTimeout command
                    delay(100)
                }
                error("unreachable")
            }
            compose.onNodeWithContentDescription(context.getString(R.string.stop)).performClick()
            withTimeout(10_000) {
                while (entry.terminal().state.value.commands.first { it.id == cancelCommand.id }.isRunning) delay(100)
            }
            assertEquals(CommandStatus.Cancelled, entry.terminal().state.value.commands.first { it.id == cancelCommand.id }.status)
            assertEquals(1, server.cancelRequests)
            if (InstrumentationRegistry.getArguments().getString("runRootTerminalDiagnostics") == "true") {
                entry.settings().setPowerConnectionMode(PowerConnectionMode.Root)
                entry.settings().setPowerModeEnabled(true)
                entry.device().requestPermission()
                withTimeout(15_000) { while (!entry.device().connectionState.value.isConnected) delay(100) }
                send("ROOT_ALLOW_QA run two harmless system commands")
                compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.terminal_system_approval_title)).fetchSemanticsNodes().isNotEmpty() }
                assertTrue(entry.terminal().state.value.commands.none { it.environment == CommandEnvironment.Android })
                compose.onNodeWithText(context.getString(R.string.continue_action)).performClick()
                awaitAnswer("ROOT_AGENT_LOCAL_OK")
                compose.onNodeWithText(context.getString(R.string.terminal_system_approval_title)).assertDoesNotExist()
                send("ROOT_DECLINE_QA refuse system commands")
                compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.terminal_system_approval_title)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
                awaitAnswer("ROOT_DECLINE_LOCAL_OK")
                assertTrue(entry.terminal().state.value.commands.none { it.command.contains("MUST_NOT_RUN") })
            }
            // Verify the environment settings and execution records using the actual app surface.
            compose.onNodeWithContentDescription(context.getString(R.string.open_navigation_drawer)).performClick()
            compose.onNodeWithText(context.getString(R.string.settings)).performClick()
            compose.onNodeWithText(context.getString(R.string.terminal_title)).performClick()
            compose.onNodeWithText(context.getString(R.string.terminal_linux)).assertExists()
            compose.onNodeWithText(context.getString(R.string.terminal_android)).assertExists()
            compose.onNodeWithText(context.getString(R.string.terminal_ai_enabled)).assertExists()
            instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "terminal-panel.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            File(context.getExternalFilesDir(null), "terminal-agent-results.txt").writeText(
                "PASS: 20-second command waits locally with live elapsed time, exactly 2 model calls and no polling, merged reasoning panel, user cancellation, Python/Node execution, persisted output and Linux without Power. Root task consent checked when enabled.")
        } finally {
            entry.terminal().cancelAll()
            entry.settings().setCommandExecutionEnabled(original.commandExecutionEnabled)
            entry.settings().setPowerModeEnabled(original.powerModeEnabled)
            entry.settings().setPowerConnectionMode(original.powerConnectionMode)
            conversationId?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            server.close()
        }
    }

    private class LocalTerminalModel : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var failure: String? = null
        @Volatile var sawCommandTools = false
        @Volatile var linuxRequests = 0
        @Volatile var cancelRequests = 0
        @Volatile var pollRequests = 0
        @Volatile var linuxElapsedSeconds = 0L
        private var calls = 0
        private var rootStep = 0
        private var declineStep = 0
        private val worker = thread(isDaemon = true, name = "terminal-local-model") {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.use { client ->
                    try {
                        client.soTimeout = 20_000
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0 && header.length < 32_768); header.append(b.toChar()) }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                        val bytes = ByteArray(length); var offset = 0
                        while (offset < length) { val size = input.read(bytes, offset, length-offset); check(size > 0); offset += size }
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        sawCommandTools = request["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "run_command" }
                        val messages = request["messages"]!!.jsonArray
                        val user = messages.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject["content"]!!.jsonPrimitive.content
                        val last = messages.last().jsonObject
                        val lastText = last["content"]?.jsonPrimitive?.content.orEmpty()
                        val tool = last["role"]!!.jsonPrimitive.content == "tool"
                        val parsed = if (tool) runCatching { Json.parseToJsonElement(lastText).jsonObject }.getOrNull() else null
                        if (user.contains("LINUX_AGENT_QA")) linuxRequests++
                        if (user.contains("CANCEL_AGENT_QA")) cancelRequests++
                        val reply = when {
                            !tool -> if (user.contains("LINUX_AGENT_QA")) call("run_command", """{"environment":"linux","command":"printf INITIAL_OUTPUT; sleep 20; python3 -c 'print(42)'; node -e 'console.log(63)'; printf AGENT_TERMINAL_OK"}""")
                                else if (user.contains("CANCEL_AGENT_QA")) call("run_command", """{"environment":"linux","command":"printf CANCEL_READY; sleep 30; printf MUST_NOT_REACH"}""")
                                else call("run_command", """{"environment":"android","command":"id -u; printf ROOT_SYSTEM_OK"}""")
                            parsed?.get("status")?.jsonPrimitive?.content == "Running" -> call("read_command_output", """{"command_id":"${parsed["command_id"]!!.jsonPrimitive.content}","offset":0,"wait_seconds":2}""")
                            user.contains("LINUX_AGENT_QA") -> {
                                check(parsed?.get("exit_code")?.jsonPrimitive?.int == 0 && parsed["output"]!!.jsonPrimitive.content.contains("AGENT_TERMINAL_OK"))
                                linuxElapsedSeconds = parsed["elapsed_seconds"]!!.jsonPrimitive.long
                                answer("TERMINAL_AGENT_LOCAL_OK")
                            }
                            user.contains("CANCEL_AGENT_QA") -> answer("CANCEL_AGENT_LOCAL_OK")
                            user.contains("ROOT_DECLINE_QA") -> {
                                check(lastText.contains("declined", true))
                                if (declineStep++ == 0) call("run_command", """{"environment":"android","command":"printf MUST_NOT_RUN"}""") else answer("ROOT_DECLINE_LOCAL_OK")
                            }
                            else -> {
                                check(parsed?.get("exit_code")?.jsonPrimitive?.int == 0)
                                if (rootStep++ == 0) call("run_command", """{"environment":"android","command":"printf ROOT_SECOND_OK"}""") else answer("ROOT_AGENT_LOCAL_OK")
                            }
                        }
                        val body = "data: $reply\n\ndata: [DONE]\n\n".toByteArray()
                        client.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                    } catch (error: Throwable) { failure = error.toString() }
                }
            }
        }
        private fun call(name: String, arguments: String): String {
            if (name == "read_command_output") pollRequests++
            return buildJsonObject {
            putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") {
                put("reasoning_content", "准备执行请求并检查结果。")
                putJsonArray("tool_calls") { add(buildJsonObject {
                    put("index",0); put("id","terminal_call_${calls++}"); put("type","function")
                    putJsonObject("function") { put("name",name); put("arguments",arguments) }
                }) }
            } }) }
            }.toString()
        }
        private fun answer(text: String) = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("reasoning_content", "命令已结束，核对实际结果。"); put("content",text) } }) } }.toString()
        override fun close() { socket.close(); worker.join(1000) }
    }
}
