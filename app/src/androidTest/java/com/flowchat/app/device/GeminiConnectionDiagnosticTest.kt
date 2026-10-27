package com.flowchat.app.device

import android.os.SystemClock
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.network.KtorModelCatalogClient
import com.flowchat.app.di.AppModule
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ChatImage
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.ChatToolDefinition
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.app.domain.provider.apiHost
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real-device check. Credentials stay in the target app's Android Keystore. */
class GeminiConnectionDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun nativeGeminiSupportsSignedToolsAndImages() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runGeminiConnectionDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val saved = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && it.apiHost() == "generativelanguage.googleapis.com"
        })
        val provider = saved.copy(defaultModel = InstrumentationRegistry.getArguments().getString("geminiDiagnosticModel") ?: saved.defaultModel)
        val key = requireNotNull(data.providers().getApiKey(provider))
        val directory = File(context.getExternalFilesDir(null), "gemini-connection").apply { mkdirs() }
        val report = File(directory, "native-adapter.txt").apply { writeText("") }
        val started = SystemClock.elapsedRealtime()
        fun record(value: String) = report.appendText("${SystemClock.elapsedRealtime() - started}ms " +
            value.replace(key, "[REDACTED]") + "\n")
        val json = AppModule.provideJson()
        val models = withTimeout(45_000) { data.modelCatalog().listModels(provider, key) }
        record("PASS native chat model catalog: ${models.joinToString()}")
        suspend fun complete(request: ChatRequest): Pair<String, Pair<List<ChatToolCall>, JsonArray?>> {
            val text = StringBuilder()
            var calls = emptyList<ChatToolCall>()
            var blocks: JsonArray? = null
            var done = false
            withTimeout(75_000) {
                data.client().streamChat(request, provider, key).collect { delta ->
                    when (delta) {
                        is ChatDelta.Content -> text.append(delta.text)
                        is ChatDelta.ProviderData -> blocks = delta.blocks
                        is ChatDelta.ToolCalls -> { calls = delta.calls; done = true }
                        is ChatDelta.Error -> error(delta.message.replace(key, "[REDACTED]"))
                        ChatDelta.Done -> done = true
                        else -> Unit
                    }
                }
            }
            check(done) { "Native Gemini stream did not finish." }
            return text.toString() to (calls to blocks)
        }
        val marker = "TOOL_VALUE_${UUID.randomUUID()}"
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        val blueImage = ChatImage("image/png", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val tool = ChatToolDefinition("read_test_value", "Return a synthetic read-only test value.", buildJsonObject {
            put("type", "object"); putJsonObject("properties") {}; put("additionalProperties", false)
        })
        val initial = ChatRequest(provider.defaultModel, listOf(ChatRequestMessage("user",
            "Call read_test_value once. Its result includes a value and image. If the image is blue, reply with the returned value exactly. Do not invent the value.")),
            0.7, 1.0, null, tools = listOf(tool), toolChoice = "required", thinkingEffort = ThinkingEffort.Low)
        val first = complete(initial)
        val calls = first.second.first
        val blocks = requireNotNull(first.second.second)
        check(calls.size == 1 && calls.single().name == tool.name)
        assertTrue("Native thinking signature was omitted.", blocks.any { (it.jsonObject["thoughtSignature"] as? JsonPrimitive)?.content?.isNotBlank() == true })
        val continued = initial.copy(toolChoice = "none", messages = initial.messages +
            ChatRequestMessage("assistant", first.first, toolCalls = calls, providerData = blocks) +
            ChatRequestMessage("tool", buildJsonObject { put("value", marker) }.toString(), toolCallId = calls.single().id, images = listOf(blueImage)))
        assertTrue("Signed tool round trip lost its result.", complete(continued).first.contains(marker))
        record("PASS native signed function call and multimodal function response; signature kept in memory only")
        val imageRequest = initial.copy(tools = emptyList(), toolChoice = null,
            thinkingEffort = if (provider.defaultModel.startsWith("gemini-3.5")) ThinkingEffort.Minimal else ThinkingEffort.Low,
            messages = listOf(ChatRequestMessage("user",
            "If the image is blue, reply BLUE_OK. Otherwise reply OTHER. No tools.",
            images = listOf(blueImage))))
        assertTrue("Native Gemini image was not understood.", complete(imageRequest).first.contains("BLUE_OK"))
        record("PASS native image understanding (synthetic blue image)")
    }

    @Test fun chatMenuLoadsGeminiModelsAndSwitchesToAnAlternative() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runGeminiConnectionDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && it.apiHost() == "generativelanguage.googleapis.com"
        })
        val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
        val alternative = InstrumentationRegistry.getArguments().getString("geminiAlternativeModel") ?: "gemini-3.8-flash"
        val directory = File(context.getExternalFilesDir(null), "gemini-connection").apply { mkdirs() }
        val report = File(directory, "model-menu.txt").apply { writeText("") }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("model-switch").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("model-switch").performClick()
            compose.waitUntil(45_000) { compose.onAllNodesWithText(alternative).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(alternative).performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { data.chats().getConversation(conversation.id)?.modelName == alternative } }
            assertTrue(data.chats().getConversation(conversation.id)?.providerId == provider.id)
            val composer = hasSetTextAction()
            compose.onNode(composer).performTextReplacement("只回复 GEMINI_ALT_UI_OK，不要调用工具。")
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(context.getString(R.string.send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            withTimeout(75_000) {
                while (true) {
                    val rows = data.chats().getMessages(conversation.id)
                    rows.firstOrNull { message -> message.role == MessageRole.Assistant && message.status == MessageStatus.Failed }
                        ?.let { message -> error(message.content) }
                    if (rows.any { message -> message.role == MessageRole.Assistant && message.status == MessageStatus.Complete && message.content.contains("GEMINI_ALT_UI_OK") }) break
                    delay(200)
                }
            }
            report.writeText("PASS real native Gemini catalog loaded in chat menu; selected $alternative; provider retained; answer GEMINI_ALT_UI_OK\n")
            data.chats().updateConversationTitle(conversation.id, "Gemini 模型切换验证")
        }
    }

    @Test fun savedGeminiCompletesTwoTurnsThroughTheChatScreen() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runGeminiConnectionDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && it.apiHost() == "generativelanguage.googleapis.com"
        })
        val key = requireNotNull(data.providers().getApiKey(provider))
        val directory = File(context.getExternalFilesDir(null), "gemini-connection").apply { mkdirs() }
        val report = File(directory, "chat-screen.txt").apply { writeText("") }
        val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
        data.chats().updateConversationTitle(conversation.id, "Gemini 连接验证")
        val started = SystemClock.elapsedRealtime()
        fun record(value: String) = report.appendText("${SystemClock.elapsedRealtime() - started}ms " +
            value.replace(key, "[REDACTED]").replace(Regex("AIza[0-9A-Za-z_-]+"), "[REDACTED]") + "\n")
        record("conversation=${conversation.id} model=${provider.defaultModel}")
        val composer = hasSetTextAction()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.advanceTimeBy(1_500)
            compose.waitUntil(15_000) { compose.onAllNodes(composer).fetchSemanticsNodes().isNotEmpty() }
            for (marker in listOf("GEMINI_UI_OK", "GEMINI_FOLLOWUP_OK")) {
                val previous = data.chats().getMessages(conversation.id).map { it.id }.toSet()
                compose.onNode(composer).performTextReplacement("只回复 $marker，不要调用工具。")
                compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(context.getString(R.string.send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
                record("START $marker")
                withTimeout(75_000) {
                    while (true) {
                        val fresh = data.chats().getMessages(conversation.id).filter { message -> message.id !in previous }
                        fresh.firstOrNull { message -> message.role == MessageRole.Assistant && message.status == MessageStatus.Failed }
                            ?.let { message -> error(message.content.replace(key, "[REDACTED]")) }
                        val answer = fresh.firstOrNull { message -> message.role == MessageRole.Assistant &&
                            message.status == MessageStatus.Complete && message.replyPhase == ReplyPhase.Answer }
                        if (answer != null) {
                            record("PASS $marker: ${answer.content}")
                            assertTrue("Gemini UI answer omitted its marker.", answer.content.contains(marker))
                            break
                        }
                        delay(200)
                    }
                }
            }
            compose.waitForIdle()
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                File(directory, "chat-screen.png").outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
                bitmap.recycle()
            }
            record("PASS new Gemini conversation and follow-up through the real chat screen")
        }
    }

    @Test fun savedGeminiCanListModelsAndCompleteAChat() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runGeminiConnectionDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && it.apiHost() == "generativelanguage.googleapis.com"
        }) { "Save a direct Gemini provider in Floryx first." }
        val key = requireNotNull(data.providers().getApiKey(provider)) { "Save the Gemini key in Floryx first." }
        check(key.isNotBlank()) { "Saved Gemini key is empty." }
        val directory = File(context.getExternalFilesDir(null), "gemini-connection").apply { mkdirs() }
        val report = File(directory, "result.txt").apply { writeText("") }
        val started = SystemClock.elapsedRealtime()
        fun record(value: String) {
            val safe = value.replace(key, "[REDACTED]").replace(Regex("AIza[0-9A-Za-z_-]+"), "[REDACTED]")
            report.appendText("${SystemClock.elapsedRealtime() - started}ms $safe\n")
        }
        record("host=${provider.apiHost()} model=${provider.defaultModel}; keyPresent=true")
        var stage = "list-models"
        try {
            val json = AppModule.provideJson()
            record("START list-models")
            val models = withTimeout(45_000) { data.modelCatalog().listModels(provider, key) }
            record("PASS list-models: ${models.joinToString()}")
            assertTrue("Configured Gemini model is absent from the live model catalog.",
                models.any { it.removePrefix("models/") == provider.defaultModel.removePrefix("models/") })
            stage = "stream-chat"
            record("START stream-chat")
            val text = StringBuilder()
            var completed = false
            withTimeout(75_000) {
                data.client().streamChat(
                    ChatRequest(provider.defaultModel, listOf(ChatRequestMessage("user", "Reply with GEMINI_OK only.")),
                        temperature = 0.7, topP = 1.0, maxTokens = null), provider, key
                ).collect { delta ->
                    when (delta) {
                        is ChatDelta.Content -> text.append(delta.text)
                        is ChatDelta.FullResponse -> text.append(delta.contentText)
                        is ChatDelta.Error -> error(delta.message.replace(key, "[REDACTED]"))
                        ChatDelta.Done -> completed = true
                        else -> Unit
                    }
                }
            }
            record("completed=$completed response=$text")
            assertTrue("Gemini did not complete its stream.", completed)
            assertTrue("Gemini returned no expected answer.", text.contains("GEMINI_OK"))
            record("PASS saved Gemini catalog and streamed chat")
        } catch (failure: Throwable) {
            val safe = "$stage: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
                .replace(key, "[REDACTED]").replace(Regex("AIza[0-9A-Za-z_-]+"), "[REDACTED]")
            record("FAIL $safe")
            throw AssertionError(safe)
        }
    }
}
