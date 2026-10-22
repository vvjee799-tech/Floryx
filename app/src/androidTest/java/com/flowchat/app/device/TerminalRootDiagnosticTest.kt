package com.flowchat.app.device

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.terminal.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TerminalRootDiagnosticTest {
    @Test fun rootGatewayAndApkNetworkOptionsAreAvailable() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runRootTerminalDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = entry.settings().state.value
        val owner = "terminal-root-qa"
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        suspend fun await(id: String): CommandSnapshot = withTimeout(15_000) {
            while (true) {
                val current = entry.terminal().read(owner, id, waitSeconds = 1)
                if (!current.isRunning) return@withTimeout current
                delay(100)
            }
            error("unreachable")
        }
        try {
            val help = entry.terminal().start(owner, CommandEnvironment.Linux, "apk --timeout 2 --repositories-file /etc/apk/repositories --version", "/workspace", 5)
            val options = await(help.id)
            File(context.getExternalFilesDir(null), "terminal-root-results.txt").writeText(options.output)
            assertEquals(options.output, 0, options.exitCode)
            assertTrue(options.output, options.output.contains("apk-tools"))
            entry.settings().setPowerConnectionMode(PowerConnectionMode.Root)
            entry.settings().setPowerModeEnabled(true)
            entry.device().requestPermission()
            withTimeout(15_000) {
                while (!entry.device().connectionState.value.isConnected) {
                    File(context.getExternalFilesDir(null), "terminal-root-results.txt").writeText(entry.device().connectionState.value.toString())
                    delay(100)
                }
            }
            val started = entry.terminal().start(owner, CommandEnvironment.Android, "id -u; printf 'ROOT_COMMAND_OK\\n'", "/data/local/tmp", 5)
            val result = await(started.id)
            assertEquals(0, result.exitCode); assertTrue(result.output.contains("ROOT_COMMAND_OK")); assertTrue(result.output.lineSequence().any { it.trim() == "0" })
            File(context.getExternalFilesDir(null), "terminal-root-results.txt").writeText("PASS: direct Root gateway, UID 0 and APK timeout/repository options.")
        } finally {
            entry.terminal().cancelAll()
            entry.settings().setPowerModeEnabled(original.powerModeEnabled)
            entry.settings().setPowerConnectionMode(original.powerConnectionMode)
        }
    }
}
