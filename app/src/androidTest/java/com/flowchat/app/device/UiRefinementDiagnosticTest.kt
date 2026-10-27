package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.presentation.chat.*
import com.flowchat.app.presentation.provider.*
import com.flowchat.app.ui.theme.*
import java.io.File
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in visual and interaction fixtures. No app records, credentials, commands or APIs are used. */
class UiRefinementDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun surfacesAndControlsRemainUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runUiRefinementDiagnostics") == "true")
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        val monitor = instrumentation.addMonitor(ComponentActivity::class.java.name, null, false)
        shell("am start -W -n ${context.packageName}/${ComponentActivity::class.java.name}")
        val activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as ComponentActivity
        instrumentation.removeMonitor(monitor)
        val page = mutableStateOf("chat")
        val appearance = mutableStateOf(AppAppearance.Dark)
        val draft = mutableStateOf("")
        val provider = ProviderConfig(id = "ui-fixture", displayName = "DeepSeek", baseUrl = "https://example.invalid/v1", defaultModel = "deepseek-v4-flash")
        val conversation = mutableStateOf(Conversation(id = "ui-fixture", providerId = provider.id, modelName = provider.defaultModel))
        val providerState = mutableStateOf(ProviderSettingsUiState(selected = provider, currentProvider = provider, providers = listOf(provider), hasApiKey = true))
        val settings = mutableStateOf(AppSettings(memoryEnabled = true, powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root))
        var sends = 0
        var fileRequests = 0
        var stopRequests = 0
        val snapshot = CommandSnapshot("ui-command", "python3 -c 'print(42)'", CommandEnvironment.Linux, "/workspace",
            status = CommandStatus.Exited, output = "42\n任务已完成。", exitCode = 0, createdAt = 1_000, finishedAt = 22_000)
        val memory = MemoryRecord("ui-memory", "preference", "希望使用简洁的中文回复，并先给出结论。", "summary", 1,
            "preference.language", "preference", 2)
        val messages = listOf(
            Message("u1", conversation.value.id, MessageRole.User, "帮我整理这份项目笔记，列出接下来要做的三件事。"),
            Message("a1", conversation.value.id, MessageRole.Assistant, "", reasoningContent = "先提取目标和进展，再将剩余事项按优先级排列。", reasoningDurationMillis = 2_000),
            Message("t1", conversation.value.id, MessageRole.Tool, "run_command\npython3 notes.py\nExited · elapsed=21s · exit=0\n已整理 12 条笔记。"),
            Message("a2", conversation.value.id, MessageRole.Assistant, "可以先做这三件事：\n\n1. 确认核心流程，把主要任务放在最容易找到的位置。\n\n2. 补齐异常状态，让等待、取消和失败都有明确反馈。\n\n3. 在不同屏幕和字体大小下检查，确保每个操作都能顺利完成。")
        )
        val paperMessages = listOf(
            Message("paper-user", conversation.value.id, MessageRole.User, "怎么让桌面看起来更清爽？"),
            Message("paper-answer", conversation.value.id, MessageRole.Assistant,
                "先从手边开始：\n\n收起暂时不用的东西，只留下今天会用到的。\n\n把零散物品放进一个托盘，再留一块空白。\n\n不必一次整理完。")
        )
        val fixtureContext = ContextThemeWrapper(activity, com.flowchat.app.R.style.Theme_FlowChat).apply {
            val config = Configuration(activity.resources.configuration)
            args.getString("fixtureLocale")?.let { config.setLocale(Locale.forLanguageTag(it)) }
            applyOverrideConfiguration(config)
        }
        instrumentation.runOnMainSync {
            activity.enableEdgeToEdge()
            activity.setContent {
              CompositionLocalProvider(LocalContext provides fixtureContext, LocalConfiguration provides fixtureContext.resources.configuration) {
                FlowChatTheme(appearance.value) {
                    SideEffect { WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = appearance.value == AppAppearance.Light
                        isAppearanceLightNavigationBars = appearance.value == AppAppearance.Light
                    } }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        key(page.value) {
                            when (page.value) {
                                "chat", "messages", "paper" -> Scaffold(
                                    topBar = { TopAppBar(title = { ConversationHeaderTitle(null, "FlowChat") },
                                        navigationIcon = { IconButton(onClick = {}) { Icon(Icons.Outlined.Menu, null) } },
                                        actions = { IconButton(onClick = {}) { Icon(Icons.Default.Add, null) } },
                                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
                                    bottomBar = { MessageComposer(draft.value, null, false, true, false, listOf(provider), conversation.value,
                                        { draft.value = it }, { sends++ }, { stopRequests++ },
                                        { conversation.value = conversation.value.copy(enableThinking = it) },
                                        { conversation.value = conversation.value.copy(thinkingEffort = it) },
                                        { fileRequests++ }, {}, { _, _ -> }) },
                                    containerColor = MaterialTheme.colorScheme.background
                                ) { padding ->
                                    Box(Modifier.fillMaxSize().padding(padding).wrapContentWidth().widthIn(max = 720.dp).padding(horizontal = com.flowchat.app.presentation.common.FlowLayout.Gutter)) {
                                        if (page.value == "chat") ChatWelcome(true, Modifier.fillMaxSize())
                                        else if (page.value == "paper") MessageList(paperMessages, null, null, false, Modifier.fillMaxSize())
                                        else MessageList(messages, null, null, false, Modifier.fillMaxSize())
                                    }
                                }
                                "settings", "profile" -> AppSettingsScreen(settings.value, listOf(memory),
                                    ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot),
                                    AccessibilityConnectionState(AccessibilityConnectionStatus.Connected), {},
                                    appearance.value, { appearance.value = it }, { page.value = "provider" }, { page.value = "terminal" },
                                    { settings.value = settings.value.copy(memoryEnabled = it) },
                                    { settings.value = settings.value.copy(powerModeEnabled = it) },
                                    { settings.value = settings.value.copy(powerConnectionMode = it) }, {}, {}, {}, { _, _ -> }, {}, { page.value = "chat" })
                                "provider" -> ProviderEditor(providerState.value,
                                    { update -> providerState.value = providerState.value.copy(selected = update(requireNotNull(providerState.value.selected))) },
                                    { providerState.value = providerState.value.copy(selected = it) },
                                    { providerState.value = providerState.value.copy(apiKey = it) }, {}, {}, {},
                                    { providerState.value = providerState.value.copy(pendingPreset = it) },
                                    { providerState.value = providerState.value.copy(presetApiKey = it) }, {},
                                    { providerState.value = providerState.value.copy(pendingPreset = null) }, {},
                                    Modifier.fillMaxSize().safeDrawingPadding().wrapContentWidth().widthIn(max = 720.dp).padding(horizontal = 20.dp))
                                "memory" -> MemorySettingsScreen(true, listOf(memory), {}, { _, _ -> }, {}, {}, { page.value = "chat" })
                                "terminal" -> TerminalSettingsScreen(TerminalUiState(
                                    LinuxEnvironmentState(ready = true, version = "3.24.2", architecture = "x86_64", developerToolsReady = true), listOf(snapshot)),
                                    true, false, {}, {}, {}, { stopRequests++ }, { page.value = "chat" })
                                "conversation" -> ConversationSettingsScreen(conversation.value, { page.value = "chat" }, { _, _, _, _, _, _, _ -> })
                                "device" -> DeviceAssistantScreen(settings.value, ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot),
                                    AccessibilityConnectionState(AccessibilityConnectionStatus.Connected), {},
                                    { settings.value = settings.value.copy(powerModeEnabled = it) },
                                    { settings.value = settings.value.copy(powerConnectionMode = it) }, {}, {}, {}, { page.value = "chat" })
                            }
                        }
                    }
                }
              }
            }
        }
        val prefix = args.getString("capturePrefix") ?: "phone"
        fun capture(name: String) {
            compose.waitForIdle()
            android.os.SystemClock.sleep(350)
            val bitmap = requireNotNull(automation.takeScreenshot())
            val output = File(context.getExternalFilesDir(null), "ui-refinement").apply { mkdirs() }
            File(output, "$prefix-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun show(target: String, mode: AppAppearance = appearance.value) {
            shell("input keyevent 111") // Dismiss an IME without navigating the fixture.
            compose.runOnIdle { page.value = target; appearance.value = mode; draft.value = "" }
            compose.waitForIdle()
            if (target == "conversation") {
                compose.onNodeWithText(fixtureContext.getString(R.string.max_tokens)).performScrollTo()
            }
        }
        try {
            val captureOnly = args.getString("captureOnly") == "true"
            capture("chat-dark")
            if (!captureOnly) {
                compose.onNodeWithText(context.getString(R.string.welcome_idea)).assertDoesNotExist()
                compose.onNode(hasSetTextAction()).performTextInput("先整理一份简短清单")
                check(sends == 0)
                compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsEnabled().performClick()
                check(sends == 1)
                compose.onNodeWithTag("thinking-toggle").assertIsOn().performClick().assertIsOff()
                compose.onNodeWithTag("thinking-toggle").performClick().assertIsOn()
                compose.onNodeWithTag("thinking-options").performClick()
                compose.onNodeWithText(context.getString(R.string.thinking_effort_high)).performClick()
                check(conversation.value.thinkingEffort == ThinkingEffort.High)
                compose.onNodeWithContentDescription(context.getString(R.string.upload_file)).performClick()
                check(fileRequests == 1)
                compose.onNode(hasSetTextAction()).performTextInput("准备好后再发送")
                if (args.getString("requireIme") == "true") {
                    compose.onNode(hasSetTextAction()).performClick()
                    compose.waitUntil(5_000) {
                        ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    compose.onNode(hasSetTextAction()).assertIsDisplayed()
                    compose.onNodeWithContentDescription(context.getString(R.string.send)).assertIsDisplayed()
                    compose.onNodeWithContentDescription(context.getString(R.string.upload_file)).assertIsDisplayed()
                }
                capture("keyboard")
            }
            show("chat", AppAppearance.Light); capture("chat-light")
            show("paper"); capture("paper-light")
            show("paper", AppAppearance.Dark); capture("paper-dark")
            show("messages"); capture("messages-light")
            show("messages", AppAppearance.Dark); capture("messages-dark")
            for (target in listOf("settings", "provider", "memory", "terminal", "conversation", "device", "profile")) {
                show(target, AppAppearance.Dark); capture("$target-dark")
                show(target, AppAppearance.Light); capture("$target-light")
            }
            if (!captureOnly) {
                show("settings")
                compose.onNodeWithText(context.getString(R.string.language)).performScrollTo().assertIsDisplayed()
                capture("settings-scrolled")
                show("paper")
                compose.onNodeWithContentDescription(context.getString(R.string.message_actions)).performClick()
                compose.onNodeWithText(context.getString(R.string.select_text)).assertIsDisplayed()
                capture("message-actions")
                show("provider")
                compose.onNodeWithText(context.getString(R.string.provider_edit_current)).performClick()
                compose.onNodeWithText(context.getString(R.string.api_key)).performScrollTo().assertIsDisplayed()
                capture("provider-editor")
                compose.onNodeWithText("ChatGPT").performScrollTo().performClick()
                compose.onNodeWithText(context.getString(R.string.provider_preset_title, "ChatGPT")).assertIsDisplayed()
                compose.onAllNodesWithText(context.getString(R.string.save)).onLast().assertIsNotEnabled()
                capture("provider-key-dialog")
                compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
                show("terminal")
                compose.onNodeWithText(context.getString(R.string.terminal_expand_output)).performScrollTo().performClick()
                compose.onNodeWithText("42\n任务已完成。").performScrollTo().assertIsDisplayed()
                capture("command-expanded")
                compose.onNodeWithText(context.getString(R.string.terminal_android)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(R.string.terminal_android_unavailable)).assertIsDisplayed()
                capture("android-unavailable")
            }
            File(File(context.getExternalFilesDir(null), "ui-refinement"), "$prefix-result.txt")
                .writeText("PASS: $prefix; isolated UI fixtures; no model API or system commands; sends=$sends fileRequests=$fileRequests stopRequests=$stopRequests")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
