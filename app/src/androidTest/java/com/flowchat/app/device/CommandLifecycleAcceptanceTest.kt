package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.terminal.*
import com.flowchat.app.data.device.ProcessDeviceCommandExecutor
import com.flowchat.app.diagnostics.CommandHostDiagnosticsService
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.presentation.chat.TaskDisplayPhase
import com.flowchat.app.presentation.chat.TaskPresentation
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Opt-in, synthetic commands only. Does not use paid providers or alter original tasks. */
class CommandLifecycleAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val json = Json { ignoreUnknownKeys = true }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun report(case: String, snapshot: CommandSnapshot, total: Long? = null) {
        val record = buildJsonObject {
            put("case", case); put("snapshot", json.encodeToJsonElement(snapshot))
            total?.let { put("total_millis", it) }
        }.toString()
        val dir = File(context.getExternalFilesDir(null), "p0b-lifecycle").apply { mkdirs() }
        File(dir, "results.jsonl").appendText(record + "\n")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nP0B $record\n") })
    }
    private suspend fun until(timeout: Long = 10_000, predicate: suspend () -> Boolean) =
        withTimeout(timeout) { while (!predicate()) delay(15) }
    private fun groups() = shell("ps -A -o PID,PGID,STAT").lineSequence().drop(1)
        .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }.toSet()
    private fun checkIdle() {
        check(InstrumentationRegistry.getArguments().getString("runCommandLifecycleDiagnostics") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        val runtime = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        check(runtime.runtime().activeConversations.value.isEmpty() && runtime.deviceTasks().currentTask == null)
        shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
    }
    private fun terminal(): TerminalManager {
        checkIdle()
        return EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).terminal().also {
            check(it.state.value.commands.none { c -> c.isRunning || c.cleanup?.blocksReuse == true })
        }
    }
    private suspend fun completed(terminal: TerminalManager, owner: String, id: String): CommandSnapshot {
        var result = terminal.read(owner, id)
        try { until { result = terminal.read(owner, id); !result.isRunning && result.cleanup?.state == CommandCleanupState.Released } }
        catch (error: Exception) { report("completion-failure", terminal.read(owner, id)); throw error }
        if (result.cleanup?.processStarted == true) assertFalse(requireNotNull(result.cleanup?.processGroup) in groups())
        return result
    }

    @Test fun warmLinuxCommandsKeepExitCodeAndExactOutput() = runBlocking<Unit> {
        val terminal = terminal()
        val owner = "p0b-short-${UUID.randomUUID()}"
        val totals = mutableListOf<Long>()
        repeat(4) { round ->
            for ((script, code, output) in listOf(Triple("true", 0, ""), Triple("printf '中文短文本'", 0, "中文短文本"), Triple("exit 7", 7, ""))) {
                val begin = SystemClock.elapsedRealtime()
                val started = terminal.start(owner, CommandEnvironment.Linux, script, "/workspace", 120)
                val result = completed(terminal, owner, started.id)
                val elapsed = SystemClock.elapsedRealtime() - begin
                report("C01-round-$round", result, elapsed)
                assertEquals(CommandStatus.Exited, result.status); assertEquals(code, result.exitCode)
                assertEquals(output, result.output)
                assertTrue("Ready-environment command took $elapsed ms", elapsed <= 3_000)
                assertEquals(code, result.lifecycle.single { it.phase == "target_exited" }.value)
                totals += elapsed
            }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nP0B C01 samples=${totals.size} sorted=${totals.sorted()}\n") })
    }

    @Test fun missingFileAndChainReturnsNonzeroWithoutRunningTheNextCommand() = runBlocking<Unit> {
        val terminal = terminal(); val owner = "p0b-missing-${UUID.randomUUID()}"
        val started = terminal.start(owner, CommandEnvironment.Linux,
            "cat '/__p0b_missing__/absent' && printf SHOULD_NOT_RUN", "/workspace", 120)
        val result = completed(terminal, owner, started.id)
        report("C02", result)
        assertEquals(CommandStatus.Exited, result.status); assertTrue(result.exitCode != 0)
        assertTrue(result.output.contains("absent")); assertFalse(result.output.contains("SHOULD_NOT_RUN"))
        assertTrue(result.elapsedMillis() <= 3_000)
    }

    @Test fun ownedChildHoldingOutputCannotHoldCompletionForever() = runBlocking<Unit> {
        checkIdle()
        ManagedCommandRegistry("p0b-held-", trackCleanup = true).use { registry ->
            val started = registry.start("controlled inherited pipe", CommandEnvironment.Android, "/", 15,
                CommandProcessSpec(listOf("/system/bin/sh", "-c", "sleep 30 & printf HELD_PIPE; exit 7")))
            var result = started
            until { result = registry.read(started.id); !result.isRunning && result.cleanup?.state == CommandCleanupState.Released }
            report("C03", result)
            assertEquals(CommandStatus.Exited, result.status); assertEquals(7, result.exitCode)
            assertEquals("HELD_PIPE", result.output); assertTrue(result.elapsedMillis() <= 4_000)
            assertFalse(requireNotNull(result.cleanup?.processGroup) in groups())
        }
    }

    @Test fun typedDeviceCommandsKeepExactOutputWithoutSupervisorControlFrames() = runBlocking<Unit> {
        checkIdle()
        for (root in listOf(false, true)) {
            withContext(Dispatchers.IO) {
                ProcessDeviceCommandExecutor(root = root, processGroup = true, trackCleanup = true).use { executor ->
                    val success = executor.execute(listOf("/system/bin/sh", "-c", "printf 42"))
                    assertTrue(success.output, success.success)
                    assertEquals("42", success.output)
                    val failed = executor.execute(listOf("/system/bin/sh", "-c", "printf DEVICE_FAILURE; exit 7"))
                    assertFalse(failed.success)
                    assertEquals("DEVICE_FAILURE", failed.output)
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "\nP0B typed-output root=$root exactSuccessAndFailure=true supervisorFrameAbsent=true\n") })
        }
    }

    @Test fun fiveSecondTimeoutIsSeparateFromCleanupAndStopsFollowingWork() = runBlocking<Unit> {
        val terminal = terminal(); val owner = "p0b-timeout-${UUID.randomUUID()}"
        val begin = SystemClock.elapsedRealtime()
        val started = terminal.start(owner, CommandEnvironment.Linux, "printf TIMEOUT_READY; sleep 30; printf BAD_AFTER_TIMEOUT", "/workspace", 5)
        val result = completed(terminal, owner, started.id)
        val elapsed = SystemClock.elapsedRealtime() - begin
        report("C04", result, elapsed)
        assertEquals(CommandStatus.TimedOut, result.status); assertNull(result.exitCode)
        assertEquals("TIMEOUT_READY", result.output); assertTrue(elapsed in 5_000..7_000)
        assertTrue(result.cleanup?.processExited == true)
    }

    @Test fun stoppingAfterOutputAndAtExitNeverStartsAnotherCommand() = runBlocking<Unit> {
        val terminal = terminal(); val owner = "p0b-stop-${UUID.randomUUID()}"
        val started = terminal.start(owner, CommandEnvironment.Linux, "printf STOP_READY; sleep 30; printf BAD_AFTER_STOP", "/workspace", 120)
        until { terminal.read(owner, started.id).output.contains("STOP_READY") }
        val begin = SystemClock.elapsedRealtime()
        terminal.cancel(owner, started.id)
        val result = completed(terminal, owner, started.id)
        report("C05-after-output", result, SystemClock.elapsedRealtime() - begin)
        assertEquals(CommandStatus.Cancelled, result.status); assertEquals("STOP_READY", result.output)
        assertTrue(SystemClock.elapsedRealtime() - begin <= 2_000)
        val observed = mutableSetOf(result.status)
        for ((attempt, stopDelay) in listOf(0L, 30L, 80L, 120L, 145L, 180L, 260L, 400L).withIndex()) {
            val race = terminal.start(owner, CommandEnvironment.Linux, "printf RACE; sleep 0.08; printf EXIT_RACE", "/workspace", 120)
            delay(stopDelay)
            val before = terminal.read(owner, race.id)
            terminal.cancel(owner, race.id)
            val ended = completed(terminal, owner, race.id)
            assertTrue(ended.status in setOf(CommandStatus.Cancelled, CommandStatus.Exited))
            observed += ended.status
            if (!before.isRunning) assertEquals(before, ended)
            assertEquals(ended, terminal.read(owner, race.id))
            report("C05-race-$attempt", ended)
        }
        // Scheduler/PRoot latency may keep every timed sample on the cancellation side.
        // Exercise the settled side from actual completion, rather than assuming 400 ms is enough.
        val settled = terminal.start(owner, CommandEnvironment.Linux, "printf EXIT_SETTLED; exit 7", "/workspace", 120)
        val settledBeforeStop = completed(terminal, owner, settled.id)
        assertEquals(CommandStatus.Exited, settledBeforeStop.status); assertEquals(7, settledBeforeStop.exitCode)
        terminal.cancel(owner, settled.id)
        assertEquals(settledBeforeStop, terminal.read(owner, settled.id))
        observed += settledBeforeStop.status
        report("C05-after-settled-exit", settledBeforeStop)
        assertTrue("Both sides of completion must be exercised", observed.containsAll(setOf(CommandStatus.Cancelled, CommandStatus.Exited)))
        val atExit = terminal.start(owner, CommandEnvironment.Linux, "printf TARGET_EXIT; exit 7", "/workspace", 120)
        until { terminal.read(owner, atExit.id).lifecycle.any { it.phase == "target_exited" } }
        val beforeStop = terminal.read(owner, atExit.id)
        terminal.cancel(owner, atExit.id)
        val afterStop = completed(terminal, owner, atExit.id)
        if (!beforeStop.isRunning) assertEquals(beforeStop, afterStop)
        else assertEquals(CommandStatus.Cancelled, afterStop.status)
        report("C05-after-target-exit", afterStop)
        assertTrue(terminal.state.value.commands.none { it.isRunning })
    }

    @Test fun killedIsolatedHostClosesLifetimeAndLeavesUnrelatedCommandAlive() = runBlocking<Unit> {
        checkIdle()
        val token = UUID.randomUUID().toString()
        val evidence = File(context.cacheDir, "p0b-host-$token.json")
        val service = Intent(context, CommandHostDiagnosticsService::class.java).putExtra("token", token)
        ManagedCommandRegistry("p0b-independent-", trackCleanup = true).use { unrelated ->
            val separate = unrelated.start("independent control", CommandEnvironment.Android, "/", 30,
                CommandProcessSpec(listOf("/system/bin/sh", "-c", "printf INDEPENDENT; sleep 30")))
            var hostPid: Int? = null; var hostGroup: Int? = null
            try {
                context.startService(service)
                var document: JsonObject? = null
                until { document = runCatching { json.parseToJsonElement(evidence.readText()).jsonObject }.getOrNull()
                    document?.get("snapshot")?.jsonObject?.get("output")?.jsonPrimitive?.content?.contains("HOST_READY") == true }
                val original = requireNotNull(document)
                hostPid = original.getValue("host_pid").jsonPrimitive.int
                val snapshot = json.decodeFromJsonElement<CommandSnapshot>(original.getValue("snapshot"))
                hostGroup = requireNotNull(snapshot.cleanup?.processGroup)
                assertTrue(hostPid != android.os.Process.myPid()); assertTrue(hostGroup in groups())
                val killedPid = requireNotNull(hostPid)
                android.os.Process.killProcess(killedPid)
                hostPid = null
                until(5_000) { hostGroup !in groups() }
                assertTrue(unrelated.read(separate.id).isRunning)
                report("C06-host-lost-original-running-evidence", snapshot)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "\nP0B C06 killedHost=$killedPid originalGroup=$hostGroup groupAbsent=true unrelatedStillRunning=true\n") })
            } finally {
                hostPid?.let { if (shell("cat /proc/$it/cmdline").contains(":command_lifecycle_probe")) android.os.Process.killProcess(it) }
                context.stopService(service)
                unrelated.cancel(separate.id)
                until { !unrelated.read(separate.id).isRunning && unrelated.read(separate.id).cleanup?.state == CommandCleanupState.Released }
                evidence.delete()
                val workspace = File(context.filesDir, "terminal/workspaces/p0b-host-$token")
                check(workspace.canonicalFile.parentFile == File(context.filesDir, "terminal/workspaces").canonicalFile)
                workspace.deleteRecursively()
            }
        }
    }

    @Test fun normalTaskRuntimeReceivesTheRealCommandResultAndFinishesWithoutRecovery() = runBlocking<Unit> {
        val terminal = terminal()
        val fixture = AutonomyRuntimeFixture(context, terminalOverride = terminal)
        var passed = false
        try {
            fixture.settings.setPowerModeEnabled(false); fixture.settings.setCommandExecutionEnabled(true)
            fixture.responder = { request, round ->
                if (round == 1) listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("p0b-run", TerminalToolDefinitions.Run,
                    """{"environment":"linux","command":"printf '完整命令结果'"}"""))))
                else {
                    check(round == 2)
                    val result = json.parseToJsonElement(requireNotNull(request.messages.last { it.role == "tool" }.content)).jsonObject
                    assertEquals("Exited", result.getValue("status").jsonPrimitive.content)
                    assertEquals(0, result.getValue("exit_code").jsonPrimitive.int)
                    assertEquals("完整命令结果", result.getValue("output").jsonPrimitive.content)
                    assertEquals("Released", result.getValue("cleanup").jsonObject.getValue("state").jsonPrimitive.content)
                    listOf(ChatDelta.Content("已取得完整命令结果。"), ChatDelta.Done)
                }
            }
            fixture.create()
            val begin = SystemClock.elapsedRealtime()
            fixture.send("在 Linux 输出一段短文本，核对退出结果后告诉我。")
            val uiProjectionStarted = SystemClock.elapsedRealtime()
            val phaseBefore = TaskPresentation.from(fixture.runtime.uiState.value).phase
            // Journal completion and the combined UI projection are separate asynchronous emissions.
            // Require the real projection to finish promptly instead of sampling before its next emission.
            withTimeout(1_000) { fixture.runtime.uiState.first { TaskPresentation.from(it).phase == TaskDisplayPhase.Finished } }
            val uiProjectionWait = SystemClock.elapsedRealtime() - uiProjectionStarted
            assertEquals(2, fixture.modelCalls.get()); assertTrue(fixture.confirmations.isEmpty())
            assertNull(fixture.runtime.uiState.value.taskRecovery)
            assertEquals(TaskDisplayPhase.Finished, TaskPresentation.from(fixture.runtime.uiState.value).phase)
            val answer = fixture.chats.getMessages(fixture.owner).last { it.role == MessageRole.Assistant }.content
            assertEquals("已取得完整命令结果。", answer)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "\nP0B normal-runtime goalComplete=true elapsed=${SystemClock.elapsedRealtime()-begin}ms model=scripted calls=2 confirmations=0 recovery=none uiPhaseBefore=$phaseBefore uiProjectionWait=${uiProjectionWait}ms database=${fixture.name}\n") })
            passed = true
        } finally { fixture.close(passed) }
    }

    @Test fun snapshotOutputReadReturnsImmediatelyAndLegacyReadStillWaitsForCompletion() = runBlocking<Unit> {
        val terminal = terminal()
        val fixture = AutonomyRuntimeFixture(context, terminalOverride = terminal)
        var passed = false; var commandId: String? = null
        try {
            fixture.settings.setPowerModeEnabled(false); fixture.settings.setCommandExecutionEnabled(true)
            fixture.create()
            val started = terminal.start(fixture.owner, CommandEnvironment.Linux, "printf READ_READY; sleep 30", "/workspace", 120)
            commandId = started.id
            until { terminal.read(fixture.owner, started.id).output.contains("READ_READY") }
            fixture.responder = { request, round ->
                when (round) {
                    1 -> listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("p0b-read-now", TerminalToolDefinitions.Read,
                        """{"command_id":"${started.id}","wait_for_completion":false}"""))))
                    2 -> {
                        val result = json.parseToJsonElement(requireNotNull(request.messages.last { it.role == "tool" }.content)).jsonObject
                        assertEquals("Running", result.getValue("status").jsonPrimitive.content)
                        assertEquals("READ_READY", result.getValue("output").jsonPrimitive.content)
                        listOf(ChatDelta.Content("当前输出已读到，命令仍在运行。"), ChatDelta.Done)
                    }
                    3 -> listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("p0b-read-legacy", TerminalToolDefinitions.Read,
                        """{"command_id":"$commandId"}"""))))
                    4 -> {
                        val result = json.parseToJsonElement(requireNotNull(request.messages.last { it.role == "tool" }.content)).jsonObject
                        assertEquals("Exited", result.getValue("status").jsonPrimitive.content)
                        assertEquals("COMPAT_DONE", result.getValue("output").jsonPrimitive.content)
                        listOf(ChatDelta.Content("旧读取方式仍等待命令结束。"), ChatDelta.Done)
                    }
                    else -> error("Unexpected local model call $round")
                }
            }
            val begin = SystemClock.elapsedRealtime()
            fixture.send("只查看已有输出和运行状态。")
            val elapsed = SystemClock.elapsedRealtime()-begin
            assertTrue(elapsed <= 2_000); assertTrue(terminal.read(fixture.owner, started.id).isRunning)
            terminal.cancel(fixture.owner, started.id); completed(terminal, fixture.owner, started.id)
            commandId = terminal.start(fixture.owner, CommandEnvironment.Linux, "sleep 1; printf COMPAT_DONE", "/workspace", 120).id
            fixture.send("等这条命令结束后查看输出。")
            assertEquals(4, fixture.modelCalls.get()); assertTrue(fixture.confirmations.isEmpty())
            report("read-legacy-default", completed(terminal, fixture.owner, requireNotNull(commandId)))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "\nP0B snapshot-read elapsed=${elapsed}ms stillRunning=true legacyDefaultWait=true model=scripted database=${fixture.name}\n") })
            passed = true
        } finally {
            commandId?.let { runCatching { terminal.cancel(fixture.owner, it) } }
            fixture.close(passed)
        }
    }
}
