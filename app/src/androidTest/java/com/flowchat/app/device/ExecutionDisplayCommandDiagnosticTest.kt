package com.flowchat.app.device

import android.app.UiAutomation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.diagnostics.RootDisplayCommandDiagnosticsService
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.*
import com.flowchat.app.shizuku.IFlowChatExecutionDisplay
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real display commands, exact requests and independent group checks. All data is synthetic. */
class ExecutionDisplayCommandDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun enabled() = check(InstrumentationRegistry.getArguments().getString("runExecutionDisplayCommandDiagnostics") == "true")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun report(value: Any) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nDISPLAY_COMMAND_R7F $value\n") })
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(50) }

    private suspend fun released(root: RootDeviceConnection, owner: String, minimumGroups: Int = 1): List<RootRequestCleanup> {
        until {
            root.recheckRootRequestCleanup()
            root.rootRequestCleanup.value.values.filter { it.ownerId == owner }.let {
                it.isNotEmpty() && it.all { record -> record.state == RootRequestState.Released }
            }
        }
        val records = root.rootRequestCleanup.value.values.filter { it.ownerId == owner }
        val groups = records.flatMap { it.processGroups }.distinct()
        assertTrue("Expected actual owned command groups: $records", groups.size >= minimumGroups)
        val listing = shell("ps -A -o PID,PGID,STAT")
        val present = listing.lineSequence().drop(1).mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }.toSet()
        assertTrue("Owned process groups remain: $groups", groups.none { it in present })
        assertTrue(records.all { it.reason == null && it.checkedAtMillis != null })
        report("owner=$owner evidence=$records independentGroupsAbsent=true")
        return records
    }

    private suspend fun displayGone(root: RootDeviceConnection, owner: String, id: Int) {
        until { root.recheckExecutionDisplayCleanup(); root.executionDisplayCleanup.value[owner]?.state == ExecutionDisplayState.Released }
        assertNull(context.getSystemService(DisplayManager::class.java).getDisplay(id))
        assertNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(0))
        report("owner=$owner display=$id systemDisplayAbsent=true physicalDisplayPreserved=true")
    }

    @Test fun productionGatewayCommandsKeepTheirPhoneTaskOwnerThroughFinish() = runBlocking<Unit> {
        enabled()
        ActionOutcomeServiceReadiness().ensureService()
        val entry = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        val tasks = entry.deviceTasks()
        check(tasks.currentTask == null && entry.runtime().activeConversations.value.isEmpty()) { "Do not interrupt user work" }
        val settings = entry.settings()
        val saved = settings.state.value
        val gateway = entry.deviceGateway()
        val root = entry.root()
        var task: DeviceTaskControl.Task? = null
        try {
            settings.setPowerModeEnabled(true)
            settings.setPowerConnectionMode(PowerConnectionMode.Root)
            settings.setBackgroundExecutionEnabled(true)
            until { gateway.backgroundExecutionRequested }
            root.refresh(requestPermission = true)
            task = tasks.begin().also(tasks::arm)
            val opened = gateway.launchApp("${context.packageName}.test", ExecutionDisplayFixtureActivity::class.java.name)
            assertTrue(opened.asToolContent(), opened.success)
            val id = requireNotNull(opened.screen?.geometry?.displayId)
            assertTrue(id > 0)
            val first = released(root, task.id)
            assertEquals(1, first.size)
            val input = opened.screen!!.elements.single { it.contentDescription == "execution_fixture_input" }
            val typed = gateway.inputText(input.index, "r7f-local-result", true, opened.screen!!.screenId)
            assertTrue(typed.asToolContent(), typed.success)
            val observed = gateway.observeScreen()
            assertTrue(observed.asToolContent(), observed.success)
            val screen = requireNotNull(observed.screen)
            val button = screen.elements.single { it.contentDescription == "execution_fixture_apply" }
            val clicked = gateway.tapUiElement(button.index, false, screen.screenId)
            assertTrue(clicked.asToolContent(), clicked.actionOutcome != DeviceActionOutcome.NotDispatched)
            val verified = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition("${context.packageName}.test",
                element = DeviceUiSelector(text = "RESULT=r7f-local-result")), 3_000))
            assertTrue(verified.asToolContent(), verified.conditionMatched == true)
            assertEquals(id, verified.screen?.geometry?.displayId)
            val beforeFinish = released(root, task.id, minimumGroups = 2)
            assertEquals(2, beforeFinish.size)
            assertEquals(beforeFinish, gateway.rootRequestCleanup.value.values.filter { it.ownerId == task.id })
            tasks.finish(task)
            displayGone(root, task.id, id)
            repeat(3) { root.recheckRootRequestCleanup(); root.recheckExecutionDisplayCleanup() }
            assertEquals(beforeFinish, root.rootRequestCleanup.value.values.filter { it.ownerId == task.id })
            report("production launch/tap and exact result verified on display=$id; task finish preserved original request evidence; no model calls")
        } finally {
            withContext(NonCancellable) {
                tasks.finish(task); root.release()
                settings.setBackgroundExecutionEnabled(saved.backgroundExecutionEnabled)
                settings.setPowerConnectionMode(saved.powerConnectionMode)
                settings.setPowerModeEnabled(saved.powerModeEnabled)
                check(context.getSharedPreferences("app_controls", android.content.Context.MODE_PRIVATE).edit().commit())
            }
        }
    }

    private suspend fun stalledSwipe(closeDisplay: Boolean) {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context, RootDisplayCommandDiagnosticsService::class.java), scope,
            displayPresent = { context.getSystemService(DisplayManager::class.java).getDisplay(it) != null })
        val marker = File(context.cacheDir, "r7f-display-command.pid")
        val count = File(context.cacheDir, "r7f-display-command.count")
        check(!marker.exists() && !count.exists()) { "Do not overwrite an existing diagnostic marker" }
        var caller: Deferred<Boolean>? = null
        try {
            root.refresh(requestPermission = true)
            val owner = UUID.randomUUID().toString()
            val lease = root.openExecutionDisplay(owner)
            val id = JSONObject(lease.remote.info).getInt("displayId")
            caller = scope.async { lease.command { it.swipe(40, 60, 40, 160, 350) } }
            until { marker.exists() && marker.readText().trim().toIntOrNull() != null }
            val group = marker.readText().trim().toInt()
            assertEquals(RootRequestState.InUse, root.rootRequestCleanup.value.values.single { it.ownerId == owner }.state)
            val otherOwner = UUID.randomUUID().toString()
            assertTrue(root.withService(ownerId = otherOwner) { it.getDeviceStatus() }.contains("battery="))
            val other = released(root, otherOwner)
            if (closeDisplay) {
                withTimeout(4_000) { lease.close() }
                assertFalse("Closing the display cannot turn its blocked swipe into success", withTimeout(4_000) { caller.await() })
            } else {
                withTimeout(4_000) { caller.cancelAndJoin() }
                assertTrue(caller.isCancelled)
                assertNotNull("Cancelling one request need not remove its owned display", context.getSystemService(DisplayManager::class.java).getDisplay(id))
            }
            val completed = released(root, owner)
            assertTrue(completed.single().processGroups.contains(group))
            assertEquals(other, root.rootRequestCleanup.value.values.filter { it.ownerId == otherOwner })
            assertEquals(1, count.readLines().size)
            repeat(3) { root.recheckRootRequestCleanup() }
            assertEquals(1, count.readLines().size)
            lease.close()
            displayGone(root, owner, id)
            assertEquals(0, requireNotNull(root.serviceInfo()).activeRequests)
            report("stalled swipe closeDisplay=$closeDisplay; one dispatch; exact group=$group absent; other owner unchanged")
        } finally {
            withContext(NonCancellable) {
                caller?.cancelAndJoin(); root.release(); scope.cancel()
                marker.delete(); count.delete()
            }
        }
    }

    @Test fun cancellationStopsOnlyTheRequestOwnedDisplayCommandWithoutReplaying() = runBlocking<Unit> { stalledSwipe(false) }
    @Test fun closingDisplayStopsItsInFlightCommandBeforeReportingTheRequestReleased() = runBlocking<Unit> { stalledSwipe(true) }

    @Test fun unscopedAndFinishedRequestHandlesCannotDispatchLateDisplayCommands() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = RootDeviceConnection(LibsuRootServiceConnector(context), scope,
            displayPresent = { context.getSystemService(DisplayManager::class.java).getDisplay(it) != null })
        try {
            root.refresh(requestPermission = true)
            val owner = UUID.randomUUID().toString()
            val lease = root.openExecutionDisplay(owner)
            assertTrue(runCatching { lease.remote.tap(10, 10, false) }.isFailure)
            var stale: IFlowChatExecutionDisplay? = null
            root.withService(ownerId = owner) { request ->
                assertTrue(runCatching { request.getRequestExecutionDisplay(UUID.randomUUID().toString()) }.isFailure)
                stale = request.getRequestExecutionDisplay(owner)
            }
            val before = released(root, owner, minimumGroups = 0)
            assertTrue(runCatching { requireNotNull(stale).tap(10, 10, false) }.isFailure)
            root.recheckRootRequestCleanup()
            assertEquals(before, root.rootRequestCleanup.value.values.filter { it.ownerId == owner })
            assertTrue(before.all { it.processGroups.isEmpty() })
            assertTrue(lease.command { it.swipe(40, 60, 40, 160, 100) })
            assertTrue(lease.command { it.pressBack() })
            assertTrue(lease.command { it.clearApps() })
            val normal = released(root, owner, minimumGroups = 2)
            assertEquals(4, normal.size)
            assertEquals(0, requireNotNull(root.serviceInfo()).activeRequests)
            lease.close()
            assertTrue(runCatching { lease.command { it.pressBack() } }.isFailure)
            report("unscoped mutation, wrong owner and retired request created no groups; scoped normal swipe/back/clear verified; closed lease rejected")
        } finally { withContext(NonCancellable) { root.release(); scope.cancel() } }
    }

    @Test fun unknownOriginalRequestEvidenceBlocksNewCommandsUntilObservationVerifiesIt() = runBlocking<Unit> {
        enabled()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connects = AtomicInteger()
        val opens = AtomicInteger()
        var readable = false
        val real = LibsuRootServiceConnector(context)
        val root = RootDeviceConnection({ death ->
            connects.incrementAndGet()
            val original = real.connect(death)
            object : RootServiceSession by original {
                override fun openRequest(id: String) = original.openRequest(id).also { opens.incrementAndGet() }
                override fun requestCleanup(id: String): String { check(readable) { "Injected unavailable evidence" }; return original.requestCleanup(id) }
            }
        }, scope, displayPresent = { context.getSystemService(DisplayManager::class.java).getDisplay(it) != null })
        try {
            root.refresh(requestPermission = true)
            val owner = UUID.randomUUID().toString()
            val lease = root.openExecutionDisplay(owner)
            assertTrue(lease.command { it.tap(10, 10, false) })
            val pending = root.rootRequestCleanup.value.values.single { it.ownerId == owner }
            assertEquals(RootRequestState.Unverified, pending.state)
            val before = opens.get()
            repeat(3) { root.recheckRootRequestCleanup() }
            assertTrue(runCatching { lease.command { it.tap(10, 10, false) } }.isFailure)
            assertTrue(runCatching { root.withService { it.getDeviceStatus() } }.isFailure)
            assertEquals(before, opens.get())
            assertEquals(1, connects.get())
            readable = true
            released(root, owner)
            assertEquals(before, opens.get())
            assertEquals(1, connects.get())
            lease.close()
            report("unknown exact-connection evidence blocked display and physical commands; observation only recovered original record without reconnect/replay")
        } finally { withContext(NonCancellable) { root.release(); scope.cancel() } }
    }
}
