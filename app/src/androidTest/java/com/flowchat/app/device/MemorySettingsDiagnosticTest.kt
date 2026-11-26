package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.presentation.chat.MemorySettingsScreen
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, isolated memory controls: no app conversations, credentials or network. */
class MemorySettingsDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun togglingEditingDeletingAndClearingSavedSummaries() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runMemorySettingsDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val monitor = instrumentation.addMonitor(ComponentActivity::class.java.name, null, false)
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W -n ${context.packageName}/${ComponentActivity::class.java.name}"
        )).use { it.readBytes() }
        val activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) as ComponentActivity
        instrumentation.removeMonitor(monitor)
        val enabled = mutableStateOf(true)
        val appearance = mutableStateOf(AppAppearance.Dark)
        val memories = mutableStateOf(listOf(
            MemoryRecord("language", "preference", "用户希望默认用中文交流。", "summary", 1, "preference.response_language", "preference", 2),
            MemoryRecord("project", "project", "用户正在开发 FlowChat 安卓应用。", "summary", 2, "project.flowchat", "project", 2)
        ))
        instrumentation.runOnMainSync { activity.setContent {
            FlowChatTheme(appearance.value) {
                Surface { MemorySettingsScreen(
                    enabled = enabled.value, memories = memories.value,
                    onEnabledChange = { enabled.value = it },
                    onUpdate = { id, summary -> memories.value = memories.value.map { if (it.id == id) it.copy(summary = summary) else it } },
                    onDelete = { id -> memories.value = memories.value.filterNot { it.id == id } },
                    onClear = { memories.value = emptyList() }, onDismiss = {}
                ) }
            }
        } }
        fun capture(name: String) {
            compose.waitForIdle()
            android.os.SystemClock.sleep(700) // Allow the Android starting-window fade to finish.
            val screenshot = requireNotNull(automation.takeScreenshot())
            File(context.getExternalFilesDir(null), name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        capture("memory-dark.png")
        compose.onNodeWithTag("memory-toggle").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("用户希望默认用中文交流。").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.edit_memory))[0].performClick()
        compose.onNodeWithTag("memory-edit").performTextReplacement("用户希望默认用英文交流。")
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.onNodeWithText("用户希望默认用英文交流。").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.delete_memory))[0].performClick()
        compose.onNodeWithText("用户希望默认用英文交流。").assertDoesNotExist()
        compose.onNodeWithText("用户正在开发 FlowChat 安卓应用。").assertIsDisplayed()
        compose.runOnIdle { appearance.value = AppAppearance.Light }
        capture("memory-light.png")
        compose.onNodeWithText(context.getString(R.string.clear_all)).performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithText("用户正在开发 FlowChat 安卓应用。").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.clear_all)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.clear_all)).onLast().performClick()
        compose.onNodeWithText(context.getString(R.string.no_memories)).assertIsDisplayed()
        compose.onNodeWithTag("memory-toggle").performClick().assertIsOn()
        instrumentation.runOnMainSync { activity.finish() }
    }
}
