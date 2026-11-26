package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.BuildConfig
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.nativevoice.NativeVoiceWorkspaceProvider
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.DeviceActionOutcomePolicy
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceTextEditMode
import com.flowchat.app.domain.device.DeviceTextEditRejected
import com.flowchat.app.domain.device.DeviceTextEditRequest
import com.flowchat.app.domain.device.DeviceTextEditing
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.terminal.TerminalToolDefinitions
import com.flowchat.app.domain.tools.AgentToolDefinitions
import com.flowchat.nativevoicebridge.VoiceBridgeWire.*
import dagger.hilt.android.EntryPointAccessors
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in Round 5 pre-closeout automated replay on MuMu #1 (0 formal Flash requests). */
class NativeVoiceRoundFivePreCloseMuMuTest {
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

    private fun callProvider(
        method: String,
        session: UUID?,
        request: UUID?,
        text: String? = null,
        stopSource: String? = null
    ): Bundle {
        val extras = Bundle().apply {
            putInt(PROTOCOL_VERSION, VERSION)
            session?.let { putString(SESSION_ID, it.toString()) }
            request?.let { putString(REQUEST_ID, it.toString()) }
            text?.let { putString(TRANSCRIPT, it) }
            stopSource?.let { putString(STOP_SOURCE, it) }
        }
        return requireNotNull(targetContext.contentResolver.call(ENDPOINT, method, null, extras)) {
            "Null reply from NativeVoiceProvider for $method"
        }
    }

    private fun parseAppOpsMode(raw: String): String = when {
        raw.contains("ignore") -> "ignore"
        raw.contains("deny") -> "deny"
        raw.contains("allow") -> "allow"
        else -> "default"
    }

    @Test fun roundFivePreCloseoutReplaysOnMuMu() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runNativeVoiceRound5PreClose") == "true")
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
        val initialUsageStatsMode = parseAppOpsMode(
            shell("appops get ${targetContext.packageName} GET_USAGE_STATS")
        )
        val initialAccServices = shell("settings get secure enabled_accessibility_services").trim().ifBlank { "null" }
        val initialAccEnabled = shell("settings get secure accessibility_enabled").trim().ifBlank { "0" }

        val mockProviderId = "native-voice-r5pre-mock-${UUID.randomUUID().toString().take(8)}"
        val workspaceDir = NativeVoiceWorkspaceProvider.workspaceDir(targetContext)
        val receiptsPrefs = targetContext.getSharedPreferences("native_voice_receipts", Context.MODE_PRIVATE)
        val server = MockOpenAiServer()
        val createdConversations = mutableListOf<String>()

        try {
            providers.upsertProvider(
                ProviderConfig(
                    id = mockProviderId,
                    displayName = "Native Voice R5 PreClose Mock",
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
            workspaceDir.deleteRecursively()

            // Case A: WaitingForUser immediate native card feedback (never hangs on AI生成).
            shell("appops set ${targetContext.packageName} GET_USAGE_STATS ignore")
            val ignoredMode = shell("appops get ${targetContext.packageName} GET_USAGE_STATS")
            assertTrue("Expected GET_USAGE_STATS=ignore for Case A, got: $ignoredMode", ignoredMode.contains("ignore"))

            val round2Entered = CountDownLatch(1)
            val round2Release = CountDownLatch(1)
            server.reset()
            server.script = { _, round ->
                when (round) {
                    1 -> toolDelta("call_usage_a", AgentToolDefinitions.AppUsageSummaryToolName, "{}")
                    else -> {
                        round2Entered.countDown()
                        round2Release.await(15, TimeUnit.SECONDS)
                        textDelta("请在 Floryx 内开启应用使用情况权限。")
                    }
                }
            }
            val sessionA = UUID.randomUUID()
            val eventA = UUID.randomUUID()
            val submitA = callProvider(SUBMIT, sessionA, eventA, "帮我看看今天用了哪些应用")
            val convA = requireNotNull(submitA.getString(CONVERSATION_ID))
            createdConversations += convA
            assertTrue(submitA.getBoolean(NEWLY_ACCEPTED))

            assertTrue("Expected Round 2 to start after requestUsageAccessPermission", round2Entered.await(10, TimeUnit.SECONDS))
            var statusA = callProvider(STATUS, sessionA, eventA)
            withTimeout(5_000) {
                while (statusA.getString(STATE) != WAITING_USER) {
                    delay(30)
                    statusA = callProvider(STATUS, sessionA, eventA)
                }
            }
            assertEquals(WAITING_USER, statusA.getString(STATE))
            val promptA = statusA.getString(STATUS_TEXT).orEmpty()
            assertTrue("Expected Floryx confirmation prompt, got: $promptA", promptA.contains("Floryx"))
            assertFalse("WaitingForUser must not request overlay yield", statusA.getBoolean(YIELD_PANEL))

            val stoppedA = callProvider(STOP, sessionA, eventA, stopSource = "voice_stop")
            assertEquals(STOPPED, stoppedA.getString(STATE))
            round2Release.countDown()
            withTimeout(5_000) {
                while (journal.latest(convA)?.state != STOPPED || convA in runtime.activeConversations.value) {
                    delay(30)
                }
            }
            shell("appops set ${targetContext.packageName} GET_USAGE_STATS $initialUsageStatsMode")

            // Case B: Read-only device date/time directly answered without approval or panel yield.
            settings.setCommandExecutionEnabled(true)
            val timeAnswer = "现在是早上六点二十分，今天星期日。"
            server.reset()
            server.script = { req, round ->
                val messages = req["messages"]!!.jsonArray
                when (round) {
                    1 -> {
                        val toolNames = req["tools"]!!.jsonArray.map {
                            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                        }
                        check(AgentToolDefinitions.DeviceTimeToolName in toolNames) {
                            "Missing get_device_time in offered tools: $toolNames"
                        }
                        check(TerminalToolDefinitions.Status in toolNames) {
                            "Missing terminal_status in offered tools: $toolNames"
                        }
                        val systemTexts = messages.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                            .joinToString("\n") { messageText(it.jsonObject) }
                        check(systemTexts.contains("Native voice reply constraint")) {
                            "Missing concise native voice constraint in system messages"
                        }
                        toolDelta("call_time_b", AgentToolDefinitions.DeviceTimeToolName, "{}")
                    }
                    2 -> {
                        val toolMsg = messageText(messages.last().jsonObject)
                        check(
                            toolMsg.contains("local_date") &&
                                toolMsg.contains("local_time") &&
                                toolMsg.contains("weekday_zh") &&
                                toolMsg.contains("android_system_clock")
                        ) { "Unexpected get_device_time output: $toolMsg" }
                        toolDelta("call_term_status_b", TerminalToolDefinitions.Status, "{}")
                    }
                    else -> {
                        val toolMsg = messageText(messages.last().jsonObject)
                        val parsed = Json.parseToJsonElement(toolMsg).jsonObject
                        check(parsed.containsKey("device_time")) {
                            "terminal_status missing top-level device_time: $toolMsg"
                        }
                        val phoneObj = parsed["phone"]?.jsonObject
                        check(phoneObj == null || !phoneObj.containsKey("device_time")) {
                            "terminal_status.phone must remain invariant without device_time: $toolMsg"
                        }
                        textDelta(timeAnswer)
                    }
                }
            }
            val sessionB = UUID.randomUUID()
            val eventB = UUID.randomUUID()
            val submitB = callProvider(SUBMIT, sessionB, eventB, "现在几点了，今天星期几？")
            val convB = requireNotNull(submitB.getString(CONVERSATION_ID))
            createdConversations += convB
            assertTrue(submitB.getBoolean(NEWLY_ACCEPTED))

            var statusB = submitB
            withTimeout(15_000) {
                while (statusB.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    assertFalse("Read-only time query must never yield panel", statusB.getBoolean(YIELD_PANEL))
                    assertTrue(statusB.getString(STATE) != WAITING_USER)
                    delay(40)
                    statusB = callProvider(STATUS, sessionB, eventB)
                }
            }
            server.failure.get()?.let { throw AssertionError("Case B mock server failure", it) }
            assertEquals(COMPLETED, statusB.getString(STATE))
            assertFalse(statusB.getBoolean(YIELD_PANEL))
            val claimB = callProvider(CLAIM_PLAYBACK, sessionB, eventB)
            assertTrue(claimB.getBoolean(PLAYBACK_CLAIMED))
            assertEquals(timeAnswer, claimB.getString(REPLY))
            assertFalse(callProvider(CLAIM_PLAYBACK, sessionB, eventB).getBoolean(PLAYBACK_CLAIMED))
            assertTrue(
                "No run_command tool call allowed for read-only time query",
                chats.getMessages(convB).none { it.role == MessageRole.Tool && it.content.contains(TerminalToolDefinitions.Run) }
            )

            // Case C: Concise voice constraint sent & full >200-char card reply preserved on Dev side.
            settings.setCommandExecutionEnabled(false)
            val longCardReply = "## 今日待办汇总\n" +
                "**重点结论**：已完成核心安排梳理，请优先处理上午的两项核对工作，其余事项按顺序顺延。\n" +
                "- 第一项：上午九点前核对日程提醒、参会时间以及本周全部账单与日常开销预算明细，仔细确认没有任何遗漏条目或重复扣款。\n" +
                "- 第二项：下午按模块整理项目验收材料并逐项核实各端设备环境与配置清单，确保后续跨端联调与真机复测能够顺利推进。\n" +
                "- 第三项：傍晚检查全部备份文件、导出记录与恢复状态校验结果，确保所有重要任务数据都已保留最新完整副本可供查验。\n" +
                "详细说明与清单均已保留在结果卡片中，可随时查看。";
            assertTrue("Expected > 200 chars, got ${longCardReply.length}", longCardReply.length > 200)
            server.reset()
            server.script = { req, _ ->
                val systemTexts = req["messages"]!!.jsonArray
                    .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                    .joinToString("\n") { messageText(it.jsonObject) }
                check(systemTexts.contains("Native voice reply constraint")) {
                    "Missing concise voice constraint for Case C"
                }
                textDelta(longCardReply)
            }
            val sessionC = UUID.randomUUID()
            val eventC = UUID.randomUUID()
            val submitC = callProvider(SUBMIT, sessionC, eventC, "请分三点总结今天注意事项")
            val convC = requireNotNull(submitC.getString(CONVERSATION_ID))
            createdConversations += convC
            var statusC = submitC
            withTimeout(15_000) {
                while (statusC.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    delay(40)
                    statusC = callProvider(STATUS, sessionC, eventC)
                }
            }
            server.failure.get()?.let { throw AssertionError("Case C mock server failure", it) }
            assertEquals(COMPLETED, statusC.getString(STATE))
            val claimC = callProvider(CLAIM_PLAYBACK, sessionC, eventC)
            assertTrue(claimC.getBoolean(PLAYBACK_CLAIMED))
            assertEquals(longCardReply, claimC.getString(REPLY))

            // Case D: Unbound accessibility emits one-time clear hint without repeating long guidance.
            shell("settings put secure enabled_accessibility_services null")
            shell("settings put secure accessibility_enabled 0")
            gateway.refreshAccessibilityConnection()
            withTimeout(5_000) {
                while (gateway.accessibilityState.value.isConnected) {
                    gateway.refreshAccessibilityConnection()
                    delay(50)
                }
            }
            shell("am force-stop com.android.settings")
            shell("input keyevent KEYCODE_HOME")
            delay(300)

            val accOffReply = "已打开系统设置，但当前未开启无障碍服务，暂时无法读取页面或点击内部按钮。"
            server.reset()
            server.script = { req, round ->
                val messages = req["messages"]!!.jsonArray
                when (round) {
                    1 -> {
                        val toolNames = req["tools"]!!.jsonArray.map {
                            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                        }
                        check(AgentToolDefinitions.OpenAppToolName in toolNames) {
                            "open_app missing when accessibility disabled: $toolNames"
                        }
                        check(AgentToolDefinitions.ObserveScreenToolName !in toolNames) {
                            "observe_screen should not be offered when accessibility disconnected"
                        }
                        check(AgentToolDefinitions.TapUiElementToolName !in toolNames) {
                            "tap_ui_element should not be offered when accessibility disconnected"
                        }
                        val systemTexts = messages.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                            .joinToString("\n") { messageText(it.jsonObject) }
                        check(systemTexts.contains("Accessibility service is not bound")) {
                            "Expected unbound accessibility runtime guidance"
                        }
                        toolDelta("call_open_d", AgentToolDefinitions.OpenAppToolName, """{"app_name":"com.android.settings"}""")
                    }
                    2 -> {
                        val openResult = messageText(messages.last().jsonObject)
                        check(openResult.contains("Opened app:") && openResult.contains("accessibility_unavailable")) {
                            "Expected one-time accessibility_unavailable hint on open_app: $openResult"
                        }
                        toolDelta("call_obs_d", AgentToolDefinitions.ObserveScreenToolName, "{}")
                    }
                    3 -> {
                        val obsResult = messageText(messages.last().jsonObject)
                        check(obsResult.contains("failed: tool_unavailable (accessibility_unavailable)")) {
                            "Expected tool_unavailable (accessibility_unavailable) for observe_screen: $obsResult"
                        }
                        check(!obsResult.contains("暂时无法读取页面或点击")) {
                            "Must not repeat long accessibility hint after open_app already emitted it: $obsResult"
                        }
                        toolDelta(
                            "call_tap_d",
                            AgentToolDefinitions.TapUiElementToolName,
                            """{"index":0,"screen_id":"s1","long_press":false}"""
                        )
                    }
                    else -> {
                        val tapResult = messageText(messages.last().jsonObject)
                        check(tapResult.contains("failed: tool_unavailable (accessibility_unavailable)")) {
                            "Expected tool_unavailable (accessibility_unavailable) for tap_ui_element: $tapResult"
                        }
                        check(!tapResult.contains("暂时无法读取页面或点击")) {
                            "Must not repeat long accessibility hint on repeated unavailable UI call: $tapResult"
                        }
                        textDelta(accOffReply)
                    }
                }
            }
            val sessionD = UUID.randomUUID()
            val eventD = UUID.randomUUID()
            val submitD = callProvider(SUBMIT, sessionD, eventD, "帮我打开设置并查看当前页面状态")
            val convD = requireNotNull(submitD.getString(CONVERSATION_ID))
            createdConversations += convD
            var statusD = submitD
            withTimeout(15_000) {
                while (statusD.getString(STATE) in setOf(RUNNING, UNKNOWN, MISSING)) {
                    if (statusD.getBoolean(YIELD_PANEL)) {
                        callProvider(ACK_PANEL_YIELD, sessionD, eventD)
                    }
                    delay(40)
                    statusD = callProvider(STATUS, sessionD, eventD)
                }
            }
            server.failure.get()?.let { throw AssertionError("Case D mock server failure", it) }
            assertEquals(COMPLETED, statusD.getString(STATE))
            val claimD = callProvider(CLAIM_PLAYBACK, sessionD, eventD)
            assertTrue(claimD.getBoolean(PLAYBACK_CLAIMED))
            assertEquals(accOffReply, claimD.getString(REPLY))

            // Case E: Custom-drawn opaque EditText (text_length_utf16=0) rejects set_text cleanly & cursor progress does not lock.
            val opaqueCalcInput = DeviceUiElement(
                index = 6,
                className = "android.widget.EditText",
                text = "",
                contentDescription = null,
                resourceId = "fixture.calc:id/formula",
                bounds = DeviceBounds(84, 476, 1176, 606),
                clickable = true,
                editable = true,
                scrollable = false,
                enabled = true,
                focused = true,
                supportedActions = listOf("click", "set_text", "set_selection"),
                textSelectionStart = 4,
                textSelectionEnd = 4
            )
            assertTrue(DeviceTextEditing.hasUnreadableText(opaqueCalcInput))
            assertTrue(opaqueCalcInput.asModelLine().contains("text_readable=false"))
            try {
                DeviceTextEditing.plan(opaqueCalcInput, DeviceTextEditRequest(6, "+5", DeviceTextEditMode.Append))
                fail("Expected ui_input_text_unreadable")
            } catch (rejected: DeviceTextEditRejected) {
                assertEquals("ui_input_text_unreadable", rejected.code)
            }
            val emptyCustomBefore = opaqueCalcInput.copy(index = 1, textSelectionStart = 0, textSelectionEnd = 0)
            val beforeSnapshot = DeviceScreenSnapshot("fixture.calc", "Calculator", listOf(emptyCustomBefore), windowId = 11)
            val afterSnapshot = beforeSnapshot.copy(
                screenId = "after-input",
                elements = listOf(emptyCustomBefore.copy(text = "", textSelectionStart = 3, textSelectionEnd = 3))
            )
            val outcome = DeviceActionOutcomePolicy.complete(
                beforeSnapshot,
                DeviceToolResult(true, "Observed", screen = afterSnapshot),
                "Text editing was dispatched to UI element [1].",
                DeviceActionOutcomePolicy.TextInput(1, "123", true)
            )
            assertTrue("Custom-drawn cursor advancement must succeed without recovery lock", outcome.success)
            assertNull(outcome.errorCode)
            assertEquals(DeviceActionOutcome.ProgressObserved, outcome.actionOutcome)

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "\nNATIVE_VOICE_R5_PRECLOSE_MUMU PASS convA=$convA convB=$convB convC=$convC convD=$convD\n"
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
            shell("appops set ${targetContext.packageName} GET_USAGE_STATS $initialUsageStatsMode")
            shell("settings put secure enabled_accessibility_services $initialAccServices")
            shell("settings put secure accessibility_enabled $initialAccEnabled")
            gateway.refreshAccessibilityConnection()
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

        private val worker = thread(isDaemon = true, name = "native-voice-r5pre-mock") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { conn ->
                    try {
                        conn.soTimeout = 20_000
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
