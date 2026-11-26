package com.flowchat.app.diagnostics

import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.flowchat.app.data.device.*
import com.flowchat.app.data.terminal.ManagedCommandRegistry
import com.topjohnwu.superuser.ipc.RootService
import java.io.File

/** Debug-only stalled swipe on an owned display; never runs a model or changes system settings. */
@Keep
class RootDisplayCommandDiagnosticsService : RootService() {
    private lateinit var endpoint: RootServiceEndpoint

    override fun onCreate() {
        val marker = File(applicationInfo.dataDir, "cache/r7f-display-command.pid")
        val count = File(applicationInfo.dataDir, "cache/r7f-display-command.count")
        endpoint = RootServiceEndpoint(applicationInfo.uid, this) {
            object : DeviceCommandExecutor, AutoCloseable, DeviceCommandCleanupSource {
                private val process = ProcessDeviceCommandExecutor(processGroup = true, trackCleanup = true)
                override fun commandCleanup() = process.commandCleanup()
                override fun execute(command: List<String>) = process.execute(
                    if (command.firstOrNull() == "input" && command.getOrNull(3) == "swipe") {
                        listOf("/system/bin/sh", "-c",
                            "printf 'start\\n' >> ${ManagedCommandRegistry.shellQuote(count.absolutePath)}; " +
                                "printf '%s\\n' \"\$\$\" > ${ManagedCommandRegistry.shellQuote(marker.absolutePath)}; sleep 30 & wait")
                    } else command
                )
                override fun close() = process.close()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onDestroy() { endpoint.close(); super.onDestroy() }
}
