package com.flowchat.app.diagnostics

import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.flowchat.app.data.device.DeviceCommandExecutor
import com.flowchat.app.data.device.ProcessDeviceCommandExecutor
import com.flowchat.app.data.device.RootServiceEndpoint
import com.flowchat.app.data.terminal.ManagedCommandRegistry
import com.topjohnwu.superuser.ipc.RootService
import java.io.File

/** Debug-only synthetic stalled command, isolated from real providers and phone settings. */
@Keep
class RootCancellationDiagnosticsService : RootService() {
    private lateinit var endpoint: RootServiceEndpoint
    override fun onCreate() {
        val marker = File(applicationInfo.dataDir, "cache/root-cancellation-diagnostic.pid")
        endpoint = RootServiceEndpoint(applicationInfo.uid) {
            object : DeviceCommandExecutor, AutoCloseable {
                private val process = ProcessDeviceCommandExecutor(processGroup = true)
                override fun execute(command: List<String>) = process.execute(
                    if (command == listOf("df", "-k", "/data")) listOf("/system/bin/sh", "-c",
                        "printf '%s\\n' \"\$\$\" > ${ManagedCommandRegistry.shellQuote(marker.absolutePath)}; sleep 30 & wait")
                    else command
                )
                override fun close() = process.close()
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onDestroy() { endpoint.close(); super.onDestroy() }
}
