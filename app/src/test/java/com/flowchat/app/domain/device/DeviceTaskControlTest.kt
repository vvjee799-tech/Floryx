package com.flowchat.app.domain.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeviceTaskControlTest {
    @Test fun displayCleanupRunsExactlyOnceForFinishReplacementAndLateRegistration() {
        val control = DeviceTaskControl()
        val first = control.begin()
        var firstCloses = 0
        control.onFinish(first) { firstCloses++ }
        val second = control.begin()
        control.finish(first)
        assertEquals(1, firstCloses)
        var secondCloses = 0
        control.onFinish(second) { secondCloses++ }
        val prepared = control.prepare()
        control.activate(prepared)
        assertEquals(1, secondCloses)
        control.finish(prepared)
        var lateCloses = 0
        control.onFinish(prepared) { lateCloses++ }
        control.finish(prepared)
        assertEquals(1, lateCloses)
        assertNotEquals(first.id, second.id)
    }
    @Test fun returningToFlowChatLatchesTakeoverUntilAnExplicitNewTask() {
        val control = DeviceTaskControl()
        control.setAppInForeground(true)
        val first = control.begin()
        control.arm(first)
        control.setAppInForeground(true) // onStart followed by onResume is not a return.
        assertFalse(first.interrupted)
        control.setAppInForeground(false)
        control.setAppInForeground(true)
        control.setAppInForeground(false) // Leaving again must not resume the old task.
        assertTrue(first.interrupted)
        assertThrows(CancellationException::class.java) { control.checkCanAct(first) }
        val resumed = control.begin()
        control.arm(resumed)
        control.checkCanAct(resumed)
        assertThrows(CancellationException::class.java) { control.checkCanAct(first) }
    }

    @Test fun aColdAssistantTaskYieldsWhenTheFullAppFirstOpensButPanelVisibilityDoesNotTakeOver() {
        val control = DeviceTaskControl()
        val bridge = com.flowchat.app.presentation.overlay.FloatingAssistantBridge(control)
        val task = control.begin()
        control.arm(task)
        val panel = bridge.showAssistantPanel()
        assertFalse(bridge.appInForeground.value)
        assertFalse(task.interrupted)
        panel.close(); panel.close()
        assertEquals(0, bridge.assistantPanels.value)
        assertFalse(task.interrupted)
        bridge.setAppInForeground(true)
        assertTrue(task.interrupted)
        assertThrows(CancellationException::class.java) { control.checkCanAct(task) }
    }

    @Test fun returningWhileTheModelIsStillPlanningBlocksItsLateFirstAction() {
        val control = DeviceTaskControl()
        val task = control.begin()
        control.setAppInForeground(false)
        control.setAppInForeground(true)
        assertFalse(task.interrupted) // Ordinary chat is unaffected until it asks to operate the phone.
        assertThrows(CancellationException::class.java) { control.arm(task) }
        assertTrue(task.interrupted)
    }

    @Test fun finishingAnOldRunCannotClearTheResumedTaskOrAllowItsQueuedActions() {
        val control = DeviceTaskControl()
        val old = control.begin()
        val resumed = control.begin()
        control.finish(old)
        assertSame(resumed, control.currentTask)
        assertThrows(CancellationException::class.java) { control.checkCanAct(old) }
        control.arm(resumed)
        control.finish(resumed)
        assertNull(control.currentTask)
    }

    @Test fun preparingAnotherConversationDoesNotTakeTheActivePhoneTask() {
        val control = DeviceTaskControl()
        val active = control.begin()
        control.arm(active)

        val waiting = control.prepare()

        assertSame(active, control.currentTask)
        control.checkCanAct(active)
        assertThrows(CancellationException::class.java) { control.checkCanAct(waiting) }
        control.activate(waiting)
        control.arm(waiting)
        assertThrows(CancellationException::class.java) { control.checkCanAct(active) }
    }

    @Test fun preparedTaskCannotActivateAfterTheUserReturned() {
        val control = DeviceTaskControl()
        val waiting = control.prepare()
        control.setAppInForeground(false)
        control.setAppInForeground(true)

        assertThrows(CancellationException::class.java) { control.activate(waiting) }
        assertTrue(waiting.interrupted)
        assertNull(control.currentTask)
    }

    @Test fun finishingAnUnactivatedTaskCannotCloseTheCurrentTask() {
        val control = DeviceTaskControl()
        val active = control.begin()
        control.arm(active)
        val waiting = control.prepare()

        control.finish(waiting)

        assertSame(active, control.currentTask)
        control.checkCanAct(active)
        assertThrows(CancellationException::class.java) { control.activate(waiting) }
    }

    @Test fun returningForConfirmationKeepsTheTaskActiveButLaterReturnsStillTakeOver() = runTest {
        val control = DeviceTaskControl()
        val task = control.begin()
        control.arm(task)
        control.setAppInForeground(false)
        control.withUserConfirmation(task) {
            control.setAppInForeground(true)
            assertFalse(task.interrupted)
            control.checkCanAct(task)
        }
        control.arm(task) // The confirmation return must not invalidate the next phone action.
        assertEquals(1L, control.foregroundReturns.value)
        control.setAppInForeground(false)
        control.setAppInForeground(true)
        assertTrue(task.interrupted)
        assertThrows(CancellationException::class.java) { control.checkCanAct(task) }
    }

    @Test fun aConfirmationCannotUndoAnEarlierTakeover() = runTest {
        val control = DeviceTaskControl()
        val task = control.begin()
        control.setAppInForeground(false)
        control.setAppInForeground(true)
        var requested = false
        try {
            control.withUserConfirmation(task) { requested = true }
            fail("A late confirmation must not restore the interrupted task")
        } catch (_: CancellationException) {
            assertTrue(task.interrupted)
            assertFalse(requested)
        }
    }

    @Test fun cancellingAConfirmationRestoresTakeoverDetection() = runTest {
        val control = DeviceTaskControl()
        val task = control.begin()
        val gate = CompletableDeferred<Unit>()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            control.withUserConfirmation(task) { gate.await() }
        }
        waiting.cancelAndJoin()
        control.setAppInForeground(false)
        control.setAppInForeground(true)
        assertTrue(task.interrupted)
    }

    @Test fun replacingATaskDoesNotInheritItsPendingConfirmation() = runTest {
        val control = DeviceTaskControl()
        val old = control.begin()
        control.withUserConfirmation(old) {
            val replacement = control.begin()
            control.arm(replacement)
            control.setAppInForeground(false)
            control.setAppInForeground(true)
            assertTrue(replacement.interrupted)
            assertThrows(CancellationException::class.java) { control.checkCanAct(old) }
        }
    }
}
