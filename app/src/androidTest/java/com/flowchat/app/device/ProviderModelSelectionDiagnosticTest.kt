package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.chat.MessageComposer
import com.flowchat.app.presentation.provider.ConversationModelSelection
import com.flowchat.app.presentation.provider.ConversationModelsDialog
import com.flowchat.app.presentation.provider.ProviderSettingsScreen
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Loopback metadata only. Never sends a generation request or changes an existing provider. */
class ProviderModelSelectionDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runProviderModelSelectionDiagnostics") == "true")
    private fun directory() = File(context.getExternalFilesDir(null), "provider-model-selection").apply { mkdirs() }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
            File(directory(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun selectionSavesReopensAndFiltersTheRealConversationMenu() = runBlocking<Unit> {
        enabled()
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val server = CatalogServer()
        val provider = ProviderConfig(id = "model-selection-${UUID.randomUUID()}", displayName = "模型选择验证",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "alpha")
        var owner: String? = null
        try {
            data.providers().upsertProvider(provider, null)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { FlowChatTheme(AppAppearance.Dark) { ProviderSettingsScreen(onBack = {}) } } }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider-models-${provider.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("provider-models-${provider.id}").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("conversation-model-beta").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("conversation-model-alpha").performScrollTo().assertIsOn().performClick()
                compose.onNodeWithTag("conversation-model-beta").performScrollTo().performClick()
                compose.onNodeWithTag("conversation-model-manual").performScrollTo().performTextInput("manual-model")
                compose.onNodeWithTag("conversation-model-add").performClick()
                compose.onNodeWithTag("conversation-model-search").performScrollTo().performTextInput("manual")
                compose.onNodeWithTag("conversation-model-manual-model").performScrollTo().assertIsOn()
                compose.onNodeWithTag("conversation-model-search").performScrollTo().performTextClearance()
                compose.onNodeWithTag("conversation-model-beta").performScrollTo().assertIsOn()
                capture("checked-models")
                compose.onNodeWithTag("conversation-models-save").performClick()
                compose.waitUntil(10_000) { runBlocking {
                    data.providers().getProvider(provider.id)?.conversationModels == listOf("beta", "manual-model")
                } }
            }
            val saved = requireNotNull(data.providers().getProvider(provider.id))
            assertEquals("beta", saved.defaultModel)
            assertNull(saved.apiKeyAlias)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { FlowChatTheme(AppAppearance.Dark) { ProviderSettingsScreen(onBack = {}) } } }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider-models-${provider.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("provider-models-${provider.id}").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("conversation-model-alpha").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("conversation-model-alpha").performScrollTo().assertIsOff()
                compose.onNodeWithTag("conversation-model-beta").performScrollTo().assertIsOn()
                compose.onNodeWithTag("conversation-model-manual-model").performScrollTo().assertIsOn()
            }
            val conversation = data.chats().createConversation(provider.id, "alpha")
            owner = conversation.id
            var selected = ""
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { FlowChatTheme(AppAppearance.Dark) {
                    Box(Modifier.fillMaxSize()) {
                        MessageComposer("", null, false, true, false, listOf(saved), conversation,
                            {}, {}, {}, {}, {}, {}, {}, { _, model -> selected = model })
                    }
                } } }
                compose.onNodeWithTag("model-switch").performClick()
                compose.onNodeWithTag("conversation-option-${provider.id}-alpha").assertDoesNotExist()
                compose.onNodeWithTag("conversation-option-${provider.id}-gamma").assertDoesNotExist()
                compose.onNodeWithTag("conversation-option-${provider.id}-beta").assertIsDisplayed()
                compose.onNodeWithTag("conversation-option-${provider.id}-manual-model").assertIsDisplayed()
                capture("filtered-chat-menu")
                compose.onNodeWithTag("conversation-option-${provider.id}-beta").performClick()
                assertEquals("beta", selected)
            }
            assertTrue(server.requests > 0)
            assertEquals(0, server.generationRequests)
            File(directory(), "results.txt").writeText("PASS: loopback model discovery, checkbox selection, manual add, search, save, default replacement, reopen, and conversation menu filtering; old active model excluded; no generation requests\n")
        } finally {
            owner?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            server.close()
        }
    }

    @Test fun selectionDialogSupportsBothThemesAndLargeTextWithoutAnAccount() {
        enabled()
        val provider = ProviderConfig(id = "dialog-fixture", displayName = "模型选择", baseUrl = "https://example.test/v1", defaultModel = "alpha")
        val state = mutableStateOf(ConversationModelSelection(provider, listOf("alpha", "beta", "manual-model"), listOf("alpha")))
        val appearance = mutableStateOf(AppAppearance.Light)
        val density = context.resources.displayMetrics.widthPixels / 375f
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FlowChatTheme(appearance.value) {
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    ConversationModelsDialog(state.value, {}, {}, { name, checked -> state.value = state.value.copy(
                        selected = if (checked) state.value.selected + name else state.value.selected - name) },
                        { all -> state.value = state.value.copy(selected = if (all) state.value.options else emptyList()) }, {}, {})
                }
            } } }
            for (theme in listOf(AppAppearance.Light, AppAppearance.Dark)) {
                compose.runOnIdle { appearance.value = theme; state.value = state.value.copy(selected = listOf("alpha")) }
                compose.onNodeWithText(context.getString(R.string.clear_model_selection)).performScrollTo().performClick()
                compose.onNodeWithTag("conversation-models-save").assertIsNotEnabled()
                compose.onNodeWithTag("conversation-model-beta").performScrollTo().performClick()
                compose.onNodeWithTag("conversation-models-save").assertIsEnabled().assertIsDisplayed()
                capture("dialog-${theme.name}-large-text")
            }
        }
        File(directory(), "layout.txt").writeText("PASS: Light/Dark, 375dp viewport, 2x fonts, scrollable checkbox list and visible save controls, empty-selection guard\n")
    }

    private class CatalogServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var requests = 0
        @Volatile var generationRequests = 0
        @Volatile private var running = true
        private val worker = thread(isDaemon = true, name = "model-selection-catalog") {
            while (running) try {
                socket.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    requests++
                    if (request.startsWith("POST ")) generationRequests++
                    val body = """{"data":[{"id":"alpha"},{"id":"beta"},{"id":"gamma"}]}""".toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                }
            } catch (_: Exception) { if (!running) break }
        }
        override fun close() { running = false; socket.close(); worker.join(1000) }
    }
}
