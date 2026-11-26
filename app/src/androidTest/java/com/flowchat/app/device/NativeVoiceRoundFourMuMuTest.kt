package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.BuildConfig
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceWorkspaceProvider
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import com.flowchat.nativevoicebridge.VoiceBridgeWire.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in Round 4 MuMu acceptance for stop-zero-actions, lost receipt deduplication, follow-up reuse, and boundaries (0 formal model requests). */
class NativeVoiceRoundFourMuMuTest {
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

    private fun focusedWindow(): String {
        val output = shell("dumpsys window displays")
        val focus = output.lineSequence().map(String::trim).firstOrNull { it.startsWith("mCurrentFocus=") }
        check(focus != null && focus.startsWith("mCurrentFocus=Window{")) {
            "No active window focus: ${focus ?: output.take(1_500)}"
        }
        return focus
    }

    private fun callProvider(
        method: String,
        session: UUID?,
        request: UUID?,
        text: String? = null,
        protocolVersion: Int = VERSION,
        stopSource: String? = null
    ): Bundle {
        val extras = Bundle().apply {
            putInt(PROTOCOL_VERSION, protocolVersion)
            session?.let { putString(SESSION_ID, it.toString()) }
            request?.let { putString(REQUEST_ID, it.toString()) }
            text?.let { putString(TRANSCRIPT, it) }
            stopSource?.let { putString(STOP_SOURCE, it) }
        }
        return requireNotNull(targetContext.contentResolver.call(ENDPOINT, method, null, extras)) {
            "Null reply from NativeVoiceProvider for $method"
        }
    }

    @Test fun roundFourStopZeroActionsLostReceiptFollowUpAndBoundaries() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runNativeVoiceRound4") == "true")
        assertTrue("Expected nativeVoiceRealRuntime=true build", BuildConfig.NATIVE_VOICE_REAL_RUNTIME)
        if (InstrumentationRegistry.getArguments().getString("rebindMuMuAccessibility") == "true") {
            ActionOutcomeServiceReadiness().ensureService()
        }

        val entry = EntryPointAccessors.fromApplication(targetContext, NativeVoiceDiagnosticsEntryPoint::class.java)
        val settings = entry.settings()
        val providers = entry.providers()
        val chats = entry.chats()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val journal = TaskRunJournal(entry.database().taskRunDao())
        val gateway = entry.gateway()

        val initialSettings = settings.state.value
        val mockProviderId = "native-voice-r4-mock-${UUID.randomUUID().toString().take(8)}"
        val workspaceDir = NativeVoiceWorkspaceProvider.workspaceDir(targetContext)
        val receiptsPrefs = targetContext.getSharedPreferences("native_voice_receipts", Context.MODE_PRIVATE)
        val server = MockOpenAiServer()
        val createdConversations = mutableListOf<String>()

        try {
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

            providers.upsertProvider(
                ProviderConfig(
                    id = mockProviderId,
                    displayName = "Native Voice R4 Mock",
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

            // 1. Protocol incompatibility & ADB shell security boundary (including stop and resume).
            assertEquals(INCOMPATIBLE, callProvider(PROBE, null, null, protocolVersion = 99).getString(STATE))
            val resumeShell = shell(
                "content call --uri $ENDPOINT --method $RESUME --extra $PROTOCOL_VERSION:i:$VERSION " +
                    "--extra $SESSION_ID:s:${UUID.randomUUID()} --extra $REQUEST_ID:s:${UUID.randomUUID()}"
            )
            assertTrue("Expected SecurityException for shell resume: $resumeShell", resumeShell.contains("SecurityException"))
            val stopShell = shell(
                "content call --uri $ENDPOINT --method $STOP --extra $PROTOCOL_VERSION:i:$VERSION " +
                    "--extra $SESSION_ID:s:${UUID.randomUUID()} --extra $REQUEST_ID:s:${UUID.randomUUID()}"
            )
            assertTrue("Expected SecurityException for shell stop: $stopShell", stopShell.contains("SecurityException"))

            val preemptedSession = UUID.randomUUID()
            val preemptedEvent = UUID.randomUUID()
            val preemptedStop = callProvider(STOP, preemptedSession, preemptedEvent, stopSource = "voice_stop")
            assertEquals(STOPPED, preemptedStop.getString(STATE))
            val lateSubmit = callProvider(SUBMIT, preemptedSession, preemptedEvent, "打开设置")
            assertEquals(STOPPED, lateSubmit.getString(STATE))
            assertFalse(lateSubmit.getBoolean(NEWLY_ACCEPTED))
            assertNull(lateSubmit.getString(CONVERSATION_ID))
            assertEquals("A stop that arrives first must prevent model work", 0, server.requests.get())

            // 2a. Stop during YIELD_PANEL handshake: open_app must NEVER execute (0 actions, 0 extra requests).
            workspaceDir.deleteRecursively()
            shell("am force-stop com.android.settings")
            server.reset()
            server.script = { _, _ ->
                toolDelta("call_open_stop_a", AgentToolDefinitions.OpenAppToolName, """{"app_name":"com.android.settings"}""")
            }
            val stopSessionA = UUID.randomUUID()
            val stopEventA = UUID.randomUUID()
            val submitStopA = callProvider(SUBMIT, stopSessionA, stopEventA, "打开设置")
            val convStopA = requireNotNull(submitStopA.getString(CONVERSATION_ID))
            createdConversations += convStopA
            assertTrue(submitStopA.getBoolean(NEWLY_ACCEPTED))

            var statusStopA = submitStopA
            withTimeout(10_000) {
                while (!statusStopA.getBoolean(YIELD_PANEL)) {
                    delay(30)
                    statusStopA = callProvider(STATUS, stopSessionA, stopEventA)
                }
            }
            assertEquals(1, server.requests.get())

            val stoppedA = callProvider(STOP, stopSessionA, stopEventA, stopSource = "voice_stop")
            assertEquals(STOPPED, stoppedA.getString(STATE))
            assertFalse(stoppedA.getBoolean(YIELD_PANEL))
            assertEquals(STOPPED, callProvider(ACK_PANEL_YIELD, stopSessionA, stopEventA).getString(STATE))
            delay(1_500)

            assertEquals("No extra Flash request may start after STOP during yield", 1, server.requests.get())
            assertTrue("No tool messages may complete after STOP during yield",
                chats.getMessages(convStopA).none { it.role == MessageRole.Tool && it.content.contains("Opened app:") })
            val focusAfterYieldStop = focusedWindow()
            assertFalse("Settings must not open after STOP during yield: $focusAfterYieldStop",
                focusAfterYieldStop.contains(" com.android.settings/"))
            val claimStoppedA = callProvider(CLAIM_PLAYBACK, stopSessionA, stopEventA)
            assertEquals(STOPPED, claimStoppedA.getString(STATE))
            assertFalse(claimStoppedA.getBoolean(PLAYBACK_CLAIMED))
            assertNull(claimStoppedA.getString(REPLY))
            val resubmitStoppedA = callProvider(SUBMIT, stopSessionA, stopEventA, "打开设置")
            assertFalse(resubmitStoppedA.getBoolean(NEWLY_ACCEPTED))
            assertEquals(STOPPED, resubmitStoppedA.getString(STATE))
            withTimeout(5_000) {
                while (journal.latest(convStopA)?.state != STOPPED || convStopA in runtime.activeConversations.value) {
                    delay(30)
                }
            }

            // 2b. Stop mid-multi-step operation (after open_app + observe_screen, while round 3 press_home arrives).
            shell("am force-stop com.android.settings")
            shell("input keyevent KEYCODE_HOME")
            delay(300)
            server.reset()
            val round3Entered = CountDownLatch(1)
            val round3Release = CountDownLatch(1)
            server.script = { _, round ->
                when (round) {
                    1 -> toolDelta("call_open_b", AgentToolDefinitions.OpenAppToolName, """{"app_name":"com.android.settings"}""")
                    2 -> toolDelta("call_obs_b", AgentToolDefinitions.ObserveScreenToolName, "{}")
                    else -> {
                        round3Entered.countDown()
                        round3Release.await(5, TimeUnit.SECONDS)
                        toolDelta("call_home_b", AgentToolDefinitions.PressHomeToolName, "{}")
                    }
                }
            }

            val stopSessionB = UUID.randomUUID()
            val stopEventB = UUID.randomUUID()
            val submitStopB = callProvider(SUBMIT, stopSessionB, stopEventB, "打开设置并多步查看")
            val convStopB = requireNotNull(submitStopB.getString(CONVERSATION_ID))
            createdConversations += convStopB
            assertTrue(submitStopB.getBoolean(NEWLY_ACCEPTED))

            withTimeout(10_000) {
                while (!callProvider(STATUS, stopSessionB, stopEventB).getBoolean(YIELD_PANEL)) {
                    delay(30)
                }
            }
            callProvider(ACK_PANEL_YIELD, stopSessionB, stopEventB)

            assertTrue("Round 3 should be requested after open_app + observe_screen", round3Entered.await(15, TimeUnit.SECONDS))
            val toolsBeforeStopB = chats.getMessages(convStopB).count { it.role == MessageRole.Tool }
            assertEquals(2, toolsBeforeStopB)
            val focusBeforeActionStop = focusedWindow()
            assertTrue("Settings must be foreground before STOP: $focusBeforeActionStop",
                focusBeforeActionStop.contains(" com.android.settings/"))

            val stoppedB = callProvider(STOP, stopSessionB, stopEventB, stopSource = "native_interrupt:wake_word")
            assertEquals(STOPPED, stoppedB.getString(STATE))
            val stoppedReceipt = requireNotNull(receiptsPrefs.getString("request:$stopEventB", null))
            val stoppedSource = Json.parseToJsonElement(stoppedReceipt).jsonObject["stopSource"]?.jsonPrimitive?.content
            assertEquals("native_interrupt:wake_word", stoppedSource)
            // Release late round-3 response containing press_home after STOP is already committed.
            round3Release.countDown()
            delay(1_500)

            assertEquals("Zero new model requests allowed after STOP", 3, server.requests.get())
            assertEquals("Zero new tool calls allowed after STOP", toolsBeforeStopB,
                chats.getMessages(convStopB).count { it.role == MessageRole.Tool })
            assertTrue("press_home must not run after STOP",
                chats.getMessages(convStopB).none { it.role == MessageRole.Tool && it.content.contains(AgentToolDefinitions.PressHomeToolName) })
            val focusAfterActionStop = focusedWindow()
            assertTrue("Settings must stay foreground after STOP: $focusAfterActionStop",
                focusAfterActionStop.contains(" com.android.settings/"))
            withTimeout(5_000) {
                while (journal.latest(convStopB)?.state != STOPPED || convStopB in runtime.activeConversations.value) {
                    delay(30)
                }
            }
            val claimStoppedB = callProvider(CLAIM_PLAYBACK, stopSessionB, stopEventB)
            assertFalse(claimStoppedB.getBoolean(PLAYBACK_CLAIMED))
            assertNull(claimStoppedB.getString(REPLY))
            val resubmitStoppedB = callProvider(SUBMIT, stopSessionB, stopEventB, "打开设置并多步查看")
            assertFalse(resubmitStoppedB.getBoolean(NEWLY_ACCEPTED))
            assertEquals(STOPPED, resubmitStoppedB.getString(STATE))

            // 2c. Wake-word interrupt follow-up in the same session reuses the interrupted conversation and preserves context.
            val capturedWakeFollowupMessages = AtomicReference<JsonArray?>(null)
            val wakeFollowupAnswer = "已继续在刚才打断的会话中处理。"
            server.reset()
            server.script = { req, _ ->
                capturedWakeFollowupMessages.set(req["messages"]!!.jsonArray)
                textDelta(wakeFollowupAnswer)
            }
            val wakeFollowupEvent = UUID.randomUUID()
            val wakeFollowupSubmit = callProvider(SUBMIT, stopSessionB, wakeFollowupEvent, "不用继续看设置了，直接告诉我刚才打开了什么")
            assertTrue(wakeFollowupSubmit.getBoolean(NEWLY_ACCEPTED))
            assertEquals("Wake-word interrupt follow-up must reuse conversation", convStopB, wakeFollowupSubmit.getString(CONVERSATION_ID))
            withTimeout(15_000) {
                var st = callProvider(STATUS, stopSessionB, wakeFollowupEvent)
                while (st.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    delay(40)
                    st = callProvider(STATUS, stopSessionB, wakeFollowupEvent)
                }
                assertEquals(COMPLETED, st.getString(STATE))
            }
            val wakeFollowupHistory = requireNotNull(capturedWakeFollowupMessages.get())
                .joinToString("\n") { messageText(it.jsonObject) }
            assertTrue("Follow-up after wake-word interrupt must retain prior instruction", wakeFollowupHistory.contains("打开设置并多步查看"))
            assertTrue("Follow-up after wake-word interrupt must retain prior tool activity", wakeFollowupHistory.contains(AgentToolDefinitions.OpenAppToolName))
            assertEquals(wakeFollowupAnswer, callProvider(CLAIM_PLAYBACK, stopSessionB, wakeFollowupEvent).getString(REPLY))

            // 3. Lost receipt deduplication: discard initial SUBMIT reply, retry same eventId, recover via LATEST.
            shell("am force-stop com.android.settings")
            shell("input keyevent KEYCODE_HOME")
            delay(300)
            server.reset()
            val lostReceiptAnswer = "只执行了一次打开设置。"
            server.script = { _, round ->
                if (round == 1) toolDelta("call_open_loss", AgentToolDefinitions.OpenAppToolName, """{"app_name":"com.android.settings"}""")
                else textDelta(lostReceiptAnswer)
            }
            val lossSession = UUID.randomUUID()
            val lossEvent = UUID.randomUUID()
            // Intentionally discard the initial acceptance receipt.
            callProvider(SUBMIT, lossSession, lossEvent, "打开设置一次")
            // Caller retries SUBMIT with the exact same session + eventId.
            val retrySubmit = callProvider(SUBMIT, lossSession, lossEvent, "打开设置一次")
            assertFalse("Retry with same eventId must never start a second run", retrySubmit.getBoolean(NEWLY_ACCEPTED))
            val lossConv = requireNotNull(retrySubmit.getString(CONVERSATION_ID))
            createdConversations += lossConv

            withTimeout(15_000) {
                var st = callProvider(LATEST, lossSession, null)
                while (st.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    if (st.getBoolean(YIELD_PANEL)) {
                        callProvider(ACK_PANEL_YIELD, lossSession, lossEvent)
                    }
                    delay(40)
                    st = callProvider(LATEST, lossSession, null)
                }
                assertEquals(COMPLETED, st.getString(STATE))
                assertEquals(lossEvent.toString(), st.getString(REQUEST_ID))
                assertEquals(lostReceiptAnswer, st.getString(REPLY))
            }
            assertEquals("Task must run strictly once across retry", 2, server.requests.get())
            assertEquals(1, chats.getMessages(lossConv).count { it.role == MessageRole.Tool })
            assertTrue(callProvider(CLAIM_PLAYBACK, lossSession, lossEvent).getBoolean(PLAYBACK_CLAIMED))
            assertFalse(callProvider(CLAIM_PLAYBACK, lossSession, lossEvent).getBoolean(PLAYBACK_CLAIMED))

            // 4. Same-session follow-up ("换成第一行") reuses conversation without leaking previous reply.
            workspaceDir.deleteRecursively()
            assertTrue(workspaceDir.mkdirs())
            val firstLine = "第一行暗号：翡翠乌龙-${UUID.randomUUID().toString().take(8)}"
            val lastLine = "末行暗号：白桃乌龙-${UUID.randomUUID().toString().take(8)}"
            File(workspaceDir, "测试便签.txt").writeText("$firstLine\n第二行：中间内容\n$lastLine", Charsets.UTF_8)
            val answer1 = "最后一行是：$lastLine"
            val answer2 = "第一行是：$firstLine"

            server.reset()
            val capturedRound3Messages = AtomicReference<JsonArray?>(null)
            server.script = { req, round ->
                val messages = req["messages"]!!.jsonArray
                when (round) {
                    1 -> toolDelta("call_list_1", WorkspaceToolDefinitions.ListFiles, "{}")
                    2 -> toolDelta("call_read_1", WorkspaceToolDefinitions.Read, """{"path":"测试便签.txt","offset":0}""")
                    3 -> textDelta(answer1)
                    4 -> {
                        capturedRound3Messages.set(messages)
                        textDelta(answer2)
                    }
                    else -> textDelta(answer2)
                }
            }

            val noteSession = UUID.randomUUID()
            val noteEvent1 = UUID.randomUUID()
            val submit1 = callProvider(SUBMIT, noteSession, noteEvent1, "读出测试便签最后一行")
            val noteConv = requireNotNull(submit1.getString(CONVERSATION_ID))
            createdConversations += noteConv

            var status1 = submit1
            withTimeout(15_000) {
                while (status1.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    delay(40)
                    status1 = callProvider(STATUS, noteSession, noteEvent1)
                }
            }
            assertEquals(COMPLETED, status1.getString(STATE))
            val task1 = requireNotNull(status1.getString(TASK_ID))
            assertTrue(callProvider(CLAIM_PLAYBACK, noteSession, noteEvent1).getBoolean(PLAYBACK_CLAIMED))

            val noteEvent2 = UUID.randomUUID()
            val submit2 = callProvider(SUBMIT, noteSession, noteEvent2, "换成第一行")
            assertTrue(submit2.getBoolean(NEWLY_ACCEPTED))
            assertEquals("Follow-up in same session must reuse conversation", noteConv, submit2.getString(CONVERSATION_ID))
            assertNull("New follow-up must not leak previous turn's reply", submit2.getString(REPLY))
            assertNotEquals("New follow-up must not expose previous task ID", task1, submit2.getString(TASK_ID))
            assertEquals(MISSING, callProvider(STOP, noteSession, noteEvent1, stopSource = "voice_stop").getString(STATE))
            assertEquals(MISSING, callProvider(ACK_PANEL_YIELD, noteSession, noteEvent1).getString(STATE))

            var status2 = submit2
            withTimeout(15_000) {
                while (status2.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    delay(40)
                    status2 = callProvider(STATUS, noteSession, noteEvent2)
                }
            }
            server.failure.get()?.let { throw AssertionError("Mock server failure", it) }
            assertEquals(COMPLETED, status2.getString(STATE))
            val task2 = requireNotNull(status2.getString(TASK_ID))
            assertNotEquals(task1, task2)
            val claim2 = callProvider(CLAIM_PLAYBACK, noteSession, noteEvent2)
            assertTrue(claim2.getBoolean(PLAYBACK_CLAIMED))
            assertEquals(answer2, claim2.getString(REPLY))

            val historyTexts = requireNotNull(capturedRound3Messages.get()).joinToString("\n") { messageText(it.jsonObject) }
            assertTrue("Follow-up request must include prior turn reply", historyTexts.contains(answer1))
            assertTrue("Follow-up request must include new instruction", historyTexts.contains("换成第一行"))

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "\nNATIVE_VOICE_R4_MUMU PASS convStopA=$convStopA convStopB=$convStopB lossConv=$lossConv noteConv=$noteConv\n" +
                            "Focus after yield stop: $focusAfterYieldStop\n" +
                            "Focus before action stop: $focusBeforeActionStop\n" +
                            "Focus after action stop: $focusAfterActionStop\n"
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

        private val worker = thread(isDaemon = true, name = "native-voice-r4-mock") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { conn ->
                    try {
                        conn.soTimeout = 15_000
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
