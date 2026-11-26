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
import com.flowchat.app.data.device.*
import com.flowchat.app.diagnostics.RootCancellationDiagnosticsService
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.*
import com.flowchat.app.presentation.chat.RootRequestCleanupStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import dagger.hilt.android.EntryPointAccessors

/** Isolated Root requests, real group absence and UI rendering; no model or account calls. */
class RootRequestCleanupDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun enabled() = check(InstrumentationRegistry.getArguments().getString("runRootRequestCleanupDiagnostics") == "true")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun report(value: Any) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nREQUEST_CLEANUP_R7B $value\n") })
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(50) }

    private suspend fun released(root: RootDeviceConnection, owner: String, otherActiveRequests: Int = 0): List<RootRequestCleanup> {
        until {
            root.recheckRootRequestCleanup()
            root.rootRequestCleanup.value.values.filter { it.ownerId == owner }.let {
                it.isNotEmpty() && it.all { record -> record.state == RootRequestState.Released }
            }
        }
        val records = root.rootRequestCleanup.value.values.filter { it.ownerId == owner }
        val groups = records.flatMap { it.processGroups }.distinct()
        assertTrue("The request must have launched actual owned groups", groups.isNotEmpty())
        // Independent system process listing, rather than an empty connection or client map.
        val listing = shell("ps -A -o PID,PGID,STAT")
        val present = listing.lineSequence().drop(1).mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }.toSet()
        assertTrue("Owned groups remain in system listing: $groups", groups.none { it in present })
        assertTrue(records.all { it.checkedAtMillis != null && it.reason == null })
        assertEquals(otherActiveRequests, requireNotNull(root.serviceInfo()).activeRequests)
        report("owner=$owner records=$records independentSystemGroupsAbsent=true")
        return records
    }

    @Test fun normalTypedRequestVerifiesItsGroupsAndRepeatedObservationDoesNotReplayIt() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope)
        val owner = UUID.randomUUID().toString()
        try {
            root.refresh(requestPermission = true)
            val pid = requireNotNull(root.serviceInfo()).pid
            assertTrue(root.withService(ownerId = owner) { it.getDeviceStatus() }.contains("battery="))
            val records = released(root, owner)
            repeat(3) { root.recheckRootRequestCleanup() }
            assertEquals(records, root.rootRequestCleanup.value.values.filter { it.ownerId == owner })
            assertEquals(pid, requireNotNull(root.serviceInfo()).pid)
            assertEquals(1, root.rootRequestCleanup.value.size)
        } finally { withContext(NonCancellable) { root.release(); scope.cancel() } }
    }

    @Test fun productionGatewayAttachesCleanupToTheActualPhoneTaskWithoutAConversationOrModelRequest() = runBlocking<Unit> {
        enabled()
        val entry = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        val tasks = entry.deviceTasks()
        check(tasks.currentTask == null && entry.runtime().activeConversations.value.isEmpty()) { "Do not interrupt user work" }
        val settings = entry.settings()
        val saved = settings.state.value
        val root = entry.root()
        val gateway = entry.deviceGateway()
        var task: DeviceTaskControl.Task? = null
        try {
            settings.setPowerModeEnabled(true)
            settings.setPowerConnectionMode(PowerConnectionMode.Root)
            settings.setBackgroundExecutionEnabled(false)
            until { !gateway.backgroundExecutionRequested }
            root.refresh(requestPermission = true)
            task = tasks.begin().also(tasks::arm)
            val result = gateway.getDeviceStatus()
            assertTrue(result.asToolContent(), result.success)
            val records = released(root, task.id)
            assertEquals(records, gateway.rootRequestCleanup.value.values.filter { it.ownerId == task.id })
            tasks.finish(task)
            assertTrue(task.closed)
            root.recheckRootRequestCleanup()
            assertEquals(records, gateway.rootRequestCleanup.value.values.filter { it.ownerId == task.id })
            report("production gateway retained exact task owner after task finish; no conversation/model calls")
        } finally {
            withContext(NonCancellable) {
                tasks.finish(task); root.release()
                settings.setBackgroundExecutionEnabled(saved.backgroundExecutionEnabled)
                settings.setPowerConnectionMode(saved.powerConnectionMode)
                settings.setPowerModeEnabled(saved.powerModeEnabled)
                check(context.getSharedPreferences("app_controls", android.content.Context.MODE_PRIVATE).edit().commit())
            }
        }
    }

    @Test fun cancellingBlockedRequestVerifiesOnlyItsOwnedGroupsAndPreservesOtherOwnerEvidence() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context, RootCancellationDiagnosticsService::class.java), scope)
        val marker = File(context.cacheDir, "root-cancellation-diagnostic.pid")
        check(!marker.exists()) { "Do not overwrite an existing diagnostic marker" }
        val ownerA = UUID.randomUUID().toString()
        val ownerB = UUID.randomUUID().toString()
        var caller: Job? = null
        try {
            root.refresh(requestPermission = true)
            caller = launch { root.withService(ownerId = ownerA) { it.getDeviceStatus() } }
            until { marker.exists() && marker.readText().trim().toIntOrNull() != null }
            val group = marker.readText().trim().toInt()
            val activeA = root.rootRequestCleanup.value.values.single { it.ownerId == ownerA }
            assertEquals(RootRequestState.InUse, activeA.state)
            assertTrue(root.withService(ownerId = ownerB) { it.getForegroundApp() }.contains("foreground_package="))
            val completedB = released(root, ownerB, otherActiveRequests = 1)
            assertEquals(1, requireNotNull(root.serviceInfo()).activeRequests)
            withTimeout(4_000) { caller.cancelAndJoin() }
            val completedA = released(root, ownerA)
            assertTrue(completedA.single().processGroups.contains(group))
            assertEquals(completedB, root.rootRequestCleanup.value.values.filter { it.ownerId == ownerB })
            assertTrue(caller.isCancelled)
        } finally {
            withContext(NonCancellable) { caller?.cancelAndJoin(); root.release(); scope.cancel(); marker.delete() }
        }
    }

    @Test fun cleanupCardSeparatesPendingFromVerifiedAndRecheckIsObservationOnly() = runBlocking<Unit> {
        enabled()
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
        val pending = RootRequestCleanup("synthetic-request", "synthetic-owner", RootRequestState.Unverified,
            RootRequestCleanupReason.CommandsPresent, listOf(123))
        val state = mutableStateOf(pending)
        val rechecks = AtomicInteger()
        try {
            shell("am start -W -f 0x10008000 -n ${context.packageName}/com.flowchat.app.MainActivity")
            until { withContext(Dispatchers.Main) {
                activity = activity ?: ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                activity != null
            } }
            withContext(Dispatchers.Main) {
                requireNotNull(activity).setContent {
                    MaterialTheme { Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) { RootRequestCleanupStatus(listOf(state.value), false) { rechecks.incrementAndGet() } }
                    } }
                }
            }
            fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> = buildList {
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                    add(node); for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                }
                automation.rootInActiveWindow?.let(::visit)
            }
            fun hasText(resource: Int) = nodes().any { it.isVisibleToUser && it.text?.toString() == requireNotNull(activity).getString(resource) }
            until { hasText(R.string.root_request_cleanup_pending) }
            assertFalse(hasText(R.string.root_request_cleanup_released))
            var button = nodes().first { it.text?.toString() == requireNotNull(activity).getString(R.string.root_request_recheck) }
            while (!button.isClickable) button = requireNotNull(button.parent)
            assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            until { rechecks.get() == 1 }
            context.cacheDir.resolve("r7b-request-cleanup-pending.png").outputStream().use {
                assertTrue(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            withContext(Dispatchers.Main) { state.value = pending.copy(reason = RootRequestCleanupReason.OriginalConnectionUnavailable) }
            until { hasText(R.string.root_request_connection_unavailable) }
            assertTrue(hasText(R.string.root_request_cleanup_pending))
            assertFalse(hasText(R.string.root_request_cleanup_released))
            context.cacheDir.resolve("r7g-request-cleanup-disconnected.png").outputStream().use {
                assertTrue(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            withContext(Dispatchers.Main) { state.value = pending.copy(state = RootRequestState.Released, reason = null, checkedAtMillis = 42) }
            until { hasText(R.string.root_request_cleanup_released) }
            assertFalse(hasText(R.string.root_request_cleanup_pending))
            assertFalse(hasText(R.string.root_request_recheck))
            context.cacheDir.resolve("r7b-request-cleanup-released.png").outputStream().use {
                assertTrue(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            report("synthetic pending/disconnected/released UI rendered; one observation callback; no model requests; synthetic state changes are not recovery evidence")
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            withContext(NonCancellable + Dispatchers.Main) { activity?.finish() }
        }
    }
}
