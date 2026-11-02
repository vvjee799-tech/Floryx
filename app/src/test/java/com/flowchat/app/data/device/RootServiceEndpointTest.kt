package com.flowchat.app.data.device

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RootServiceEndpointTest {
    @Test fun cancelledDisplayOwnerCannotCreateLateNativeResources() {
        RootServiceEndpoint(Process.myUid()).use { endpoint ->
            val id = UUID.randomUUID().toString()
            endpoint.closeExecutionDisplay(id)
            assertThrows(IllegalStateException::class.java) { endpoint.openExecutionDisplay(id) }
            assertEquals(0, RootServiceInfo.decode(endpoint.getServiceInfo()).activeDisplays)
            assertThrows(IllegalArgumentException::class.java) { endpoint.openExecutionDisplay("not-an-owner-id") }
        }
    }
    private class Executor : DeviceCommandExecutor, AutoCloseable {
        var closed = false
        override fun execute(command: List<String>) = DeviceCommandResult(!closed, "level: 50")
        override fun close() { closed = true }
    }

    @Test fun owningUidIsCheckedEvenForControlCalls() {
        RootServiceEndpoint(Process.myUid() + 1).use { endpoint ->
            assertThrows(SecurityException::class.java) { endpoint.getServiceInfo() }
            assertThrows(SecurityException::class.java) { endpoint.openRequest(UUID.randomUUID().toString()) }
        }
    }

    @Test fun cancellationBeforeOpenCannotStartAnOperationLater() {
        var created = 0
        RootServiceEndpoint(Process.myUid()) { created++; Executor() }.use { endpoint ->
            val id = UUID.randomUUID().toString()
            endpoint.finishRequest(id, true)
            assertThrows(IllegalArgumentException::class.java) { endpoint.finishRequest("invalid", true) }
            assertThrows(IllegalStateException::class.java) { endpoint.openRequest(id) }
            assertEquals(0, created)
        }
    }

    @Test fun cancellingOneRequestClosesItsProcessesAndLeavesOtherRequestsUsable() {
        val executors = mutableListOf<Executor>()
        RootServiceEndpoint(Process.myUid()) { Executor().also(executors::add) }.use { endpoint ->
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            val old = endpoint.openRequest(first)
            val active = endpoint.openRequest(second)
            endpoint.finishRequest(first, true)
            assertTrue(executors[0].closed)
            assertFalse(executors[1].closed)
            assertTrue(active.getDeviceStatus().contains("battery=50%"))
            val input = Parcel.obtain()
            val output = Parcel.obtain()
            try {
                assertThrows(IllegalStateException::class.java) {
                    old.asBinder().transact(IBinder.INTERFACE_TRANSACTION, input, output, 0)
                    output.readException()
                }
            } finally { input.recycle(); output.recycle() }
            endpoint.finishRequest(second, false)
            assertTrue(executors[1].closed)
            assertEquals(0, RootServiceInfo.decode(endpoint.getServiceInfo()).activeRequests)
        }
    }

    @Test fun requestLimitAndServiceTeardownBoundResources() {
        val executors = mutableListOf<Executor>()
        val endpoint = RootServiceEndpoint(Process.myUid()) { Executor().also(executors::add) }
        repeat(8) { endpoint.openRequest(UUID.randomUUID().toString()) }
        assertThrows(IllegalStateException::class.java) { endpoint.openRequest(UUID.randomUUID().toString()) }
        endpoint.close()
        assertTrue(executors.all { it.closed })
        assertThrows(IllegalStateException::class.java) { endpoint.openRequest(UUID.randomUUID().toString()) }
    }
}
