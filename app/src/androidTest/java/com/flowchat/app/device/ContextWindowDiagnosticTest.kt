package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.data.chat.ContextBudget
import com.flowchat.app.domain.model.ContextWindowUsage
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.presentation.chat.MessageComposer
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in MuMu checks for the composer context-window meter. No real account or paid model calls. */
class ContextWindowDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("runContextWindowDiagnostics") == "true"
    )

    @Test fun composerRingDetailsThemesAndConversationSwitch() {
        enabled()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val provider = ProviderConfig(id = "context-ring-fixture", displayName = "Local fixture",
            baseUrl = "http://127.0.0.1:1/v1", defaultModel = "deepseek-flash")
        val appearance = mutableStateOf(AppAppearance.Light)
        val narrow = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        val input = mutableStateOf("Synthetic composer text")
        val conversation = mutableStateOf(Conversation(providerId = provider.id, modelName = provider.defaultModel,
            enableThinking = false))
        val usage = mutableStateOf(ContextWindowUsage(usedTokens = 25_600, totalTokens = 32_000))
        var thinkingChanges = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                    FlowChatTheme(appearance.value) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.BottomCenter) {
                            Box(if (narrow.value) Modifier.width(320.dp) else Modifier.fillMaxWidth()) {
                                MessageComposer(input.value, null, false, true, false, listOf(provider), conversation.value,
                                    { input.value = it }, {}, {}, { enabled ->
                                        thinkingChanges++
                                        conversation.value = conversation.value.copy(enableThinking = enabled)
                                    }, {}, {}, {}, { _, _ -> }, contextWindow = usage.value)
                            }
                        }
                    }
                }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("context_window_indicator").fetchSemanticsNodes().isNotEmpty() }
            for (theme in listOf(AppAppearance.Light, AppAppearance.Dark)) {
                compose.runOnIdle { appearance.value = theme }
                compose.onNodeWithTag("context_window_indicator").assertIsDisplayed()
                compose.onNodeWithTag("context_window_ring", useUnmergedTree = true).assertExists()
                val bounds = compose.onNodeWithTag("context_window_indicator").fetchSemanticsNode().boundsInRoot
                val minTouch = 44 * context.resources.displayMetrics.density
                assertTrue("Context meter needs a finger-sized target", bounds.width >= minTouch && bounds.height >= minTouch)
                screenshot("${theme.name.lowercase()}.png")
                compose.onNodeWithTag("context_window_indicator").performClick()
                compose.onNodeWithTag("context_window_popup").assertIsDisplayed()
                compose.onNodeWithText("80%", substring = true).assertExists()
                screenshot("${theme.name.lowercase()}-popup.png")
                if (theme == AppAppearance.Light) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                } else {
                    val x = context.resources.displayMetrics.widthPixels - 20f
                    val y = context.resources.displayMetrics.heightPixels / 2f
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).let {
                        instrumentation.sendPointerSync(it); it.recycle()
                    }
                    MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0).let {
                        instrumentation.sendPointerSync(it); it.recycle()
                    }
                }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("context_window_popup").fetchSemanticsNodes().isEmpty() }
            }
            compose.onNodeWithTag("context_window_indicator").performClick()
            compose.onNodeWithTag("context_window_popup").assertIsDisplayed()
            compose.runOnIdle { conversation.value = conversation.value.copy(id = UUID.randomUUID().toString()) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("context_window_popup").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { narrow.value = true; fontScale.value = 1.3f }
            compose.onNodeWithTag("context_window_indicator").assertIsDisplayed()
            compose.onNodeWithTag("thinking-toggle").assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsDisplayed()
            screenshot("narrow.png")
            compose.onNodeWithTag("context_window_indicator").performClick()
            compose.onNodeWithTag("context_window_popup").assertIsDisplayed()
            compose.onNodeWithText("80%", substring = true).assertExists()
            screenshot("narrow-popup.png")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("context_window_popup").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("thinking-toggle").performClick()
            assertEquals(1, thinkingChanges)
            assertTrue(conversation.value.enableThinking)
            compose.onNodeWithTag("context_window_indicator").assertIsDisplayed()
        }
    }

    @Test fun realVmMeterTracksSelectedConversationAndLocalRequest() = runBlocking<Unit> {
        enabled()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value
        val model = GatedModel()
        val provider = ProviderConfig(id = "context-meter-${UUID.randomUUID()}", displayName = "Local meter QA",
            baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "deepseek-flash")
        val owners = mutableListOf<String>()
        var scenario: ActivityScenario<MainActivity>? = null
        var collector: Job? = null
        try {
            settings.setPowerModeEnabled(false)
            settings.setCommandExecutionEnabled(false)
            settings.setMemoryEnabled(false)
            data.providers().upsertProvider(provider, null)
            val a = data.chats().createConversation(provider.id, provider.defaultModel).also { owners += it.id }
            val b = data.chats().createConversation(provider.id, provider.defaultModel).also { owners += it.id }
            data.chats().updateConversationSettings(a.id, "A", null, false, false, "", 0.0, 1.0, 256)
            data.chats().updateConversationSettings(b.id, "B", null, false, false, "", 0.0, 1.0, 256)
            data.chats().appendMessage(a.id, MessageRole.User,
                "A_SOURCE_9172 " + "合成历史资料。".repeat(900), MessageStatus.Sent, provider.defaultModel)
            delay(2)
            data.chats().appendMessage(a.id, MessageRole.Assistant, "A_HISTORY_ANSWER", MessageStatus.Complete,
                provider.defaultModel)
            data.chats().appendMessage(b.id, MessageRole.User, "B_SOURCE_ONLY", MessageStatus.Sent, provider.defaultModel)
            delay(2)
            data.chats().appendMessage(b.id, MessageRole.Assistant, "B_HISTORY_ANSWER", MessageStatus.Complete,
                provider.defaultModel)

            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm: ChatViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[ChatViewModel::class.java] }
            collector = launch(Dispatchers.Default) { vm.uiState.collect { } }
            scenario.onActivity { vm.selectConversation(b.id) }
            awaitState(vm) { it.currentConversation?.id == b.id && it.contextWindow != null }
            val bIdle = requireNotNull(vm.uiState.value.contextWindow)
            assertEquals(32_000, bIdle.totalTokens)
            assertTrue("Idle estimate includes the system prompt", bIdle.usedTokens > 0)

            scenario.onActivity { vm.selectConversation(a.id) }
            awaitState(vm) { it.currentConversation?.id == a.id && it.contextWindow != null }
            val aIdle = requireNotNull(vm.uiState.value.contextWindow)
            assertEquals(32_000, aIdle.totalTokens)
            assertTrue("A history must occupy more context than B", aIdle.usedTokens > bIdle.usedTokens + 500)
            scenario.onActivity { vm.updateInput("A_REQUEST_MARKER"); vm.send() }
            val wire = model.awaitRequest()
            val wireText = wire["messages"]!!.jsonArray.joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            assertTrue(wireText.contains("A_SOURCE_9172"))
            assertTrue(wireText.contains("A_REQUEST_MARKER"))
            assertFalse(wireText.contains("B_SOURCE_ONLY"))
            awaitState(vm) { it.currentConversation?.id == a.id && it.isStreaming &&
                (it.contextWindow?.usedTokens ?: 0) >= aIdle.usedTokens }
            val aRunning = requireNotNull(vm.uiState.value.contextWindow)
            assertTrue(aRunning.usedTokens >= ContextBudget.text(wireText))
            assertEquals(32_000, aRunning.totalTokens)

            scenario.onActivity { vm.selectConversation(b.id) }
            awaitState(vm) { it.currentConversation?.id == b.id && !it.isStreaming && it.contextWindow != null }
            assertEquals("A's active request must not change B's context", bIdle.usedTokens,
                vm.uiState.value.contextWindow!!.usedTokens)
            scenario.onActivity { vm.selectConversation(a.id) }
            awaitState(vm) { it.currentConversation?.id == a.id && it.isStreaming && it.contextWindow != null }
            assertEquals(aRunning.usedTokens, vm.uiState.value.contextWindow!!.usedTokens)
            model.release()
            withTimeout(20_000) { while (data.chats().getMessages(a.id).none { it.role == MessageRole.Assistant &&
                    it.status == MessageStatus.Complete && it.content == "CONTEXT_METER_LOCAL_OK" }) {
                model.failure?.let { throw it }
                delay(50)
            } }
            scenario.onActivity { vm.selectConversation(b.id) }
            awaitState(vm) { it.currentConversation?.id == b.id && !it.isStreaming && it.contextWindow != null }
            assertEquals(bIdle.usedTokens, vm.uiState.value.contextWindow!!.usedTokens)
            model.failure?.let { throw it }
        } finally {
            model.release()
            collector?.cancel()
            scenario?.close()
            settings.setPowerModeEnabled(original.powerModeEnabled)
            settings.setCommandExecutionEnabled(original.commandExecutionEnabled)
            settings.setMemoryEnabled(original.memoryEnabled)
            owners.forEach { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            model.close()
        }
    }

    private suspend fun awaitState(vm: ChatViewModel,
                                   predicate: (com.flowchat.app.presentation.chat.ChatUiState) -> Boolean) {
        withTimeout(15_000) { while (!predicate(vm.uiState.value)) delay(25) }
    }

    private class GatedModel : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val requests = LinkedBlockingQueue<JsonObject>()
        private val gate = CountDownLatch(1)
        @Volatile var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "context-window-local-model") {
            try {
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
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    requests.put(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
                    check(gate.await(25, TimeUnit.SECONDS))
                    val response = "data: {\"choices\":[{\"delta\":{\"content\":\"CONTEXT_METER_LOCAL_OK\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    val body = response.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (error: Throwable) { if (!server.isClosed) failure = error }
        }
        fun awaitRequest(): JsonObject = requests.poll(20, TimeUnit.SECONDS) ?: error("No local context-meter request")
        fun release() { gate.countDown() }
        override fun close() { gate.countDown(); server.close(); worker.join(2_000) }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null), "context-window/$name")
        output.parentFile?.mkdirs()
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .takeScreenshot() ?: error("MuMu screenshot unavailable")
        try { output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
