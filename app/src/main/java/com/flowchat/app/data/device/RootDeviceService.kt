package com.flowchat.app.data.device

import android.content.Intent
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import com.flowchat.app.data.terminal.ManagedCommandRegistry
import com.flowchat.app.shizuku.IFlowChatRootService
import com.flowchat.app.shizuku.IFlowChatShizukuService
import com.flowchat.app.shizuku.IFlowChatExecutionDisplay
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject

/** Bound only by libsu, never exported as an Android service or started at boot. */
@Keep
class RootDeviceService : RootService() {
    private lateinit var endpoint: RootServiceEndpoint

    override fun onCreate() {
        check(Process.myUid() == 0) { "The device service must run as Root." }
        endpoint = RootServiceEndpoint(applicationInfo.uid, this)
    }

    override fun onBind(intent: Intent): IBinder = endpoint

    override fun onDestroy() {
        endpoint.close()
        super.onDestroy()
    }
}

internal data class RootServiceInfo(
    val uid: Int, val pid: Int, val protocol: Int, val activeRequests: Int, val activeCommands: Int,
    val activeDisplays: Int = 0
) {
    companion object {
        const val Protocol = 2
        fun decode(value: String): RootServiceInfo = JSONObject(value).let {
            RootServiceInfo(it.getInt("uid"), it.getInt("pid"), it.getInt("protocol"),
                it.getInt("activeRequests"), it.getInt("activeCommands"), it.optInt("activeDisplays"))
        }
    }
}

/** The transport and every operation binder enforce the original app's exact UID. */
internal class RootServiceEndpoint(
    private val ownerUid: Int,
    private val context: Context? = null,
    private val executorFactory: () -> DeviceCommandExecutor = { ProcessDeviceCommandExecutor(processGroup = true) }
) : IFlowChatRootService.Stub(), AutoCloseable {
    private val commands = ManagedCommandRegistry("root-")
    private val requests = linkedMapOf<String, Request>()
    // A cancellation may arrive on another Binder thread before openRequest.
    private val cancelled = linkedSetOf<String>()
    @Volatile private var closed = false
    private var displayOwner: String? = null
    private var display: RootExecutionDisplayEndpoint? = null
    private val closedDisplayOwners = linkedSetOf<String>()

    private fun checkCaller() {
        check(!closed) { "Root service has closed." }
        if (Binder.getCallingUid() != ownerUid) throw SecurityException("Caller is not the owning app.")
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        checkCaller()
        return super.onTransact(code, data, reply, flags)
    }

    @Synchronized override fun getServiceInfo(): String {
        checkCaller()
        return JSONObject().apply {
            put("uid", Process.myUid())
            put("pid", Process.myPid())
            put("protocol", RootServiceInfo.Protocol)
            put("activeRequests", requests.size)
            put("activeCommands", commands.state.value.count { it.isRunning })
            put("activeDisplays", if (displayOwner == null) 0 else 1)
        }.toString()
    }

    @Synchronized override fun openRequest(requestId: String): IFlowChatShizukuService {
        checkCaller()
        require(RequestId.matches(requestId)) { "Invalid request ID." }
        check(requestId !in cancelled) { "Request was cancelled before it started." }
        check(requestId !in requests) { "Request already exists." }
        check(requests.size < 8) { "Too many device requests." }
        return Request(executorFactory()).also { requests[requestId] = it }
    }

    override fun finishRequest(requestId: String, wasCancelled: Boolean) {
        val request = synchronized(this) {
            checkCaller()
            require(RequestId.matches(requestId)) { "Invalid request ID." }
            if (wasCancelled) {
                cancelled.add(requestId)
                while (cancelled.size > 128) cancelled.remove(cancelled.first())
            }
            requests.remove(requestId)
        }
        request?.finish(wasCancelled)
    }

    override fun openExecutionDisplay(ownerId: String): IFlowChatExecutionDisplay {
        synchronized(this) {
            checkCaller()
            require(RequestId.matches(ownerId)) { "Invalid display owner." }
            check(ownerId !in closedDisplayOwners) { "This execution display was closed. Start a new task." }
            check(displayOwner == null) { "An execution display is already active." }
            check(android.os.Build.VERSION.SDK_INT >= 35) { "Background execution requires Android 15 or newer." }
            displayOwner = ownerId
        }
        var created: RootExecutionDisplayEndpoint? = null
        try {
            val identity = Binder.clearCallingIdentity()
            try {
                created = RootExecutionDisplayEndpoint(RootExecutionDisplay.create(requireNotNull(context))) { checkCaller() }
            } finally { Binder.restoreCallingIdentity(identity) }
            return synchronized(this) {
                check(!closed && displayOwner == ownerId && ownerId !in closedDisplayOwners) { "Display creation was cancelled." }
                requireNotNull(created).also { display = it }
            }
        } catch (error: Throwable) {
            created?.close()
            synchronized(this) { if (displayOwner == ownerId) displayOwner = null }
            throw error
        }
    }

    override fun closeExecutionDisplay(ownerId: String) {
        val old = synchronized(this) {
            checkCaller()
            require(RequestId.matches(ownerId)) { "Invalid display owner." }
            closedDisplayOwners.add(ownerId)
            while (closedDisplayOwners.size > 128) closedDisplayOwners.remove(closedDisplayOwners.first())
            if (displayOwner != ownerId || display == null) null
            else display.also { display = null; displayOwner = null }
        }
        old?.close()
    }

    override fun close() {
        val remaining = synchronized(this) {
            if (closed) return
            closed = true
            requests.values.toList().also { requests.clear(); cancelled.clear() }
        }
        remaining.forEach { it.finish(true) }
        val oldDisplay = synchronized(this) { display.also { display = null; displayOwner = null } }
        oldDisplay?.close()
        commands.close()
    }

    private inner class Request(private val executor: DeviceCommandExecutor) :
        FlowChatShizukuService(executor, rootCommands = true, commandsAlreadyRoot = true, sharedCommands = commands) {
        @Volatile private var active = true
        private val startedCommands = mutableListOf<String>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            checkCaller()
            check(active) { "Request has finished." }
            return super.onTransact(code, data, reply, flags)
        }

        @Synchronized override fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): String {
            check(active) { "Request has finished." }
            return super.startShellCommand(command, workingDirectory, timeoutSeconds).also {
                startedCommands.add(JSONObject(it).getString("id"))
            }
        }

        override fun destroy() = finish(true)

        @Synchronized fun finish(cancel: Boolean) {
            active = false
            if (cancel) startedCommands.forEach { runCatching { commands.cancel(it) } }
            (executor as? AutoCloseable)?.close()
        }
    }

    companion object {
        private val RequestId = Regex("[a-fA-F0-9-]{36}")
    }
}
