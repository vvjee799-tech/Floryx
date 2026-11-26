package com.flowchat.app.device

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.chat.*
import com.flowchat.app.presentation.chat.TaskPowerStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic presentation states; actual locks/services are checked separately by the runtime diagnostics. */
class TaskPowerStatusDiagnosticTest {
    @Test fun taskPowerCardSeparatesWaitingHeldUnknownAndReleasedWithoutRevivingWork() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("runTaskPowerDiagnostics") == "true")
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
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
        val owner = TaskPowerOwner("power-ui-synthetic", "power-ui-run", TaskPowerWork.WaitingForUser)
        val state = mutableStateOf(TaskPowerState("power-ui-service", TaskServiceState.Running,
            owners = setOf(owner), recentOwners = setOf(owner), leaseHeld = false))
        var rechecks = 0
        withContext(Dispatchers.Main) { application.registerActivityLifecycleCallbacks(callbacks) }
        suspend fun until(check: suspend () -> Boolean) = withTimeout(15_000) { while (!check()) delay(50) }
        fun hasText(resource: Int): Boolean {
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
                if (node.isVisibleToUser && node.text?.toString() == requireNotNull(activity).getString(resource)) return true
                return (0 until node.childCount).any { index -> node.getChild(index)?.let(::visit) == true }
            }
            return automation.rootInActiveWindow?.let(::visit) == true
        }
        fun screenshot(name: String) {
            context.cacheDir.resolve(name).outputStream().use {
                check(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        try {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")).use { it.readBytes() }
            until { withContext(Dispatchers.Main) {
                activity = activity ?: ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                activity != null
            } }
            withContext(Dispatchers.Main) { requireNotNull(activity).setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                    TaskPowerStatus(state.value, owner.conversationId) { rechecks++ }
                } } }
            } }
            until { hasText(R.string.task_power_own_waiting) && hasText(R.string.task_power_released) }
            assertFalse(hasText(R.string.task_power_service_destroyed))
            screenshot("r7e-power-waiting.png")
            withContext(Dispatchers.Main) { state.value = state.value.copy(owners = setOf(owner.copy(work = TaskPowerWork.Model)), leaseHeld = true) }
            until { hasText(R.string.task_power_held) && hasText(R.string.task_power_own_working) }
            screenshot("r7e-power-held.png")
            withContext(Dispatchers.Main) { state.value = state.value.copy(owners = setOf(
                owner.copy(work = TaskPowerWork.WaitingForCleanup), owner.copy(runId = "new-model", work = TaskPowerWork.Model))) }
            until { hasText(R.string.task_power_own_working) }
            assertFalse(hasText(R.string.task_power_own_cleanup_wait))
            withContext(Dispatchers.Main) { state.value = state.value.copy(
                owners = setOf(owner.copy(work = TaskPowerWork.WaitingForCleanup)), leaseHeld = false) }
            until { hasText(R.string.task_power_own_cleanup_wait) && hasText(R.string.task_power_released) }
            assertFalse(hasText(R.string.task_power_service_destroyed))
            screenshot("r7e-power-cleanup-wait.png")
            withContext(Dispatchers.Main) { state.value = state.value.copy(owners = emptySet(),
                serviceState = TaskServiceState.Destroyed, leaseHeld = null, failure = TaskPowerFailure.ReadFailed) }
            until { hasText(R.string.task_power_unknown) && hasText(R.string.task_power_recheck) }
            assertFalse(hasText(R.string.task_power_released))
            screenshot("r7e-power-unknown.png")
            withContext(Dispatchers.Main) { state.value = state.value.copy(leaseHeld = false, failure = null) }
            until { hasText(R.string.task_power_released) && hasText(R.string.task_power_service_destroyed) }
            assertFalse(hasText(R.string.task_power_unknown)); assertFalse(hasText(R.string.task_power_recheck))
            assertEquals(0, rechecks)
            screenshot("r7e-power-released.png")
        } finally {
            withContext(Dispatchers.Main) {
                activity?.finish()
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
        }
    }
}
