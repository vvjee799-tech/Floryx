package com.flowchat.app.device

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
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

/** Explicit opt-in: consumes the existing provider's credits; only synthetic conversation files. */
class LiveAgentDeepDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun liveAgentBuildsCorrectsRecoversAndReadsPagedOutput() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveAgentDeepDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = entry.settings().state.value
        val provider = requireNotNull(data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        }) { "An existing enabled DeepSeek configuration is required" }
        val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
        val reportDir = File(context.filesDir, "live-agent-deep/${conversation.id}").apply { mkdirs() }
        val workspace = File(context.filesDir, "terminal/workspaces/${conversation.id}/qa")
        fun record(text: String) { File(reportDir, "results.txt").appendText(text + "\n") }
        suspend fun saveTranscript() {
            // Synthetic task messages and execution evidence only. No provider configuration or reasoning.
            File(reportDir, "transcript.json").writeText(buildJsonArray {
                data.chats().getMessages(conversation.id).forEach { message -> add(buildJsonObject {
                    put("role", message.role.name); put("status", message.status.name)
                    put("phase", message.replyPhase.name); put("content", message.content)
                    put("execution", message.executionContext)
                }) }
            }.toString())
        }
        suspend fun sendAndWait(prompt: String): List<Message> {
            val prior = data.chats().getMessages(conversation.id).map { it.id }.toSet()
            compose.onNode(hasSetTextAction()).performTextInput(prompt)
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(context.getString(R.string.send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            return withTimeout(180_000) {
                while (true) {
                    val fresh = data.chats().getMessages(conversation.id).filter { it.id !in prior }
                    val failure = fresh.firstOrNull { it.status == MessageStatus.Failed && it.role == MessageRole.Assistant }
                    if (failure != null) error("Live task failed: ${failure.content}")
                    if (fresh.any { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.replyPhase == ReplyPhase.Answer }) {
                        saveTranscript()
                        record("Completed turn: ${prompt.take(100)}; tool rows=${fresh.count { it.role == MessageRole.Tool }}")
                        return@withTimeout fresh
                    }
                    delay(500)
                }
                error("unreachable")
            }
        }
        fun total(expected: Int) {
            val result = Json.parseToJsonElement(File(workspace, "result.json").readText()).jsonObject
            assertEquals(expected, result.getValue("total").jsonPrimitive.int)
        }
        try {
            data.chats().updateConversationTitle(conversation.id, "深度测试：文件、纠错与恢复")
            data.chats().updateThinkingSettings(conversation.id, true, ThinkingEffort.Low)
            entry.settings().setCommandExecutionEnabled(true)
            entry.settings().setPowerModeEnabled(false)
            entry.settings().setMemoryEnabled(false)
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            sendAndWait("这是已授权的隔离测试。只操作 Linux /workspace/qa，不联网、不安装软件、不访问 Android。请实际用工具创建该目录，在订单.csv 写入四行数据：name,amount,status 表头；甲,12,ok；乙,20,ok；丙,99,failed；丁,5,ok。创建可重复运行的汇总脚本，只求 ok 金额和，输出 result.json，字段 total 为数字。运行脚本并读取结果验证，完成后报告实际总额。可使用已有 Python 或其他已有工具。")
            total(37)
            val csv = File(workspace, "订单.csv")
            assertTrue(csv.readText().contains("丙,99,failed"))
            record("PASS turn 1: on-disk total=37, failed row preserved")
            sendAndWait("更正：把乙的金额从20改为25，其他订单保持不变。沿用刚才文件和汇总脚本，不要重建环境。重新运行并验证 result.json。另建 note.txt，内容为 中文 空格 '单引号' \"双引号\"，读取回验。")
            total(42)
            assertTrue(csv.readText().contains("乙,25,ok"))
            assertTrue(csv.readText().contains("丙,99,failed"))
            assertEquals("中文 空格 '单引号' \"双引号\"", File(workspace, "note.txt").readText().trimEnd())
            record("PASS turn 2: on-disk total=42, Unicode and quotes round-trip")
            val recovery = sendAndWait("接着验证失败恢复：先尝试读取 /workspace/qa/missing-result.json（预期不存在，不要创建它），看到失败后根据前两轮已知的正确文件路径读取真实结果。将真实总额写入 recovery.txt，严格内容 recovered=42。不要把前一次失败说成成功，不要重新创建订单文件。")
            total(42)
            assertFalse(File(workspace, "missing-result.json").exists())
            assertEquals("recovered=42", File(workspace, "recovery.txt").readText().trim())
            assertTrue("The missing-file error must be visible in execution evidence", recovery.any {
                it.role == MessageRole.Tool && (it.content.contains("No such file") || it.content.contains("FileNotFoundError"))
            })
            record("PASS turn 3: missing-file failure recorded, existing result reused")
            // The tail value is deliberately unknown to the model until it reads command output.
            val tail = "TAIL_" + java.util.UUID.randomUUID().toString()
            File(workspace, "large.txt").writeText("synthetic output data only\n".repeat(650) + tail + "\n")
            sendAndWait("最后检验长输出：用一次 cat /workspace/qa/large.txt 完整输出文件；如果工具结果 has_more 为 true，必须使用 read_command_output 和返回的 next_offset 继续读取。把实际读到的最后一行原样写到 /workspace/qa/tail.txt，不要猜测，也不要用 tail 或另一个命令绕过这次分页验证。")
            assertEquals(tail, File(workspace, "tail.txt").readText().trim())
            record("PASS turn 4: paginated output tail verified independently")
            record("PASS all four live turns; conversation=${conversation.id}; model=${provider.defaultModel}")
        } finally {
            saveTranscript()
            entry.terminal().cancelAll()
            entry.settings().setCommandExecutionEnabled(original.commandExecutionEnabled)
            entry.settings().setPowerModeEnabled(original.powerModeEnabled)
            entry.settings().setMemoryEnabled(original.memoryEnabled)
            // Keep this synthetic conversation and its workspace for user review and follow-up.
        }
    }
}
