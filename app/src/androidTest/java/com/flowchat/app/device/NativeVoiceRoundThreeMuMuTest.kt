package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.BuildConfig
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceWorkspaceProvider
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import com.flowchat.nativevoicebridge.VoiceBridgeWire.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in Round 3 real-runtime & panel-yield acceptance on MuMu (0 formal model requests). */
class NativeVoiceRoundThreeMuMuTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(command: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fds = automation.executeShellCommandRwe(command)
            fds[1].close()
            val errOut = AtomicReference("")
            val reader = thread(start = true) {
                errOut.set(ParcelFileDescriptor.AutoCloseInputStream(fds[2]).use { it.readBytes().toString(Charsets.UTF_8) })
            }
            val stdOut = ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes().toString(Charsets.UTF_8) }
            reader.join(5_000)
            return stdOut + errOut.get()
        }
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun callProvider(method: String, session: UUID?, request: UUID?, text: String? = null): Bundle {
        val extras = Bundle().apply {
            putInt(PROTOCOL_VERSION, VERSION)
            session?.let { putString(SESSION_ID, it.toString()) }
            request?.let { putString(REQUEST_ID, it.toString()) }
            text?.let { putString(TRANSCRIPT, it) }
        }
        return requireNotNull(targetContext.contentResolver.call(ENDPOINT, method, null, extras)) {
            "Null reply from NativeVoiceProvider for $method"
        }
    }

    @Test fun isolatedNoteCrossAppYieldAndSecurityBoundary() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runNativeVoiceRound3") == "true")
        assertTrue("Expected nativeVoiceRealRuntime=true build", BuildConfig.NATIVE_VOICE_REAL_RUNTIME)
        if (InstrumentationRegistry.getArguments().getString("rebindMuMuAccessibility") == "true") {
            ActionOutcomeServiceReadiness().ensureService()
        }

        val providerInfo = targetContext.packageManager.resolveContentProvider(AUTHORITY, 0)
        assertNotNull(providerInfo)
        assertEquals(targetContext.packageName, providerInfo?.processName)

        val entry = EntryPointAccessors.fromApplication(targetContext, NativeVoiceDiagnosticsEntryPoint::class.java)
        val settings = entry.settings()
        val providers = entry.providers()
        val chats = entry.chats()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val journal = TaskRunJournal(entry.database().taskRunDao())
        val gateway = entry.gateway()

        val initialSettings = settings.state.value
        val mockProviderId = "native-voice-r3-mock-${UUID.randomUUID().toString().take(8)}"
        val workspaceDir = NativeVoiceWorkspaceProvider.workspaceDir(targetContext)
        val receiptsPrefs = targetContext.getSharedPreferences("native_voice_receipts", Context.MODE_PRIVATE)
        val server = MockOpenAiServer()
        val createdConversations = mutableListOf<String>()

        try {
            val probe = callProvider(PROBE, null, null)
            assertEquals(READY, probe.getString(STATE))
            assertEquals("real", probe.getString(RUNTIME_MODE))

            // 1. Security boundaries from ADB shell (both native_voice and isolated workspace provider).
            val probeShell = shell("content call --uri $ENDPOINT --method $PROBE --extra $PROTOCOL_VERSION:i:$VERSION")
            assertTrue("Expected SecurityException for shell probe: $probeShell", probeShell.contains("SecurityException"))
            val submitShell = shell(
                "content call --uri $ENDPOINT --method $SUBMIT --extra $PROTOCOL_VERSION:i:$VERSION " +
                    "--extra $SESSION_ID:s:${UUID.randomUUID()} --extra $REQUEST_ID:s:${UUID.randomUUID()} --extra $TRANSCRIPT:s:hello"
            )
            assertTrue("Expected SecurityException for shell submit: $submitShell", submitShell.contains("SecurityException"))
            val workspaceShell = shell("content query --uri content://${targetContext.packageName}.native_voice_workspace/document/root")
            assertTrue(
                "Expected SecurityException/Permission Denial for isolated workspace query: $workspaceShell",
                workspaceShell.contains("SecurityException") || workspaceShell.contains("Permission Denial")
            )

            // Configure mock provider & settings (restored in finally).
            providers.upsertProvider(
                ProviderConfig(
                    id = mockProviderId,
                    displayName = "Native Voice R3 Mock",
                    baseUrl = "http://127.0.0.1:${server.port}/v1",
                    defaultModel = "mock-flash"
                ),
                "mock-key"
            )
            settings.setMemoryEnabled(false)
            settings.setPowerModeEnabled(true)
            settings.setBackgroundExecutionEnabled(false)
            settings.setCommandExecutionEnabled(false)
            settings.setPowerConnectionMode(PowerConnectionMode.Shizuku)
            settings.setAssistantModelSelection(
                AssistantModelSelection(mockProviderId, "mock-flash", enableThinking = false)
            )

            // 2. Case 1: Isolated workspace note reading (last line).
            workspaceDir.deleteRecursively()
            assertTrue(workspaceDir.mkdirs())
            val secretLine = "本轮暗号：青提乌龙-${UUID.randomUUID().toString().take(8)}"
            File(workspaceDir, "测试便签.txt").writeText(
                "第一行：普通备忘\n第二行：不要读错\n第三行：继续往下\n$secretLine",
                Charsets.UTF_8
            )
            val noteAnswer = "最后一行是：$secretLine"
            server.script = { req, round ->
                val messages = req["messages"]!!.jsonArray
                    if (round == 1) {
                        val sys = messageText(messages.first().jsonObject)
                        check(sys.contains("[Selected phone workspace]") && sys.contains("native-voice-workspace")) {
                            "Missing isolated workspace system context"
                        }
                        toolDelta("call_list", WorkspaceToolDefinitions.ListFiles, "{}")
                    } else if (round == 2) {
                        val toolMsg = messageText(messages.last().jsonObject)
                        check(toolMsg.contains("测试便签.txt")) { "workspace_list did not list note: $toolMsg" }
                        toolDelta("call_read", WorkspaceToolDefinitions.Read, """{"path":"测试便签.txt","offset":0}""")
                    } else {
                        val toolMsg = messageText(messages.last().jsonObject)
                        check(toolMsg.contains(secretLine)) { "workspace_read missing secretLine: $toolMsg" }
                        textDelta(noteAnswer)
                    }
            }

            val noteSession = UUID.randomUUID()
            val noteEvent = UUID.randomUUID()
            val noteSubmit = callProvider(SUBMIT, noteSession, noteEvent, "读出测试便签最后一行")
            val noteConv = noteSubmit.getString(CONVERSATION_ID)
            assertNotNull(noteConv)
            createdConversations += requireNotNull(noteConv)
            assertTrue(noteSubmit.getBoolean(NEWLY_ACCEPTED))

            var noteStatus = noteSubmit
            withTimeout(15_000) {
                while (noteStatus.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    assertFalse("Workspace read must not trigger panel yield", noteStatus.getBoolean(YIELD_PANEL))
                    delay(50)
                    noteStatus = callProvider(STATUS, noteSession, noteEvent)
                }
            }
            server.failure.get()?.let { throw AssertionError("Mock server failure", it) }
            assertEquals(COMPLETED, noteStatus.getString(STATE))
            assertEquals("real", noteStatus.getString(RUNTIME_MODE))
            assertEquals(mockProviderId, noteStatus.getString(PROVIDER_ID))
            assertEquals("mock-flash", noteStatus.getString(MODEL_NAME))
            assertFalse(noteStatus.getBoolean(YIELD_PANEL))

            val noteClaim = callProvider(CLAIM_PLAYBACK, noteSession, noteEvent)
            assertTrue(noteClaim.getBoolean(PLAYBACK_CLAIMED))
            assertEquals(noteAnswer, noteClaim.getString(REPLY))
            assertFalse(callProvider(CLAIM_PLAYBACK, noteSession, noteEvent).getBoolean(PLAYBACK_CLAIMED))

            val noteRun = journal.latest(noteConv)!!
            assertEquals(COMPLETED, noteRun.state)
            assertEquals(
                noteAnswer,
                chats.getMessages(noteConv).single { it.id == noteRun.assistantMessageId }.content
            )

            // 3. Case 2: Cross-app operation (open_app + observe_screen) & panel yield handshake.
            ActionOutcomeServiceReadiness().ensureService()
            shell("input keyevent KEYCODE_HOME")
            delay(300)
            gateway.refreshAccessibilityConnection()
            withTimeout(5_000) {
                while (!gateway.accessibilityState.value.isConnected || FlowChatAccessibilityService.current() == null) {
                    gateway.refreshAccessibilityConnection()
                    delay(50)
                }
            }
            workspaceDir.deleteRecursively()
            shell("am force-stop com.android.settings")

            server.reset()
            val crossAppAnswer = "已打开设置并核对页面，结果为 376。"
            server.script = { req, round ->
                val messages = req["messages"]!!.jsonArray
                when (round) {
                    1 -> toolDelta("call_open", AgentToolDefinitions.OpenAppToolName, """{"app_name":"com.android.settings"}""")
                    2 -> {
                        val toolMsg = messageText(messages.last().jsonObject)
                        check(toolMsg.contains("Opened app:") && toolMsg.contains("com.android.settings")) {
                            "open_app tool result unexpected: $toolMsg"
                        }
                        toolDelta("call_observe", AgentToolDefinitions.ObserveScreenToolName, "{}")
                    }
                    else -> {
                        val toolMsg = messageText(messages.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject)
                        check(toolMsg.contains("com.android.settings") && !toolMsg.contains("error_code")) {
                            "observe_screen tool result unexpected: $toolMsg"
                        }
                        textDelta(crossAppAnswer)
                    }
                }
            }

            val uiSession = UUID.randomUUID()
            val uiEvent = UUID.randomUUID()
            val uiSubmit = callProvider(SUBMIT, uiSession, uiEvent, "打开设置并看一下屏幕")
            val uiConv = uiSubmit.getString(CONVERSATION_ID)
            assertNotNull(uiConv)
            createdConversations += requireNotNull(uiConv)
            assertTrue(uiSubmit.getBoolean(NEWLY_ACCEPTED))

            // Wait until Floryx requests panel yield before executing open_app.
            var uiStatus = uiSubmit
            withTimeout(10_000) {
                while (!uiStatus.getBoolean(YIELD_PANEL)) {
                    assertTrue(
                        "Unexpected terminal state while awaiting panel yield: ${uiStatus.getString(STATE)}",
                        uiStatus.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)
                    )
                    delay(30)
                    uiStatus = callProvider(STATUS, uiSession, uiEvent)
                }
            }
            // Confirm prepareForExternalUiAction blocks until ACK_PANEL_YIELD arrives.
            delay(250)
            assertEquals("open_app must wait for ACK_PANEL_YIELD before finishing round 1", 1, server.requests.get())
            assertTrue(callProvider(STATUS, uiSession, uiEvent).getBoolean(YIELD_PANEL))

            val acked = callProvider(ACK_PANEL_YIELD, uiSession, uiEvent)
            assertFalse(acked.getBoolean(YIELD_PANEL))

            withTimeout(20_000) {
                uiStatus = callProvider(STATUS, uiSession, uiEvent)
                while (uiStatus.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    assertFalse("Subsequent UI actions must not re-request panel yield", uiStatus.getBoolean(YIELD_PANEL))
                    delay(50)
                    uiStatus = callProvider(STATUS, uiSession, uiEvent)
                }
            }
            server.failure.get()?.let { throw AssertionError("Mock server failure", it) }
            assertEquals(COMPLETED, uiStatus.getString(STATE))
            assertFalse(uiStatus.getBoolean(YIELD_PANEL))
            assertEquals(3, server.requests.get())

            val uiClaim = callProvider(CLAIM_PLAYBACK, uiSession, uiEvent)
            assertTrue(uiClaim.getBoolean(PLAYBACK_CLAIMED))
            assertEquals(crossAppAnswer, uiClaim.getString(REPLY))

            val uiRun = journal.latest(uiConv)!!
            assertEquals(COMPLETED, uiRun.state)
            val uiMessages = chats.getMessages(uiConv)
            val attachedMsg = uiMessages.single { it.id == uiRun.assistantMessageId }
            assertEquals(MessageStatus.Complete, attachedMsg.status)
            assertEquals(crossAppAnswer, attachedMsg.content)
            assertNotNull(FlowChatAccessibilityService.current())
            assertEquals(0, runtime.activeConversations.value.size)

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "\nNATIVE_VOICE_R3_MUMU PASS noteConv=$noteConv uiConv=$uiConv secretLine=$secretLine\n"
                    )
                }
            )
        } finally {
            server.close()
            workspaceDir.deleteRecursively()
            receiptsPrefs.edit().clear().commit()
            withContext(Dispatchers.Main) {
                createdConversations.forEach { runtime.stopFromAssistant(it) }
                createdConversations.forEach { runtime.deleteConversation(it) }
            }
            withTimeout(5_000) {
                while (createdConversations.any { chats.getConversation(it) != null }) delay(50)
            }
            providers.deleteProvider(mockProviderId)
            settings.setMemoryEnabled(initialSettings.memoryEnabled)
            settings.setPowerModeEnabled(initialSettings.powerModeEnabled)
            settings.setBackgroundExecutionEnabled(initialSettings.backgroundExecutionEnabled)
            settings.setCommandExecutionEnabled(initialSettings.commandExecutionEnabled)
            settings.setPowerConnectionMode(initialSettings.powerConnectionMode)
            settings.setAssistantModelSelection(initialSettings.assistantModelSelection)
            withContext(Dispatchers.IO) {
                targetContext.getSharedPreferences("app_controls", Context.MODE_PRIVATE).edit().commit()
            }
            shell("am force-stop com.android.settings")
            shell("input keyevent KEYCODE_HOME")
        }
    }

    private fun messageText(message: JsonObject): String {
        val content: JsonElement = message["content"] ?: return ""
        return when (content) {
            is JsonArray -> content.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("\n")
            else -> content.jsonPrimitive.content
        }
    }

    private fun toolDelta(callId: String, name: String, argsJson: String): JsonObject = buildJsonObject {
        putJsonArray("tool_calls") {
            add(buildJsonObject {
                put("index", 0)
                put("id", callId)
                put("type", "function")
                putJsonObject("function") {
                    put("name", name)
                    put("arguments", argsJson)
                }
            })
        }
    }

    private fun textDelta(content: String): JsonObject = buildJsonObject {
        put("content", content)
    }

    private class MockOpenAiServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requests = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        @Volatile var script: (JsonObject, Int) -> JsonObject = { _, _ -> buildJsonObject { put("content", "ok") } }

        private val worker = thread(isDaemon = true, name = "native-voice-r3-mock") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { conn ->
                    try {
                        conn.soTimeout = 10_000
                        val input = conn.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            check(b >= 0 && header.length < 32_768)
                            header.append(b.toChar())
                        }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                            .find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        val bytes = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val read = input.read(bytes, offset, length - offset)
                            check(read > 0)
                            offset += read
                        }
                        val req = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        val round = requests.incrementAndGet()
                        val delta = script(req, round)
                        val finish = if (delta.containsKey("tool_calls")) "tool_calls" else "stop"
                        val sse = "data: ${buildJsonObject {
                            putJsonArray("choices") {
                                add(buildJsonObject {
                                    put("delta", delta)
                                    put("finish_reason", JsonNull)
                                })
                            }
                        }}\n\n" +
                            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\n" +
                            "data: [DONE]\n\n"
                        val body = sse.toByteArray(Charsets.UTF_8)
                        conn.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/event-stream\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                            )
                            write(body)
                            flush()
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
        }

        fun reset() {
            requests.set(0)
            failure.set(null)
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }
    }
}
