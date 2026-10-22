package com.flowchat.app.presentation.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAssistantUiContractTest {
    @Test
    fun settingsExposeOnePowerModeInsteadOfScatteredFeatureToggles() {
        val screen = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val state = File("src/main/java/com/flowchat/app/presentation/chat/ChatUiState.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val dialogStart = screen.indexOf("internal fun DeviceAssistantDialog(")
        val dialogEnd = screen.indexOf("private fun DeviceActionConfirmationDialog(", dialogStart)
        val dialog = screen.substring(dialogStart, dialogEnd)

        assertTrue(state.contains("val shizukuState: ShizukuConnectionState"))
        assertTrue(dialog.contains("R.string.power_mode"))
        assertTrue(dialog.contains("R.string.power_mode_description"))
        assertTrue(dialog.contains("appSettings.powerModeEnabled"))
        assertTrue(dialog.contains("onPowerModeEnabledChange"))
        assertFalse(dialog.contains("appSettings.appUsageToolEnabled"))
        assertFalse(dialog.contains("appSettings.recentAppActivityToolEnabled"))
        assertFalse(dialog.contains("appSettings.openAppToolEnabled"))
        assertFalse(dialog.contains("appSettings.deviceAssistantEnabled"))
        assertFalse(dialog.contains("appSettings.floatingAssistantEnabled"))
        assertFalse(dialog.contains("appSettings.forceStopToolEnabled"))
        assertFalse(dialog.contains("R.string.available_capabilities"))
        assertTrue(strings.contains("name=\"power_mode\""))
        assertTrue(zhStrings.contains("name=\"power_mode\">Power 模式</string>"))
        assertFalse(zhStrings.contains("一键拥有最高权限"))
    }

    @Test
    fun structuredAutomationShowsItsOwnReadinessAndRepairAction() {
        val screen = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val state = File("src/main/java/com/flowchat/app/presentation/chat/ChatUiState.kt").readText()
        val viewModel = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val zhStrings = File("src/main/res/values-zh-rCN/strings.xml").readText()
        val dialogStart = screen.indexOf("internal fun DeviceAssistantDialog(")
        val dialogEnd = screen.indexOf("private fun DeviceActionConfirmationDialog(", dialogStart)
        val dialog = screen.substring(dialogStart, dialogEnd)

        assertTrue(state.contains("val accessibilityState: AccessibilityConnectionState"))
        assertTrue(dialog.contains("R.string.accessibility_screen_control"))
        assertTrue(dialog.contains("R.string.device_accessibility_settings"))
        assertTrue(dialog.contains("accessibilityState"))
        assertTrue(viewModel.contains("deviceAssistantGateway.accessibilityState"))
        assertTrue(viewModel.contains("fun refreshDeviceAssistantConnections()"))
        assertTrue(strings.contains("name=\"accessibility_screen_control\""))
        assertTrue(zhStrings.contains("name=\"accessibility_screen_control\""))
        assertTrue(screen.contains("\"observe_screen\""))
        assertTrue(screen.contains("\"tap_ui_element\""))
        assertTrue(screen.contains("\"input_text\""))
    }

    @Test
    fun dangerousDeviceActionUsesAnExplicitConfirmationDialog() {
        val screen = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val state = File("src/main/java/com/flowchat/app/presentation/chat/ChatUiState.kt").readText()
        val viewModel = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()

        assertTrue(state.contains("val pendingDeviceActionConfirmation"))
        assertTrue(state.contains("data class DeviceActionConfirmationUi("))
        assertTrue(screen.contains("DeviceActionConfirmationDialog("))
        assertTrue(screen.contains("viewModel::confirmDeviceAction"))
        assertTrue(screen.contains("viewModel::cancelDeviceAction"))
        assertTrue(viewModel.contains("DeviceActionPolicy.requiresConfirmation(call.name)"))
        assertTrue(viewModel.contains("confirmation.await()"))
        assertFalse(viewModel.contains("run_shell"))
    }
}
