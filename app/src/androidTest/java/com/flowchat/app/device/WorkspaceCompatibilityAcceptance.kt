package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.workspace.WorkspaceManager
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskPersistenceBoundary
import com.flowchat.app.domain.chat.TaskPersistenceObserver
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.app.domain.terminal.TerminalToolDefinitions
import com.flowchat.app.domain.workspace.WorkspaceLocation
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Production ChatScreen, SAF and phone facts, with a local scripted model and no provider client. */
internal class WorkspaceCompatibilityAcceptance(
    private val context: Context,
    private val location: WorkspaceLocation,
    private val workspace: WorkspaceManager,
    private val activity: MainActivity,
    private val evidence: File
) {
    enum class Case { Deliver, ChangedSource, Mixed, Stop }

    private val answerTool = WorkspaceToolDefinitions.AnswerCompatibility
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val parser = Json { ignoreUnknownKeys = true }
    private val unsupportedProse = "这个文件没有任何外部依赖，肯定可以运行。"
    private val androidConflict = "Android 14，与手机 Android 16 冲突"

    suspend fun verify(case: Case, changeSource: () -> Unit) = coroutineScope {
        val terminal = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).terminal()
        val fixture = AutonomyRuntimeFixture(context, terminalOverride = terminal, workspaceOverride = workspace)
        val decisionReached = CompletableDeferred<Unit>()
        val unsupportedVisible = AtomicBoolean(false)
        val observedErrors = mutableListOf<String>()
        val continuedAnswer = if (case == Case.Mixed) "已核对两个文件。第二份文本是 abc中文末尾。"
            else "源版本已经变化，本轮无法确认原兼容结论。"
        var passed = false
        try {
            if (case == Case.Stop) fixture.persistenceObserver = TaskPersistenceObserver { boundary ->
                if (fixture.modelCalls.get() == 2 && boundary == TaskPersistenceBoundary.BeforeIntentSaved) {
                    decisionReached.complete(Unit)
                    awaitCancellation()
                }
            }
            fixture.settings.setPowerModeEnabled(false)
            fixture.settings.setCommandExecutionEnabled(true)
            fixture.create()
            fixture.chats.updateConversationWorkspace(fixture.owner, location)
            fixture.await { fixture.runtime.uiState.value.currentConversation?.workspace == location }
            fixture.responder = { request, round ->
                val offered = request.tools.any { it.name == answerTool }
                when (round) {
                    1 -> {
                        check(!offered)
                        toolCalls(
                            ChatToolCall("compat-stat", WorkspaceToolDefinitions.Stat, """{"path":"analysis.sh"}"""),
                            ChatToolCall("compat-phone", TerminalToolDefinitions.Status, "{}")
                        )
                    }
                    2 -> {
                        check(offered)
                        val observed = parser.parseToJsonElement(requireNotNull(
                            request.messages.single { it.toolCallId == "compat-stat" }.content)).jsonObject
                        val version = observed.getValue("source").jsonObject.getValue("source_version").jsonPrimitive.content
                        if (case == Case.ChangedSource) {
                            changeSource()
                            check(workspace.sourceVersion(location, "analysis.sh") != version)
                        }
                        val answer = ChatToolCall("compat-answer", answerTool, buildJsonObject {
                            put("path", "analysis.sh")
                            put("expected_source_version", version)
                        }.toString())
                        val extra = if (case == Case.Mixed) listOf(ChatToolCall("compat-other", WorkspaceToolDefinitions.Read,
                            """{"path":"plain.txt","byte_offset":0,"max_bytes":64}""")) else emptyList()
                        listOf(ChatDelta.Content(unsupportedProse), ChatDelta.ToolCalls(listOf(answer) + extra))
                    }
                    3 -> {
                        check(case == Case.ChangedSource || case == Case.Mixed)
                        check(!offered)
                        val error = parser.parseToJsonElement(requireNotNull(
                            request.messages.single { it.toolCallId == "compat-answer" }.content)).jsonObject
                        observedErrors += error.getValue("error").jsonPrimitive.content
                        if (case == Case.Mixed) check(request.messages.single { it.toolCallId == "compat-other" }
                            .content.orEmpty().contains("abc中文末尾"))
                        listOf(ChatDelta.Content(continuedAnswer), ChatDelta.Done)
                    }
                    else -> error("Unexpected compatibility answer round $round")
                }
            }
            val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.runtime.uiState.collect { state ->
                    if (state.messages.any { unsupportedProse in it.content }) unsupportedVisible.set(true)
                }
            }
            val prompt = if (case == Case.Mixed) "看一下 analysis.sh 的类型和手机适配，再读一下 plain.txt。只检查。"
                else "看一下工作区里的 analysis.sh 适不适配这个手机，属于哪种类型。只检查，不执行也不修改。"
            val start = SystemClock.elapsedRealtime()
            try {
                sendFromChat(fixture, prompt, case)
                if (case == Case.Stop) {
                    withTimeout(15_000) { decisionReached.await() }
                    withContext(Dispatchers.Main) { fixture.runtime.stopFromAssistant(fixture.owner) }
                }
                fixture.await {
                    val state = fixture.journal.latest(fixture.owner)?.state
                    state in setOf("Completed", "Stopped", "Failed", "Paused", "Interrupted") &&
                        fixture.owner !in fixture.runtime.activeConversations.value
                }
            } finally {
                monitor.cancelAndJoin()
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            val run = requireNotNull(fixture.journal.latest(fixture.owner))
            val messages = fixture.chats.getMessages(fixture.owner)
            val last = messages.last { it.role == MessageRole.Assistant }
            val steps = messages.flatMap { message ->
                message.executionContext?.let { parser.decodeFromString<List<ExecutionStep>>(it) }.orEmpty()
            }
            assertEquals(if (case == Case.Stop) "Stopped" else "Completed", run.state.toString())
            assertEquals(if (case in setOf(Case.Deliver, Case.Stop)) 2 else 3, fixture.modelCalls.get())
            assertFalse(unsupportedVisible.get())
            assertFalse(messages.any { unsupportedProse in it.content })
            assertTrue(fixture.confirmations.isEmpty())
            assertTrue(steps.none { it.tool == TerminalToolDefinitions.Run })
            assertTrue(terminal.state.value.commands.none { terminal.owns(fixture.owner, it.id) })
            val answerStep = steps.singleOrNull { it.tool == answerTool }
            when (case) {
                Case.Deliver -> {
                    assertEquals(MessageStatus.Complete, last.status)
                    assertEquals(ReplyPhase.Answer, last.replyPhase)
                    assertEquals("summary_prepared", parser.parseToJsonElement(requireNotNull(answerStep).result)
                        .jsonObject.getValue("status").jsonPrimitive.content)
                    for (text in listOf("Shell 文本", "6,500,000 字节", "3,299,884 字节", androidConflict,
                        "V2302A 未经验证", "arm64-v8a 与手机支持的 ABI 字面匹配",
                        "外部命令、库及其 ABI 尚未分析", "文件的 ABI 兼容性未知", "实际运行情况未验证")) {
                        assertTrue(text, text in last.content)
                    }
                    val visible = withTimeoutOrNull(10_000) {
                        while (nodes(automation.rootInActiveWindow).none {
                            it.isVisibleToUser && it.text?.contains("文件的 ABI 兼容性未知") == true
                        }) delay(60)
                        true
                    }
                    assertEquals("The final summary did not appear in the chat window", true, visible)
                    File(evidence, "F06-compatibility-chat-final.png").outputStream().use {
                        requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                Case.ChangedSource -> {
                    assertEquals(listOf("compatibility_source_changed_or_unavailable"), observedErrors)
                    assertEquals(continuedAnswer, last.content)
                }
                Case.Mixed -> {
                    assertEquals(listOf("compatibility_answer_requires_sole_call"), observedErrors)
                    assertEquals(continuedAnswer, last.content)
                    assertTrue(steps.any { it.tool == WorkspaceToolDefinitions.Read && "abc中文末尾" in it.result })
                }
                Case.Stop -> {
                    assertNull(answerStep)
                    assertTrue(messages.none { androidConflict in it.content })
                }
            }
            val id = "F06-compatibility-chat-${case.name}"
            File(evidence, "$id.json").writeText(buildJsonObject {
                put("case", case.name); put("entry", "production_ChatScreen_send_button")
                put("state", run.state.toString()); put("elapsed_ms", elapsed)
                put("scripted_model_calls", fixture.modelCalls.get()); put("real_model_requests", 0)
                put("tool_calls", JsonArray(steps.map { JsonPrimitive(it.tool) })); put("confirmations", fixture.confirmations.size)
                put("shell_dispatches", 0); put("unsupported_text_displayed", false)
                put("answer", last.content); put("compatibility_tool_result", answerStep?.result)
            }.toString())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nP0D $id ${run.state} elapsed=${elapsed}ms model=local shell=0 confirmations=0\n")
            })
            passed = true
        } finally {
            fixture.close(passed)
        }
    }

    private fun toolCalls(vararg calls: ChatToolCall) = listOf(ChatDelta.ToolCalls(calls.toList()))

    private suspend fun sendFromChat(fixture: AutonomyRuntimeFixture, prompt: String, case: Case) {
        withContext(Dispatchers.Main) {
            val model = ChatViewModel(fixture.runtime)
            activity.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, model) } }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        withContext(Dispatchers.Main) { fixture.runtime.updateInput(prompt) }
        val labels = setOf("发送", "Send", activity.getString(com.flowchat.app.R.string.send))
        val clicked = withTimeoutOrNull(10_000) {
            var sent = false
            while (!sent) {
                val send = nodes(automation.rootInActiveWindow).firstOrNull {
                    it.packageName == context.packageName && it.isVisibleToUser && it.isEnabled && it.isClickable &&
                        it.contentDescription?.toString() in labels
                }
                sent = send?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!sent) delay(60)
            }
            true
        }
        if (clicked != true) {
            val active = automation.rootInActiveWindow
            val state = fixture.runtime.uiState.value
            val descriptions = nodes(active).filter { it.contentDescription?.toString() in labels }
                .map { "visible=${it.isVisibleToUser},enabled=${it.isEnabled},clickable=${it.isClickable}" }
            error("$case could not send from ChatScreen: window=${active?.packageName} " +
                "draftMatches=${state.input == prompt} conversationSelected=${state.currentConversation?.id == fixture.owner} " +
                "streaming=${state.isStreaming} matchingControls=$descriptions")
        }
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else buildList {
        add(root)
        repeat(root.childCount) { addAll(nodes(root.getChild(it))) }
    }
}
