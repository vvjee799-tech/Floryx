package com.flowchat.app.device

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.device.*
import com.flowchat.app.diagnostics.*
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.presentation.chat.CommandCleanupStatus
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Opt-in local scripts only. No conversations, providers, accounts or model calls. */
class RetainedCommandCleanupDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun enabled() = check(InstrumentationRegistry.getArguments().getString("runRetainedCommandCleanupDiagnostics") == "true")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun report(value: Any) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCOMMAND_CLEANUP_R7C $value\n") })
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(50) }
    private suspend fun start(root: RootDeviceConnection, script: String, timeout: Int = 60) =
        root.withShellCommand { it.startShellCommand(script, "/data/local/tmp", timeout) }
    private suspend fun read(root: RootDeviceConnection, id: String) = root.withShellCommand(id) { it.readShellCommand(id) }
    private suspend fun cancel(root: RootDeviceConnection, id: String) = root.withShellCommand(id) { it.cancelShellCommand(id) }

    private fun groups(): Set<Int> = shell("ps -A -o PID,PGID,STAT").lineSequence().drop(1)
        .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }.toSet()
    private suspend fun released(root: RootDeviceConnection, id: String): CommandSnapshot {
        var snapshot = read(root, id)
        until { snapshot = read(root, id); !snapshot.isRunning && snapshot.cleanup?.state == CommandCleanupState.Released }
        val evidence = requireNotNull(snapshot.cleanup)
        assertEquals(id, evidence.commandId)
        assertTrue(evidence.processExited)
        assertNotNull(evidence.checkedAtMillis)
        assertNull(evidence.reason)
        val group = requireNotNull(evidence.processGroup)
        assertTrue(group > 1)
        assertFalse("Owned group remains in the independent system process listing", group in groups())
        report("snapshot=$snapshot independentSystemGroupAbsent=true")
        return snapshot
    }

    private suspend fun withRoot(block: suspend (RootDeviceConnection) -> Unit) {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope)
        try { root.refresh(requestPermission = true); assertTrue(root.state.value.isConnected); block(root) }
        finally { withContext(NonCancellable) { root.release(); scope.cancel() } }
    }

    @Test fun naturalExitHasItsOwnGroupEvidenceAndObservationNeverReplaysTheCommand() = runBlocking<Unit> {
        withRoot { root ->
            val token = "NATURAL_${UUID.randomUUID()}"
            val started = start(root, "printf '$token\\n'; sleep 1; printf 'DONE\\n'")
            val completed = released(root, started.id)
            assertEquals(CommandStatus.Exited, completed.status)
            assertEquals(0, completed.exitCode)
            assertEquals(listOf(token, "DONE"), completed.output.trim().lines())
            val pid = requireNotNull(root.serviceInfo()).pid
            repeat(3) { assertEquals(completed, read(root, started.id)) }
            assertEquals(pid, requireNotNull(root.serviceInfo()).pid)
            assertEquals(0, requireNotNull(root.serviceInfo()).activeCommands)
        }
    }

    @Test fun cancellingOneCommandVerifiesItsGroupWithoutStoppingAnotherCommand() = runBlocking<Unit> {
        withRoot { root ->
            val a = start(root, "printf 'READY_A\\n'; sleep 30 & wait")
            val b = start(root, "printf 'READY_B\\n'; sleep 30 & wait")
            until { read(root, a.id).output.contains("READY_A") && read(root, b.id).output.contains("READY_B") }
            val groupB = requireNotNull(read(root, b.id).cleanup?.processGroup)
            assertTrue(groupB in groups())
            cancel(root, a.id)
            assertEquals(CommandStatus.Cancelled, released(root, a.id).status)
            assertTrue(read(root, b.id).isRunning)
            assertTrue(groupB in groups())
            cancel(root, b.id)
            assertEquals(CommandStatus.Cancelled, released(root, b.id).status)
            assertEquals(0, requireNotNull(root.serviceInfo()).activeCommands)
        }
    }

    @Test fun commandTimeoutIsSeparateFromVerifiedProcessGroupRelease() = runBlocking<Unit> {
        withRoot { root ->
            val started = start(root, "printf 'TIMEOUT_READY\\n'; sleep 30 & wait", 1)
            val completed = released(root, started.id)
            assertEquals(CommandStatus.TimedOut, completed.status)
            assertTrue(completed.output.contains("TIMEOUT_READY"))
        }
    }

    @Test fun productionTerminalKeepsConversationAndRunOwnershipDuringCancellationAndRecheck() = runBlocking<Unit> {
        enabled()
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val runtime = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        check(runtime.runtime().activeConversations.value.isEmpty() && runtime.deviceTasks().currentTask == null)
        val terminal = entry.terminal()
        check(terminal.state.value.commands.none { it.isRunning || it.cleanup?.blocksReuse == true }) { "Do not interrupt user commands" }
        val settings = entry.settings()
        val saved = settings.state.value
        val root = runtime.root()
        val ownerA = "r7c-a-${UUID.randomUUID()}"
        val ownerB = "r7c-b-${UUID.randomUUID()}"
        val runA = UUID.randomUUID().toString()
        val ownIds = mutableListOf<Pair<String, String>>()
        try {
            settings.setPowerModeEnabled(true)
            settings.setPowerConnectionMode(PowerConnectionMode.Root)
            settings.setBackgroundExecutionEnabled(false)
            until { !entry.device().backgroundExecutionRequested }
            shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
            root.refresh(requestPermission = true)
            val a = terminal.start(ownerA, CommandEnvironment.Android, "printf 'RUN_A\\n'; sleep 30 & wait", "/data/local/tmp", 60, runA)
                .also { ownIds += ownerA to it.id }
            val b = terminal.start(ownerB, CommandEnvironment.Android, "printf 'MANUAL_B\\n'; sleep 30 & wait", "/data/local/tmp", 60)
                .also { ownIds += ownerB to it.id }
            until { terminal.read(ownerA, a.id).output.contains("RUN_A") && terminal.read(ownerB, b.id).output.contains("MANUAL_B") }
            assertTrue(runCatching { terminal.recheckCleanup(ownerB, a.id) }.isFailure)
            assertTrue(runCatching { terminal.cancel(ownerB, a.id) }.isFailure)
            terminal.cancelRun(runA)
            until { terminal.read(ownerA, a.id).status == CommandStatus.Cancelled }
            until { terminal.recheckCleanup(ownerA, a.id).cleanup?.state == CommandCleanupState.Released }
            assertTrue(terminal.read(ownerB, b.id).isRunning)
            terminal.cancel(ownerB, b.id)
            until { terminal.recheckCleanup(ownerB, b.id).cleanup?.state == CommandCleanupState.Released }
            for ((owner, id) in ownIds) {
                val snapshot = terminal.read(owner, id)
                assertEquals(id, snapshot.cleanup?.commandId)
                assertFalse(requireNotNull(snapshot.cleanup?.processGroup) in groups())
                report("production owner=$owner runA=$runA command=$snapshot independentSystemGroupAbsent=true")
            }
        } finally {
            withContext(NonCancellable) {
                for ((owner, id) in ownIds) runCatching { terminal.cancel(owner, id) }
                root.release()
                settings.setBackgroundExecutionEnabled(saved.backgroundExecutionEnabled)
                settings.setPowerConnectionMode(saved.powerConnectionMode)
                settings.setPowerModeEnabled(saved.powerModeEnabled)
                check(context.getSharedPreferences("app_controls", android.content.Context.MODE_PRIVATE).edit().commit())
            }
        }
    }

    @Test fun commandCleanupCardRendersPendingAndVerifiedWithAnObservationOnlyButton() = runBlocking<Unit> {
        enabled()
        var activity: MainActivity? = null
        val pending = CommandCleanup("synthetic-command", CommandCleanupState.Unverified, 123, true, CommandCleanupReason.GroupPresent, processStarted = true)
        val state = mutableStateOf(pending)
        val rechecks = AtomicInteger()
        try {
            shell("am start -W -f 0x10008000 -n ${context.packageName}/com.flowchat.app.MainActivity")
            until { withContext(Dispatchers.Main) {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                activity != null
            } }
            withContext(Dispatchers.Main) { requireNotNull(activity).setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) { CommandCleanupStatus(state.value) { rechecks.incrementAndGet() } }
                } }
            } }
            fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> = buildList {
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                    add(node); for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                }
                automation.rootInActiveWindow?.let(::visit)
            }
            fun hasText(resource: Int) = nodes().any { it.isVisibleToUser && it.text?.toString() == requireNotNull(activity).getString(resource) }
            until { hasText(R.string.command_cleanup_unverified) }
            assertFalse(hasText(R.string.command_cleanup_released))
            var button = nodes().first { it.text?.toString() == requireNotNull(activity).getString(R.string.command_cleanup_recheck) }
            while (!button.isClickable) button = requireNotNull(button.parent)
            assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            until { rechecks.get() == 1 }
            fun screenshot(name: String) { context.cacheDir.resolve(name).outputStream().use {
                assertTrue(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } }
            screenshot("r7c-command-cleanup-pending.png")
            withContext(Dispatchers.Main) { state.value = pending.copy(state = CommandCleanupState.Released, reason = null, checkedAtMillis = 42) }
            until { hasText(R.string.command_cleanup_released) }
            assertFalse(hasText(R.string.command_cleanup_unverified))
            assertFalse(hasText(R.string.command_cleanup_recheck))
            screenshot("r7c-command-cleanup-released.png")
            withContext(Dispatchers.Main) { state.value = CommandCleanup("synthetic-command", CommandCleanupState.Released, checkedAtMillis = 43) }
            until { hasText(R.string.command_cleanup_not_started) }
            assertFalse(hasText(R.string.command_cleanup_released))
            assertFalse(hasText(R.string.command_cleanup_recheck))
            screenshot("r7c-command-cleanup-not-started.png")
            report("synthetic pending, released and never-started command UI rendered; one observation callback; no model or command requests")
        } finally { withContext(NonCancellable + Dispatchers.Main) { activity?.finish() } }
    }
}
