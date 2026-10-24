package com.flowchat.app.device

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.terminal.CommandEnvironment
import com.flowchat.app.domain.workspace.*
import com.flowchat.app.data.workspace.AndroidWorkspaceFileAccess
import com.flowchat.app.presentation.chat.WorkspaceSettingsSheet
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun systemPickerAgentCommandsRecoveryAndConversationBindings() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runWorkspaceDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val terminal = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val settings = terminal.settings()
        val original = settings.state.value
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val documentAccess = AndroidWorkspaceFileAccess(context)
        fun readPhone(location: WorkspaceLocation, path: String) = documentAccess.read(location, path, 1024 * 1024).toString(Charsets.UTF_8)
        val server = LocalWorkspaceModel()
        val provider = ProviderConfig(id = "workspace-qa-${UUID.randomUUID()}", displayName = "Workspace local QA",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "workspace-qa")
        var scenario: ActivityScenario<MainActivity>? = null
        var owner: String? = null
        var other: String? = null
        val selectedUris = mutableListOf<Uri>()

        fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else buildList {
            add(root); repeat(root.childCount) { addAll(nodes(root.getChild(it))) }
        }
        fun clickNative(label: String, timeout: Long = 6000): Boolean {
            val until = android.os.SystemClock.elapsedRealtime() + timeout
            do {
                val matching = nodes(automation.rootInActiveWindow).filter {
                    it.isVisibleToUser && (it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString()?.equals(label, true) == true)
                }
                for (node in matching) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if ((target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                Thread.sleep(100)
            } while (android.os.SystemClock.elapsedRealtime() < until)
            return false
        }
        fun pickFolder(name: String) {
            val storageTitle = InstrumentationRegistry.getArguments().getString("workspaceStorageTitle") ?: "V2245A"
            compose.onNodeWithTag("workspace_choose").performClick()
            Thread.sleep(500)
            if (!clickNative(storageTitle, 700)) {
                val opened = clickNative("显示根目录", 700) || clickNative("Show roots", 700) ||
                    nodes(automation.rootInActiveWindow).firstOrNull { it.viewIdResourceName?.endsWith(":id/roots_toolbar") == true }
                        ?.let { toolbar -> (0 until toolbar.childCount).any { toolbar.getChild(it)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } } == true
                assertTrue("Open folder picker sources: ${nodes(automation.rootInActiveWindow).mapNotNull { it.text }.take(25)}", opened)
                assertTrue("Find local phone storage: ${nodes(automation.rootInActiveWindow).mapNotNull { it.text }}", clickNative(storageTitle))
            }
            assertTrue("Open Documents", clickNative("Documents"))
            assertTrue("Open dedicated workspace fixture directory", clickNative("FloryxWorkspaceQA"))
            assertTrue("Choose test folder $name", clickNative(name))
            Thread.sleep(600)
            assertTrue("Confirm selected folder", clickNative("使用此文件夹", 1500) || clickNative("USE THIS FOLDER", 1500))
            clickNative("允许", 900) || clickNative("ALLOW", 900)
            compose.waitUntil(8000) { compose.onAllNodesWithTag("workspace_name").fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { text -> text.text == name }
            } }
        }
        fun openWorkspace() {
            compose.onNodeWithContentDescription(context.getString(R.string.open_navigation_drawer)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.settings)).performClick()
            compose.onNodeWithText(context.getString(R.string.workspace_title)).performScrollTo().performClick()
            compose.onNodeWithTag("workspace_settings").assertIsDisplayed()
        }
        fun closeWorkspace() { compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick() }
        fun send(text: String) {
            compose.onNode(hasSetTextAction()).performTextInput(text)
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        }
        suspend fun answer(marker: String) {
            withTimeout(25_000) { while (data.chats().getMessages(owner!!).none {
                it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.content.contains(marker)
            }) { server.failure?.let { error(it) }; delay(100) } }
            compose.waitForIdle()
        }

        try {
            settings.setDefaultWorkspace(null)
            settings.setCommandExecutionEnabled(true)
            settings.setPowerModeEnabled(false)
            settings.setMemoryEnabled(false)
            settings.setUsageTrackingEnabled(false)
            data.providers().upsertProvider(provider, null)
            owner = data.chats().createConversation(provider.id, provider.defaultModel).id
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            openWorkspace()
            pickFolder("工作区 A")
            val location = withTimeout(5000) { while (true) {
                data.chats().getConversation(owner!!)!!.workspace?.let { return@withTimeout it }; delay(50)
            }; error("unreachable") }
            selectedUris += Uri.parse(location.treeUri)
            assertTrue(location.displayPath.contains("FloryxWorkspaceQA"))
            for (name in listOf("结果.txt", "继续.txt", "待保存.txt")) if (documentAccess.exists(location, name)) {
                val tree = Uri.parse(location.treeUri)
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                    android.provider.DocumentsContract.getTreeDocumentId(tree) + "/" + name)
                assertTrue(android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri))
            }
            documentAccess.write(location, "输入 空格.txt", "原始资料".toByteArray())
            compose.onNodeWithTag("workspace_access").assertTextEquals(context.getString(R.string.workspace_status_ready))
            compose.onNodeWithTag("workspace_default").performScrollTo().performClick()
            assertEquals(location, settings.state.value.defaultWorkspace)
            closeWorkspace()

            send("WORKSPACE_AGENT_QA 处理所选文件夹中的测试资料")
            answer("WORKSPACE_AGENT_OK")
            assertEquals("资料已修改", readPhone(location, "输入 空格.txt"))
            assertEquals("资料已修改", readPhone(location, "结果.txt"))
            assertEquals(5, server.agentRequests)
            assertTrue(server.sawWorkspaceTools)

            send("WORKSPACE_CANCEL_QA 等待停止")
            withTimeout(10000) { while (terminal.terminal().state.value.commands.none { it.isRunning && it.output.contains("WORKSPACE_STOP_READY") }) delay(100) }
            compose.onNodeWithContentDescription(context.getString(R.string.stop)).performClick()
            withTimeout(5000) { while (terminal.terminal().state.value.commands.any { it.isRunning }) delay(100) }
            assertFalse(documentAccess.exists(location, "继续.txt"))
            assertTrue(data.workspaces().inspect(owner, location).pending.any { it.path == "继续.txt" })
            send("WORKSPACE_RESUME_QA 继续保存刚才的结果")
            answer("WORKSPACE_RESUME_OK")
            assertEquals("partial complete", readPhone(location, "继续.txt"))

            // A real command creates an output; revoked document access prevents publish but retains the bytes.
            data.workspaces().prepareCommand(owner!!, location, emptyList(), listOf("待保存.txt"))
            val command = terminal.terminal().start(owner!!, CommandEnvironment.Linux, "printf retained > '待保存.txt'",
                data.workspaces().executionDirectory(owner!!, location), 30)
            assertEquals(0, terminal.terminal().awaitCompletion(owner!!, command.id).exitCode)
            context.contentResolver.releasePersistableUriPermission(Uri.parse(location.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val failed = data.workspaces().publish(owner!!, location, listOf("待保存.txt"))
            assertTrue(failed.saved.isEmpty() && failed.errors.isNotEmpty())
            scenario.recreate()
            openWorkspace()
            compose.onNodeWithTag("workspace_access").assertTextEquals(context.getString(R.string.workspace_status_unavailable))
            compose.onNodeWithText("待保存.txt").performScrollTo().assertIsDisplayed()
            pickFolder("工作区 A")
            compose.onNodeWithText(context.getString(R.string.workspace_save_result)).performScrollTo().performClick()
            withTimeout(6000) { while (!documentAccess.exists(location, "待保存.txt")) delay(100) }
            assertEquals("retained", readPhone(location, "待保存.txt"))
            compose.waitUntil(5000) { compose.onAllNodesWithTag("workspace_save_receipt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("workspace_save_receipt").assertTextContains("工作区 A", substring = true)
            closeWorkspace()

            compose.onNodeWithContentDescription(context.getString(R.string.new_chat)).performClick()
            val chatB = withTimeout(5000) { while (true) {
                val newest = data.chats().observeConversations().first().firstOrNull()
                if (newest != null && newest.id != owner) return@withTimeout newest
                delay(100)
            }; error("unreachable") }
            other = chatB.id
            assertEquals(location, chatB.workspace)
            openWorkspace()
            pickFolder("工作区 B")
            val bindingB = data.chats().getConversation(chatB.id)!!.workspace!!
            selectedUris += Uri.parse(bindingB.treeUri)
            assertEquals("工作区 B", bindingB.name)
            assertEquals(location, data.chats().getConversation(owner!!)!!.workspace)
            assertEquals(location, settings.state.value.defaultWorkspace)
            closeWorkspace()

            // Capture the real settings component in both themes with the same verified binding.
            val state = data.workspaces().inspect(other, bindingB)
            for (theme in listOf(AppAppearance.Light, AppAppearance.Dark)) {
                scenario.onActivity { activity -> activity.setContent { FlowChatTheme(theme) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        WorkspaceSettingsSheet(state, false, false, {}, {}, {}, {}, {}, {})
                    }
                } } }
                compose.waitForIdle()
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                automation.takeScreenshot()?.let { bitmap -> File(context.getExternalFilesDir(null), "workspace-${theme.name}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }; bitmap.recycle() }
            }
        } catch (error: Throwable) {
            println("WORKSPACE_QA_BINDING=" + owner?.let { data.chats().getConversation(it)?.workspace })
            println("WORKSPACE_QA_ORIGINAL_ERROR=$error")
            println("WORKSPACE_QA_STATE=" + runCatching { compose.onAllNodes(isRoot()).printToString(maxDepth = 12) }.getOrDefault("unavailable"))
            automation.takeScreenshot()?.let { bitmap -> File(context.getExternalFilesDir(null), "workspace-failure.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle() }
            throw error
        } finally {
            scenario?.close()
            terminal.terminal().cancelAll()
            selectedUris.distinct().forEach { uri -> runCatching { context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
            owner?.let { data.chats().deleteConversation(it) }
            other?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            settings.setDefaultWorkspace(original.defaultWorkspace)
            settings.setCommandExecutionEnabled(original.commandExecutionEnabled)
            settings.setPowerModeEnabled(original.powerModeEnabled)
            settings.setMemoryEnabled(original.memoryEnabled)
            settings.setUsageTrackingEnabled(original.usageTrackingEnabled)
            server.close()
        }
    }

    private class LocalWorkspaceModel : AutoCloseable {
        private val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        @Volatile var failure: String? = null
        @Volatile var agentRequests = 0
        @Volatile var sawWorkspaceTools = false
        private var step = 0
        private var calls = 0
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    try {
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) { val byte = input.read(); check(byte >= 0 && header.length < 32768); header.append(byte.toChar()) }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                        val bytes = ByteArray(length); var offset = 0
                        while (offset < length) { val size = input.read(bytes, offset, length - offset); check(size > 0); offset += size }
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        val tools = request["tools"]!!.jsonArray.map { tool -> tool.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
                        sawWorkspaceTools = "workspace_read" in tools && "workspace_write" in tools && "run_command" in tools
                        val messages = request["messages"]!!.jsonArray
                        val user = messages.last { message -> message.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject["content"]!!.jsonPrimitive.content
                        val last = messages.last().jsonObject
                        val toolResult = last["role"]!!.jsonPrimitive.content == "tool"
                        val result = if (toolResult) Json.parseToJsonElement(last["content"]!!.jsonPrimitive.content).jsonObject else null
                        val response = when {
                            user.contains("WORKSPACE_AGENT_QA") -> {
                                agentRequests++
                                when (step++) {
                                    0 -> call("workspace_list", "{}")
                                    1 -> { check(result!!["files"]!!.jsonArray.isNotEmpty()); call("workspace_read", """{"path":"输入 空格.txt"}""") }
                                    2 -> call("workspace_write", buildJsonObject { put("path", "输入 空格.txt"); put("text", "资料已修改"); put("expected_revision", result!!["revision"]!!.jsonPrimitive.content) }.toString())
                                    3 -> { check(result!!["saved"]!!.jsonPrimitive.boolean); call("run_command", """{"environment":"linux","command":"cat '输入 空格.txt' > '结果.txt'; printf WORKSPACE_COMMAND_OK","workspace_inputs":["输入 空格.txt"],"workspace_outputs":["结果.txt"]}""") }
                                    else -> { check(result!!["exit_code"]!!.jsonPrimitive.int == 0 && result["workspace_saved"]!!.jsonArray.size == 1 && result["workspace_errors"]!!.jsonArray.isEmpty()); answer("WORKSPACE_AGENT_OK") }
                                }
                            }
                            user.contains("WORKSPACE_CANCEL_QA") -> if (!toolResult) call("run_command", """{"environment":"linux","command":"printf partial > '继续.txt'; printf WORKSPACE_STOP_READY; sleep 30; printf BAD >> '继续.txt'","workspace_outputs":["继续.txt"]}""") else answer("STOPPED")
                            user.contains("WORKSPACE_RESUME_QA") -> if (!toolResult) call("run_command", """{"environment":"linux","command":"printf ' complete' >> '继续.txt'","workspace_outputs":["继续.txt"]}""")
                                else { check(result!!["workspace_saved"]!!.jsonArray.size == 1); answer("WORKSPACE_RESUME_OK") }
                            else -> error("Unexpected local test prompt")
                        }
                        val body = "data: $response\n\ndata: [DONE]\n\n".toByteArray()
                        client.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                    } catch (error: Throwable) { failure = error.toString() }
                }
            }
        }
        private fun call(name: String, arguments: String) = buildJsonObject {
            putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { putJsonArray("tool_calls") { add(buildJsonObject {
                put("index", 0); put("id", "workspace_call_${calls++}"); put("type", "function")
                putJsonObject("function") { put("name", name); put("arguments", arguments) }
            }) } } }) }
        }.toString()
        private fun answer(text: String) = buildJsonObject { putJsonArray("choices") { add(buildJsonObject {
            putJsonObject("delta") { put("content", text) }
        }) } }.toString()
        override fun close() { socket.close(); worker.join(1000) }
    }
}
