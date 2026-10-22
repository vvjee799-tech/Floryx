package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.data.preferences.AppSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RootDeviceConnectionTest {
    @Test
    fun selectedConnectionModeSurvivesSettingsReload() {
        val context = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(context)
        store.setPowerConnectionMode(PowerConnectionMode.Root)
        store.setPowerModeEnabled(true)
        val restored = AppSettingsStore(context)
        assertEquals(PowerConnectionMode.Root, restored.state.value.powerConnectionMode)
        assertTrue(restored.state.value.powerModeEnabled)
    }

    @Test
    fun returningFromRootManagerReconnectsAfterSuBecomesVisible() = runTest {
        var visible = false
        val connection = RootDeviceConnection({ command ->
            when (command) {
                listOf("id", "-u") -> DeviceCommandResult(true, "0")
                listOf("dumpsys", "battery") -> DeviceCommandResult(true, "level: 50")
                else -> DeviceCommandResult(false, "unavailable")
            }
        }, { visible })
        connection.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.RootUnavailable, connection.state.value.status)
        visible = true
        connection.refresh()
        assertEquals(ShizukuConnectionStatus.ConnectedDirectRoot, connection.state.value.status)
    }

    @Test
    fun missingSuDoesNotAttemptAuthorization() = runTest {
        val connection = RootDeviceConnection({ error("su must not run") }, { false })
        connection.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.RootUnavailable, connection.state.value.status)
        assertFalse(connection.state.value.isConnected)
    }

    @Test
    fun passiveRefreshDoesNotPromptAndDenialRequiresExplicitRetry() = runTest {
        var attempts = 0
        val connection = RootDeviceConnection({
            attempts++
            DeviceCommandResult(false, "Permission denied")
        }, { true })
        connection.refresh()
        assertEquals(0, attempts)
        assertEquals(ShizukuConnectionStatus.RootPermissionRequired, connection.state.value.status)
        connection.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.RootPermissionDenied, connection.state.value.status)
        connection.refresh()
        assertEquals(1, attempts)
        connection.refresh(requestPermission = true)
        assertEquals(2, attempts)
    }

    @Test
    fun successfulRootIsVerifiedAndRevocationClearsCapabilities() = runTest {
        var granted = true
        val connection = RootDeviceConnection({ command ->
            if (command == listOf("id", "-u")) DeviceCommandResult(granted, if (granted) "0" else "denied")
            else when (command) {
                listOf("dumpsys", "battery") -> DeviceCommandResult(true, "level: 50\nscale: 100")
                listOf("settings", "get", "system", "screen_brightness") -> DeviceCommandResult(true, "128")
                else -> DeviceCommandResult(false, "unavailable")
            }
        }, { true })
        connection.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.ConnectedDirectRoot, connection.state.value.status)
        assertTrue(connection.state.value.isConnected)
        assertTrue(DeviceCapability.DeviceStatus in connection.state.value.capabilities)
        assertTrue(DeviceCapability.ScreenBrightness in connection.state.value.capabilities)
        assertTrue(connection.requireService().getDeviceStatus().contains("battery=50%"))
        granted = false
        connection.refresh()
        assertFalse(connection.state.value.isConnected)
        assertTrue(connection.state.value.capabilities.isEmpty())
    }

    @Test
    fun nonRootUidIsNeverTreatedAsSuperuser() = runTest {
        val connection = RootDeviceConnection({ DeviceCommandResult(true, "2000") }, { true })
        connection.refresh(requestPermission = true)
        assertEquals(ShizukuConnectionStatus.RootPermissionDenied, connection.state.value.status)
    }
}
