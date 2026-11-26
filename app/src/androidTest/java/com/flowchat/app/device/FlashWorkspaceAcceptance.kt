package com.flowchat.app.device

import android.content.Context
import android.os.SystemClock
import android.app.UiAutomation
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import com.flowchat.app.data.network.*
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.data.workspace.WorkspaceManager
import com.flowchat.app.di.AppModule
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.observeF06OutboundBody
import com.flowchat.app.diagnostics.summarizeF06OutboundEvidence
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.terminal.TerminalToolDefinitions
import com.flowchat.app.domain.workspace.WorkspaceLocation
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import dagger.hilt.android.EntryPointAccessors
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue

/** Real Flash decisions through ChatTaskRuntime.send. No tool filtering, scripted answers or approval clicks. */
internal class FlashWorkspaceAcceptance(private val context: Context, private val location: WorkspaceLocation,
    private val workspace: WorkspaceManager, private val activity: MainActivity) {
    private val args get() = InstrumentationRegistry.getArguments()
    private val directory = File(context.getExternalFilesDir(null), args.getString("flashEvidenceDir") ?: "p0d-flash").apply { mkdirs() }
    private class BudgetUnavailable(message: String) : IllegalStateException(message)
    private val automation get() = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (root == null) emptyList() else buildList {
        add(root); repeat(root.childCount) { addAll(nodes(root.getChild(it))) }
    }
    private fun screenshot(name: String) {
        File(directory, "$name.png").outputStream().use { requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    suspend fun run() {
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val app = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        val terminal = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).terminal()
        val localLoopback = args.getString("flashLocalLoopback") == "true"
        val dry = !localLoopback && args.getString("flashDryRun") == "true"
        val inputCap = args.getString("flashInputBudget")?.toLong() ?: 47970L
        val outputCap = args.getString("flashOutputBudget")?.toLong() ?: 106251L
        val requestCap = args.getString("flashRequestBudget")?.toInt() ?: 13

        val selection = if (localLoopback) null else requireNotNull(app.settings().state.value.assistantModelSelection)
        val provider = if (localLoopback) null else {
            check(selection!!.providerId == "provider-deepseek" && selection.modelName == "deepseek-flash")
            requireNotNull(data.providers().getProvider(selection.providerId)).also {
                check(it.isEnabled && java.net.URI(it.baseUrl).host == "api.deepseek.com")
            }
        }
        val key = if (localLoopback) null else requireNotNull(data.providers().getApiKey(provider!!))
        val ledgerFile = File(directory, "usage-ledger.json")
        val ledger = if (ledgerFile.exists()) JSONObject(ledgerFile.readText()) else JSONObject()
            .put("requests", 0).put("input_accounted", 0L).put("output_accounted", 0L).put("requests_detail", JSONArray())
        val results = JSONObject().put(
            "mode",
            when {
                localLoopback -> "mumu-local-loopback"
                dry -> "preflight_no_network"
                else -> "phone-real-model"
            }
        )
            .put("provider_id", provider?.id ?: "autonomy-local")
            .put("host", if (localLoopback) "127.0.0.1" else "api.deepseek.com")
            .put("model", selection?.modelName ?: "autonomy-local")
            .put("thinking", if (localLoopback) "local" else "enabled/auto")
            .put("caps_from_remaining_ledger", JSONObject().put("input", inputCap).put("output", outputCap).put("requests", requestCap))
            .put("cases", JSONArray())
        fun save() {
            val fileName = when {
                localLoopback -> "local-results.json"
                dry -> "preflight.json"
                else -> "results.json"
            }
            File(directory, fileName).writeText(results.toString(2))
        }
        val variants = listOf(
            Triple("F06-normal", "看一下工作区里的 small-check.sh 适不适配这个手机，属于哪种类型。只检查，不执行。", "small-check.sh"),
            Triple("F06-other-start", "帮我看看工作区里的 small-check.sh 是什么类型，能不能适配当前手机。只检查内容，不执行也不修改。", "small-check.sh"),
            Triple("F06-large", "看一下工作区里的 analysis.sh 适不适配这个手机，属于哪种类型。只检查，不执行也不修改。", "analysis.sh"))
        // Starting instrumentation can force-stop the already authorized accessibility service.
        // Restore its existing binding before preparing the user's normal tool combination.
        ActionOutcomeServiceReadiness().ensureService()
        val selectedVariant = args.getString("flashVariant")
        check(selectedVariant == null || variants.any { it.first == selectedVariant })
        for ((id, prompt, file) in variants.filter { selectedVariant == null || it.first == selectedVariant }) {
            val fixture = if (localLoopback) {
                AutonomyRuntimeFixture(
                    context = context,
                    terminalOverride = terminal,
                    workspaceOverride = workspace,
                    settingsOverride = app.settings().state.value.copy(
                        powerModeEnabled = false,
                        commandExecutionEnabled = true,
                        memoryEnabled = false
                    )
                )
            } else {
                AutonomyRuntimeFixture(
                    context,
                    provider!!.copy(defaultModel = selection!!.modelName),
                    key,
                    terminal,
                    workspace,
                    app.settings().state.value
                )
            }
            val http: HttpClient? = if (localLoopback) null else AppModule.provideHttpClient(AppModule.provideJson())
            val actual = http?.let {
                KtorOpenAiCompatibleClient(it, AppModule.provideJson()).apply {
                    usageTracker = UsageTracker(fixture.database, fixture.settings)
                }
            }
            val outboundRequests = JSONArray()
            val row = JSONObject().put("case_id", id).put("prompt", prompt).put("file", file)
                .put("extra_questions", JSONObject.NULL).put("manual_recovery_steps", 0).put("app_confirmations", 0)
                .put("initial_directory", if (id == "F06-other-start") "/workspace" else "/workspace/phone/${location.key}")
                .put("outbound_requests", outboundRequests)
                .put("official_requests", 0)
            results.getJSONArray("cases").put(row); save()
            var requestsForCase = 0; var modelMillis = 0L
            var reservedOutbound: ChatRequest? = null
            if (localLoopback) {
                fixture.responder = { request, round ->
                    if (file == "analysis.sh") {
                        when (round) {
                            1 -> listOf(
                                ChatDelta.ToolCalls(
                                    listOf(
                                        ChatToolCall("stat-$id", WorkspaceToolDefinitions.Stat, """{"path":"analysis.sh"}"""),
                                        ChatToolCall("phone-$id", TerminalToolDefinitions.Status, "{}")
                                    )
                                ),
                                ChatDelta.Done
                            )
                            2 -> listOf(
                                ChatDelta.ToolCalls(
                                    listOf(
                                        ChatToolCall("head-$id", WorkspaceToolDefinitions.Read, """{"path":"analysis.sh","byte_offset":0,"max_bytes":512}"""),
                                        ChatToolCall("mid-$id", WorkspaceToolDefinitions.Read, """{"path":"analysis.sh","byte_offset":3200000,"max_bytes":256}"""),
                                        ChatToolCall("tail-$id", WorkspaceToolDefinitions.Read, """{"path":"analysis.sh","byte_offset":6499820,"max_bytes":512}""")
                                    )
                                ),
                                ChatDelta.Done
                            )
                            3 -> {
                                val toolContents = request.messages.filter { it.role == "tool" }.joinToString("\n") { it.content.orEmpty() }
                                check("arm64-v8a" in toolContents && "Android 14 only" in toolContents)
                                listOf(
                                    ChatDelta.Content("analysis.sh 是 6,500,000 字节的 Shell 脚本文本，中段声明 arm64-v8a，尾部声明仅限 Android 14（未在当前设备验证，外部依赖未分析）。只读检查未执行、未修改原文件。"),
                                    ChatDelta.Done
                                )
                            }
                            else -> error("Unexpected F06-large local round $round")
                        }
                    } else {
                        when (round) {
                            1 -> listOf(
                                ChatDelta.ToolCalls(
                                    listOf(
                                        ChatToolCall("stat-$id", WorkspaceToolDefinitions.Stat, """{"path":"small-check.sh"}"""),
                                        ChatToolCall("read-$id", WorkspaceToolDefinitions.Read, """{"path":"small-check.sh","byte_offset":0,"max_bytes":4096}"""),
                                        ChatToolCall("phone-$id", TerminalToolDefinitions.Status, "{}")
                                    )
                                ),
                                ChatDelta.Done
                            )
                            2 -> {
                                val toolContents = request.messages.filter { it.role == "tool" }.joinToString("\n") { it.content.orEmpty() }
                                check("arm64-v8a" in toolContents && "Android support: 14 only" in toolContents)
                                listOf(
                                    ChatDelta.Content("small-check.sh 是 Shell 脚本文本，声明 CPU 为 arm64-v8a、Android 14 only，且明确未在当前手机或 Android 16 验证。只读检查未执行、未修改原文件。"),
                                    ChatDelta.Done
                                )
                            }
                            else -> error("Unexpected $id local round $round")
                        }
                    }
                }
            } else {
                http!!.observeF06OutboundBody { body ->
                    val request = checkNotNull(reservedOutbound) { "F06 HTTP dispatch has no separate budget reservation" }
                    reservedOutbound = null
                    val evidence = JSONObject(summarizeF06OutboundEvidence(request, body).toString())
                        .put("ledger_sequence", ledger.getInt("requests"))
                    outboundRequests.put(evidence)
                    save()
                }
                fixture.externalClient = object : ChatCompletionClient {
                    override fun streamChat(request: ChatRequest, config: ProviderConfig, apiKey: String?) = flow {
                        check(config == fixture.provider && apiKey == key && request.model == selection!!.modelName)
                        check(request.enableThinking && request.thinkingEffort == ThinkingEffort.Auto)
                        val sent = request.copy(maxTokens = minOf(request.maxTokens ?: 8192, 8192))
                        val noImages = sent.copy(messages = sent.messages.map { it.copy(images = emptyList()) })
                        val reserve = Json.encodeToString(noImages.toOpenAiRequest(config)).toByteArray().size.toLong() + 4096L + sent.messages.sumOf { it.images.size } * 1024L
                        row.put("preflight_input_reserve", reserve).put("normal_tools", JSONArray(sent.tools.map { it.name }))
                        save()
                        if (dry) throw BudgetUnavailable("Dry run: no request sent; reserve=$reserve")
                        if (ledger.getInt("requests") >= requestCap || ledger.getLong("input_accounted") + reserve > inputCap ||
                            ledger.getLong("output_accounted") + sent.maxTokens!! > outputCap)
                            throw BudgetUnavailable("Authorized remaining budget cannot reserve this request: input=$reserve")
                        val detail = JSONObject().put("sample", id).put("sequence", ledger.getInt("requests") + 1)
                            .put("input_reserved", reserve).put("output_reserved", sent.maxTokens).put("tool_names", JSONArray(sent.tools.map { it.name }))
                        ledger.put("requests", ledger.getInt("requests") + 1).put("input_accounted", ledger.getLong("input_accounted") + reserve)
                            .put("output_accounted", ledger.getLong("output_accounted") + sent.maxTokens!!)
                        ledger.getJSONArray("requests_detail").put(detail); ledgerFile.writeText(ledger.toString(2))
                        requestsForCase++
                        val old = fixture.database.usageDao().observe(fixture.owner).first().map { it.id }.toSet()
                        val start = SystemClock.elapsedRealtime()
                        try {
                            reservedOutbound = sent
                            actual!!.streamChat(sent, config, apiKey).collect { emit(it) }
                        }
                        finally {
                            reservedOutbound = null
                            modelMillis += SystemClock.elapsedRealtime() - start
                            val usage = fixture.database.usageDao().observe(fixture.owner).first().singleOrNull { it.id !in old }
                            if (usage?.inputTokens != null && usage.outputTokens != null) {
                                ledger.put("input_accounted", ledger.getLong("input_accounted") - reserve + usage.inputTokens)
                                    .put("output_accounted", ledger.getLong("output_accounted") - sent.maxTokens!! + usage.outputTokens)
                                detail.put("reported_input", usage.inputTokens).put("reported_output", usage.outputTokens)
                            } else detail.put("usage_unknown_reservation_retained", true)
                            ledgerFile.writeText(ledger.toString(2))
                        }
                    }
                }
            }
            var completed = false
            fun stage(name: String) {
                row.put("stage", name); save()
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "\nP0D_F06 $id $name\n") })
            }
            try {
                fixture.settings.setUsageTrackingEnabled(true)
                stage("creating_isolated_chat")
                fixture.create()
                stage("connecting_normal_power_tools")
                if (!localLoopback && fixture.settings.state.value.powerModeEnabled) try { fixture.connect() } catch (error: Exception) {
                    row.put("root_status", fixture.root.state.value.status.name)
                        .put("gateway_status", fixture.real.connectionState.value.status.name)
                        .put("accessibility_status", fixture.real.accessibilityState.value.status.name)
                        .put("power_mode", fixture.settings.state.value.powerConnectionMode.name)
                    throw error
                }
                stage("selecting_synthetic_workspace")
                fixture.chats.updateConversationWorkspace(fixture.owner, location)
                fixture.await { fixture.runtime.uiState.value.currentConversation?.workspace == location }
                stage("opening_production_chat_screen")
                withContext(Dispatchers.Main) {
                    val model = ChatViewModel(fixture.runtime)
                    activity.setContent { FlowChatTheme(AppAppearance.Dark) { ChatScreen({}, {}, {}, AppAppearance.Dark, {}, model) } }
                }
                if (id == "F06-other-start") {
                    val modelLabel = selection?.modelName ?: fixture.provider.defaultModel
                    val initialDirResult = runCatching {
                        val initial = terminal.start(fixture.owner, com.flowchat.app.domain.terminal.CommandEnvironment.Linux, "pwd", "/workspace", 5)
                        terminal.awaitCompletion(fixture.owner, initial.id)
                    }
                    val initialDirectory = if (localLoopback) {
                        val observed = initialDirResult.getOrNull()
                        if (observed != null && observed.exitCode == 0 && observed.output.trim() == "/workspace") "/workspace"
                        else "/workspace (emulator-no-proot)"
                    } else {
                        val observed = initialDirResult.getOrThrow()
                        check(observed.exitCode == 0 && observed.output.trim() == "/workspace")
                        observed.output.trim()
                    }
                    row.put("observed_initial_directory", initialDirectory)
                    fixture.chats.appendMessage(fixture.owner, MessageRole.User, "刚才查看了 Linux 当前目录。", MessageStatus.Sent, modelLabel)
                    fixture.chats.appendMessage(fixture.owner, MessageRole.Assistant, "此前实际目录：$initialDirectory。", MessageStatus.Complete, modelLabel)
                }
                val started = SystemClock.elapsedRealtime()
                stage("clicking_production_send")
                withContext(Dispatchers.Main) { fixture.runtime.updateInput(prompt) }
                var clicked = false
                withTimeout(10000) { while (!clicked) {
                    val button = nodes(automation.rootInActiveWindow).singleOrNull { it.isVisibleToUser && it.isEnabled &&
                        it.contentDescription?.toString() in setOf("发送", "Send") }
                    clicked = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (!clicked) delay(50)
                } }
                row.put("entry", "production_ChatScreen_send_button"); screenshot("$id-sent")
                stage("waiting_for_task_result")
                withTimeout(240000) {
                    fixture.await { fixture.journal.latest(fixture.owner) != null }
                    while (fixture.owner in fixture.runtime.activeConversations.value && fixture.confirmations.isEmpty()) delay(100)
                }
                if (fixture.owner in fixture.runtime.activeConversations.value) {
                    withContext(Dispatchers.Main) { fixture.runtime.stopFromAssistant(fixture.owner) }
                    fixture.await { fixture.owner !in fixture.runtime.activeConversations.value &&
                        com.flowchat.app.domain.chat.TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson)?.stopReason == "user_stopped" }
                }
                val messages = fixture.chats.getMessages(fixture.owner)
                delay(300); screenshot("$id-final")
                File(directory, "$id-messages.json").writeText(JSONArray(messages.map { JSONObject().put("role", it.role.name)
                    .put("content", it.content).put("status", it.status.name).put("execution", it.executionContext) }).toString(2))
                val latestRun = fixture.journal.latest(fixture.owner)
                row.put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("model_ms", modelMillis)
                    .put("requests", requestsForCase).put("app_confirmations", fixture.confirmations.size)
                    .put("official_requests", if (localLoopback) 0 else requestsForCase)
                    .put("local_model_calls", if (localLoopback) fixture.modelCalls.get() else 0)
                    .put("answer", messages.lastOrNull { it.role == MessageRole.Assistant }?.content)
                    .put("runtime_state", latestRun?.state)
                    .put("task_run_id", latestRun?.id)
                    .put("task_metadata", latestRun?.metadataJson?.let { JSONObject(it) })
                    .put(
                        "result",
                        when {
                            localLoopback -> if (latestRun?.state == "Completed" && fixture.confirmations.isEmpty()) "passed" else "failed"
                            requestsForCase == 0 -> "not_run"
                            else -> "requires_independent_review"
                        }
                    )
                if (localLoopback) {
                    check(latestRun?.state == "Completed" && fixture.confirmations.isEmpty()) {
                        "F06 local loopback failed: state=${latestRun?.state}, confirmations=${fixture.confirmations.size}"
                    }
                }
                completed = true
                stage("recorded")
            } catch (error: Exception) {
                row.put("error", error.javaClass.simpleName + ": " + error.message); save(); throw error
            } finally {
                row.put("database", fixture.name)
                fixture.close(completed)
                http?.close()
                val copy = File(context.filesDir, "terminal/workspaces/${fixture.owner}")
                check(copy.canonicalFile.parentFile == File(context.filesDir, "terminal/workspaces").canonicalFile)
                copy.deleteRecursively()
                val transferFile = File(context.filesDir, "workspace-transfers/${fixture.owner}.json")
                if (transferFile.exists()) transferFile.delete()
                val dbDeleted = !completed || !context.getDatabasePath("${fixture.name}.db").exists()
                val copyDeleted = !copy.exists()
                val transferDeleted = !transferFile.exists()
                val activeCommands = terminal.state.value.commands.count {
                    terminal.owns(fixture.owner, it.id) && (it.isRunning || it.cleanup?.blocksReuse == true)
                }
                val cleanupPassed = dbDeleted && copyDeleted && transferDeleted && activeCommands == 0
                row.put(
                    "cleanup",
                    JSONObject()
                        .put("isolated_db_deleted", dbDeleted)
                        .put("workspace_copy_deleted", copyDeleted)
                        .put("transfer_file_deleted", transferDeleted)
                        .put("active_commands_remaining", activeCommands)
                        .put("cleanup_passed", cleanupPassed)
                )
                save()
                assertTrue("F06 zero-side-effect cleanup failed: ${row.optJSONObject("cleanup")}", cleanupPassed)
            }
        }
    }
}
