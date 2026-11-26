package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import com.flowchat.app.shizuku.IFlowChatShizukuService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RootConnectionLossTest {
    private class Session : RootServiceSession {
        override var alive = true
        var beforeRead: () -> Unit = {}
        val cleanupReads = AtomicInteger()
        val service = object : FlowChatShizukuService(DeviceCommandExecutor { DeviceCommandResult(true, "") }) {
            override fun probeCapabilities() = DeviceCapability.DeviceStatus.name
            override fun getDeviceStatus(): String { beforeRead(); return "battery=50%" }
        }
        override fun info() = RootServiceInfo(0, 42, RootServiceInfo.Protocol, 0, 0)
        override fun openRequest(id: String): IFlowChatShizukuService = service
        override fun finishRequest(id: String, cancelled: Boolean) = Unit
        override fun requestCleanup(id: String): String {
            cleanupReads.incrementAndGet()
            return JSONObject().apply {
                put("requestId", id); put("finishReceived", true); put("activeCalls", 0)
                put("activeCommands", 0); put("identityUnknown", false); put("retainedShellCommands", 0)
                put("processGroups", JSONArray()); put("remainingGroups", JSONArray())
            }.toString()
        }
        override suspend fun close() { alive = false }
    }

    @Test fun detachingInFlightRequestImmediatelyBlocksReconnectAndRejectsItsLateSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val reply = CountDownLatch(1)
        val connects = AtomicInteger()
        val session = Session().apply { beforeRead = { entered.complete(Unit); check(reply.await(5, TimeUnit.SECONDS)) } }
        val root = RootDeviceConnection({ connects.incrementAndGet(); session }, scope)
        try {
            root.refresh(requestPermission = true)
            val caller = scope.async { root.withService(ownerId = "owner") { it.getDeviceStatus() } }
            withTimeout(2_000) { entered.await() }
            // No death callback is delivered. The dispatch gate must still detect the dead original.
            session.alive = false
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            val lost = root.rootRequestCleanup.value.values.single()
            assertEquals(RootRequestState.Unverified, lost.state)
            assertEquals(RootRequestCleanupReason.OriginalConnectionUnavailable, lost.reason)
            root.release()
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            root.refresh(requestPermission = true, force = true)
            assertEquals(1, connects.get())
            reply.countDown()
            assertTrue(withTimeout(2_000) { runCatching { caller.await() }.isFailure })
            repeat(3) { root.recheckRootRequestCleanup() }
            assertEquals(lost, root.rootRequestCleanup.value.values.single())
            assertEquals(0, session.cleanupReads.get())
            assertEquals(1, connects.get())
        } finally { reply.countDown(); root.release(); scope.cancel() }
    }

    @Test fun unverifiedDisplayAlsoBlocksForegroundRequestsAndCapabilityReconnect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connects = AtomicInteger()
        var present = true
        val base = Session()
        val display = object : com.flowchat.app.shizuku.IFlowChatExecutionDisplay.Stub() {
            override fun getInfo() = "{\"displayId\":7}"
            override fun launchApp(packageName: String, activityName: String) = "{}"
            override fun tap(x: Int, y: Int, longPress: Boolean) = true
            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Int) = true
            override fun pressBack() = true
            override fun clearApps() = true
        }
        val root = RootDeviceConnection({
            connects.incrementAndGet()
            object : RootServiceSession by base {
                override fun openExecutionDisplay(id: String) = display
            }
        }, scope, displayPresent = { present })
        try {
            root.refresh(requestPermission = true)
            val lease = root.openExecutionDisplay("owner")
            lease.close()
            assertTrue(runCatching { root.withService { it.getDeviceStatus() } }.isFailure)
            root.release()
            root.refresh(requestPermission = true, force = true)
            assertEquals(1, connects.get())
            root.recheckExecutionDisplayCleanup()
            assertTrue(runCatching { root.requireRequestCleanupVerified() }.isFailure)
            present = false
            root.recheckExecutionDisplayCleanup()
            root.requireRequestCleanupVerified()
            assertEquals(1, connects.get())
        } finally { root.release(); scope.cancel() }
    }
}
