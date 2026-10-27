package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.presentation.chat.*
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in MuMu checks. Editable data uses an isolated preferences/file directory; no model calls. */
class SettingsNavigationDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runSettingsNavigationDiagnostics") == "true")

    @Test fun profileControlsAndConversationDraftsStayIndependent() {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var fixtureContext: Context
            val page = mutableStateOf("home")
            val appearance = mutableStateOf(AppAppearance.Light)
            val fontScale = mutableStateOf(1f)
            val conversation = mutableStateOf(Conversation(id = "settings-fixture", providerId = "fixture", modelName = "fixture-model"))
            var savedConversation: Conversation? = null
            var pickerRequests = 0
            lateinit var image: File
            val registry = object : ActivityResultRegistry() {
                @Suppress("UNCHECKED_CAST")
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    pickerRequests++
                    dispatchResult(requestCode, Uri.fromFile(image) as O)
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            scenario.onActivity { activity ->
                fixtureContext = object : ContextWrapper(activity) {
                    override fun getFilesDir() = File(activity.cacheDir, "settings-fixture").apply { mkdirs() }
                    override fun getSharedPreferences(name: String, mode: Int) = activity.getSharedPreferences("settings-fixture-$name", mode)
                }
                saveUserProfile(fixtureContext, "测试用户", null)
                image = File(fixtureContext.filesDir, "picker.png")
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.rgb(100, 94, 76))
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalContext provides fixtureContext, LocalActivityResultRegistryOwner provides owner,
                        LocalDensity provides Density(density, fontScale.value)) {
                        val profile = rememberUserProfile()
                        FlowChatTheme(appearance.value) {
                            SideEffect {
                                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                                    isAppearanceLightStatusBars = appearance.value == AppAppearance.Light
                                    isAppearanceLightNavigationBars = appearance.value == AppAppearance.Light
                                }
                            }
                            when (page.value) {
                                "home" -> Surface(Modifier.fillMaxSize()) {
                                    Column {
                                        UserProfileEntry(profile.name, profile.avatarPath)
                                        Button(onClick = { page.value = "settings" }) { Text("Open settings fixture") }
                                        Button(onClick = { page.value = "conversation" }) { Text("Open conversation fixture") }
                                    }
                                }
                                "settings" -> AppSettingsScreen(
                                    appSettings = AppSettings(), memories = emptyList(), shizukuState = ShizukuConnectionState(),
                                    accessibilityState = AccessibilityConnectionState(), onOpenAccessibilitySettings = {},
                                    appAppearance = appearance.value, onAppAppearanceChange = { appearance.value = it },
                                    onOpenProviders = {}, onOpenTerminal = {}, onMemoryEnabledChange = {},
                                    onPowerModeEnabledChange = {}, onPowerConnectionModeChange = {}, onRequestShizukuPermission = {},
                                    onRefreshShizuku = {}, onDeleteMemory = {}, onUpdateMemory = { _, _ -> }, onClearMemories = {},
                                    onDismiss = { page.value = "home" }, userName = profile.name, userAvatarPath = profile.avatarPath,
                                    onProfileNameChange = { saveUserProfile(fixtureContext, it, profile.avatarPath) },
                                    onProfileAvatarChange = { saveUserProfile(fixtureContext, profile.name, it) }
                                )
                                "conversation" -> ConversationSettingsScreen(conversation.value, onDismiss = { page.value = "home" },
                                    onSave = { name, avatar, show, prompt, temperature, topP, limit ->
                                        savedConversation = conversation.value.copy(assistantName = name, assistantAvatarPath = avatar,
                                            showAvatars = show, systemPrompt = prompt, temperature = temperature, topP = topP, maxTokens = limit)
                                        conversation.value = requireNotNull(savedConversation)
                                        page.value = "home"
                                    })
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("drawer_profile").assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
                .performTouchInput { click(); longClick() }
            compose.onNodeWithText("Open settings fixture").performClick()
            val beforeName = loadUserProfile(fixtureContext, "fallback")
            compose.onNodeWithTag("user_avatar_edit").performClick()
            compose.waitUntil(10_000) { loadUserProfile(fixtureContext, "fallback").avatarPath != null }
            assertEquals(1, pickerRequests)
            val firstAvatar = loadUserProfile(fixtureContext, "fallback").avatarPath
            assertEquals(beforeName.name, loadUserProfile(fixtureContext, "fallback").name)
            compose.onNodeWithTag("user_avatar").performClick()
            compose.waitUntil(10_000) { loadUserProfile(fixtureContext, "fallback").avatarPath != firstAvatar }
            assertEquals(2, pickerRequests)
            compose.onNodeWithTag("profile_name").performClick()
            compose.onNodeWithTag("name_editor").performTextReplacement("   ")
            compose.onNodeWithTag("name_save").assertIsNotEnabled()
            compose.onNodeWithTag("name_editor").performTextReplacement("新的名字")
            capture("name-editor")
            compose.onNodeWithTag("name_save").performClick()
            compose.onNodeWithTag("profile_name").assertTextEquals("新的名字")
            val updatedAvatar = loadUserProfile(fixtureContext, "fallback").avatarPath
            compose.onNodeWithTag("profile_name").performClick()
            compose.onNodeWithTag("name_editor").performTextReplacement("取消的名字")
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            assertEquals("新的名字", loadUserProfile(fixtureContext, "fallback").name)
            assertEquals(updatedAvatar, loadUserProfile(fixtureContext, "fallback").avatarPath)
            hideKeyboard()
            capture("settings-light")
            compose.onNodeWithTag("app_settings_content").performTouchInput { swipeDown() }
            compose.onNodeWithTag("settings_page").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.memory), substring = false).performScrollTo().performClick()
            compose.onNodeWithTag("memory-list").assertIsDisplayed()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithTag("profile_name").assertIsDisplayed()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText("新的名字").assertIsDisplayed()
            compose.onNodeWithText("Open conversation fixture").performClick()
            compose.onNodeWithTag("conversation_save").assertIsNotEnabled()
            compose.onNodeWithTag("assistant_name").performClick()
            compose.onNodeWithTag("name_editor").performTextReplacement("写作助手")
            compose.onNodeWithTag("name_save").performClick()
            compose.onNodeWithTag("conversation_prompt").performScrollTo().performClick()
            compose.onNodeWithTag("system_prompt_editor").performTextReplacement("请使用简洁中文回答。\n先给结论，再解释原因。")
            compose.onNodeWithTag("system_prompt_editor").performClick()
            capture("prompt-editor")
            hideKeyboard()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("conversation_prompt").assertTextContains("请使用简洁中文回答。\n先给结论，再解释原因。")
            compose.onNodeWithText(context.getString(R.string.max_tokens)).performScrollTo().performClick()
            compose.onNodeWithTag("output_limit_editor").performTextReplacement("99999999999999999999")
            compose.onNodeWithText(context.getString(R.string.done)).assertIsNotEnabled()
            compose.onNodeWithTag("output_limit_editor").performTextReplacement("2048")
            compose.onNodeWithText(context.getString(R.string.done)).performClick()
            hideKeyboard()
            compose.onNodeWithTag("assistant_avatar_edit").performScrollTo().performClick()
            compose.waitUntil(10_000) { pickerRequests == 3 }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("conversation_save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("conversation_save").assertIsEnabled()
            capture("conversation-light")
            compose.runOnIdle { appearance.value = AppAppearance.Dark }
            capture("conversation-dark")
            compose.runOnIdle { fontScale.value = 2f }
            compose.onNodeWithText(context.getString(R.string.max_tokens)).performScrollTo().assertIsDisplayed()
            val labelBounds = compose.onNodeWithText(context.getString(R.string.max_tokens), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val valueBounds = compose.onNodeWithText("2048", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Large text must not overlap the output limit", labelBounds.bottom <= valueBounds.top)
            capture("conversation-large-text")
            compose.runOnIdle { fontScale.value = 1f }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(context.getString(R.string.keep_editing)).performClick()
            assertNull(savedConversation)
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText(context.getString(R.string.save_and_return)).performClick()
            assertEquals("写作助手", savedConversation?.assistantName)
            assertEquals(2048, savedConversation?.maxTokens)
            assertNotNull(savedConversation?.assistantAvatarPath)
            assertEquals("新的名字", loadUserProfile(fixtureContext, "fallback").name)
            compose.onNodeWithText("Open conversation fixture").performClick()
            compose.onNodeWithTag("assistant_name").performClick()
            compose.onNodeWithTag("name_editor").performTextReplacement("不保存的名字")
            compose.onNodeWithTag("name_save").performClick()
            hideKeyboard()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText(context.getString(R.string.discard_changes)).performClick()
            compose.onNodeWithText("Open conversation fixture").performClick()
            compose.onNodeWithTag("assistant_name").assertTextEquals("写作助手")
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText("Open settings fixture").performClick()
            capture("settings-dark")
            compose.runOnIdle { fontScale.value = 2f }
            compose.onNodeWithText(context.getString(R.string.language)).performScrollTo().assertIsDisplayed()
            capture("settings-large-text")
        }
    }

    @Test fun liveNavigationReturnsToSettingsAndKeepsComposerDraft() {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            val input = compose.onAllNodes(hasSetTextAction()).onFirst()
            val original = input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            input.performTextReplacement("settings-navigation-fixture")
            hideKeyboard()
            compose.onNodeWithContentDescription(context.getString(R.string.open_navigation_drawer)).performClick()
            compose.onNodeWithTag("drawer_profile").assertHasNoClickAction()
            compose.onNodeWithContentDescription(context.getString(R.string.settings)).performClick()
            compose.onNodeWithTag("settings_page").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.model_provider_configuration)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()
            compose.onNodeWithTag("profile_name").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.workspace_title)).performScrollTo().performClick()
            compose.onNodeWithTag("workspace_settings").assertIsDisplayed()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithTag("app_settings_content").assertIsDisplayed()
            compose.onNodeWithTag("settings_back").performClick()
            compose.onAllNodes(hasSetTextAction()).onFirst().assertTextEquals("settings-navigation-fixture")
                .performTextReplacement(original)
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 111")).use { it.readBytes() }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Android dialog/window exit animations finish after Compose becomes idle.
        android.os.SystemClock.sleep(400)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val screenshot = requireNotNull(automation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "settings-redesign").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
