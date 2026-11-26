package com.flowchat.app.device

import android.app.UiAutomation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.diagnostics.RootCancellationDiagnosticsService
import com.flowchat.app.diagnostics.RootDisplayCommandDiagnosticsService
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.terminal.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Isolated real Root connections and synthetic settings. Never touches a provider or account. */
class RootConnectionLossDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val json = Json { ignoreUnknownKeys = true }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nROOT_LOSS_R7G $value\n") })
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(50) }
    private fun groups() = shell("ps -A -o PID,PGID,STAT").lineSequence().drop(1)
        .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }.toSet()

    private inner class Fixture(stallStatus: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connects = AtomicInteger()
        val opens = AtomicInteger()
        val reads = AtomicInteger()
        val readable = AtomicBoolean(true)
        val displayReadable = AtomicBoolean(true)
        val settings = AppSettingsStore(AppSettings(powerConnectionMode = PowerConnectionMode.Root, installId = UUID.randomUUID().toString()))
        private val connector = LibsuRootServiceConnector(context,
            if (stallStatus) RootCancellationDiagnosticsService::class.java else RootDisplayCommandDiagnosticsService::class.java)
        val root = RootDeviceConnection({ death ->
            connects.incrementAndGet()
            val original = connector.connect(death)
            object : RootServiceSession by original {
                override fun openRequest(id: String) = original.openRequest(id).also { opens.incrementAndGet() }
                override fun requestCleanup(id: String): String {
                    reads.incrementAndGet()
                    check(readable.get()) { "Injected temporary evidence failure" }
                    return original.requestCleanup(id)
                }
            }
        }, scope, displayPresent = { id ->
            if (displayReadable.get()) context.getSystemService(DisplayManager::class.java).getDisplay(id) != null else null
        })
        val gateway = ShizukuDeviceAssistantGateway(context, settings, root)
        suspend fun ready() {
            // Let the gateway observe its initial disabled state before enabling this isolated fixture.
            delay(200)
            settings.setPowerModeEnabled(true)
            until { root.state.value.isConnected }
            assertEquals(1, connects.get())
            gateway.refreshSafetyState()
            assertEquals(false, gateway.safetyState.value.screenLocked)
            assertEquals(true, gateway.safetyState.value.screenInteractive)
        }
        suspend fun close() = withContext(NonCancellable) {
            settings.setPowerModeEnabled(false)
            settings.setBackgroundExecutionEnabled(false)
            root.release()
            scope.cancel()
        }
    }

    private suspend fun fixture(stallStatus: Boolean = false, block: suspend (Fixture) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("runRootConnectionLossDiagnostics") == "true")
        val entry = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        check(entry.runtime().activeConversations.value.isEmpty() && entry.deviceTasks().currentTask == null) { "Do not interrupt user work" }
        check(entry.root().serviceInfo()?.let { it.activeRequests == 0 && it.activeCommands == 0 && it.activeDisplays == 0 } != false)
        val terminal = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).terminal()
        check(terminal.state.value.commands.none { it.isRunning || it.cleanup?.blocksReuse == true }) { "Do not interrupt user commands" }
        val fixture = Fixture(stallStatus)
        try { fixture.ready(); block(fixture) } finally { fixture.close() }
    }

    private suspend fun blocked(action: suspend () -> Any?) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected the cleanup gate, received $error", error is IllegalStateException &&
            (error.message.orEmpty().contains("cleanup", true) || error.message.orEmpty().contains("unresolved", true)))
    }

    private suspend fun acrossModes(f: Fixture) {
        val before = f.opens.get()
        val connections = f.connects.get()
        val modes = listOf(Triple(PowerConnectionMode.Root, true, false), Triple(PowerConnectionMode.Root, true, true),
            Triple(PowerConnectionMode.Root, false, false), Triple(PowerConnectionMode.Shizuku, true, false),
            Triple(PowerConnectionMode.Shizuku, false, false), Triple(PowerConnectionMode.Root, true, false))
        for ((mode, enabled, background) in modes) {
            f.settings.setPowerModeEnabled(false)
            f.settings.setPowerConnectionMode(mode)
            f.settings.setBackgroundExecutionEnabled(background)
            f.settings.setPowerModeEnabled(enabled)
            delay(200)
            blocked { f.gateway.checkExecutionTarget() }
            blocked { f.gateway.pressHome() }
            blocked { f.gateway.startShellCommand("printf R7G_MUST_NOT_START", "/data/local/tmp", 5) }
            f.root.refresh(requestPermission = true, force = true)
            assertEquals(before, f.opens.get())
            assertEquals(connections, f.connects.get())
            report("blocked mode=$mode power=$enabled background=$background; no new request or connection")
        }
    }

    private suspend fun killOwnedRoot(root: RootDeviceConnection): Int {
        val identity = requireNotNull(root.serviceInfo())
        check(identity.uid == 0 && identity.pid > 1 && identity.pid != android.os.Process.myPid())
        val result = withContext(Dispatchers.IO) { ProcessDeviceCommandExecutor(root = true).use {
            it.execute(listOf("/system/bin/kill", "-KILL", identity.pid.toString()))
        } }
        assertTrue(result.output, result.success)
        until { root.serviceInfo() == null }
        report("killed only this fixture's pinned Root process pid=${identity.pid}")
        return identity.pid
    }

    @Test fun realRootDeathDuringRequestKeepsUnknownAcrossModesAndNeverReplays() = runBlocking<Unit> {
        fixture(stallStatus = true) { f ->
            val marker = File(context.cacheDir, "root-cancellation-diagnostic.pid")
            check(!marker.exists())
            val owner = UUID.randomUUID().toString()
            val caller = f.scope.async { f.root.withService(ownerId = owner) { it.getDeviceStatus() } }
            try {
                until { marker.exists() && marker.readText().trim().toIntOrNull() != null }
                val group = marker.readText().trim().toInt()
                assertTrue(group in groups())
                killOwnedRoot(f.root)
                assertTrue(withTimeout(4_000) { runCatching { caller.await() }.isFailure })
                val pending = f.root.rootRequestCleanup.value.values.single { it.ownerId == owner }
                assertEquals(RootRequestState.Unverified, pending.state)
                assertEquals(RootRequestCleanupReason.OriginalConnectionUnavailable, pending.reason)
                val reads = f.reads.get()
                repeat(3) { f.root.recheckRootRequestCleanup() }
                assertEquals(reads, f.reads.get())
                acrossModes(f)
                until { group !in groups() }
                assertEquals(pending, f.root.rootRequestCleanup.value[pending.requestId])
                report("request=$pending; actual group=$group absent independently; absence does not clear unavailable request evidence")
            } finally { caller.cancelAndJoin(); marker.delete() }
        }
    }

    @Test fun recoverableEvidenceFailureSurvivesBackgroundSwitchAndOnlyOriginalReadClearsIt() = runBlocking<Unit> {
        fixture { f ->
            f.readable.set(false)
            val owner = UUID.randomUUID().toString()
            assertTrue(f.root.withService(ownerId = owner) { it.getForegroundApp() }.contains("foreground_package="))
            val pending = f.root.rootRequestCleanup.value.values.single { it.ownerId == owner }
            assertEquals(RootRequestState.Unverified, pending.state)
            val originalPid = requireNotNull(f.root.serviceInfo()).pid
            val before = f.opens.get()
            f.settings.setBackgroundExecutionEnabled(true)
            delay(200)
            blocked { f.gateway.checkExecutionTarget() }
            blocked { f.gateway.launchApp("${context.packageName}.test", ExecutionDisplayFixtureActivity::class.java.name) }
            repeat(3) { f.root.recheckRootRequestCleanup() }
            assertEquals("A mode-triggered refresh must preserve the live original for recheck", originalPid, requireNotNull(f.root.serviceInfo()).pid)
            assertEquals(before, f.opens.get())
            assertEquals(1, f.connects.get())
            f.readable.set(true)
            f.root.recheckRootRequestCleanup()
            val verified = f.root.rootRequestCleanup.value[pending.requestId]
            assertEquals("Original evidence: $verified", RootRequestState.Released, verified?.state)
            assertEquals(before, f.opens.get())
            assertEquals(1, f.connects.get())
            f.settings.setBackgroundExecutionEnabled(false)
            f.gateway.checkExecutionTarget()
            assertTrue(f.root.withService(ownerId = UUID.randomUUID().toString()) { it.getForegroundApp() }.contains("foreground_package="))
            report("temporary evidence failure recovered solely by original-connection read; background change did not release gate; normal new work then succeeded")
        }
    }

    @Test fun displayUnknownBlocksOtherModesUntilExactSystemRemovalCanBeObserved() = runBlocking<Unit> {
        fixture { f ->
            val owner = UUID.randomUUID().toString()
            val lease = f.root.openExecutionDisplay(owner)
            val id = org.json.JSONObject(lease.remote.info).getInt("displayId")
            f.displayReadable.set(false)
            lease.close()
            until { context.getSystemService(DisplayManager::class.java).getDisplay(id) == null }
            assertTrue(f.root.executionDisplayCleanup.value.getValue(owner).blocksReuse)
            f.settings.setPowerModeEnabled(false)
            f.settings.setPowerConnectionMode(PowerConnectionMode.Shizuku)
            delay(200)
            blocked { f.gateway.pressHome() }
            blocked { f.gateway.checkExecutionTarget() }
            val opens = f.opens.get()
            repeat(3) { f.root.recheckExecutionDisplayCleanup() }
            assertTrue(f.root.executionDisplayCleanup.value.getValue(owner).blocksReuse)
            f.displayReadable.set(true)
            f.root.recheckExecutionDisplayCleanup()
            assertEquals(ExecutionDisplayState.Released, f.root.executionDisplayCleanup.value[owner]?.state)
            f.gateway.checkExecutionTarget()
            assertEquals(1, f.connects.get()); assertEquals(opens, f.opens.get())
            assertNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(0))
            report("display=$id actually removed; injected unreadable system evidence retained gate across transport change; exact system observation alone released display gate")
        }
    }

    @Test fun retainedCommandFromDeadRootCannotBeReadCancelledOrReplacedThroughAnotherMode() = runBlocking<Unit> {
        fixture { f ->
            val command = f.root.withShellCommand { it.startShellCommand("printf R7G_RETAINED; sleep 30 & wait", "/data/local/tmp", 60) }
            var current = command
            until {
                current = f.root.withShellCommand(command.id) { it.readShellCommand(command.id) }
                current.cleanup?.processGroup != null
            }
            val group = requireNotNull(current.cleanup?.processGroup)
            assertTrue(group in groups())
            killOwnedRoot(f.root)
            val before = f.opens.get()
            repeat(3) {
                assertTrue(runCatching { f.gateway.readShellCommand(command.id) }.isFailure)
                assertTrue(runCatching { f.gateway.cancelShellCommand(command.id) }.isFailure)
            }
            acrossModes(f)
            until { group !in groups() }
            assertEquals(before, f.opens.get())
            blocked { f.root.requireRequestCleanupVerified() }
            report("retained command=${command.id} group=$group; exact group absent independently; no new read/cancel RPC or replacement connection; cleanup remains unknown")
        }
    }

    @Test fun lostStartResponseCannotBecomeVerifiedFromItsLateReplyAfterDetachment() = runBlocking<Unit> {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val reply = CountDownLatch(1)
            val last = AtomicReference<CommandStartUpdate>()
            var recheck: (suspend () -> Unit)? = null
            val actual = AtomicReference<CommandSnapshot>()
            val observer = CommandStartObserver(prepared = { _, check -> recheck = check }, update = { last.set(it) })
            val caller = f.scope.async(observer) {
                f.root.withShellCommand { service ->
                    val raw = service.startShellCommand("printf R7G_PENDING; sleep 30 & wait", "/data/local/tmp", 60)
                    var snapshot = json.decodeFromString<CommandSnapshot>(raw)
                    runBlocking { until {
                        snapshot = json.decodeFromString(service.readShellCommand(snapshot.id))
                        snapshot.cleanup?.processGroup != null
                    } }
                    actual.set(snapshot)
                    entered.complete(Unit)
                    check(reply.await(15, TimeUnit.SECONDS))
                    raw
                }
            }
            try {
                withTimeout(10_000) { entered.await() }
                val group = requireNotNull(actual.get().cleanup?.processGroup)
                assertTrue(group in groups())
                f.root.release()
                assertEquals(CommandCleanupReason.OriginalConnectionUnavailable, last.get()?.reason)
                reply.countDown()
                assertTrue(withTimeout(4_000) { runCatching { caller.await() }.isFailure })
                val reads = f.reads.get()
                repeat(3) { requireNotNull(recheck).invoke() }
                assertEquals(reads, f.reads.get())
                assertEquals(CommandCleanupReason.OriginalConnectionUnavailable, last.get()?.reason)
                assertNull(last.get()?.snapshot)
                acrossModes(f)
                until { group !in groups() }
                report("startRequest=${last.get()?.requestId} actualCommand=${actual.get().id} group=$group; detached late reply rejected; original-only checks cannot turn it green")
            } finally { reply.countDown(); caller.cancelAndJoin() }
        }
    }
}
