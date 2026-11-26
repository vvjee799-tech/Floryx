package com.flowchat.app.data.task

import com.flowchat.app.domain.chat.*
import org.junit.Assert.*
import org.junit.Test

class TaskPowerMonitorTest {
    private class Lock : TaskWakeLock {
        var held = false
        var failRead = false
        var failAcquire = false
        var failRelease = false
        var renewals = 0
        override val isHeld: Boolean get() { check(!failRead); return held }
        override fun renew() { check(!failAcquire); held = true; renewals++ }
        override fun close() { check(!failRelease); held = false }
    }
    private fun owner(id: String, work: TaskPowerWork) = TaskPowerOwner("chat-$id", "run-$id", work)

    @Test fun waitingOwnersDoNotRenewButConcurrentModelWorkDoes() {
        val lock = Lock(); val monitor = TaskPowerMonitor(); val session = TaskPowerSession(lock, monitor)
        val waiters = setOf(owner("a", TaskPowerWork.WaitingForUser), owner("b", TaskPowerWork.WaitingForDevice),
            owner("d", TaskPowerWork.WaitingForCleanup))
        session.update(waiters)
        assertFalse(lock.held); assertEquals(0, lock.renewals)
        session.update(waiters + owner("c", TaskPowerWork.Model))
        assertTrue(lock.held)
        assertEquals(4, monitor.state.value!!.owners.size)
        session.update(waiters)
        assertFalse(lock.held)
        assertEquals(TaskServiceState.Running, monitor.state.value!!.serviceState)
        session.close()
        assertEquals(TaskServiceState.Destroyed, monitor.state.value!!.serviceState)
        assertEquals(false, monitor.state.value!!.leaseHeld)
    }

    @Test fun stoppingOneOwnerKeepsTheOtherAndLocalFinalizationAlive() {
        val lock = Lock(); val monitor = TaskPowerMonitor(); val session = TaskPowerSession(lock, monitor)
        val a = owner("a", TaskPowerWork.Execution); val b = owner("b", TaskPowerWork.Model)
        session.update(setOf(a, b))
        session.update(setOf(a.copy(work = TaskPowerWork.Finalizing), b))
        assertTrue(lock.held)
        session.update(setOf(b)); assertTrue(lock.held)
        session.update(emptySet()); assertFalse(lock.held)
        assertEquals(setOf("run-a", "run-b"), monitor.state.value!!.recentOwners.map { it.runId }.toSet())
    }

    @Test fun releaseFailureDoesNotBecomeSuccessfulCleanup() {
        val lock = Lock(); val monitor = TaskPowerMonitor(); val session = TaskPowerSession(lock, monitor)
        session.update(setOf(owner("a", TaskPowerWork.Model)))
        lock.failRelease = true
        session.close()
        assertEquals(TaskServiceState.Destroyed, monitor.state.value!!.serviceState)
        assertEquals(true, monitor.state.value!!.leaseHeld)
        assertEquals(TaskPowerFailure.ReleaseFailed, monitor.state.value!!.failure)
    }

    @Test fun unreadableLockIsUnknownEvenAfterClose() {
        val lock = Lock(); val monitor = TaskPowerMonitor(); val session = TaskPowerSession(lock, monitor)
        session.update(setOf(owner("a", TaskPowerWork.Model)))
        lock.failRead = true
        session.close()
        assertNull(monitor.state.value!!.leaseHeld)
        assertEquals(TaskPowerFailure.ReadFailed, monitor.state.value!!.failure)
    }

    @Test fun failedAcquisitionDoesNotPretendPowerWasHeldAndNextAttemptCanRecover() {
        val lock = Lock(); val monitor = TaskPowerMonitor(); val session = TaskPowerSession(lock, monitor)
        lock.failAcquire = true
        val work = setOf(owner("a", TaskPowerWork.Model))
        session.update(work)
        assertEquals(false, monitor.state.value!!.leaseHeld)
        assertEquals(TaskPowerFailure.AcquireFailed, monitor.state.value!!.failure)
        lock.failAcquire = false
        session.update(work)
        assertEquals(true, monitor.state.value!!.leaseHeld)
        assertNull(monitor.state.value!!.failure)
    }

    @Test fun anUnverifiedOldLockSurvivesServiceReplacementUntilReadOnlyRecheckFindsActualRelease() {
        val monitor = TaskPowerMonitor(); val lock = Lock(); val old = TaskPowerSession(lock, monitor)
        old.update(setOf(owner("old", TaskPowerWork.Model)))
        lock.failRelease = true; old.close()
        val fresh = TaskPowerSession(Lock(), monitor)
        fresh.update(emptySet())
        assertEquals(false, monitor.state.value!!.leaseHeld)
        assertEquals(1, monitor.state.value!!.otherUnverifiedLeases)
        lock.held = false // The old Android lock's own timeout, independently of the new service.
        monitor.recheck()
        assertEquals(0, monitor.state.value!!.otherUnverifiedLeases)
        assertEquals(fresh.id, monitor.state.value!!.serviceId)
    }

    @Test fun oldServiceCallbacksCannotOverwriteANewServiceAndClosedSessionCannotRenew() {
        val monitor = TaskPowerMonitor(); val oldLock = Lock(); val old = TaskPowerSession(oldLock, monitor)
        old.update(setOf(owner("old", TaskPowerWork.Model)))
        val fresh = TaskPowerSession(Lock(), monitor)
        fresh.update(setOf(owner("new", TaskPowerWork.Execution)))
        old.close(); old.update(setOf(owner("old", TaskPowerWork.Model)))
        monitor.stopping(old.id)
        assertEquals(fresh.id, monitor.state.value!!.serviceId)
        assertEquals("run-new", monitor.state.value!!.owners.single().runId)
        assertEquals(TaskServiceState.Running, monitor.state.value!!.serviceState)
        assertFalse(oldLock.held)
    }
}
