package com.flowchat.app.diagnostics

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.flowchat.app.data.terminal.LinuxRuntime
import com.flowchat.app.data.terminal.ManagedCommandRegistry
import com.flowchat.app.domain.terminal.CommandEnvironment
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*

/** Debug-only host for testing real process death without killing the user's app or Root service. */
class CommandHostDiagnosticsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = ManagedCommandRegistry("p0b-host-", trackCleanup = true)
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra("token")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
            ?: return START_NOT_STICKY.also { stopSelf() }
        scope.launch {
            val linux = LinuxRuntime(this@CommandHostDiagnosticsService)
            linux.prepare()
            val command = "printf HOST_READY; sleep 30; printf BAD_AFTER_HOST_DEATH"
            val started = registry.start(command, CommandEnvironment.Linux, "/workspace", 60,
                linux.processSpec("p0b-host-$token", command, "/workspace"))
            registry.state.collectLatest { entries ->
                if (entries.none { it.id == started.id }) return@collectLatest
                val snapshot = registry.read(started.id)
                File(cacheDir, "p0b-host-$token.json").writeText(buildJsonObject {
                    put("host_pid", android.os.Process.myPid()); put("snapshot", Json.encodeToJsonElement(snapshot))
                }.toString())
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { registry.close(); scope.cancel(); super.onDestroy() }
}
