package com.flowchat.app.device

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.domain.workspace.*
import com.flowchat.app.presentation.BloomSplashContent
import com.flowchat.app.presentation.BloomSplashBrandReadyMillis
import com.flowchat.app.presentation.chat.*
import com.flowchat.app.presentation.common.FlowSettingsPage
import com.flowchat.app.presentation.provider.*
import com.flowchat.app.presentation.overlay.AnswerBanner
import com.flowchat.app.presentation.overlay.AnswerBannerState
import com.flowchat.app.presentation.overlay.ExpandedAssistantPanel
import com.flowchat.app.presentation.overlay.FloatingAssistantState
import com.flowchat.app.presentation.overlay.ScreenQuestionState
import com.flowchat.app.ui.theme.*
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in design regression fixtures. No provider calls or persisted account/permission changes. */
class StitchDesignDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val provider = ProviderConfig(id = "stitch", displayName = "DeepSeek", baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-v4-flash", conversationModels = listOf("deepseek-v4-flash"))
    private val conversation get() = Conversation(id = "stitch-chat", providerId = provider.id, modelName = provider.defaultModel,
        assistantName = "写作助手", systemPrompt = "使用简洁中文回答。\n先给结论，再解释原因。")
    private val ready = ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot)
    private val accessibility = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runStitchDesignDiagnostics") == "true")

    private fun render(scenario: ActivityScenario<MainActivity>, theme: State<AppAppearance>, content: @Composable () -> Unit) {
        scenario.onActivity { activity -> activity.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            val localized = remember(base) { base.createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides activity) {
                FlowChatTheme(theme.value) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.systemBars)) {
                        Box(Modifier.fillMaxSize().testTag("stitch_fixture")) { content() }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("stitch_fixture").captureToImage().asAndroidBitmap()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "stitch-design").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun captureWindow(name: String) {
        compose.waitForIdle()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "stitch-design/$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun settingsRoutesRemainNavigableAndUseRealCallbacks() {
        enabled()
        val settings = mutableStateOf(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root))
        val theme = mutableStateOf(AppAppearance.Light)
        var openedProviders = false
        val memories = listOf(MemoryRecord("one", "", "偏好简洁中文", "", 0), MemoryRecord("two", "", "先给结论", "", 0))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            render(scenario, theme) {
                AppSettingsScreen(settings.value, memories, ready, accessibility, {}, theme.value, { theme.value = it },
                    { openedProviders = true }, {}, { settings.value = settings.value.copy(memoryEnabled = it) },
                    { settings.value = settings.value.copy(powerModeEnabled = it) },
                    { settings.value = settings.value.copy(powerConnectionMode = it) }, {}, {}, {}, { _, _ -> }, {}, {},
                    userName = "我的名字", providerName = "DeepSeek", workspaceName = "Documents",
                    onUsageTrackingChange = { settings.value = settings.value.copy(usageTrackingEnabled = it) },
                    onPhotoAnswerChange = { settings.value = settings.value.copy(photoAnswerEnabled = it) })
            }
            capture("07-settings")
            compose.onNodeWithText("模型与服务商").performClick()
            compose.runOnIdle { assertTrue(openedProviders) }
            compose.onNodeWithText("手机操作").performClick()
            capture("13-phone")
            compose.onNodeWithText("系统助手").performClick()
            capture("14-system-assistant")
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText("答题模式").performClick()
            capture("15-answer-mode")
            compose.onNodeWithTag("photo_answer_enabled").performClick()
            compose.runOnIdle { assertFalse(settings.value.photoAnswerEnabled) }
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText("外观").performClick()
            capture("18-appearance-language")
            compose.onNodeWithText("深色").performClick()
            capture("18-dark")
            compose.runOnIdle { assertEquals(AppAppearance.Dark, theme.value) }
        }
    }

    @Test fun conversationEditsAndUnsavedConfirmationKeepChanges() {
        enabled()
        val theme = mutableStateOf(AppAppearance.Light)
        var saved = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            render(scenario, theme) {
                ConversationSettingsScreen(conversation, {}, { _, _, _, prompt, _, _, _ -> saved = prompt })
            }
            capture("08-conversation")
            compose.onNodeWithTag("conversation_prompt").performClick()
            capture("09-prompt")
            compose.onNodeWithTag("system_prompt_editor").performTextReplacement("先给结论。")
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText("保存更改？").assertIsDisplayed()
            captureWindow("24-unsaved")
            compose.onAllNodesWithText("保存").onLast().performClick()
            compose.runOnIdle { assertEquals("先给结论。", saved) }
        }
    }

    @Test fun modelSheetSelectionAndComposerSendStillWork() {
        enabled()
        val theme = mutableStateOf(AppAppearance.Light)
        val input = mutableStateOf("")
        var sent = ""
        var selected = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            render(scenario, theme) {
                Column(Modifier.fillMaxSize()) {
                    ConversationHeaderTitle(null, "Floryx", Modifier.padding(horizontal = 24.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                        MessageBubble(Message(conversationId = conversation.id, role = MessageRole.User, content = "把这段内容整理成三点"), null, null, false, null)
                        MessageBubble(Message(conversationId = conversation.id, role = MessageRole.Assistant,
                            content = "1. 明确目标\n2. 制定计划\n3. 持续执行", status = MessageStatus.Complete), null, null, false, null)
                    }
                    MessageComposer(input.value, null, false, true, false, listOf(provider), conversation,
                        { input.value = it }, { sent = input.value }, {}, {}, {}, {}, {}, { _, model -> selected = model })
                }
            }
            capture("03-chat")
            compose.onNodeWithTag("model-switch").performClick()
            compose.onNodeWithTag("conversation-option-stitch-deepseek-v4-flash").assertIsDisplayed()
            // Dialogs are captured by the device-level screenshot, avoiding exclusion from the fixture root.
            val shot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), "stitch-design/05-model-sheet.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithTag("conversation-option-stitch-deepseek-v4-flash").performClick()
            compose.runOnIdle { assertEquals(provider.defaultModel, selected) }
            compose.onNode(hasSetTextAction()).performTextInput("测试发送")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.runOnIdle { assertEquals("测试发送", sent); assertEquals("", input.value) }
        }
    }

    @Test fun floatingPanelFitsViewportAndKeepsSendStopAndCollapseWorking() {
        enabled()
        val theme = mutableStateOf(AppAppearance.Light)
        val running = mutableStateOf(false)
        var sent = ""
        var stopped = false
        var collapsed = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            render(scenario, theme) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                    ExpandedAssistantPanel(FloatingAssistantState(conversationId = "fixture", canChat = true,
                        isStreaming = running.value, assistantText = "读取文件\n分析内容\n生成结果…"), ScreenQuestionState(), false,
                        { collapsed = true }, { sent = it }, { stopped = true; running.value = false }, { _, _, _ -> })
                }
            }
            capture("19-floating-panel")
            compose.onNode(hasSetTextAction()).performTextInput("继续整理")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.runOnIdle { assertEquals("继续整理", sent); running.value = true }
            compose.onNodeWithContentDescription("停止").performClick()
            compose.runOnIdle { assertTrue(stopped) }
            compose.onNodeWithContentDescription("收起浮窗助手").performClick()
            compose.runOnIdle { assertTrue(collapsed) }
        }
    }

    @Test fun memoryWorkspaceTerminalAndProviderPagesRenderInBothThemes() {
        enabled()
        val theme = mutableStateOf(AppAppearance.Light)
        val page = mutableStateOf(0)
        val memories = mutableStateOf(listOf(MemoryRecord("one", "", "偏好简洁中文", "", 0), MemoryRecord("two", "", "先给结论", "", 0), MemoryRecord("three", "", "使用公制单位", "", 0)))
        val editProvider = mutableStateOf(false)
        var cleared = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            render(scenario, theme) {
                when (page.value) {
                    0 -> MemorySettingsScreen(true, memories.value, {}, { _, _ -> }, {}, { cleared = true; memories.value = emptyList() }, {})
                    1 -> WorkspaceSettingsScreen(WorkspaceUiState(location = WorkspaceLocation("fixture", "Documents", "Documents/Floryx"),
                        status = WorkspaceStatus(WorkspaceAccess.ReadWrite), pending = listOf(WorkspacePending(WorkspaceLocation("fixture", "Documents"), "report.md", null, "fixture-pending"))), true, false, {}, {}, {}, {}, {}, {})
                    2 -> TerminalSettingsScreen(TerminalUiState(linux = LinuxEnvironmentState(ready = true, developerToolsReady = true),
                        commands = listOf(CommandSnapshot("run", "python3 report.py", CommandEnvironment.Linux, "/", createdAt = System.currentTimeMillis() - 4000),
                            CommandSnapshot("done", "ls -la", CommandEnvironment.Linux, "/", CommandStatus.Exited, exitCode = 0, finishedAt = System.currentTimeMillis()))), true, true, {}, {}, {}, {}, {})
                    3 -> FlowSettingsPage(if (editProvider.value) "编辑连接" else "模型与服务商", { editProvider.value = false }) {
                        ProviderEditor(state = ProviderSettingsUiState(providers = listOf(provider), selected = provider, currentProvider = provider, hasApiKey = true),
                            onUpdate = {}, onSelectProvider = {}, onApiKey = {}, onLoadModelOptions = {}, onSelectModelOption = {},
                            onDismissModelOptions = {}, onApplyPreset = {}, onPresetApiKey = {}, onSavePresetApiKey = {},
                            onDismissPresetApiKeyDialog = {}, onSave = {}, editing = editProvider.value, onEditingChange = { editProvider.value = it },
                            modifier = Modifier.padding(horizontal = 24.dp))
                    }
                    4 -> BloomSplashContent(BloomSplashBrandReadyMillis.toFloat(), theme.value)
                    5 -> AnswerBanner(AnswerBannerState("design", "design", "选 B。由题意可得 x = 4。"), {}, {})
                }
            }
            capture("12-memory")
            compose.onAllNodesWithText("清空全部").onLast().performClick()
            compose.onNodeWithText("此操作无法撤销。").assertIsDisplayed()
            compose.onAllNodesWithText("清空全部").onLast().performClick()
            compose.runOnIdle { assertTrue(cleared) }
            compose.runOnIdle { page.value = 1 }; capture("17-workspace")
            compose.onNodeWithTag("workspace_default").assertIsDisplayed()
            compose.runOnIdle { page.value = 2 }; capture("16-terminal")
            compose.runOnIdle { page.value = 3 }; capture("10-providers")
            compose.onNodeWithText("编辑").performClick(); capture("11-edit-connection")
            compose.onNodeWithText("API Key").assertIsDisplayed()
            compose.runOnIdle { page.value = 4 }; capture("01-splash")
            compose.runOnIdle { theme.value = AppAppearance.Dark }; capture("01-splash-dark")
            compose.runOnIdle { theme.value = AppAppearance.Light; page.value = 5 }; capture("21-answer-banner")
            compose.onNodeWithText("选 B。由题意可得 x = 4。").assertIsDisplayed()
        }
    }
}
