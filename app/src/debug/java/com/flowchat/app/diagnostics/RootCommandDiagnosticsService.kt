package com.flowchat.app.diagnostics

import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.flowchat.app.data.device.DeviceCommandExecutor
import com.flowchat.app.data.device.ProcessDeviceCommandExecutor
import com.flowchat.app.data.device.RootServiceEndpoint
import com.topjohnwu.superuser.ipc.RootService
import java.io.File

/** Debug-only command failure evidence; contains no model/runtime dependencies. */
@Keep
class RootCommandDiagnosticsService : RootService() {
    private lateinit var endpoint: RootServiceEndpoint
    override fun onCreate() {
        val trace = File(applicationInfo.dataDir, "cache/root-command-diagnostic.txt")
        endpoint = RootServiceEndpoint(applicationInfo.uid) {
            object : DeviceCommandExecutor, AutoCloseable {
                private val process = ProcessDeviceCommandExecutor(processGroup = true)
                override fun execute(command: List<String>) = process.execute(command).also { result ->
                    trace.appendText("${command.first()}: success=${result.success}; ${result.output.take(200)}\n")
                }
                override fun close() = process.close()
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onDestroy() { endpoint.close(); super.onDestroy() }
}
