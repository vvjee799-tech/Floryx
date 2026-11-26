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
        const val Protocol = 3
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
    private val executorFactory: () -> DeviceCommandExecutor = { ProcessDeviceCommandExecutor(processGroup = true, trackCleanup = true) }
) : IFlowChatRootService.Stub(), AutoCloseable {
    private val commands = ManagedCommandRegistry("root-", trackCleanup = true)
    private val requests = linkedMapOf<String, Request>()
    private val finishedRequests = linkedMapOf<String, Request?>()
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
            put("activeRequests", requests.size + finishedRequests.values.count { it != null && !it.cleanupReleased() })
            put("activeCommands", commands.activeResourceCount())
            put("activeDisplays", if (displayOwner == null) 0 else 1)
        }.toString()
    }

    @Synchronized override fun openRequest(requestId: String): IFlowChatShizukuService {
        checkCaller()
        require(RequestId.matches(requestId)) { "Invalid request ID." }
        check(requestId !in cancelled) { "Request was cancelled before it started." }
        check(requestId !in requests) { "Request already exists." }
        check(requestId !in finishedRequests) { "Request has already finished." }
        check(requests.size + finishedRequests.values.count { it != null && !it.cleanupReleased() } < 8) { "Too many device requests." }
        return Request(executorFactory()).also { requests[requestId] = it }
    }

    override fun finishRequest(requestId: String, wasCancelled: Boolean) {
        val request = synchronized(this) {
            checkCaller()
            require(RequestId.matches(requestId)) { "Invalid request ID." }
            // Also retire normally finished IDs: an open racing a finish must never revive it.
            cancelled.add(requestId)
            while (cancelled.size > 128) cancelled.remove(cancelled.first())
            if (requestId !in finishedRequests) finishedRequests[requestId] = requests.remove(requestId)
            finishedRequests[requestId]
        }
        request?.finish(wasCancelled)
        synchronized(this) {
            while (finishedRequests.size > 128) {
                val old = finishedRequests.entries.firstOrNull { it.value?.cleanupReleased() != false } ?: break
                finishedRequests.remove(old.key)
            }
        }
    }

    @Synchronized override fun getRequestCleanup(requestId: String): String {
        checkCaller()
        require(RequestId.matches(requestId)) { "Invalid request ID." }
        check(requestId in requests || requestId in finishedRequests) { "No evidence for this request." }
        val request = requests[requestId] ?: finishedRequests[requestId]
        return (request?.cleanup() ?: JSONObject().apply {
            // A finish-before-open tombstone. The client must independently await its open RPC.
            put("finishReceived", true); put("activeCalls", 0); put("activeCommands", 0)
            put("processGroups", org.json.JSONArray()); put("remainingGroups", org.json.JSONArray())
            put("identityUnknown", false); put("retainedShellCommands", 0)
            put("shellCommandEvidenceVersion", 1); put("shellCommandCancellationRequested", true)
            put("shellCommands", org.json.JSONArray())
        }).put("requestId", requestId).toString()
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
                created = RootExecutionDisplayEndpoint(RootExecutionDisplay.create(requireNotNull(context)), { checkCaller() })
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
        val ownedRequests: List<Request>
        val old = synchronized(this) {
            checkCaller()
            require(RequestId.matches(ownerId)) { "Invalid display owner." }
            closedDisplayOwners.add(ownerId)
            while (closedDisplayOwners.size > 128) closedDisplayOwners.remove(closedDisplayOwners.first())
            ownedRequests = (requests.values + finishedRequests.values.filterNotNull()).distinct().filter { it.ownsDisplay(ownerId) }
            if (displayOwner != ownerId || display == null) null
            else display.also { display = null; displayOwner = null }
        }
        ownedRequests.forEach { it.finish(true) }
        old?.close()
    }

    override fun close() {
        val remaining = synchronized(this) {
            if (closed) return
            closed = true
            (requests.values + finishedRequests.values.filterNotNull()).distinct().also {
                requests.clear(); finishedRequests.clear(); cancelled.clear()
            }
        }
        remaining.forEach { it.finish(true) }
        val oldDisplay = synchronized(this) { display.also { display = null; displayOwner = null } }
        oldDisplay?.close()
        commands.close()
    }

    private inner class Request(private val executor: DeviceCommandExecutor) :
        FlowChatShizukuService(executor, rootCommands = true, commandsAlreadyRoot = true, sharedCommands = commands) {
        @Volatile private var active = true
        private var activeCalls = 0
        private var executionDisplayOwner: String? = null
        private val startedCommands = linkedMapOf<String, () -> com.flowchat.app.domain.terminal.CommandSnapshot>()
        private var shellCommandCancellationRequested = false

        @Synchronized private fun enterDisplayCall() {
            check(active) { "Request has finished." }
            activeCalls++
        }

        @Synchronized private fun exitDisplayCall() { activeCalls-- }
        @Synchronized fun ownsDisplay(ownerId: String) = executionDisplayOwner == ownerId

        override fun getRequestExecutionDisplay(ownerId: String): IFlowChatExecutionDisplay = synchronized(this@RootServiceEndpoint) {
            checkCaller()
            check(displayOwner == ownerId && ownerId !in closedDisplayOwners) { "The task's execution display is unavailable." }
            val current = checkNotNull(display)
            synchronized(this) {
                check(active) { "Request has finished." }
                check(executionDisplayOwner == null || executionDisplayOwner == ownerId) { "Request belongs to another execution display." }
                executionDisplayOwner = ownerId
            }
            current.forRequest(executor, ::enterDisplayCall, ::exitDisplayCall)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            checkCaller()
            synchronized(this) { check(active) { "Request has finished." }; activeCalls++ }
            return try { super.onTransact(code, data, reply, flags) }
            finally { synchronized(this) { activeCalls-- } }
        }

        @Synchronized override fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): String {
            check(active) { "Request has finished." }
            return super.startShellCommand(command, workingDirectory, timeoutSeconds).also {
                val id = JSONObject(it).getString("id")
                startedCommands[id] = commands.observer(id)
            }
        }

        override fun destroy() = finish(true)

        fun finish(cancel: Boolean) {
            val started = synchronized(this) {
                if (!active) return
                active = false
                shellCommandCancellationRequested = cancel
                startedCommands.keys.toList()
            }
            if (cancel) started.forEach { runCatching { commands.cancel(it) } }
            (executor as? AutoCloseable)?.close()
        }

        @Synchronized fun cleanup(): JSONObject {
            val evidence = (executor as? DeviceCommandCleanupSource)?.commandCleanup()
            return JSONObject().apply {
                put("finishReceived", !active && evidence?.closed == true)
                put("activeCalls", activeCalls)
                put("activeCommands", evidence?.activeCommands ?: 0)
                put("processGroups", org.json.JSONArray(evidence?.processGroups ?: emptyList<Int>()))
                put("remainingGroups", org.json.JSONArray(evidence?.remainingGroups ?: emptyList<Int>()))
                put("identityUnknown", evidence == null || evidence.identityUnknown)
                put("retainedShellCommands", startedCommands.size)
                put("shellCommandEvidenceVersion", 1)
                put("shellCommandCancellationRequested", shellCommandCancellationRequested)
                put("shellCommands", org.json.JSONArray(startedCommands.values.map { observe ->
                    JSONObject(kotlinx.serialization.json.Json.encodeToString(
                        com.flowchat.app.domain.terminal.CommandSnapshot.serializer(), observe()))
                }))
            }
        }

        @Synchronized fun cleanupReleased(): Boolean = !active && activeCalls == 0 &&
            (executor as? DeviceCommandCleanupSource)?.commandCleanup()?.released == true &&
            (!shellCommandCancellationRequested || startedCommands.values.all {
                it().cleanup?.state == com.flowchat.app.domain.terminal.CommandCleanupState.Released
            })
    }

    companion object {
        private val RequestId = Regex("[a-fA-F0-9-]{36}")
    }
}
