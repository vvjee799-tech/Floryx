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
        @Volatile override var alive = true
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
        var onDisplayOpenFinished: () -> Unit = {}
        var beforeCloseDisplay: () -> Unit = {}
        val closedDisplays = java.util.Collections.synchronizedList(mutableListOf<String>())
        val activeDisplayOwners = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val lifecycleEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val cancelledDisplayOwners = mutableSetOf<String>()
        var beforeRead: () -> Unit = {}
        var cleanupReadable = true
        var onDisplayCommand: (String) -> IFlowChatExecutionDisplay = { onOpenDisplay() }
        val service = object : FlowChatShizukuService(DeviceCommandExecutor { DeviceCommandResult(true, "level: 50") }) {
            override fun probeCapabilities(): String { probes++; return DeviceCapability.DeviceStatus.name }
            override fun getDeviceStatus(): String { reads++; beforeRead(); return "battery=50%" }
            override fun getRequestExecutionDisplay(ownerId: String) = onDisplayCommand(ownerId)
        }
        override fun info() = RootServiceInfo(uid, pid, protocol, open.size, activeCommands, activeDisplayOwners.size)
        override fun openRequest(id: String): IFlowChatShizukuService { synchronized(open) { check(open.add(id)) }; return service }
        override fun finishRequest(id: String, cancelled: Boolean) {
            synchronized(open) { open.remove(id) }
            if (cancelled) { this.cancelled.add(id); onCancel() }
        }
        override fun requestCleanup(id: String): String {
            check(cleanupReadable) { "Original cleanup connection unavailable" }
            return org.json.JSONObject().apply {
                put("requestId", id); put("finishReceived", id !in open); put("activeCalls", 0)
                put("activeCommands", 0); put("identityUnknown", false); put("retainedShellCommands", 0)
                put("processGroups", org.json.JSONArray()); put("remainingGroups", org.json.JSONArray())
            }.toString()
        }
        override suspend fun close() { lifecycleEvents.add("session-close"); alive = false; closes++ }
        override fun openExecutionDisplay(id: String): IFlowChatExecutionDisplay = try {
            val remote = onOpenDisplay()
            synchronized(cancelledDisplayOwners) {
                check(id !in cancelledDisplayOwners) { "Display owner was closed before creation finished." }
                activeDisplayOwners.add(id)
            }
            remote
        } finally { onDisplayOpenFinished() }
        override fun closeExecutionDisplay(id: String) {
            beforeCloseDisplay()
            synchronized(cancelledDisplayOwners) {
                closedDisplays.add(id)
                lifecycleEvents.add("display-close:$id")
                cancelledDisplayOwners.add(id)
                activeDisplayOwners.remove(id)
            }
            onCancel()
        }
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

    @Test fun failedCleanupBlocksNewOperationsAndObservationDoesNotReconnectOrReplay() = runBlocking {
        val session = Session()
        var connections = 0
        // The connection uses real IO. Auto-advancing virtual idle timers while IO is
        // pending can close this one-shot fake session before the next assertion.
        val manager = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection({ connections++; session }, manager)
        try {
            root.refresh(requestPermission = true)
            session.cleanupReadable = false
            assertEquals("battery=50%", root.withService(ownerId = "A") { it.getDeviceStatus() })
            assertEquals(RootRequestState.Unverified, root.rootRequestCleanup.value.values.single().state)
            assertThrows(IllegalStateException::class.java) { runBlocking { root.withService { it.getDeviceStatus() } } }
            assertEquals(1, session.reads)
            assertEquals(1, connections)
            root.refresh(requestPermission = true, force = true)
            assertTrue("A cleanup gate must not detach the live original during a refresh", session.alive)
            assertEquals(0, session.closes)
            root.recheckRootRequestCleanup()
            assertEquals(1, connections)
            session.cleanupReadable = true
            root.recheckRootRequestCleanup()
            assertEquals(RootRequestState.Released, root.rootRequestCleanup.value.values.single().state)
            assertEquals("battery=50%", root.withService(ownerId = "B") { it.getDeviceStatus() })
            assertEquals(2, session.reads)
        } finally { root.release(); manager.cancel() }
    }

    @Test fun cancelledDeferredCannotReportReleaseWhileTheSynchronousOperationIsStillBlocked() = runBlocking {
        val manager = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val session = Session().apply { beforeRead = { entered.complete(Unit); check(gate.await(5, TimeUnit.SECONDS)) } }
        val root = RootDeviceConnection({ session }, manager)
        try {
            root.refresh(requestPermission = true)
            val caller = launch { root.withService(ownerId = "A") { it.getDeviceStatus() } }
            withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { caller.cancelAndJoin() }
            assertEquals(RootRequestCleanupReason.RpcPending, root.rootRequestCleanup.value.values.single().reason)
            root.recheckRootRequestCleanup()
            assertEquals(RootRequestState.Unverified, root.rootRequestCleanup.value.values.single().state)
            gate.countDown()
            withTimeout(3_000) {
                while (root.rootRequestCleanup.value.values.single().state != RootRequestState.Released) {
                    root.recheckRootRequestCleanup(); delay(10)
                }
            }
            assertEquals(1, session.reads)
        } finally { gate.countDown(); root.release(); manager.cancel() }
    }

    @Test fun failedCloseAndNullConnectionRemainUnverifiedUntilTheExactSystemDisplayDisappears() = runTest {
        var present = true
        var connections = 0
        val session = Session().apply {
            onOpenDisplay = { testDisplay() }
            beforeCloseDisplay = { error("Simulated close failure") }
        }
        val root = RootDeviceConnection({ connections++; session }, backgroundScope, displayPresent = { present })
        root.refresh(requestPermission = true)
        val owner = java.util.UUID.randomUUID().toString()
        val lease = root.openExecutionDisplay(owner)
        lease.close()
        assertEquals(ExecutionDisplayState.Unverified, root.executionDisplayCleanup.value[owner]?.state)
        assertTrue(runCatching { root.openExecutionDisplay(java.util.UUID.randomUUID().toString()) }.isFailure)
        root.release()
        assertNull(root.serviceInfo())
        assertEquals(ExecutionDisplayState.Unverified, root.executionDisplayCleanup.value[owner]?.state)
        assertEquals(1, connections)
        present = false
        root.recheckExecutionDisplayCleanup()
        assertEquals(ExecutionDisplayState.Released, root.executionDisplayCleanup.value[owner]?.state)
        assertEquals(1, connections)
    }

    private fun testDisplay(onTap: () -> Boolean = { true }) = object : IFlowChatExecutionDisplay.Stub() {
        override fun getInfo() = "{\"displayId\":7}"
        override fun launchApp(packageName: String, activityName: String) = "{}"
        override fun tap(x: Int, y: Int, longPress: Boolean) = onTap()
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Int) = true
        override fun pressBack() = true
        override fun clearApps() = true
    }

    @Test fun displayCommandsUseTheDisplayOwnerAndUnknownCleanupBlocksReuseWithoutReplaying() = runTest {
        val owner = java.util.UUID.randomUUID().toString()
        var dispatches = 0
        var connections = 0
        val session = Session().apply {
            onOpenDisplay = { testDisplay() }
            onDisplayCommand = { requested -> assertEquals(owner, requested); testDisplay { dispatches++; true } }
        }
        val root = RootDeviceConnection({ connections++; session }, backgroundScope, displayPresent = { false })
        try {
            root.refresh(requestPermission = true)
            val lease = root.openExecutionDisplay(owner)
            session.cleanupReadable = false
            assertTrue(lease.command { it.tap(10, 10, false) })
            val record = root.rootRequestCleanup.value.values.single()
            assertEquals(owner, record.ownerId)
            assertEquals(RootRequestState.Unverified, record.state)
            assertTrue(runCatching { lease.command { it.tap(10, 10, false) } }.isFailure)
            assertTrue(runCatching { root.withService { it.getDeviceStatus() } }.isFailure)
            repeat(3) { root.recheckRootRequestCleanup() }
            assertEquals(1, dispatches)
            assertEquals(1, connections)
            session.cleanupReadable = true
            root.recheckRootRequestCleanup()
            assertEquals(RootRequestState.Released, root.rootRequestCleanup.value[record.requestId]?.state)
            assertTrue(lease.command { it.tap(10, 10, false) })
            assertEquals(2, dispatches)
            lease.close()
            assertTrue(runCatching { lease.command { it.tap(10, 10, false) } }.isFailure)
            assertEquals(2, dispatches)
        } finally { root.release() }
    }

    @Test fun cancelledDisplayRpcRemainsUnverifiedUntilTheOriginalCallActuallyReturns() = runBlocking {
        val manager = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        var dispatches = 0
        val session = Session().apply {
            onOpenDisplay = { testDisplay() }
            onDisplayCommand = { testDisplay { dispatches++; entered.complete(Unit); check(gate.await(5, TimeUnit.SECONDS)); true } }
        }
        val root = RootDeviceConnection({ session }, manager, displayPresent = { false })
        try {
            root.refresh(requestPermission = true)
            val owner = java.util.UUID.randomUUID().toString()
            val lease = root.openExecutionDisplay(owner)
            val caller = launch { lease.command { it.tap(10, 10, false) } }
            withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { caller.cancelAndJoin() }
            val record = root.rootRequestCleanup.value.values.single()
            assertEquals(owner, record.ownerId)
            assertEquals(RootRequestCleanupReason.RpcPending, record.reason)
            lease.close()
            assertEquals(ExecutionDisplayState.Released, root.executionDisplayCleanup.value[owner]?.state)
            // Display disappearance is not proof that its still-blocked request has returned.
            assertTrue(runCatching { root.openExecutionDisplay(java.util.UUID.randomUUID().toString()) }.isFailure)
            gate.countDown()
            withTimeout(3_000) {
                while (root.rootRequestCleanup.value[record.requestId]?.state != RootRequestState.Released) {
                    root.recheckRootRequestCleanup(); delay(10)
                }
            }
            assertEquals(1, dispatches)
        } finally { gate.countDown(); root.release(); manager.cancel() }
    }

    @Test fun detachedDisplayCannotSendCommandsToAReplacementConnection() = runTest {
        var dispatches = 0
        var connections = 0
        val root = RootDeviceConnection({
            connections++
            Session().apply {
                onOpenDisplay = { testDisplay() }
                onDisplayCommand = { testDisplay { dispatches++; true } }
            }
        }, backgroundScope, displayPresent = { false })
        try {
            root.refresh(requestPermission = true)
            val old = root.openExecutionDisplay(java.util.UUID.randomUUID().toString())
            root.release()
            root.refresh(requestPermission = true)
            val current = root.openExecutionDisplay(java.util.UUID.randomUUID().toString())
            assertTrue(runCatching { old.command { it.tap(10, 10, false) } }.isFailure)
            assertTrue(current.command { it.tap(10, 10, false) })
            assertEquals(1, dispatches)
            assertEquals(2, connections)
        } finally { root.release() }
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
            assertEquals(0, requireNotNull(root.serviceInfo()).activeDisplays)
            // No display identity was delivered. Zero server handles does not prove system removal.
            assertTrue(root.executionDisplayCleanup.value.getValue(id).blocksReuse)
            assertTrue(runCatching { root.withService { it.getDeviceStatus() } }.isFailure)
        } finally { gate.countDown(); root.release(); manager.cancel() }
    }

    @Test fun explicitReleaseClosesOwnedDisplaysBeforeUnbindingAndOnlyOnce() = runTest {
        var connects = 0
        val session = Session().apply { onOpenDisplay = { testDisplay() } }
        val root = RootDeviceConnection({ connects++; session }, backgroundScope)
        root.refresh(requestPermission = true)
        val id = java.util.UUID.randomUUID().toString()
        val otherOwner = java.util.UUID.randomUUID().toString()
        val lease = root.openExecutionDisplay(id)
        session.activeDisplayOwners.add(otherOwner) // The transport must never close an untracked owner.
        root.release()
        assertEquals(listOf(id), session.closedDisplays)
        assertEquals(setOf(otherOwner), session.activeDisplayOwners)
        assertEquals(listOf("display-close:$id", "session-close"), session.lifecycleEvents)
        assertFalse(lease.alive)
        lease.close(); root.release(); lease.close()
        assertEquals(listOf(id), session.closedDisplays)
        assertEquals(1, session.closes)
        assertEquals(1, connects)
        assertNull(root.serviceInfo())
    }

    @Test fun releaseClosesAPendingOwnerBeforeItsLazyOpenCanStart() = runTest {
        var opens = 0
        val session = Session().apply { onOpenDisplay = { opens++; testDisplay() } }
        val root = RootDeviceConnection({ session }, backgroundScope)
        root.refresh(requestPermission = true)
        val id = java.util.UUID.randomUUID().toString()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { root.openExecutionDisplay(id) } }
        root.release()
        runCurrent()
        assertTrue(pending.await().isFailure)
        assertEquals(0, opens)
        assertEquals(listOf(id), session.closedDisplays)
        assertTrue(session.activeDisplayOwners.isEmpty())
        assertEquals(listOf("display-close:$id", "session-close"), session.lifecycleEvents)
    }

    @Test fun releaseTombstonesAnInFlightOpenSoLateCreationCannotResurrectADisplay() = runBlocking {
        val manager = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val session = Session().apply {
            onOpenDisplay = { entered.complete(Unit); check(gate.await(10, TimeUnit.SECONDS)); testDisplay() }
            onDisplayOpenFinished = { finished.complete(Unit) }
            onCancel = { gate.countDown() }
        }
        var connects = 0
        val root = RootDeviceConnection({ connects++; session }, manager)
        try {
            root.refresh(requestPermission = true)
            val id = java.util.UUID.randomUUID().toString()
            val pending = async { runCatching { root.openExecutionDisplay(id) } }
            withTimeout(5_000) { entered.await() }
            withTimeout(2_000) { root.release() }
            assertTrue(withTimeout(2_000) { pending.await() }.isFailure)
            withTimeout(2_000) { finished.await() }
            assertEquals(listOf(id), session.closedDisplays)
            assertTrue(session.activeDisplayOwners.isEmpty())
            assertEquals(listOf("display-close:$id", "session-close"), session.lifecycleEvents)
            assertEquals(1, connects)
            assertNull(root.serviceInfo())
        } finally { gate.countDown(); root.release(); manager.cancel() }
    }

    @Test fun closingAnOldDisplayLeaseCannotCloseANewConnectionsDisplay() = runTest {
        val sessions = mutableListOf<Session>()
        val root = RootDeviceConnection({ Session(sessions.size + 1).apply {
            onOpenDisplay = { testDisplay() }
        }.also(sessions::add) }, backgroundScope, displayPresent = { false })
        root.refresh(requestPermission = true)
        val oldId = java.util.UUID.randomUUID().toString()
        val oldLease = root.openExecutionDisplay(oldId)
        root.release()
        root.refresh(requestPermission = true)
        val newId = java.util.UUID.randomUUID().toString()
        val newLease = root.openExecutionDisplay(newId)
        oldLease.close()
        assertEquals(listOf(oldId), sessions[0].closedDisplays)
        assertTrue(sessions[1].closedDisplays.isEmpty())
        assertEquals(setOf(newId), sessions[1].activeDisplayOwners)
        assertTrue(newLease.alive)
        root.release()
        newLease.close()
        assertEquals(listOf(newId), sessions[1].closedDisplays)
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

    @Test fun idleReadyReconnectionPreservesTheSafetyRevisionAndLastVerification() = runTest {
        val boundary = DeviceSafetyBoundary()
        val sessions = mutableListOf<Session>()
        val root = RootDeviceConnection({ Session(sessions.size + 1).also(sessions::add) }, backgroundScope,
            clockMillis = { testScheduler.currentTime }, safetyBoundary = boundary)
        try {
            root.refresh(requestPermission = true)
            val originalRevision = boundary.state.value.revision
            val verifiedAt = requireNotNull(root.state.value.lastVerifiedAtMillis)
            val capabilities = root.state.value.capabilities
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()

            assertEquals(ShizukuConnectionStatus.RootReady, root.state.value.status)
            assertEquals(verifiedAt, root.state.value.lastVerifiedAtMillis)
            assertEquals(capabilities, root.state.value.capabilities)
            assertEquals(originalRevision, boundary.state.value.revision)
            root.refresh()
            assertEquals(1, sessions.size)
            assertEquals(verifiedAt, root.state.value.lastVerifiedAtMillis)

            assertEquals("battery=50%", root.withService {
                boundary.requireCurrent(originalRevision)
                it.getDeviceStatus()
            })
            assertEquals(2, sessions.size)
            assertEquals(1, sessions.last().reads)
            assertEquals(ShizukuConnectionStatus.ConnectedDirectRoot, root.state.value.status)
            assertNotNull(root.state.value.lastVerifiedAtMillis)
            assertEquals(originalRevision, boundary.state.value.revision)
            assertNull(boundary.state.value.reason)
        } finally { root.release() }
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

    @Test fun deadSessionBeforeItsDeathCallbackInvalidatesTheOldPlanBeforeDispatch() = runTest {
        val boundary = DeviceSafetyBoundary()
        val deaths = mutableListOf<() -> Unit>()
        val sessions = mutableListOf<Session>()
        val root = RootDeviceConnection({ death ->
            deaths.add(death)
            Session(sessions.size + 1).also(sessions::add)
        }, backgroundScope, safetyBoundary = boundary)
        try {
            root.refresh(requestPermission = true)
            val oldRevision = boundary.state.value.revision
            val oldSession = sessions.single()
            // Binder liveness can be observed before the queued death callback runs.
            oldSession.alive = false
            try {
                root.withService {
                    boundary.requireCurrent(oldRevision)
                    it.getDeviceStatus()
                }
                fail("A recovered service must not dispatch the old plan")
            } catch (_: CancellationException) { }

            assertEquals(2, sessions.size)
            assertEquals(1, oldSession.closes)
            assertEquals(0, oldSession.reads)
            assertEquals(0, sessions.last().reads)
            val recoveredRevision = boundary.state.value.revision
            assertTrue(recoveredRevision > oldRevision)
            assertEquals(DeviceSafetyReason.PowerConnectionLost, boundary.state.value.reason)

            deaths.first()()
            runCurrent()
            assertEquals(recoveredRevision, boundary.state.value.revision)
            assertEquals(0, sessions.last().closes)
            assertEquals(ShizukuConnectionStatus.ConnectedDirectRoot, root.state.value.status)
            assertEquals("battery=50%", root.withService {
                boundary.requireCurrent(recoveredRevision)
                it.getDeviceStatus()
            })
            assertEquals(1, sessions.last().reads)
        } finally { root.release() }
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
