package com.flowchat.app.device

import android.app.UiAutomation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.tools.AgentToolDefinitions
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in verification for B1 non-main accessibility recovery.
 * External runner stops the app with `am stop-app` so the selected service is in Crashed services,
 * then starts this test directly without launching MainActivity or AssistantPanelActivity.
 */
class NonMainAccessibilityReconnectTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val entry get() = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
    private val data get() = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("runNonMainAccessibilityReconnect") == "true"
    )

    private fun report(message: String) = instrumentation.sendStatus(
        0,
        Bundle().apply { putString("stream", "\nNON_MAIN_A11Y $message\n") }
    )

    private suspend fun await(label: String, timeoutMs: Long = 20_000, check: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!check()) delay(50)
        }
        report(label)
    }

    @Test
    fun assistantTaskReconnectsCrashedAccessibilityWithoutMainActivity() = runBlocking<Unit> {
        enabled()
        // Keep accessibility enabled under instrumentation.
        automation
        val runId = InstrumentationRegistry.getArguments().getString("reconnectRunId") ?: "b1-case-b"
        require(runId.matches(Regex("[a-z0-9-]+")))
        val outDir = File(context.getExternalFilesDir(null), "b1-nonmain/$runId")
        check(outDir.mkdirs())

        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val gateway = entry.deviceGateway()
        val settingsStore = entry.settings()
        val priorSettings = settingsStore.state.value
        val collector = CoroutineScope(Dispatchers.Main).launch { runtime.uiState.collect {} }
        val model = TaskRuntimeDiagnosticTest.GatedModel("已完成无障碍重连并收到工具。", "")
        val providerId = "b1-nonmain-${UUID.randomUUID()}"
        var createdConversationId: String? = null
        try {
            await("runtime providers loaded") { runtime.uiState.value.providers.isNotEmpty() }
            withContext(Dispatchers.Main) { gateway.refreshAccessibilityConnection() }
            assertNull(
                "Accessibility should start unbound after am stop-app",
                FlowChatAccessibilityService.current()
            )
            assertFalse(
                "Gateway accessibility state should start disconnected",
                gateway.accessibilityState.value.isConnected
            )

            settingsStore.setPowerModeEnabled(true)
            settingsStore.setPowerConnectionMode(PowerConnectionMode.Root)
            settingsStore.setBackgroundExecutionEnabled(false)

            data.providers().upsertProvider(
                ProviderConfig(
                    id = providerId,
                    displayName = "B1 NonMain Local Model",
                    baseUrl = "http://127.0.0.1:${model.port}/v1",
                    defaultModel = "local-flash"
                ),
                null
            )
            val conversation = data.chats().createConversation(providerId, "local-flash")
            createdConversationId = conversation.id

            val started = withContext(Dispatchers.Main) {
                runtime.sendFromAssistant(conversation.id, "看看当前屏幕有什么")
            }
            assertTrue("sendFromAssistant should accept task", started)

            await("loopback model received first request") { model.requests.get() == 1 }
            val firstRequest = JSONObject(model.bodies.single())
            val tools = firstRequest.optJSONArray("tools")
            val toolNames = (0 until (tools?.length() ?: 0)).map {
                tools!!.getJSONObject(it).getJSONObject("function").getString("name")
            }
            assertNotNull("FlowChatAccessibilityService should be bound before first model request", FlowChatAccessibilityService.current())
            assertTrue("Gateway accessibility should be connected", gateway.accessibilityState.value.isConnected)
            assertTrue("First model request must include read_screen", AgentToolDefinitions.ObserveScreenToolName in toolNames)

            model.release()
            await("assistant task completed") {
                entry.journal().latest(conversation.id)?.state == "Completed" &&
                    conversation.id !in runtime.activeConversations.value
            }

            val messages = data.chats().getMessages(conversation.id)
            val answer = messages.last().content
            assertFalse("Answer must not mention accessibility_unavailable", answer.contains("accessibility_unavailable"))
            assertEquals("已完成无障碍重连并收到工具。", answer)

            val result = JSONObject()
                .put("bound_before_first_request", true)
                .put("has_read_screen_tool", AgentToolDefinitions.ObserveScreenToolName in toolNames)
                .put("tool_count", toolNames.size)
                .put("local_requests", model.requests.get())
                .put("official_requests", 0)
                .put("task_state", entry.journal().latest(conversation.id)?.state)
            File(outDir, "result.json").writeText(result.toString(2))
            report("caseB_passed=true tools=${toolNames.size}")
        } finally {
            model.close()
            createdConversationId?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(providerId)
            settingsStore.setPowerModeEnabled(priorSettings.powerModeEnabled)
            settingsStore.setPowerConnectionMode(priorSettings.powerConnectionMode)
            settingsStore.setBackgroundExecutionEnabled(priorSettings.backgroundExecutionEnabled)
            collector.cancelAndJoin()
        }
    }
}
