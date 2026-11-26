package com.flowchat.app.data.task

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskPowerLeaseTest {
    @Test fun timeoutAndRepeatedRenewalDoNotLeaveAReferenceCountedLock() {
        val lease = TaskPowerLease(RuntimeEnvironment.getApplication())
        lease.renew()
        val lock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertFalse(shadowOf(lock).isReferenceCounted)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(TaskPowerLease.RenewMillis))
        lease.renew()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(TaskPowerLease.RenewMillis + 1))
        assertTrue(lock.isHeld)
        lease.close(); lease.close()
        assertFalse(lock.isHeld)
        lease.renew()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(TaskPowerLease.TimeoutMillis + 1))
        assertFalse(lock.isHeld)
        lease.close()
    }
}
