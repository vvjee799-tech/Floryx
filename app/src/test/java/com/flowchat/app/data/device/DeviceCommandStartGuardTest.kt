package com.flowchat.app.data.device

import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.domain.device.PowerConnectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceCommandStartGuardTest {
    private class Fixture(mode: PowerConnectionMode = PowerConnectionMode.Root) {
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = mode, installId = "phone"))
        val tasks = DeviceTaskControl()
        val task = tasks.begin().also(tasks::arm)
        val caller = Job()
        val target = settings.state.value.let {
            DeviceExecutionTarget(it.installId, "conversation", it.powerModeEnabled, it.powerConnectionMode,
                it.backgroundExecutionEnabled, it.deviceConfigurationVersion)
        }
        fun guard(withTarget: Boolean = true) = DeviceCommandStartGuard(
            if (withTarget) caller + target else caller, settings, tasks)
    }

    @Test fun delayedIndependentRootDispatchRejectsTheCancelledOriginalCaller() = runTest {
        val fixture = Fixture()
        val guard = fixture.guard()
        val ready = CompletableDeferred<Unit>()
        val rpcScope = CoroutineScope(coroutineContext + SupervisorJob())
        var starts = 0
        try {
            val rpc = rpcScope.async {
                ready.await()
                try {
                    guard.checkCanDispatch()
                    starts++
                    false
                } catch (_: CancellationException) { true }
            }
            runCurrent()
            fixture.caller.cancel()
            ready.complete(Unit)
            assertTrue(rpc.await())
            assertEquals(0, starts)
        } finally { rpcScope.cancel() }
    }

    @Test fun delayedStartRejectsItsFinishedTaskEvenWhenANewTaskOwnsTheSameTarget() = runTest {
        val fixture = Fixture()
        val guard = fixture.guard()
        val ready = CompletableDeferred<Unit>()
        var starts = 0
        val rpc = async {
            ready.await()
            try {
                guard.checkCanDispatch()
                starts++
                false
            } catch (_: CancellationException) { true }
        }
        runCurrent()
        val replacement = fixture.tasks.begin().also(fixture.tasks::arm)
        ready.complete(Unit)
        assertTrue(rpc.await())
        assertEquals(0, starts)
        fixture.tasks.checkCanAct(replacement)
    }

    @Test fun serviceReadyAfterConnectionSwitchAwayAndBackCannotStartTheOldPlan() = runTest {
        for (mode in PowerConnectionMode.entries) {
            val fixture = Fixture(mode)
            val guard = fixture.guard()
            val ready = CompletableDeferred<Unit>()
            var starts = 0
            val rpc = async {
                ready.await()
                try {
                    guard.checkCanDispatch()
                    starts++
                    false
                } catch (_: CancellationException) { true }
            }
            runCurrent()
            fixture.settings.setPowerConnectionMode(PowerConnectionMode.entries.single { it != mode })
            fixture.settings.setPowerConnectionMode(mode)
            ready.complete(Unit)
            assertTrue(rpc.await())
            assertEquals(0, starts)
        }
    }

    @Test fun userTakeoverRejectsAQueuedStartWithoutWaitingForCallerCancellation() {
        val fixture = Fixture()
        val guard = fixture.guard()
        assertTrue(fixture.tasks.requestUserTakeover())
        org.junit.Assert.assertThrows(CancellationException::class.java) { guard.checkCanDispatch() }
        assertTrue(fixture.caller.isActive)
    }

    @Test fun manualStartWithoutATargetKeepsItsCapturedTransport() {
        val fixture = Fixture()
        val guard = fixture.guard(withTarget = false)
        fixture.settings.setPowerConnectionMode(PowerConnectionMode.Shizuku)
        assertTrue(guard.useRoot)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { guard.checkCanDispatch() }
    }

    @Test fun unchangedTargetAllowsBothTransportsAndUnrelatedSettings() {
        for (mode in PowerConnectionMode.entries) {
            val fixture = Fixture(mode)
            val guard = fixture.guard()
            fixture.settings.setMemoryEnabled(false)
            guard.checkCanDispatch()
            if (mode == PowerConnectionMode.Root) assertTrue(guard.useRoot) else assertFalse(guard.useRoot)
        }
    }
}
