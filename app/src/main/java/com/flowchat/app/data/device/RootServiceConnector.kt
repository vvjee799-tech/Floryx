package com.flowchat.app.data.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.shizuku.IFlowChatRootService
import com.flowchat.app.shizuku.IFlowChatShizukuService
import com.flowchat.app.shizuku.IFlowChatExecutionDisplay
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal class RootConnectionException(val status: ShizukuConnectionStatus, message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal interface RootServiceSession {
    val alive: Boolean
    fun info(): RootServiceInfo
    fun openRequest(id: String): IFlowChatShizukuService
    fun finishRequest(id: String, cancelled: Boolean)
    fun openExecutionDisplay(id: String): IFlowChatExecutionDisplay = error("Execution displays are unavailable.")
    fun closeExecutionDisplay(id: String) = Unit
    suspend fun close()
}

internal fun interface RootServiceConnector {
    suspend fun connect(onDisconnected: () -> Unit): RootServiceSession
}

/** Owns only a short-lived launch shell; no cached global shell or daemon mode. */
internal class LibsuRootServiceConnector(
    private val context: Context,
    private val serviceClass: Class<out RootService> = RootDeviceService::class.java
) : RootServiceConnector {
    // libsu 6 can keep a pending launch after startup failure. Reuse its public launch task on
    // an explicit subsequent attempt instead of reflecting into its private manager state.
    private var startupTask: Shell.Task? = null

    override suspend fun connect(onDisconnected: () -> Unit): RootServiceSession = coroutineScope {
        val shell = try {
            runInterruptible(Dispatchers.IO) { Shell.Builder.create().setCommands("su").setTimeout(45).build() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val missing = generateSequence<Throwable>(error) { it.cause }.any {
                it.message?.let { message -> message.contains("error=2") || message.contains("No such file") } == true
            }
            throw RootConnectionException(if (missing) ShizukuConnectionStatus.RootUnavailable else ShizukuConnectionStatus.RootPermissionDenied,
                if (missing) "The app could not launch su." else "Root permission could not be obtained for this app.", error)
        }
        val ready = CompletableDeferred<RootServiceSession>()
        val abandoned = AtomicBoolean(false)
        val intent = Intent(context, serviceClass)
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (abandoned.get()) {
                    RootService.unbind(this)
                    return
                }
                val remote = IFlowChatRootService.Stub.asInterface(binder)
                val death = IBinder.DeathRecipient { if (!abandoned.get()) onDisconnected() }
                try {
                    binder.linkToDeath(death, 0)
                    val session = object : RootServiceSession {
                        override val alive: Boolean get() = !abandoned.get() && binder.isBinderAlive
                        override fun info() = RootServiceInfo.decode(remote.getServiceInfo())
                        override fun openRequest(id: String) = remote.openRequest(id)
                        override fun finishRequest(id: String, cancelled: Boolean) = remote.finishRequest(id, cancelled)
                        override fun openExecutionDisplay(id: String) = remote.openExecutionDisplay(id)
                        override fun closeExecutionDisplay(id: String) = remote.closeExecutionDisplay(id)
                        override suspend fun close() {
                            if (abandoned.compareAndSet(false, true)) {
                                runCatching { binder.unlinkToDeath(death, 0) }
                                withContext(Dispatchers.Main.immediate) { RootService.unbind(connection) }
                            }
                        }
                    }
                    ready.complete(session)
                } catch (error: Exception) {
                    ready.completeExceptionally(error)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!abandoned.get()) {
                    ready.completeExceptionally(IllegalStateException("Root service disconnected."))
                    onDisconnected()
                }
            }

            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("Root service returned no Binder."))
            }
        }
        var connected = false
        try {
            if (!shell.isRoot) throw RootConnectionException(ShizukuConnectionStatus.RootPermissionDenied, "The app did not obtain UID 0.")
            val task = withContext(Dispatchers.Main.immediate) {
                RootService.bindOrTask(intent, ContextCompat.getMainExecutor(context), connection)?.also { startupTask = it }
            }
            val launch = task ?: if (withTimeoutOrNull(200) { ready.await() } == null) startupTask else null
            launch?.let { withContext(Dispatchers.IO) { shell.execTask(it) } }
            withTimeout(10_000) { ready.await() }.also { connected = true }
        } catch (error: TimeoutCancellationException) {
            throw RootConnectionException(ShizukuConnectionStatus.Error, "Root service startup timed out. Retry explicitly.", error)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { shell.close() } }
            if (!connected || !currentCoroutineContext().isActive) {
                abandoned.set(true)
                withContext(NonCancellable + Dispatchers.Main.immediate) { runCatching { RootService.unbind(connection) } }
            }
        }
    }
}
