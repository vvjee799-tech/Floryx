package com.flowchat.app.device

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.device.*
import com.flowchat.app.data.terminal.LinuxRuntime
import com.flowchat.app.data.terminal.TerminalManager
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.presentation.chat.CommandCleanupStatus
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Opt-in client-side fault injection around the real Root Binder and product terminal. */
class CommandStartDiagnosticTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCOMMAND_START_R7D $value\n") })
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(30) }
    private fun groups() = shell("ps -A -o PID,PGID,STAT").lineSequence().drop(1)
        .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }.toSet()

    private class Faults {
        val holdOpen = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val reply = CountDownLatch(1)
        val starts = AtomicInteger()
        val cancelledRequests = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var hideEvidence = false
        @Volatile var actual: CommandSnapshot? = null
        @Volatile var holdReply = false
        @Volatile var loseReply = false
        @Volatile var connects = 0
    }
    private suspend fun fixture(block: suspend (RootDeviceConnection, TerminalManager, Faults, CoroutineScope) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("runCommandStartDiagnostics") == "true")
        val runtime = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        check(runtime.runtime().activeConversations.value.isEmpty() && runtime.deviceTasks().currentTask == null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val faults = Faults()
        val connector = LibsuRootServiceConnector(context)
        val root = RootDeviceConnection(RootServiceConnector { disconnected ->
            faults.connects++
            val original = connector.connect(disconnected)
            object : RootServiceSession by original {
                override fun openRequest(id: String): com.flowchat.app.shizuku.IFlowChatShizukuService {
                    if (faults.holdOpen.compareAndSet(true, false)) {
                        faults.entered.countDown()
                        check(faults.reply.await(20, TimeUnit.SECONDS))
                    }
                    return original.openRequest(id)
                }
                override fun finishRequest(id: String, cancelled: Boolean) {
                    if (cancelled) faults.cancelledRequests.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    original.finishRequest(id, cancelled)
                }
                override fun requestCleanup(id: String): String {
                    check(!faults.hideEvidence) { "Injected original-evidence unavailability" }
                    return original.requestCleanup(id)
                }
            }
        }, scope)
        val gateway = object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
            override suspend fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int) =
                root.withShellCommand { service ->
                    faults.starts.incrementAndGet()
                    val result = service.startShellCommand(command, workingDirectory, timeoutSeconds)
                    if (command.contains("R7D_A")) {
                        faults.actual = json.decodeFromString<CommandSnapshot>(result)
                        if (faults.holdReply) {
                            faults.entered.countDown()
                            check(faults.reply.await(20, TimeUnit.SECONDS))
                        }
                        if (faults.loseReply) {
                            runBlocking { until {
                                faults.actual = json.decodeFromString<CommandSnapshot>(
                                    service.readShellCommand(requireNotNull(faults.actual).id))
                                faults.actual!!.output.contains("R7D_A") && faults.actual!!.cleanup?.processStarted == true
                            } }
                            error("Injected lost start reply after real command creation")
                        }
                    }
                    result
                }
            override suspend fun readShellCommand(commandId: String) = root.withShellCommand(commandId) { it.readShellCommand(commandId) }
            override suspend fun cancelShellCommand(commandId: String) = root.withShellCommand(commandId) { it.cancelShellCommand(commandId) }
        }
        val terminal = TerminalManager(context, LinuxRuntime(context), gateway)
        try {
            shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
            root.refresh(requestPermission = true)
            assertTrue(root.state.value.isConnected)
            block(root, terminal, faults, scope)
        } finally { withContext(NonCancellable) {
            faults.reply.countDown()
            terminal.cancelAll()
            // The exact Root service owns only this fixture's commands and is closed normally.
            root.release(); scope.cancel()
        } }
    }
    private suspend fun pending(terminal: TerminalManager): CommandSnapshot {
        until { terminal.state.value.commands.any { it.id.startsWith("start-") } }
        return terminal.state.value.commands.single { it.id.startsWith("start-") }
    }
    private suspend fun resolved(terminal: TerminalManager, intent: String): CommandSnapshot {
        until { terminal.state.value.commands.any { it.startIntentId == intent && it.id.startsWith("root-") && it.cleanup?.state == CommandCleanupState.Released } }
        val snapshot = terminal.state.value.commands.single { it.startIntentId == intent }
        assertEquals(snapshot.id, snapshot.cleanup!!.commandId)
        assertTrue(snapshot.cleanup.processExited)
        assertFalse(requireNotNull(snapshot.cleanup.processGroup) in groups())
        report("intent=$intent request=${snapshot.startRequestId} command=${snapshot.id} status=${snapshot.status} cleanup=${snapshot.cleanup} independentSystemGroupAbsent=true")
        return snapshot
    }

    @Test fun cancelledBeforeOpenCannotCreateALateCommandAndHasExactNotStartedProof() = runBlocking<Unit> {
        fixture { root, terminal, faults, scope ->
            faults.holdOpen.set(true)
            val run = UUID.randomUUID().toString()
            val task = scope.async { terminal.start("r7d-owner-a", CommandEnvironment.Android, "printf 'R7D_A\\n'; sleep 30", "/data/local/tmp", 60, run) }
            assertTrue(faults.entered.await(10, TimeUnit.SECONDS))
            val intent = pending(terminal)
            terminal.cancelRun(run)
            task.cancelAndJoin()
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            assertTrue(terminal.read("r7d-owner-a", intent.id).cleanup?.blocksReuse == true)
            faults.reply.countDown()
            until { terminal.read("r7d-owner-a", intent.id).cleanup?.state == CommandCleanupState.Released }
            val verified = terminal.read("r7d-owner-a", intent.id)
            assertFalse(verified.cleanup!!.processStarted)
            assertFalse(verified.cleanup.processExited)
            repeat(3) { terminal.recheckCleanup("r7d-owner-a", intent.id) }
            root.requireRequestCleanupVerified()
            assertEquals(0, faults.starts.get())
            assertEquals(1, faults.cancelledRequests[verified.startRequestId]?.get())
            assertEquals(0, root.serviceInfo()!!.activeCommands)
            report("before-open intent=${intent.id} request=${verified.startRequestId} neverStarted=true starts=0 cancelRequests=1")
        }
    }

    private suspend fun delayedCancellation(stop: String) = fixture { root, terminal, faults, scope ->
        val ownerA = "r7d-a-${UUID.randomUUID()}"
        val ownerB = "r7d-b-${UUID.randomUUID()}"
        val manual = terminal.start(ownerB, CommandEnvironment.Android, "printf 'R7D_B\\n'; sleep 30 & wait", "/data/local/tmp", 60)
        until { terminal.read(ownerB, manual.id).output.contains("R7D_B") }
        val run = UUID.randomUUID().toString()
        faults.holdReply = true
        val task = scope.async { terminal.start(ownerA, CommandEnvironment.Android, "printf 'R7D_A\\n'; sleep 30 & wait", "/data/local/tmp", 60, run) }
        assertTrue(faults.entered.await(10, TimeUnit.SECONDS))
        val intent = pending(terminal)
        until {
            val raw = root.withService(cleanupOnly = true) { it.readShellCommand(requireNotNull(faults.actual).id) }
            faults.actual = json.decodeFromString<CommandSnapshot>(raw)
            faults.actual!!.output.contains("R7D_A") && (faults.actual!!.cleanup?.processGroup ?: 0) > 1
        }
        assertTrue(requireNotNull(faults.actual!!.cleanup?.processGroup) in groups())
        assertTrue(runCatching { terminal.cancel(ownerB, intent.id) }.isFailure)
        assertTrue(runCatching { terminal.recheckCleanup(ownerB, intent.id) }.isFailure)
        when (stop) {
            "run" -> terminal.cancelRun(run)
            "agents" -> terminal.cancelAgentCommands()
            "all" -> terminal.cancelAll()
        }
        task.cancelAndJoin()
        assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
        faults.reply.countDown()
        val verified = resolved(terminal, intent.id)
        assertTrue(terminal.owns(ownerA, verified.id)); assertFalse(terminal.owns(ownerB, verified.id))
        assertEquals(faults.actual!!.id, verified.id)
        assertEquals(1, faults.cancelledRequests[verified.startRequestId]?.get())
        if (stop == "all") until { !terminal.read(ownerB, manual.id).isRunning }
        else {
            assertTrue(terminal.read(ownerB, manual.id).isRunning)
            terminal.cancel(ownerB, manual.id)
        }
        until { terminal.recheckCleanup(ownerB, manual.id).cleanup?.state == CommandCleanupState.Released }
        assertFalse(requireNotNull(terminal.read(ownerB, manual.id).cleanup?.processGroup) in groups())
        assertEquals(2, faults.starts.get()); assertEquals(1, faults.connects)
        until { runCatching { root.requireRequestCleanupVerified() }.isSuccess }
        report("stop=$stop owner/run/final-ID preserved; manual command isolation matched existing policy; no replay")
    }
    @Test fun runCancellationKeepsLateCommandOwnershipAndLeavesManualCommandRunning() = runBlocking<Unit> { delayedCancellation("run") }
    @Test fun agentStopCoversPendingStartAndLeavesManualCommandRunning() = runBlocking<Unit> { delayedCancellation("agents") }
    @Test fun stopAllCoversBothPendingStartAndExistingManualCommand() = runBlocking<Unit> { delayedCancellation("all") }

    @Test fun lostReplyStaysUnknownUntilOriginalEvidenceIsReadableAndRecheckNeverReplays() = runBlocking<Unit> {
        fixture { root, terminal, faults, _ ->
            faults.loseReply = true
            faults.hideEvidence = true
            val owner = "r7d-lost-${UUID.randomUUID()}"
            assertTrue(runCatching { terminal.start(owner, CommandEnvironment.Android,
                "printf 'R7D_A\\n'; sleep 30 & wait", "/data/local/tmp", 60, UUID.randomUUID().toString()) }.isFailure)
            val intent = pending(terminal)
            assertEquals(CommandCleanupState.Unverified, intent.cleanup?.state)
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            repeat(3) { terminal.recheckCleanup(owner, intent.id) }
            assertEquals(1, faults.starts.get()); assertEquals(1, faults.connects)
            assertTrue(terminal.read(owner, intent.id).cleanup?.blocksReuse == true)
            faults.hideEvidence = false
            terminal.recheckCleanup(owner, intent.id)
            val verified = resolved(terminal, intent.id)
            repeat(3) { terminal.recheckCleanup(owner, verified.id) }
            assertEquals(faults.actual!!.id, verified.id)
            assertEquals(1, faults.cancelledRequests[verified.startRequestId]?.get())
            assertEquals(1, faults.starts.get()); assertEquals(1, faults.connects)
            root.requireRequestCleanupVerified()
            report("lost reply recovered from original request only; one start, one cancellation; rechecks read-only")
        }
    }

    @Test fun realPendingStartCardRechecksOriginalRequestAndShowsVerifiedNotStarted() = runBlocking<Unit> {
        fixture { root, terminal, faults, scope ->
            faults.holdOpen.set(true)
            val owner = "r7d-card-${UUID.randomUUID()}"
            val run = UUID.randomUUID().toString()
            val task = scope.async { terminal.start(owner, CommandEnvironment.Android,
                "printf 'R7D_A\\n'; sleep 30", "/data/local/tmp", 60, run) }
            assertTrue(faults.entered.await(10, TimeUnit.SECONDS))
            val intent = pending(terminal)
            terminal.cancelRun(run)
            task.cancelAndJoin()
            var activity: MainActivity? = null
            val clicks = AtomicInteger()
            try {
                until { withContext(Dispatchers.Main) {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                    activity != null
                } }
                withContext(Dispatchers.Main) { requireNotNull(activity).setContent {
                    val state by terminal.state.collectAsState()
                    val record = state.commands.single { it.startIntentId == intent.id }
                    MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
                        CommandCleanupStatus(requireNotNull(record.cleanup)) {
                            clicks.incrementAndGet()
                            faults.reply.countDown()
                            scope.launch { terminal.recheckCleanup(owner, intent.id) }
                        }
                    } } }
                } }
                fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> = buildList {
                    fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                        add(node); for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                    }
                    automation.rootInActiveWindow?.let(::visit)
                }
                fun hasText(resource: Int) = nodes().any { it.isVisibleToUser && it.text?.toString() == requireNotNull(activity).getString(resource) }
                fun screenshot(name: String) { context.cacheDir.resolve(name).outputStream().use {
                    assertTrue(requireNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } }
                until { hasText(R.string.command_cleanup_start_pending) && hasText(R.string.command_cleanup_unverified) }
                assertFalse(hasText(R.string.command_cleanup_not_started))
                screenshot("r7d-command-start-pending.png")
                var button = nodes().first { it.text?.toString() == requireNotNull(activity).getString(R.string.command_cleanup_recheck) }
                while (!button.isClickable) button = requireNotNull(button.parent)
                assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                until { hasText(R.string.command_cleanup_not_started) }
                assertFalse(hasText(R.string.command_cleanup_unverified))
                assertFalse(hasText(R.string.command_cleanup_recheck))
                screenshot("r7d-command-start-not-started.png")
                assertEquals(1, clicks.get()); assertEquals(0, faults.starts.get())
                root.requireRequestCleanupVerified()
                report("actual pending and verified-not-started UI rendered; one read-only recheck; no command created")
            } finally { withContext(NonCancellable + Dispatchers.Main) { activity?.finish() } }
        }
    }
}
