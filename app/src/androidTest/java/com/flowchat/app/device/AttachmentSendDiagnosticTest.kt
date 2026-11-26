package com.flowchat.app.device

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.attachment.*
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real reader + chat UI + HTTP transport test. All files and the endpoint are synthetic/local. */
class AttachmentSendDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun imagesAndDocumentsSendTheirActualContent() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runAttachmentDiagnostics") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val entry = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = ProviderConfig(id = "attachment-qa-${UUID.randomUUID()}", displayName = "Attachment QA local",
            baseUrl = "", defaultModel = "attachment-test-model")
        val uris = mutableListOf<Uri>()
        var conversationId: String? = null
        var activity: Activity? = null
        val server = LocalChatServer()
        var nextSelection: Pair<Uri, String>? = null
        val openedIntents = mutableListOf<Intent>()
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                openedIntents += Intent(intent)
                val selection = requireNotNull(nextSelection)
                return Instrumentation.ActivityResult(Activity.RESULT_OK,
                    Intent().setDataAndType(selection.first, selection.second).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }

        fun sample(name: String, mime: String, bytes: ByteArray? = null): Uri {
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "flowchat-qa-$name")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/FlowChatAttachmentQA")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            uris += uri
            val body = bytes ?: instrumentation.context.assets.open("attachments/$name").use { it.readBytes() }
            resolver.openOutputStream(uri)!!.use { it.write(body) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }

        fun choose(uri: Uri, mime: String) {
            nextSelection = uri to mime
            compose.onNodeWithContentDescription(context.getString(R.string.upload_file)).performClick()
            compose.waitForIdle()
        }

        fun sendReady() {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription(context.getString(R.string.send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        }

        val results = mutableListOf<String>()
        try {
            entry.providers().upsertProvider(provider.copy(baseUrl = "http://127.0.0.1:${server.port}/v1"), null)
            val conversation = entry.chats().createConversation(provider.id, provider.defaultModel)
            conversationId = conversation.id
            entry.chats().updateConversationSettings(conversation.id, "Attachment QA", null, false, false, "", 0.0, 1.0, 256)
            instrumentation.addMonitor(picker)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes() }
            activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000))
            instrumentation.removeMonitor(monitor)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.upload_file)).fetchSemanticsNodes().isNotEmpty() }

            val cases = listOf(
                Triple("sample.txt", "text/plain", "TXT_ATTACHMENT_MARKER"),
                Triple("sample.csv", "text/csv", "CSV_ATTACHMENT_MARKER"),
                Triple("sample.json", "application/json", "JSON_ATTACHMENT_MARKER"),
                Triple("sample.pdf", "application/pdf", "PDF_ATTACHMENT_MARKER"),
                Triple("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "DOCX_ATTACHMENT_MARKER"),
                Triple("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "XLSX_ATTACHMENT_MARKER"),
                Triple("sample.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PPTX_ATTACHMENT_MARKER"),
                Triple("sample.odt", "application/vnd.oasis.opendocument.text", "ODT_ATTACHMENT_MARKER")
            )
            cases.forEachIndexed { index, (name, mime, marker) ->
                val uri = sample(name, mime)
                val extracted = AttachmentFileReader.read(context, uri)
                assertTrue("Native reader must extract $name", extracted.text.contains(marker))
                choose(uri, mime)
                compose.waitUntil(10_000) { compose.onAllNodesWithText(extracted.name).fetchSemanticsNodes().isNotEmpty() }
                if (index == 0) {
                    compose.onNodeWithContentDescription(context.getString(R.string.remove_attachment)).performClick()
                    compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsNotEnabled()
                    choose(uri, mime)
                    compose.waitUntil(10_000) { compose.onAllNodesWithText(extracted.name).fetchSemanticsNodes().isNotEmpty() }
                }
                sendReady()
                val sent = requireNotNull(server.requests.poll(20, TimeUnit.SECONDS)) { "No local HTTP request for $name" }
                val lastUser = sent["messages"]!!.jsonArray.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject
                val content = lastUser["content"]!!.jsonPrimitive.content
                assertTrue(content.contains(marker))
                assertTrue(content.contains("<attached-file"))
                assertTrue(content.contains(extracted.name))
                compose.waitUntil(20_000) {
                    runBlocking { entry.chats().getMessages(conversation.id).count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } } == index + 1
                }
                val saved = entry.chats().getMessages(conversation.id).last { it.role == MessageRole.User }
                assertEquals(extracted.name, saved.attachmentName)
                assertTrue(saved.attachmentText.orEmpty().contains(marker))
                results += "$name: native read, attachment-only send, HTTP payload and persisted content PASS"
            }
            assertTrue(openedIntents.isNotEmpty())
            assertTrue(openedIntents.all { intent -> intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty().contains("image/*") })
            results += "picker: images and documents offered together PASS"

            // Exercise the actual Android picker too; previous selections isolate each file type via ActivityResult.
            sample("picker-image.png", "image/png", instrumentation.context.assets.open("attachments/sample.png").use { it.readBytes() })
            instrumentation.removeMonitor(picker)
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            compose.onNodeWithContentDescription(context.getString(R.string.upload_file)).performClick()
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (automation.rootInActiveWindow?.packageName?.toString() != "com.android.documentsui" && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(100)
            }
            assertEquals("com.android.documentsui", automation.rootInActiveWindow?.packageName?.toString())
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isScrollable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> scrollable(child)?.let { return it } }
                return null
            }
            var chosen: AccessibilityNodeInfo? = null
            repeat(6) {
                if (chosen == null) {
                    val root = requireNotNull(automation.rootInActiveWindow)
                    chosen = root.findAccessibilityNodeInfosByText("flowchat-qa-picker-image.png").firstOrNull { it.text?.toString() == "flowchat-qa-picker-image.png" }
                    if (chosen == null) {
                        scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        android.os.SystemClock.sleep(300)
                    }
                }
            }
            val selection = requireNotNull(chosen) { "PNG sample not visible in native picker" }
            android.os.SystemClock.sleep(500)
            automation.takeScreenshot()?.let { screenshot ->
                File(context.getExternalFilesDir(null), "attachment-picker.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            val bounds = android.graphics.Rect().also(selection::getBoundsInScreen)
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}" )).use { it.readBytes() }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.remove_attachment)).fetchSemanticsNodes().isNotEmpty() }
            sendReady()
            val selectedPayload = requireNotNull(server.requests.poll(20, TimeUnit.SECONDS))
            assertTrue(selectedPayload["messages"]!!.jsonArray.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject["content"]!!.jsonArray.single { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/"))
            compose.waitUntil(20_000) { runBlocking { entry.chats().getMessages(conversation.id).count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } } == cases.size + 1 }
            val nativeImagePath = requireNotNull(entry.chats().getMessages(conversation.id).last { it.role == MessageRole.User }.attachmentImagePath)
            results += "native Android picker: actual PNG selection and send PASS"
            instrumentation.addMonitor(picker)

            val imageCases = listOf("sample.png" to "image/png", "sample.jpg" to "image/jpeg")
            val savedPaths = mutableListOf(nativeImagePath)
            imageCases.forEachIndexed { index, (name, mime) ->
                val uri = sample(name, mime)
                choose(uri, mime)
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.preview_image, "flowchat-qa-$name")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(context.getString(R.string.preview_image, "flowchat-qa-$name")).performClick()
                compose.onNodeWithContentDescription(context.getString(R.string.close_image_preview)).assertExists().performClick()
                if (index == 0) {
                    val count = File(context.filesDir, "attachments/images").listFiles()?.size ?: 0
                    compose.onNodeWithContentDescription(context.getString(R.string.remove_attachment)).performClick()
                    compose.waitUntil(10_000) { (File(context.filesDir, "attachments/images").listFiles()?.size ?: 0) < count }
                    compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsNotEnabled()
                    choose(uri, mime)
                    compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.remove_attachment)).fetchSemanticsNodes().isNotEmpty() }
                } else {
                    compose.onNode(hasSetTextAction()).performTextInput("Read the image")
                }
                automation.takeScreenshot()?.let { screenshot ->
                    File(context.getExternalFilesDir(null), "image-composer.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
                sendReady()
                val payload = requireNotNull(server.requests.poll(20, TimeUnit.SECONDS))
                val parts = payload["messages"]!!.jsonArray.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject["content"]!!.jsonArray
                val dataUrl = parts.single { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                assertTrue(dataUrl.startsWith("data:image/"))
                val bytes = java.util.Base64.getDecoder().decode(dataUrl.substringAfter(";base64,"))
                val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                assertEquals(480, decoded.width); assertEquals(180, decoded.height); decoded.recycle()
                if (index == 1) assertTrue(parts.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Read the image") == true })
                compose.waitUntil(20_000) { runBlocking { entry.chats().getMessages(conversation.id).count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } } == cases.size + 2 + index }
                val saved = entry.chats().getMessages(conversation.id).last { it.role == MessageRole.User }
                assertNull(saved.attachmentText)
                assertTrue(ImageAttachmentStore.resolve(context, requireNotNull(saved.attachmentImagePath)).isFile)
                savedPaths += saved.attachmentImagePath!!
                results += "$name: preview, image-only/caption send, actual base64 pixels and persisted file PASS"
            }
            // Recreate the UI and ask a follow-up: previously sent images must still be transmitted.
            instrumentation.runOnMainSync { activity!!.recreate() }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.upload_file)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("Compare those two images")
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            val followUp = requireNotNull(server.requests.poll(20, TimeUnit.SECONDS))
            assertEquals(3, followUp["messages"]!!.jsonArray.sumOf { message ->
                (message.jsonObject["content"] as? JsonArray)?.count { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" } ?: 0
            })
            compose.waitUntil(20_000) { runBlocking { entry.chats().getMessages(conversation.id).count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } } == cases.size + 4 }
            results += "activity recreation and follow-up retain all three images PASS"

            val failures = listOf(
                Triple("corrupt.png", "image/png", AttachmentReadFailure.InvalidImage),
                Triple("sample.zip", "application/zip", AttachmentReadFailure.UnsupportedType),
                Triple("empty.txt", "text/plain", AttachmentReadFailure.EmptyContent),
                Triple("scanned.pdf", "application/pdf", AttachmentReadFailure.EmptyContent),
                Triple("corrupt.pdf", "application/pdf", AttachmentReadFailure.ReadFailed)
            )
            failures.forEach { (name, mime, expected) ->
                val uri = sample(name, mime, if (name == "corrupt.png") byteArrayOf(1, 2, 3) else null)
                val error = runCatching { AttachmentFileReader.read(context, uri) }.exceptionOrNull()
                assertEquals(name, expected, (error as? AttachmentReadException)?.failure)
                choose(uri, mime)
                val message = context.getString(when (expected) {
                    AttachmentReadFailure.UnsupportedType -> R.string.attachment_unsupported
                    AttachmentReadFailure.EmptyContent -> R.string.attachment_empty
                    AttachmentReadFailure.InvalidImage -> R.string.attachment_invalid_image
                    else -> R.string.attachment_read_failed
                })
                compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsNotEnabled()
                results += "$name: $expected, visible error and disabled send PASS"
            }
            val large = sample("oversize.txt", "text/plain", ByteArray(AttachmentDocumentParser.MaxSourceBytes + 1))
            choose(large, "text/plain")
            compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.attachment_too_large)).fetchSemanticsNodes().isNotEmpty() }
            results += "oversize.txt: >20 MB blocked with visible error PASS"
            assertTrue(server.requests.isEmpty())
            entry.chats().deleteConversation(conversation.id)
            conversationId = null
            assertTrue(savedPaths.none { ImageAttachmentStore.resolve(context, it).exists() })
            results += "deleting test conversation removes its stored images PASS"
            File(context.getExternalFilesDir(null), "attachment-results.txt").writeText(results.joinToString("\n"))
        } finally {
            instrumentation.removeMonitor(picker)
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            conversationId?.let { entry.chats().deleteConversation(it) }
            entry.providers().deleteProvider(provider.id)
            uris.forEach { resolver.delete(it, null, null) }
            server.close()
        }
    }

    private class LocalChatServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val requests = LinkedBlockingQueue<JsonObject>()
        private val worker = thread(name = "attachment-local-endpoint", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { connection ->
                    connection.soTimeout = 20_000
                    val input = connection.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        check(byte >= 0 && header.length < 32_768)
                        header.append(byte.toChar())
                    }
                    val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                    requests.put(Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject)
                    val response = "data: {\"choices\":[{\"delta\":{\"content\":\"ATTACHMENT_LOCAL_OK\"}}]}\n\ndata: [DONE]\n\n".toByteArray()
                    val output = connection.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(response); output.flush()
                }
            }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
}
