package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real-model interaction with the separate, synthetic 48-level Android test page. */
class LiveDeviceDeepDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun liveAgentUsesDeepControlsAndAppliesFollowUp() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveAgentDeepDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = entry.settings().state.value
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        })
        val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
        val reportDir = File(context.filesDir, "live-agent-deep/${conversation.id}").apply { mkdirs() }
        suspend fun save() {
            File(reportDir, "transcript.json").writeText(buildJsonArray {
                data.chats().getMessages(conversation.id).forEach { message -> add(buildJsonObject {
                    put("role", message.role.name); put("status", message.status.name)
                    put("phase", message.replyPhase.name); put("content", message.content)
                    put("execution", message.executionContext)
                }) }
            }.toString())
        }
        suspend fun main() {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(600)
        }
        suspend fun turn(prompt: String, reset: Boolean) {
            main()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            val prior = data.chats().getMessages(conversation.id).map { it.id }.toSet()
            compose.onNode(hasSetTextAction()).performTextInput(prompt)
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            context.startActivity(Intent().apply {
                component = ComponentName(instrumentation.context.packageName, DeviceAutomationFixtureActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or if (reset) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("deep_tree", true)
            })
            withTimeout(180_000) {
                while (true) {
                    val fresh = data.chats().getMessages(conversation.id).filter { it.id !in prior }
                    fresh.firstOrNull { it.role == MessageRole.Assistant && it.status in listOf(MessageStatus.Failed, MessageStatus.Stopped) }?.let {
                        error("Live device task did not complete: ${it.content}")
                    }
                    if (fresh.any { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.replyPhase == ReplyPhase.Answer }) break
                    delay(500)
                }
            }
            save()
        }
        try {
            data.chats().updateConversationTitle(conversation.id, "深度测试：48 层页面多轮操作")
            data.chats().updateThinkingSettings(conversation.id, true, ThinkingEffort.Low)
            entry.settings().setCommandExecutionEnabled(false)
            entry.settings().setMemoryEnabled(false)
            entry.settings().setPowerModeEnabled(true)
            main()
            val component = ComponentName(context, FlowChatAccessibilityService::class.java).flattenToString()
            val existing = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            check(existing.split(':').contains(component)) { "Previously enabled accessibility service required" }
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            if (FlowChatAccessibilityService.current() == null) {
                automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
                try {
                    try {
                        check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existing.split(':').filterNot { it == component }.joinToString(":")))
                        delay(250)
                    } finally {
                        check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existing))
                    }
                } finally { automation.dropShellPermissionIdentity() }
            }
            withTimeout(10_000) { while (!entry.device().accessibilityState.value.isConnected) delay(200) }
            turn("这是已授权的界面测试。专用测试页面马上显示，包名 com.flowchat.app.debug.test，标题 FlowChat automation test。只操作这个测试页面，不要切到其他应用。先 observe_screen，若尚未显示则 wait_for_screen。把 Automation test input 输入框填为 深度测试 中文 123，再把 Test toggle 勾选。使用实际页面索引及 screen_id，操作后观察验证文本和选中状态再报告。不要操作其他控件。", reset = true)
            val first = requireNotNull(entry.device().observeScreen().screen)
            assertEquals(instrumentation.context.packageName, first.packageName)
            assertTrue(first.elements.any { it.editable && it.text == "深度测试 中文 123" })
            assertTrue(first.elements.any { it.text == "Test toggle" && it.checked == true })
            File(reportDir, "results.txt").appendText("PASS turn 1: actual deep input text and checkbox checked\n")
            turn("继续刚才的专用测试页面，已回到该页面。先重新观察，不复用旧 screen_id。把原输入内容替换为 已更正 456，不要追加；把 Test toggle 取消勾选。最后重新观察确认两个结果，报告即可。不要重开页面或操作别的应用。", reset = false)
            val second = requireNotNull(entry.device().observeScreen().screen)
            assertTrue(second.elements.any { it.editable && it.text == "已更正 456" })
            assertTrue(second.elements.any { it.text == "Test toggle" && it.checked == false })
            File(reportDir, "results.txt").appendText("PASS turn 2: actual replacement and checkbox unchecked; conversation=${conversation.id}\n")
        } finally {
            save()
            entry.settings().setCommandExecutionEnabled(original.commandExecutionEnabled)
            entry.settings().setPowerModeEnabled(original.powerModeEnabled)
            entry.settings().setMemoryEnabled(original.memoryEnabled)
        }
    }
}
