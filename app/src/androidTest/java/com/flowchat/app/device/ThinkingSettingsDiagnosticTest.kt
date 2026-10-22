package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.provider.ThinkingSupport
import com.flowchat.app.presentation.chat.ThinkingModeControl
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in UI-only diagnostic: no credentials, conversations or network requests. */
class ThinkingSettingsDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun switchEffortAndModelCapabilitiesStayConsistent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runThinkingSettingsDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun dismissMenu() {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
            compose.waitForIdle()
        }
        // Launch through the shell: vivo blocks ActivityScenario's background activity start.
        val monitor = instrumentation.addMonitor(ComponentActivity::class.java.name, null, false)
        val launch = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W -n ${context.packageName}/${ComponentActivity::class.java.name}"
        )).use { it.readBytes().toString(Charsets.UTF_8) }
        check(!launch.contains("Error:") && !launch.contains("Exception")) { launch }
        val activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as ComponentActivity
        instrumentation.removeMonitor(monitor)
        val model = mutableStateOf("deepseek-v4-pro")
        val enabled = mutableStateOf(true)
        val effort = mutableStateOf(ThinkingEffort.Auto)
        instrumentation.runOnMainSync { activity.setContent {
            FlowChatTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 70.dp)) {
                        ThinkingModeControl(
                            support = ThinkingSupport.resolve(null, model.value),
                            enabled = enabled.value, effort = effort.value,
                            interactive = true,
                            onEnabledChange = { enabled.value = it }, onEffortChange = { effort.value = it }
                        )
                    }
                }
            }
        } }
        compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("thinking-options").performClick()
        compose.onNodeWithText(context.getString(R.string.thinking_effort_auto)).assertIsNotEnabled()
        dismissMenu()
        compose.onNode(isToggleable()).performClick().assertIsOn()
        compose.onNodeWithTag("thinking-options").performClick()
        compose.onNodeWithText(context.getString(R.string.thinking_effort_medium)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.thinking_effort_max)).performClick()
        compose.runOnIdle { check(effort.value == ThinkingEffort.Max); model.value = "gemini-2.5-pro"; enabled.value = false }
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("thinking-options").performClick()
        compose.onNodeWithText(context.getString(R.string.thinking_required_hint)).assertIsDisplayed()
        dismissMenu()
        compose.runOnIdle { model.value = "gpt-4.1" }
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("thinking-options").performClick()
        compose.onNodeWithText(context.getString(R.string.thinking_unsupported_hint)).assertIsDisplayed()
        dismissMenu()
        compose.runOnIdle { model.value = "deepseek-v4-pro"; enabled.value = true; effort.value = ThinkingEffort.High }
        compose.waitForIdle()
        val screenshot = requireNotNull(automation.takeScreenshot())
        File(context.getExternalFilesDir(null), "thinking-settings.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
