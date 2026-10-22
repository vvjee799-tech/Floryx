package com.flowchat.app.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import com.flowchat.app.domain.device.PowerConnectionMode

class AppSettingsStoreTest {
    @Test
    fun choosingRootDoesNotChangePowerModeToggle() {
        val store = AppSettingsStore(AppSettings())
        assertEquals(PowerConnectionMode.Shizuku, store.state.value.powerConnectionMode)
        store.setPowerConnectionMode(PowerConnectionMode.Root)
        assertEquals(PowerConnectionMode.Root, store.state.value.powerConnectionMode)
        assertFalse(store.state.value.powerModeEnabled)
        store.setPowerModeEnabled(true)
        store.setPowerConnectionMode(PowerConnectionMode.Shizuku)
        assertTrue(store.state.value.powerModeEnabled)
    }

    @Test
    fun powerModeControlsEveryDeviceAssistantFeatureFromOneSetting() {
        val store = AppSettingsStore(AppSettings(installId = "test-install"))

        assertTrue(store.state.value.memoryEnabled)
        assertFalse(store.state.value.powerModeEnabled)

        store.setMemoryEnabled(false)
        store.setPowerModeEnabled(true)

        assertFalse(store.state.value.memoryEnabled)
        assertTrue(store.state.value.powerModeEnabled)
        assertFalse(store.state.value.webSearchDisclosureAccepted)

        store.setPowerModeEnabled(false)

        assertFalse(store.state.value.powerModeEnabled)
    }
}
