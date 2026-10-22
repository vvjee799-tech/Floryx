package com.flowchat.app.domain.device

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DeviceTaskControlTest {
    @Test fun returningToFlowChatLatchesTakeoverUntilAnExplicitNewTask() {
        val control = DeviceTaskControl()
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
}
