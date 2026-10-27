package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.device.ScreenImageRegion
import dagger.hilt.android.EntryPointAccessors
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic fixtures only; no paid model requests and no personal screen images. */
class ScreenVisionDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device by lazy { EntryPointAccessors.fromApplication(context, DeviceDiagnosticsEntryPoint::class.java) }
    private val data by lazy { EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java) }

    private suspend fun ensureService() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(400)
        val original = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, FlowChatAccessibilityService::class.java).flattenToString()
        check(component in original.split(':')) { "Previously authorized Floryx Dev accessibility service is required." }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            try {
                check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    original.split(':').filterNot { it == component }.joinToString(":")))
                delay(200)
            } finally { check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)) }
        } finally { automation.dropShellPermissionIdentity() }
        withTimeout(10000) { while (FlowChatAccessibilityService.current()?.observeScreen() == null) delay(100) }
    }
    private suspend fun fixture(secure: Boolean = false, sensitive: Boolean = false, animated: Boolean = false, emptyControls: Boolean = false) {
        context.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName, ScreenVisionFixtureActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("secure", secure); putExtra("sensitive", sensitive)
            putExtra("animated", animated); putExtra("empty_controls", emptyControls)
        })
        withTimeout(7000) { while (device.gateway().observeScreen().screen?.packageName != instrumentation.context.packageName) delay(100) }
        delay(500)
    }

    @Test fun preservesNativeTextPixelsAndMapsDetailCropsToTheFullWindow() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScreenVisionDiagnostics") == "true")
        ensureService()
        try {
            fixture()
            val fullResult = device.gateway().captureScreen()
            val full = requireNotNull(fullResult.screenImage) { fullResult.asToolContent() }
            assertEquals("image/png", full.image.mimeType)
            assertEquals(full.sourceWidth, full.width); assertEquals(full.sourceHeight, full.height)
            assertTrue("Synthetic screen must exercise the previous downscaling limit", maxOf(full.width, full.height) > 1920)
            val region = ScreenImageRegion(0, 50, 100, 100)
            val detailResult = device.gateway().captureScreen(region)
            val detail = requireNotNull(detailResult.screenImage) { detailResult.asToolContent() }
            assertEquals(region, detail.region)
            val bounds = region.pixels(full.width, full.height)
            assertEquals(bounds.right - bounds.left, detail.width)
            assertEquals(bounds.bottom - bounds.top, detail.height)
            val fullBytes = Base64.getDecoder().decode(full.image.base64)
            val cropBytes = Base64.getDecoder().decode(detail.image.base64)
            val fullBitmap = requireNotNull(BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size))
            val cropBitmap = requireNotNull(BitmapFactory.decodeByteArray(cropBytes, 0, cropBytes.size))
            try {
                for (y in 0 until cropBitmap.height step 11) for (x in 0 until cropBitmap.width step 7)
                    assertEquals("Detail crops must keep source pixels", fullBitmap.getPixel(x + bounds.left, y + bounds.top), cropBitmap.getPixel(x, y))
            } finally { fullBitmap.recycle(); cropBitmap.recycle() }
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test fun capturesMovingFramesWhileControlsContinueUpdating() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScreenVisionDiagnostics") == "true")
        ensureService()
        try {
            fixture(animated = true)
            val counters = mutableSetOf<String>()
            val frames = mutableSetOf<String>()
            repeat(3) {
                val result = device.gateway().captureScreen()
                assertTrue(result.asToolContent(), result.success)
                val frame = requireNotNull(result.screenImage) { result.asToolContent() }
                assertEquals(result.screen!!.screenId, frame.screenId)
                counters += result.screen.elements.first { it.displayText.startsWith("tick ") }.displayText
                frames += frame.image.base64
                verifyPixels(Base64.getDecoder().decode(frame.image.base64))
            }
            assertEquals("Animation must remain running across captures", 3, counters.size)
            assertEquals("Capture must sample changing frames", 3, frames.size)
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test fun capturesCustomDrawnFramesWithoutAnyReadableControls() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScreenVisionDiagnostics") == "true")
        ensureService()
        try {
            fixture(animated = true, emptyControls = true)
            val result = device.gateway().captureScreen()
            assertTrue(result.asToolContent(), result.success)
            assertTrue("The synthetic page must expose no UI text or actions", result.screen!!.elements.isEmpty())
            val frame = requireNotNull(result.screenImage) { result.asToolContent() }
            verifyPixels(Base64.getDecoder().decode(frame.image.base64))
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test fun capturesPixelsAndKeepsSensitiveAndSecureWindowsProtected() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScreenVisionDiagnostics") == "true")
        ensureService()
        try {
            fixture()
            val result = device.gateway().captureScreen()
            assertTrue(result.asToolContent(), result.success)
            val frame = requireNotNull(result.screenImage) { result.asToolContent() }
            assertEquals(result.screen!!.screenId, frame.screenId)
            assertTrue(maxOf(frame.width, frame.height) <= 3840)
            assertFalse(result.screen.elements.any { it.displayText.contains("circle", true) || it.displayText.contains("blue", true) })
            verifyPixels(Base64.getDecoder().decode(frame.image.base64))

            fixture(secure = true)
            val protected = device.gateway().captureScreen(ScreenImageRegion(0, 0, 50, 50))
            assertNull("Secure windows must not provide image bytes", protected.screenImage)
            assertEquals(protected.asToolContent(), "secure_window_blocked", protected.imageErrorCode)

            fixture(sensitive = true)
            val sensitive = device.gateway().captureScreen(ScreenImageRegion(0, 0, 50, 50))
            assertNull(sensitive.screenImage)
            assertTrue(sensitive.screen!!.sensitive)
            assertTrue(sensitive.screen.elements.isEmpty())
            assertEquals("sensitive_screen_blocked", sensitive.imageErrorCode)
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test fun screenshotPixelsReachTheModelAndNeverEnterStoredTaskHistory() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScreenVisionDiagnostics") == "true")
        ensureService()
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value
        val server = LocalVisionModel()
        val provider = ProviderConfig(id = "screen-vision-${UUID.randomUUID()}", displayName = "Screen vision local QA",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "vision-qa")
        var owner: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            settings.setPowerModeEnabled(true)
            settings.setCommandExecutionEnabled(false)
            settings.setMemoryEnabled(false)
            settings.setUsageTrackingEnabled(false)
            data.providers().upsertProvider(provider, null)
            owner = data.chats().createConversation(provider.id, provider.defaultModel).id
            data.chats().updateConversationTitle(owner!!, "Screen vision local QA")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.open_navigation_drawer)).performClick()
            compose.onNodeWithText("Screen vision local QA").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("message_composer_panel")))
                .performTextInput("SCREEN_VISION_QA 请查看屏幕上的画面，说明图形和颜色。")
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            fixture(animated = true)
            server.fixtureReady.countDown()
            withTimeout(25000) {
                while (data.chats().getMessages(owner!!).none {
                    it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.content.contains("红色圆形")
                }) { server.failure?.let { error(it) }; delay(100) }
            }
            assertTrue(server.sawRealImage)
            assertEquals(2, server.requests)
            val stored = data.chats().getMessages(owner!!)
            assertTrue(stored.any { it.executionContext?.contains("image_available=true") == true })
            assertFalse(stored.any { it.content.contains("data:image/") || it.executionContext.orEmpty().contains("data:image/") })
            assertTrue(stored.none { it.attachmentImagePath != null })
        } finally {
            server.fixtureReady.countDown()
            scenario?.close()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            owner?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            settings.setPowerModeEnabled(original.powerModeEnabled)
            settings.setCommandExecutionEnabled(original.commandExecutionEnabled)
            settings.setMemoryEnabled(original.memoryEnabled)
            settings.setUsageTrackingEnabled(original.usageTrackingEnabled)
            server.close()
        }
    }

    private class LocalVisionModel : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val fixtureReady = CountDownLatch(1)
        @Volatile var failure: String? = null
        @Volatile var sawRealImage = false
        @Volatile var requests = 0
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    try {
                        client.soTimeout = 15000
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) { val byte = input.read(); check(byte >= 0 && header.length < 32768); header.append(byte.toChar()) }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                        check(length in 1..8_000_000)
                        val bytes = ByteArray(length); var offset = 0
                        while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        requests++
                        val response = if (requests == 1) {
                            check(request["tools"]!!.jsonArray.any { tool -> tool.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "observe_screen" })
                            check(fixtureReady.await(10000, TimeUnit.MILLISECONDS))
                            buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { putJsonArray("tool_calls") {
                                add(buildJsonObject { put("index", 0); put("id", "screen-frame-call"); put("type", "function")
                                    putJsonObject("function") { put("name", "observe_screen"); put("arguments", """{"image_region":{"left":0,"top":0,"right":100,"bottom":90}}""") } })
                            } } }) } }.toString()
                        } else {
                            val messages = request["messages"]!!.jsonArray
                            val result = messages.first { message -> message.jsonObject["role"]!!.jsonPrimitive.content == "tool" }.jsonObject
                            check(result["tool_call_id"]!!.jsonPrimitive.content == "screen-frame-call")
                            check(result["content"]!!.jsonPrimitive.content.contains("image_available=true"))
                            check(result["content"]!!.jsonPrimitive.content.contains("image_region_percent=0,0,100,90"))
                            val parts = messages.last().jsonObject["content"]!!.jsonArray
                            val url = parts.first { part -> part.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                            check(url.startsWith("data:image/png;base64,"))
                            verifyPixels(Base64.getDecoder().decode(url.substringAfter(',')))
                            sawRealImage = true
                            buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("content", "画面中有红色圆形和蓝色方块。SCREEN_VISION_LOCAL_OK") } }) } }.toString()
                        }
                        val body = "data: $response\n\ndata: [DONE]\n\n".toByteArray()
                        client.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                    } catch (error: Throwable) { failure = error.toString() }
                }
            }
        }
        override fun close() { socket.close(); worker.join(1000) }
    }

    companion object {
        private fun verifyPixels(bytes: ByteArray) {
            assertTrue(bytes.size <= 4 * 1024 * 1024)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                var red = 0; var blue = 0
                for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90) red++
                    if (Color.blue(pixel) > 180 && Color.red(pixel) < 90 && Color.green(pixel) < 120) blue++
                }
                assertTrue("Actual red image pixels required", red > 1000)
                assertTrue("Actual blue image pixels required", blue > 1000)
            } finally { bitmap.recycle() }
        }
    }
}
