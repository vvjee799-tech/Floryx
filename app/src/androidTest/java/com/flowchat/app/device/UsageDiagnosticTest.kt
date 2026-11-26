package com.flowchat.app.device

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
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
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class UsageDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun localUsageToggleAccumulatesAndSurvivesRecreation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runUsageDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = it.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val next = input.read(); if (next < 0) break; header.append(next.toChar()) }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { input.read() }
                    val content = """data: {"choices":[{"delta":{"content":"USAGE_LOCAL_OK"},"finish_reason":"stop"}]}"""
                    val usage = """data: {"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":100,"prompt_cache_hit_tokens":600,"prompt_cache_miss_tokens":400}}"""
                    val body = "$content\n\n$usage\n\ndata: [DONE]\n\n".toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
        val provider = ProviderConfig(id = "usage-qa-${UUID.randomUUID()}", displayName = "Usage QA local",
            baseUrl = "http://127.0.0.1:${server.localPort}/v1", defaultModel = "deepseek-flash")
        var id: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        fun screenshot(name: String) {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun toggle() {
            compose.onNodeWithContentDescription(context.getString(R.string.settings)).performClick()
            compose.onNodeWithTag("usage_tracking").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.usage_setting_description)).performScrollTo()
            screenshot("usage-settings")
            compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        }
        suspend fun send(number: Int) {
            compose.onNode(hasSetTextAction()).performTextInput("Usage local test $number")
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            withTimeout(15_000) {
                while (data.chats().getMessages(id!!).count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } < number) delay(100)
            }
            compose.waitForIdle()
        }
        try {
            settings.setUsageTrackingEnabled(false); settings.setMemoryEnabled(false); settings.setPowerModeEnabled(false)
            data.providers().upsertProvider(provider, null)
            id = data.chats().createConversation(provider.id, provider.defaultModel).id
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("usage_summary").assertDoesNotExist()
            toggle()
            assertTrue(settings.state.value.usageTrackingEnabled)
            assertTrue(com.flowchat.app.data.preferences.AppSettingsStore(context).state.value.usageTrackingEnabled)
            send(1); send(2)
            val records = data.usage().observe(id!!).first()
            assertEquals(2, records.size)
            assertEquals(2200L, records.sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) })
            assertTrue(records.all { it.costLowNanos == null && it.costHighNanos == null })
            compose.onNodeWithText(context.getString(R.string.balance_unsupported)).assertIsDisplayed()
            compose.onNodeWithTag("usage_summary").assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.usage_title)).assertIsDisplayed()
            screenshot("usage-details")
            compose.onNodeWithText(context.getString(R.string.close)).performClick()
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("usage_summary").fetchSemanticsNodes().isNotEmpty() }
            screenshot("usage-composer")
            assertEquals(2, data.usage().observe(id!!).first().size)
            toggle()
            compose.onNodeWithTag("usage_summary").assertDoesNotExist()
            send(3)
            assertEquals(2, data.usage().observe(id!!).first().size)
        } finally {
            scenario?.close()
            settings.setUsageTrackingEnabled(original.usageTrackingEnabled)
            settings.setMemoryEnabled(original.memoryEnabled); settings.setPowerModeEnabled(original.powerModeEnabled)
            id?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            server.close(); worker.join(1000)
        }
    }
}
