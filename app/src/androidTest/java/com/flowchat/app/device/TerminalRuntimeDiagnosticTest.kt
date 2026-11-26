package com.flowchat.app.device

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.domain.device.PowerConnectionMode
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TerminalRuntimeDiagnosticTest {
    @Test fun linuxToolsOutputCancellationAndRootCommandsWork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runTerminalDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = entry.settings().state.value
        val terminal = entry.terminal()
        val owner = "terminal-qa-${UUID.randomUUID()}"
        val log = File(context.getExternalFilesDir(null), "terminal-diagnostics.txt")
        val records = mutableListOf<String>()
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        suspend fun await(id: String, timeout: Long = 30_000): CommandSnapshot = withTimeout(timeout) {
            while (true) {
                val result = terminal.read(owner, id, 0, 1)
                log.writeText(records.joinToString("\n") + "\nCURRENT: ${result.command}\n${result.output.takeLast(10000)}\n${result.error.orEmpty()}")
                if (!result.isRunning) return@withTimeout result
                delay(100)
            }
            error("unreachable")
        }
        suspend fun run(command: String, timeout: Int = 20, env: CommandEnvironment = CommandEnvironment.Linux): CommandSnapshot {
            val started = terminal.start(owner, env, command, if (env == CommandEnvironment.Linux) "/workspace" else "/data/local/tmp", timeout)
            return await(started.id, (timeout + 10) * 1000L)
        }
        try {
            terminal.prepare()
            withTimeout(30_000) {
                while (!terminal.state.value.linux.ready) {
                    terminal.state.value.linux.error?.let { error(it) }
                    delay(100)
                }
            }
            assertTrue(terminal.state.value.linux.architecture in listOf("x86_64", "arm64-v8a"))
            val first = run("printf 'FLOWCHAT_LINUX_OK\\n'; id -u; pwd; printf 'saved-data' > flowchat-qa.txt")
            assertEquals(0, first.exitCode); assertTrue(first.output.contains("FLOWCHAT_LINUX_OK")); assertTrue(first.output.contains("/workspace"))
            assertEquals("saved-data", run("cat flowchat-qa.txt").output.trim())
            records += "Linux shell, fake root, workspace and file persistence PASS"
            assertTrue(runCatching { terminal.read("another-owner", first.id) }.isFailure)
            records += "Cross-conversation command ownership enforced PASS"
            val failure = run("printf 'expected-error' >&2; exit 7")
            assertEquals(7, failure.exitCode); assertTrue(failure.output.contains("expected-error"))
            val large = run("yes X | head -c 200000")
            assertEquals(0, large.exitCode); assertTrue(large.output.length <= 65_536); assertTrue(large.outputOffset > 0)
            val page = large.outputSince(0)
            assertTrue(page.output.length <= 12_000); assertEquals(large.outputOffset, page.outputOffset)
            records += "Stderr, nonzero exit, output cap and pagination PASS"
            val timed = run("sleep 30 & child=\$!; echo timeout-child=\$child; wait", 1)
            assertEquals(CommandStatus.TimedOut, timed.status)
            val cancellable = terminal.start(owner, CommandEnvironment.Linux, "sleep 30 & child=\$!; echo cancel-child=\$child; wait", "/workspace", 45)
            withTimeout(10_000) { while (!terminal.read(owner, cancellable.id).output.contains("cancel-child=")) delay(50) }
            val child = Regex("cancel-child=(\\d+)").find(terminal.read(owner, cancellable.id).output)!!.groupValues[1]
            terminal.cancel(owner, cancellable.id)
            assertEquals(CommandStatus.Cancelled, await(cancellable.id).status)
            delay(300)
            val childStatus = runCatching { File("/proc/$child/stat").readText() }.getOrNull()
            assertTrue("Cancelled child must be dead", childStatus == null || childStatus.substringAfterLast(") ").startsWith("Z "))
            records += "Timeout, cancellation and child process cleanup PASS"
            if (!terminal.state.value.linux.developerToolsReady) {
                val install = run("flowchat-install-tools", 600)
                assertEquals("Developer packages install: ${install.output.takeLast(1800)}", 0, install.exitCode)
            }
            val tools = run("python3 -c 'print(6*7)'; node -e 'console.log(7*9)'; git --version; npm --version")
            assertEquals(0, tools.exitCode); assertTrue(tools.output.contains("42")); assertTrue(tools.output.contains("63")); assertTrue(tools.output.contains("git version"))
            records += "Python, Node.js, npm and Git installed and executed PASS\n${tools.output}"
            if (InstrumentationRegistry.getArguments().getString("runRootTerminalDiagnostics") == "true") {
            entry.settings().setPowerConnectionMode(PowerConnectionMode.Root)
            entry.settings().setPowerModeEnabled(true)
            entry.device().requestPermission()
            withTimeout(15_000) { while (!entry.device().connectionState.value.isConnected) delay(100) }
            val root = run("id -u; printf 'FLOWCHAT_ANDROID_ROOT_OK\\n'", env = CommandEnvironment.Android)
            assertEquals(0, root.exitCode); assertTrue(root.output.lineSequence().any { it.trim() == "0" }); assertTrue(root.output.contains("FLOWCHAT_ANDROID_ROOT_OK"))
            records += "Android direct Root command path PASS"
            val rootWait = terminal.start(owner, CommandEnvironment.Android, "sleep 30 & echo root-child=\$!; wait", "/data/local/tmp", 45)
            withTimeout(10_000) { while (!terminal.read(owner, rootWait.id).output.contains("root-child=")) delay(100) }
            terminal.cancel(owner, rootWait.id)
            assertEquals(CommandStatus.Cancelled, await(rootWait.id).status)
            records += "Android Root process-group cancellation PASS"
            }
            log.writeText(records.joinToString("\n"))
        } finally {
            terminal.cancelAll()
            runCatching { run("rm -f flowchat-qa.txt") }
            entry.settings().setPowerModeEnabled(original.powerModeEnabled)
            entry.settings().setPowerConnectionMode(original.powerConnectionMode)
        }
    }
}
