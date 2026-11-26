package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.provider.apiHost
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only. Credentials remain inside the app process; no private chat content is used. */
class DeepSeekAttachmentLiveTest {
    @Test fun configuredProviderAnswersTextAndReadsSyntheticImage() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDeepSeekLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = entry.providers().getProvidersOnce().firstOrNull { it.isEnabled && it.apiHost() == "api.deepseek.com" && !it.apiKeyAlias.isNullOrBlank() }
        val report = File(context.getExternalFilesDir(null), "deepseek-attachment-live.txt")
        if (provider == null) {
            report.writeText("NOT RUN: No configured official DeepSeek credential on this emulator.")
            assumeTrue("No configured DeepSeek credential on emulator", false)
            return@runBlocking
        }
        val apiKey = entry.providers().getApiKey(provider)
        assertFalse("Configured credential unavailable", apiKey.isNullOrBlank())
        val results = mutableListOf<String>()
        suspend fun ask(label: String, model: String, message: ChatRequestMessage): String {
            val request = ChatRequest(model, listOf(message), 0.0, 1.0, 96, enableThinking = false)
            val events = withTimeout(90_000) { entry.client().streamChat(request, provider, apiKey).toList() }
            val error = events.filterIsInstance<ChatDelta.Error>().firstOrNull()
            // Record only a status category; never serialize provider configuration, request headers or credentials.
            if (error != null) {
                results += "$label: FAIL ${Regex("HTTP [0-9]{3}").find(error.message)?.value ?: "provider/transport error"}"
                report.writeText(results.joinToString("\n"))
                fail("$label failed; see sanitized status report")
            }
            val content = events.filterIsInstance<ChatDelta.Content>().joinToString("") { it.text } +
                events.filterIsInstance<ChatDelta.FullResponse>().joinToString("") { it.contentText }
            assertTrue("$label empty reply", content.isNotBlank())
            return content
        }
        try {
            val text = ask("text", provider.defaultModel, ChatRequestMessage("user", "Reply with FLOWCHAT_OK only."))
            assertTrue(text.contains("FLOWCHAT_OK")); results += "text: PASS model=${provider.defaultModel}"
            val bytes = instrumentation.context.assets.open("attachments/sample.png").use { it.readBytes() }
            val answer = ask("vision", "deepseek-flash", ChatRequestMessage("user", "Read the uppercase marker printed in this image. Return only the marker.",
                images = listOf(ChatImage("image/png", Base64.getEncoder().encodeToString(bytes)))))
            assertTrue("Vision reply must read the image", answer.contains("IMAGE_ATTACHMENT_MARKER"))
            results += "vision: PASS model=deepseek-flash; synthetic image marker read correctly"
        } finally { report.writeText(results.joinToString("\n")) }
    }
}
