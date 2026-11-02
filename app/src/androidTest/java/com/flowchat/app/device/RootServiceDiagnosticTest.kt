package com.flowchat.app.device

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.diagnostics.RootCancellationDiagnosticsService
import com.flowchat.app.diagnostics.RootCommandDiagnosticsService
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.terminal.CommandSnapshot
import com.flowchat.app.domain.terminal.CommandStatus
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in Root transport tests. No runtime, conversations, providers, or phone-setting changes. */
class RootServiceDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runRootServiceDiagnostics") == "true")
    private fun report(key: String, value: Any) = instrumentation.sendStatus(1, Bundle().apply { putString(key, value.toString()) })
    private suspend fun until(timeout: Long = 10_000, predicate: suspend () -> Boolean) = withTimeout(timeout) {
        while (!predicate()) delay(100)
    }
    private suspend fun command(root: RootDeviceConnection, id: String): CommandSnapshot =
        Json.decodeFromString(root.withService { it.readShellCommand(id) })
    private suspend fun start(root: RootDeviceConnection, script: String, timeout: Int = 60): CommandSnapshot =
        Json.decodeFromString(root.withService { it.startShellCommand(script, "/data/local/tmp", timeout) })
    private suspend fun finished(root: RootDeviceConnection, id: String): CommandSnapshot {
        until { !command(root, id).isRunning }
        return command(root, id)
    }
    private suspend fun groupGone(root: RootDeviceConnection, pid: Int, processGroup: Boolean = true): Boolean {
        require(pid > 1)
        val target = if (processGroup) "-$pid" else pid.toString()
        val probe = start(root, "if /system/bin/kill -0 -- $target 2>/dev/null; then printf ALIVE; else printf GONE; fi", 5)
        return finished(root, probe.id).output.trim() == "GONE"
    }
    private suspend fun groupOf(root: RootDeviceConnection, id: String): Int {
        var pid: Int? = null
        until { pid = Regex("ROOT_GROUP=(\\d+)").find(command(root, id).output)?.groupValues?.get(1)?.toInt(); pid != null }
        return requireNotNull(pid)
    }
    private suspend fun stopOwnGroup(pid: Int?) {
        if (pid != null && pid > 1) withContext(Dispatchers.IO) {
            val cleanup = "case \"\$(cat /proc/$pid/cmdline 2>/dev/null)\" in " +
                "*ROOT_GROUP*|*root-cancellation-diagnostic.pid*) /system/bin/kill -KILL -- -$pid;; esac"
            ProcessDeviceCommandExecutor(root = true).use { it.execute(listOf("/system/bin/sh", "-c", cleanup)) }
        }
    }
    private suspend fun cleanup(root: RootDeviceConnection, scope: CoroutineScope, group: Int? = null) = withContext(NonCancellable) {
        stopOwnGroup(group)
        root.release()
        scope.cancel()
    }

    @Test fun readOnlyCommandDiagnosis() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context, RootCommandDiagnosticsService::class.java), scope)
        val trace = File(context.cacheDir, "root-command-diagnostic.txt")
        trace.delete()
        try {
            root.refresh(requestPermission = true)
            assertTrue(root.state.value.toString(), root.state.value.isConnected)
            val status = root.withService { it.getDeviceStatus() }
            report("ROOT_COMMAND_TRACE", trace.readText())
            assertTrue(status, status.contains("battery="))
        } finally { cleanup(root, scope); trace.delete() }
    }

    @Test fun remoteUidProcessReuseAndActualIdleRelease() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope)
        try {
            root.refresh(requestPermission = true)
            assertEquals(root.state.value.toString(), ShizukuConnectionStatus.ConnectedDirectRoot, root.state.value.status)
            val first = requireNotNull(root.serviceInfo())
            assertEquals(0, first.uid)
            assertNotEquals(Process.myPid(), first.pid)
            repeat(5) {
                val status = root.withService { it.getDeviceStatus() }
                assertTrue(status, status.contains("battery="))
                assertEquals(first.pid, root.serviceInfo()!!.pid)
                assertEquals(0, root.serviceInfo()!!.activeRequests)
            }
            report("ROOT_REUSE_PID", first.pid)
            until(40_000) { root.serviceInfo() == null }
            assertEquals(ShizukuConnectionStatus.RootReady, root.state.value.status)
            root.refresh()
            assertNull(root.serviceInfo())
            assertTrue(root.withService { it.getDeviceStatus() }.contains("battery="))
            val second = requireNotNull(root.serviceInfo())
            assertNotEquals(first.pid, second.pid)
            report("ROOT_IDLE_RECONNECTED_PID", second.pid)
        } finally { cleanup(root, scope) }
    }

    @Test fun cancellingBlockedTypedRpcStopsOnlyItsOwnedProcessGroup() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context, RootCancellationDiagnosticsService::class.java), scope)
        val marker = File(context.cacheDir, "root-cancellation-diagnostic.pid")
        marker.delete()
        var group: Int? = null
        try {
            root.refresh(requestPermission = true)
            assertTrue(root.state.value.isConnected)
            val caller = launch { root.withService { it.getDeviceStatus() } }
            until { marker.exists() && marker.readText().trim().toIntOrNull() != null }
            group = marker.readText().trim().toInt()
            assertEquals(1, root.serviceInfo()!!.activeRequests)
            assertTrue(root.withService { it.getForegroundApp() }.contains("foreground_package="))
            withTimeout(3_000) { caller.cancelAndJoin() }
            assertEquals(0, root.serviceInfo()!!.activeRequests)
            until { groupGone(root, group!!) }
            report("ROOT_TYPED_CANCEL_GROUP", group!!)
            group = null
        } finally { cleanup(root, scope, group); marker.delete() }
    }

    @Test fun shellCancellationPinsThenReleasesServiceWithoutAdditionalSu() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope, idleTimeoutMillis = 1_000)
        var group: Int? = null
        try {
            root.refresh(requestPermission = true)
            val pid = root.serviceInfo()!!.pid
            val running = start(root, "printf 'ROOT_GROUP=%s\\n' \"\$\$\"; id -u; sleep 30 & wait")
            group = groupOf(root, running.id)
            assertTrue(command(root, running.id).output.lineSequence().any { it.trim() == "0" })
            delay(2_200)
            assertEquals(pid, root.serviceInfo()!!.pid)
            assertEquals(1, root.serviceInfo()!!.activeCommands)
            root.withService { it.cancelShellCommand(running.id) }
            assertEquals(CommandStatus.Cancelled, finished(root, running.id).status)
            until { groupGone(root, group!!) }
            val stoppedGroup = group!!
            group = null
            until(5_000) { root.serviceInfo() == null }
            report("ROOT_SHELL_CANCEL_GROUP", stoppedGroup)
            root.refresh(requestPermission = true)
            val teardown = start(root, "printf 'ROOT_GROUP=%s\\n' \"\$\$\"; sleep 30 & wait")
            group = groupOf(root, teardown.id)
            root.release()
            assertNull(root.serviceInfo())
            assertEquals(ShizukuConnectionStatus.RootPermissionRequired, root.state.value.status)
            root.refresh(requestPermission = true)
            until { groupGone(root, group!!) }
            report("ROOT_RELEASE_CANCEL_GROUP", group!!)
            group = null
        } finally { cleanup(root, scope, group) }
    }

    @Test fun serviceDeathKillsOwnedChildrenAndReconnectDoesNotReplayCommand() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope)
        var group: Int? = null
        try {
            root.refresh(requestPermission = true)
            val first = root.serviceInfo()!!
            val running = start(root, "printf 'ROOT_GROUP=%s\\n' \"\$\$\"; sleep 30 & wait")
            group = groupOf(root, running.id)
            val killed = withContext(Dispatchers.IO) { ProcessDeviceCommandExecutor(root = true).use {
                it.execute(listOf("kill", "-KILL", first.pid.toString()))
            } }
            assertTrue(killed.output, killed.success)
            until { root.state.value.status == ShizukuConnectionStatus.Error }
            assertNull(root.serviceInfo())
            assertTrue(root.withService { it.getDeviceStatus() }.contains("battery="))
            assertNotEquals(first.pid, root.serviceInfo()!!.pid)
            until { groupGone(root, group!!) }
            group = null
            try { root.withService { it.readShellCommand(running.id) }; fail("Old command must not be recreated") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("Command not found")) }
            report("ROOT_DEATH_RECONNECTED_PID", root.serviceInfo()!!.pid)
        } finally { cleanup(root, scope, group) }
    }

    @Test fun clientProcessDeathSeed() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootClientDeathPhase") == "seed")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope)
        try {
            root.refresh(requestPermission = true)
            val running = start(root, "printf 'ROOT_GROUP=%s\\n' \"\$\$\"; sleep 90 & wait", 120)
            val group = groupOf(root, running.id)
            File(context.filesDir, "root-client-death-diagnostic.txt").writeText("${root.serviceInfo()!!.pid}\n$group\n${running.id}")
            report("ROOT_CLIENT_DEATH_SEED", "pid=${root.serviceInfo()!!.pid},group=$group")
            // The controller now ends the app process; intentionally retain this bound session.
        } catch (error: Exception) { cleanup(root, scope); throw error }
    }

    @Test fun clientProcessDeathVerify() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootClientDeathPhase") == "verify")
        val metadata = File(context.filesDir, "root-client-death-diagnostic.txt")
        val (oldPid, oldGroup, oldId) = metadata.readLines()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope)
        var group: Int? = oldGroup.toInt()
        try {
            root.refresh(requestPermission = true)
            assertTrue(root.state.value.isConnected)
            assertNotEquals(oldPid.toInt(), root.serviceInfo()!!.pid)
            until { groupGone(root, oldPid.toInt(), processGroup = false) }
            until { groupGone(root, group!!) }
            group = null
            try { root.withService { it.readShellCommand(oldId) }; fail("Old command must not be recreated") }
            catch (_: IllegalArgumentException) { }
            report("ROOT_CLIENT_DEATH_CLEAN", "oldPid=$oldPid,group=$oldGroup")
            metadata.delete()
        } finally { cleanup(root, scope, group) }
    }
}
