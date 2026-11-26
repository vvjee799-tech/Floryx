package com.flowchat.app.device

import android.app.UiAutomation
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.*
import dagger.hilt.android.EntryPointAccessors
import com.flowchat.app.presentation.chat.ExecutionDisplayCleanupStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real task finish, Root Binder and system display evidence; no model or account requests. */
class ExecutionDisplayCleanupDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun report(value: Any) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nDISPLAY_CLEANUP_R7 $value\n") })
    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(50) }

    private suspend fun verifyReleased(owner: String, id: Int, root: RootDeviceConnection) {
        val displays = context.getSystemService(DisplayManager::class.java)
        until { root.executionDisplayCleanup.value[owner]?.state == ExecutionDisplayState.Released }
        val evidence = requireNotNull(root.executionDisplayCleanup.value[owner])
        assertEquals(id, evidence.displayId)
        assertNotNull(evidence.checkedAtMillis)
        assertNull(evidence.reason)
        assertNull("The product's release report must agree with the real system", displays.getDisplay(id))
        assertNotNull("Never remove the physical display", displays.getDisplay(0))
        val info = root.serviceInfo()
        assertTrue(info == null || (info.activeRequests == 0 && info.activeDisplays == 0 && info.activeCommands == 0))
        report("owner=$owner evidence=$evidence systemAbsent=true primaryPresent=true service=$info")
    }

    @Test fun normalTaskFinishReportsActualDisplayRemovalAndRepeatedFinishCannotChangeIt() = runBlocking<Unit> {
        withBackground { tasks, root, _, task, id ->
            tasks.finish(task)
            verifyReleased(task.id, id, root)
            val released = root.executionDisplayCleanup.value[task.id]
            tasks.finish(task)
            root.recheckExecutionDisplayCleanup()
            assertEquals(released, root.executionDisplayCleanup.value[task.id])
        }
    }

    @Test fun cancelledOwnerRunsProductionFinishCallbackAndVerifiesRemoval() = runBlocking<Unit> {
        withBackground { tasks, root, _, task, id ->
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { tasks.finish(task) }
            }
            owner.cancelAndJoin()
            assertTrue(task.closed)
            verifyReleased(task.id, id, root)
        }
    }

    @Test fun rootDisconnectDoesNotUseNullConnectionAsProofOrReviveTheInterruptedTask() = runBlocking<Unit> {
        withBackground { tasks, root, gateway, task, id ->
            root.release()
            assertNull(root.serviceInfo())
            verifyReleased(task.id, id, root)
            val old = root.executionDisplayCleanup.value[task.id]
            val observation = gateway.observeScreen()
            assertFalse(observation.asToolContent(), observation.success)
            assertNull(observation.screen)
            assertNull(root.serviceInfo())
            tasks.finish(task)
            root.recheckExecutionDisplayCleanup()
            assertEquals(old, root.executionDisplayCleanup.value[task.id])
        }
    }

    @Test fun cleanupCardShowsUnverifiedAndReleasedAsDifferentResultsWithoutSendingRequests() = runBlocking<Unit> {
        check(InstrumentationRegistry.getArguments().getString("runExecutionDisplayCleanupDiagnostics") == "true")
        val application = context.applicationContext as Application
        var activity: MainActivity? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(value: Activity) { if (value is MainActivity) activity = value }
            override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(value: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        val state = mutableStateOf(ExecutionDisplayCleanup("synthetic-display-owner", ExecutionDisplayState.Unverified,
            7, ExecutionDisplayCleanupReason.DisplayPresent))
        val rechecks = java.util.concurrent.atomic.AtomicInteger()
        try {
            shell("am start -W -f 0x10008000 -n ${context.packageName}/com.flowchat.app.MainActivity")
            report("ui waiting for resumed MainActivity")
            until { withContext(Dispatchers.Main) {
                activity = activity ?: ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                activity != null
            } }
            report("ui MainActivity obtained")
            report("ui resource locales activity=${requireNotNull(activity).resources.configuration.locales.toLanguageTags()} target=${context.resources.configuration.locales.toLanguageTags()}")
            withContext(Dispatchers.Main) {
                requireNotNull(activity).setContent {
                    MaterialTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(16.dp)) { ExecutionDisplayCleanupStatus(state.value, false, true) { rechecks.incrementAndGet() } }
                        }
                    }
                }
            }
            report("ui content installed; waiting for unverified text")
            fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> = buildList {
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                    add(node)
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                }
                automation.rootInActiveWindow?.let(::visit)
            }
            // Compose exposes virtual semantic children; provider text-search is not a tree walk.
            fun hasText(resource: Int) = nodes().any { it.isVisibleToUser &&
                it.text?.toString() == requireNotNull(activity).getString(resource) }
            until { hasText(R.string.execution_display_unverified) }
            report("ui unverified text observed")
            assertFalse(hasText(R.string.execution_display_released))
            val retry = nodes().first { it.isVisibleToUser &&
                it.text?.toString() == requireNotNull(activity).getString(R.string.execution_display_recheck) }
            var node: android.view.accessibility.AccessibilityNodeInfo? = retry
            while (node != null && !node.isClickable) node = node.parent
            assertTrue(requireNotNull(node).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            until { rechecks.get() == 1 }
            report("ui observation-only recheck clicked")
            context.cacheDir.resolve("r7-display-cleanup-unverified.png").outputStream().use {
                assertTrue(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            withContext(Dispatchers.Main) { state.value = state.value.copy(state = ExecutionDisplayState.Released, reason = null, checkedAtMillis = 123L) }
            until { hasText(R.string.execution_display_released) }
            report("ui released text observed")
            assertFalse(hasText(R.string.execution_display_unverified))
            assertFalse(hasText(R.string.execution_display_recheck))
            context.cacheDir.resolve("r7-display-cleanup-released.png").outputStream().use {
                assertTrue(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            report("Synthetic UI states rendered separately; recheck is observation only; screenshots contain no user data")
        } catch (error: Throwable) {
            report("ui failure: activityKnown=${activity != null} activePackage=${automation.rootInActiveWindow?.packageName}")
            throw error
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            withContext(NonCancellable + Dispatchers.Main) { activity?.finish() }
        }
    }

    private suspend fun withBackground(block: suspend (DeviceTaskControl, RootDeviceConnection, DeviceAssistantGateway,
        DeviceTaskControl.Task, Int) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("runExecutionDisplayCleanupDiagnostics") == "true")
        shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
        if (FlowChatAccessibilityService.current() == null) {
            val component = ComponentName(context, FlowChatAccessibilityService::class.java)
            val before = shell("settings --user 0 get secure enabled_accessibility_services")
            check(before.split(':').any { ComponentName.unflattenFromString(it) == component }) { "Existing accessibility authorization required" }
            val remaining = before.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                check(shell("settings --user 0 get secure enabled_accessibility_services") == before)
                try {
                    check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, remaining))
                    delay(250)
                } finally {
                    check(shell("settings --user 0 get secure enabled_accessibility_services").let { if (it == "null") "" else it } == remaining)
                    check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, before))
                }
            } finally { automation.dropShellPermissionIdentity() }
        }
        until { FlowChatAccessibilityService.current() != null }
        val entry = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        check(entry.runtime().activeConversations.value.isEmpty() && entry.deviceTasks().currentTask == null) { "Do not interrupt user work" }
        val settings = entry.settings()
        val saved = settings.state.value
        val tasks = entry.deviceTasks()
        val root = entry.root()
        val gateway = entry.deviceGateway()
        var task: DeviceTaskControl.Task? = null
        try {
            settings.setPowerModeEnabled(true)
            settings.setPowerConnectionMode(PowerConnectionMode.Root)
            settings.setBackgroundExecutionEnabled(true)
            until { gateway.backgroundExecutionRequested }
            root.refresh(requestPermission = true)
            task = tasks.begin().also(tasks::arm)
            val result = gateway.launchApp("${context.packageName}.test", "com.flowchat.app.device.ExecutionDisplayFixtureActivity")
            assertTrue(result.asToolContent(), result.success)
            val id = requireNotNull(result.screen?.geometry?.displayId)
            assertTrue(id > 0)
            val inUse = requireNotNull(gateway.executionDisplayCleanup.value[task.id])
            assertEquals(ExecutionDisplayState.InUse, inUse.state)
            assertEquals(id, inUse.displayId)
            assertNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(id))
            report("opened owner=${task.id} display=$id inUse=$inUse")
            block(tasks, root, gateway, task, id)
        } finally {
            withContext(NonCancellable) {
                tasks.finish(task)
                root.release()
                settings.setBackgroundExecutionEnabled(saved.backgroundExecutionEnabled)
                settings.setPowerConnectionMode(saved.powerConnectionMode)
                settings.setPowerModeEnabled(saved.powerModeEnabled)
                check(context.getSharedPreferences("app_controls", android.content.Context.MODE_PRIVATE).edit().commit())
            }
        }
    }
}
