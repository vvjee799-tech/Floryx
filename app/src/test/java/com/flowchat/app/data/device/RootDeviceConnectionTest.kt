package com.flowchat.app.data.device

import android.os.DeadObjectException
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.*
import com.flowchat.app.shizuku.IFlowChatShizukuService
import com.flowchat.app.shizuku.IFlowChatExecutionDisplay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RootDeviceConnectionTest {
    private class Session(val pid: Int = 4242) : RootServiceSession {
        override var alive = true
        var uid = 0
        var protocol = RootServiceInfo.Protocol
        var activeCommands = 0
        var closes = 0
        var probes = 0
        var reads = 0
        val open = mutableSetOf<String>()
        val cancelled = mutableListOf<String>()
        var onCancel: () -> Unit = {}
        var onOpenDisplay: () -> IFlowChatExecutionDisplay = { error("No test display configured") }
        val closedDisplays = mutableListOf<String>()
        var beforeRead: () -> Unit = {}
        val service = object : FlowChatShizukuService(DeviceCommandExecutor { DeviceCommandResult(true, "level: 50") }) {
            override fun probeCapabilities(): String { probes++; return DeviceCapability.DeviceStatus.name }
            override fun getDeviceStatus(): String { reads++; beforeRead(); return "battery=50%" }
        }
        override fun info() = RootServiceInfo(uid, pid, protocol, open.size, activeCommands)
        override fun openRequest(id: String): IFlowChatShizukuService { synchronized(open) { check(open.add(id)) }; return service }
        override fun finishRequest(id: String, cancelled: Boolean) {
            synchronized(open) { open.remove(id) }
            if (cancelled) { this.cancelled.add(id); onCancel() }
        }
        override suspend fun close() { alive = false; closes++ }
        override fun openExecutionDisplay(id: String) = onOpenDisplay()
        override fun closeExecutionDisplay(id: String) { closedDisplays.add(id); onCancel() }
    }

    @Test fun selectedConnectionModeSurvivesSettingsReload() {
        val context = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(context)
        store.setPowerConnectionMode(PowerConnectionMode.Root)
        store.setPowerModeEnabled(true)
        store.setBackgroundExecutionEnabled(true)
        val restored = AppSettingsStore(context)
        assertEquals(PowerConnectionMode.Root, restored.state.value.powerConnectionMode)
        assertTrue(restored.state.value.powerModeEnabled)
        assertTrue(restored.state.value.backgroundExecutionEnabled)
    }

    private fun testDisplay() = object : IFlowChatExecutionDisplay.Stub() {
        override fun getInfo() = "{}"
        override fun launchApp(packageName: String, activityName: String) = "{}"
        override fun tap(x: Int, y: Int, longPress: Boolean) = true
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Int) = true
        override fun pressBack() = true
        override fun clearApps() = true
    }

    @Test fun displayLeasePinsIdleConnectionAndReleasesOnlyOnce() = runTest {
        val session = Session().apply { onOpenDisplay = { testDisplay() } }
        val root = RootDeviceConnection({ session }, backgroundScope)
        root.refresh(requestPermission = true)
        val id = java.util.UUID.randomUUID().toString()
        val lease = root.openExecutionDisplay(id)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(0, session.closes)
        lease.close(); lease.close()
        assertEquals(listOf(id), session.closedDisplays)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(1, session.closes)
        assertFalse(lease.alive)
    }

    @Test fun displayCreationCancellationUsesSideChannelAndUnpinsConnection() = runBlocking {
        val manager = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val session = Session().apply {
            onOpenDisplay = { entered.complete(Unit); check(gate.await(10, TimeUnit.SECONDS)); testDisplay() }
            onCancel = { gate.countDown() }
        }
        val root = RootDeviceConnection({ session }, manager)
        try {
            root.refresh(requestPermission = true)
            val id = java.util.UUID.randomUUID().toString()
            val pending = launch { root.openExecutionDisplay(id) }
            withTimeout(5_000) { entered.await() }
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertEquals(listOf(id), session.closedDisplays)
            assertEquals("battery=50%", root.withService { it.getDeviceStatus() })
        } finally { gate.countDown(); root.release(); manager.cancel() }
    }

    @Test fun passiveRefreshDoesNotPromptAndDenialRequiresExplicitRetry() = runTest {
        var attempts = 0
        val root = RootDeviceConnection({ attempts++; throw RootConnectionException(ShizukuConnectionStatus.RootPermissionDenied, "denied") }, backgroundScope)
        root.refresh()
        assertEquals(0, attempts)
        root.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.RootPermissionDenied, root.state.value.status)
        root.refresh()
        root.invalidate()
        root.refresh()
        assertEquals(1, attempts)
        root.refresh(requestPermission = true)
        assertEquals(2, attempts)
        assertFalse(root.state.value.isConnected)
    }

    @Test fun startupFailureCanRecoverWithoutInspectingSuFileVisibility() = runTest {
        var available = false
        val session = Session()
        val root = RootDeviceConnection({
            if (!available) throw RootConnectionException(ShizukuConnectionStatus.RootUnavailable, "Cannot launch su")
            session
        }, backgroundScope)
        root.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.RootUnavailable, root.state.value.status)
        available = true
        root.refresh()
        assertEquals(ShizukuConnectionStatus.ConnectedDirectRoot, root.state.value.status)
        root.release()
    }

    @Test fun identityAndProtocolMismatchNeverPublishCapabilities() = runTest {
        for (badIdentity in listOf(true, false)) {
            val session = Session().apply { if (badIdentity) uid = 2000 else protocol = 99 }
            val root = RootDeviceConnection({ session }, backgroundScope)
            root.refresh(requestPermission = true)
            assertFalse(root.state.value.isConnected)
            assertTrue(root.state.value.capabilities.isEmpty())
            assertEquals(0, session.probes)
            assertEquals(1, session.closes)
        }
    }

    @Test fun concurrentCallsReuseOneProcessButReleaseEachRequest() = runTest {
        var starts = 0
        val session = Session()
        val root = RootDeviceConnection({ starts++; session }, backgroundScope)
        root.refresh(requestPermission = true)
        List(12) { async { root.withService { it.getDeviceStatus() } } }.awaitAll().forEach { assertEquals("battery=50%", it) }
        assertEquals(1, starts)
        assertEquals(1, session.probes)
        assertEquals(12, session.reads)
        assertTrue(session.open.isEmpty())
        root.release()
    }

    @Test fun probeExpiryAndInvalidationDoNotLaunchMoreSuProcesses() = runTest {
        var starts = 0
        var now = 0L
        val session = Session()
        val root = RootDeviceConnection({ starts++; session }, backgroundScope, clockMillis = { now })
        root.refresh(requestPermission = true)
        repeat(5) { root.withService { it.getDeviceStatus() } }
        assertEquals(1, session.probes)
        now = 30_000
        root.withService { it.getDeviceStatus() }
        root.invalidate()
        root.withService { it.getDeviceStatus() }
        assertEquals(3, session.probes)
        assertEquals(1, starts)
        root.release()
    }

    @Test fun idleReleaseDoesNotReconnectOnResumeButNextOperationDoes() = runTest {
        var starts = 0
        val sessions = mutableListOf<Session>()
        val root = RootDeviceConnection({ Session(++starts).also(sessions::add) }, backgroundScope, clockMillis = { testScheduler.currentTime })
        root.refresh(requestPermission = true)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, sessions[0].closes)
        assertEquals(ShizukuConnectionStatus.RootReady, root.state.value.status)
        assertTrue(root.state.value.isConnected)
        root.refresh()
        assertEquals(1, starts)
        root.withService { it.getDeviceStatus() }
        assertEquals(2, starts)
        root.release()
        root.refresh()
        assertEquals(2, starts)
        assertEquals(ShizukuConnectionStatus.RootPermissionRequired, root.state.value.status)
    }

    @Test fun runningCommandPinsServiceUntilItFinishes() = runTest {
        val session = Session().apply { activeCommands = 1 }
        val root = RootDeviceConnection({ session }, backgroundScope)
        root.refresh(requestPermission = true)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(0, session.closes)
        session.activeCommands = 0
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, session.closes)
    }

    @Test fun deadOperationIsNotReplayedAndStaleDeathDoesNotDropNewSession() = runTest {
        val deaths = mutableListOf<() -> Unit>()
        val sessions = mutableListOf<Session>()
        val root = RootDeviceConnection({ death -> deaths.add(death); Session(sessions.size + 1).also(sessions::add) }, backgroundScope)
        root.refresh(requestPermission = true)
        val first = sessions.single()
        first.beforeRead = { first.alive = false; throw DeadObjectException() }
        try { root.withService { it.getDeviceStatus() }; fail("Dead operation must fail") } catch (_: DeadObjectException) { }
        assertEquals(1, first.reads)
        assertEquals(1, sessions.size)
        assertTrue(root.state.value.capabilities.isEmpty())
        assertEquals("battery=50%", root.withService { it.getDeviceStatus() })
        deaths.first()()
        runCurrent()
        assertEquals(2, sessions.size)
        assertEquals(0, sessions.last().closes)
        root.release()
    }

    @Test fun cancellationUsesSideChannelWhileRpcIsBlocked() = runBlocking {
        val manager = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val session = Session().apply {
            beforeRead = { entered.complete(Unit); check(gate.await(5, TimeUnit.SECONDS)) }
            onCancel = { gate.countDown() }
        }
        val root = RootDeviceConnection({ session }, manager)
        try {
            root.refresh(requestPermission = true)
            val caller = launch { root.withService { it.getDeviceStatus() } }
            withTimeout(5_000) { entered.await() }
            withTimeout(2_000) { caller.cancelAndJoin() }
            assertEquals(1, session.cancelled.size)
            assertEquals(1, session.reads)
            assertTrue(session.open.isEmpty())
            session.beforeRead = {}
            assertEquals("battery=50%", root.withService { it.getDeviceStatus() })
        } finally { gate.countDown(); root.release(); manager.cancel() }
    }

    @Test fun releaseCancelsPendingStartupAndClearsConnectingState() = runTest {
        val entered = CompletableDeferred<Unit>()
        val root = RootDeviceConnection({ entered.complete(Unit); awaitCancellation() }, backgroundScope)
        val pending = launch { root.refresh(requestPermission = true) }
        entered.await()
        root.release()
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(ShizukuConnectionStatus.RootPermissionRequired, root.state.value.status)
        assertNull(root.serviceInfo())
    }

    @Test fun cancelledIdleReconnectStaysReadyAndCanBeRetriedByANewOperation() = runTest {
        var starts = 0
        val entered = CompletableDeferred<Unit>()
        val root = RootDeviceConnection({
            starts++
            if (starts == 2) { entered.complete(Unit); awaitCancellation() }
            Session(starts)
        }, backgroundScope, clockMillis = { testScheduler.currentTime })
        root.refresh(requestPermission = true)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        val pending = launch { root.withService { it.getDeviceStatus() } }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(ShizukuConnectionStatus.RootReady, root.state.value.status)
        assertNull(root.serviceInfo())
        root.refresh()
        assertEquals(2, starts)
        assertEquals("battery=50%", root.withService { it.getDeviceStatus() })
        assertEquals(3, starts)
        root.release()
    }

    @Test fun reauthorizationAfterDisconnectedServiceFailsClearsCapabilities() = runTest {
        var granted = true
        val root = RootDeviceConnection({
            if (!granted) throw RootConnectionException(ShizukuConnectionStatus.RootPermissionDenied, "denied")
            Session()
        }, backgroundScope)
        root.refresh(requestPermission = true)
        root.release()
        granted = false
        root.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.RootPermissionDenied, root.state.value.status)
        assertTrue(root.state.value.capabilities.isEmpty())
    }
}
