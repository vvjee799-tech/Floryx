package com.flowchat.app.device

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.presentation.chat.*
import com.flowchat.app.ui.theme.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Production timeline, status, details and composer on the physical display; no paid requests or personal writes. */
class TaskStatusAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nP0A $text\n") })
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        automation.waitForIdle(500, 5_000)
        val directory = File(context.getExternalFilesDir(null), "p0a-status").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private suspend fun awaitIme(activity: MainActivity, shown: Boolean) {
        withTimeout(8_000) {
            var stable = 0; var previousBottom = -1
            while (stable < 5) {
                val insets = withContext(Dispatchers.Main) { ViewCompat.getRootWindowInsets(activity.window.decorView) }
                val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: -1
                val visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                stable = if (visible == shown && bottom == previousBottom && (if (shown) bottom > 0 else bottom == 0)) stable + 1 else 0
                previousBottom = bottom
                delay(80)
            }
        }
    }
    private fun hideIme(activity: MainActivity) = instrumentation.runOnMainSync {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
    }
    private suspend fun open(): MainActivity {
        shell("am start -W -f 0x10008000 -n ${context.packageName}/com.flowchat.app.MainActivity")
        var activity: MainActivity? = null
        withTimeout(15_000) { while (activity == null) {
            withContext(Dispatchers.Main) {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            if (activity == null) delay(50)
        } }
        return requireNotNull(activity)
    }
    private val owner = Conversation(id = "p0a-ui-synthetic", providerId = "p0a-local", modelName = "local-script")
    private fun ended() = ChatUiState(currentConversation = owner, conversations = listOf(owner),
        taskRunId = "p0a-ui-run", taskRunState = "Completed", taskStopReason = "Completed",
        messages = listOf(Message(conversationId = owner.id, role = MessageRole.User, content = "查看这个测试文件的类型与适配信息"),
            Message(conversationId = owner.id, role = MessageRole.Assistant,
                content = "已读取文件头，类型为测试文本。\n" + "这是保留的分析结果，用于检查长回答与状态是否都能滚动阅读。\n".repeat(12) + "结果末尾：ORCHID-42")))

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun statusAndDetailsRemainUsableAcrossFontsKeyboardAndShortHeight() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runTaskStatusDiagnostics") == "true")
        var activity = open()
        val originalOrientation = activity.requestedOrientation
        val state = mutableStateOf(ended())
        val scale = mutableStateOf(1f)
        val input = mutableStateOf("")
        fun scrollStatus() {
            compose.onNodeWithTag("chat-message-list").performScrollToNode(hasTestTag("task-status"))
            compose.onNodeWithTag("task-status").assertIsDisplayed()
        }
        var stopCount = 0
        var skippedRunId: String? = null
        val actions = TaskStatusActions(stop = { stopCount++ }, continueRemaining = { skippedRunId = it })
        fun install(target: MainActivity) {
            target.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                    FlowChatTheme(AppAppearance.Dark) {
                        val current = state.value
                        val status = TaskPresentation.from(current)
                        Scaffold(topBar = { TopAppBar(title = { Text("Floryx") }) }, bottomBar = {
                            MessageComposer(input.value, null, false, true, current.isStreaming, emptyList(), owner,
                                { input.value = it }, {}, actions.stop, {}, {}, {}, {}, { _, _ -> })
                        }) { padding ->
                            MessageList(current.messages, null, null, false,
                                Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
                                statusKey = status.takeIf { it.showChatStatus }, statusContent = if (status.showChatStatus) ({
                                    TaskStatusPanel(current, actions)
                                }) else null)
                        }
                    }
                }
            }
        }
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(value: Activity) {
                if (value is MainActivity && value !== activity) { activity = value; install(value) }
            }
            override fun onActivityCreated(value: Activity, saved: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, saved: Bundle) = Unit
            override fun onActivityDestroyed(value: Activity) = Unit
        }
        withContext(Dispatchers.Main) { application.registerActivityLifecycleCallbacks(callbacks); install(activity) }
        try {
            compose.waitForIdle()
            // U01/U02: normal released power is retained in details, never a fixed card above the answer.
            compose.runOnIdle { state.value = ended().copy(taskPower = TaskPowerState("s", TaskServiceState.Destroyed,
                recentOwners = setOf(TaskPowerOwner(owner.id, "r", TaskPowerWork.Finalizing)), leaseHeld = false)) }
            compose.onNodeWithTag("task-status").assertDoesNotExist()
            screenshot("normal-finished")
            compose.runOnIdle { state.value = state.value.copy(isStreaming = true) }
            compose.onNodeWithTag("task-status").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.stop)).performClick()
            assertEquals(1, stopCount)
            compose.runOnIdle { state.value = state.value.copy(isStreaming = false) }
            compose.onNodeWithTag("task-status").assertDoesNotExist()

            for (font in listOf(1f, 1.3f, 2f)) {
                compose.runOnIdle {
                    scale.value = font
                    state.value = ended().copy(taskStopReason = "command_timeout", taskRunState = "Stopped",
                        taskRecovery = TaskRecoveryUi("r", 1, false, listOf("run_command")),
                        taskPower = TaskPowerState("s", TaskServiceState.Destroyed,
                            recentOwners = setOf(TaskPowerOwner(owner.id, "r", TaskPowerWork.Finalizing)), leaseHeld = false))
                }
                scrollStatus()
                compose.onNodeWithText(context.getString(R.string.task_status_command_timeout)).assertIsDisplayed()
                compose.onNodeWithTag("task_power_status").assertDoesNotExist()
                screenshot("uncertain-font-$font")
                compose.onNodeWithTag("task-status-resume").performScrollTo().assertIsDisplayed()
                if (font == 1f) {
                    compose.runOnIdle { state.value = state.value.copy(taskRecovery = state.value.taskRecovery!!.copy(canAcknowledge = true)) }
                    compose.onNodeWithText(context.getString(R.string.task_status_skip_uncertain)).performScrollTo().performClick()
                    assertEquals("r", skippedRunId)
                }
                compose.onNode(hasSetTextAction()).performClick()
                awaitIme(activity, true)
                scrollStatus()
                val composer = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot
                val statusCard = compose.onNodeWithTag("task-status").fetchSemanticsNode().boundsInRoot
                assertTrue("Status must scroll above the composer at $font", statusCard.bottom <= composer.top + 1f)
                screenshot("keyboard-font-$font")
                hideIme(activity)
                awaitIme(activity, false)
                report("font=$font keyboard=closed/open answer/status/actions reachable")
            }
            // A short actual display, rather than a wider fake screenshot.
            withContext(Dispatchers.Main) { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(8_000) { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            scrollStatus()
            screenshot("landscape-font-2")
            // U03/U05: the projection updates on the same surface, and unrelated old resources do not leak.
            compose.runOnIdle { state.value = ended().copy(taskStopReason = "model_timeout") }
            compose.onNodeWithText(context.getString(R.string.task_status_model_timeout)).performScrollTo().assertIsDisplayed()
            screenshot("model-timeout")
            compose.runOnIdle { state.value = ended().copy(taskStopReason = "user_stopped") }
            compose.onNodeWithText(context.getString(R.string.task_status_user_stopped)).performScrollTo().assertIsDisplayed()
            screenshot("user-stopped")
            compose.runOnIdle { state.value = ended().copy(currentConversation = owner.copy(id = "new-clean"), messages = emptyList(),
                taskRunId = null, taskStopReason = null, otherRootRequestCleanupPending = true,
                taskPower = TaskPowerState("s", TaskServiceState.Destroyed, leaseHeld = null,
                    recentOwners = setOf(TaskPowerOwner(owner.id, "r", TaskPowerWork.Finalizing)))) }
            compose.onNodeWithTag("task-status").assertDoesNotExist()
            screenshot("other-conversation-clean")
            report("normal completion, uncertain command, timeout, stop and conversation switch verified")
        } catch (failure: Throwable) {
            screenshot("failure")
            compose.onRoot().printToLog("P0A_FAILURE")
            throw failure
        } finally {
            hideIme(activity)
            withContext(Dispatchers.Main) {
                application.unregisterActivityLifecycleCallbacks(callbacks)
                activity.requestedOrientation = originalOrientation; activity.finish()
            }
        }
    }
}
