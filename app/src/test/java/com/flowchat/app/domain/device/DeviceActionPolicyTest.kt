package com.flowchat.app.domain.device

import com.flowchat.app.domain.tools.AgentToolDefinitions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionPolicyTest {
    @Test
    fun readAndReversibleActionsDoNotInterruptTheConversation() {
        assertFalse(DeviceActionPolicy.requiresConfirmation(AgentToolDefinitions.DeviceStatusToolName))
        assertFalse(DeviceActionPolicy.requiresConfirmation(AgentToolDefinitions.ForegroundAppToolName))
        assertFalse(DeviceActionPolicy.requiresConfirmation(AgentToolDefinitions.SetBrightnessToolName))
        assertFalse(DeviceActionPolicy.requiresConfirmation(AgentToolDefinitions.SetMediaVolumeToolName))
    }

    @Test
    fun forceStopAlwaysRequiresExplicitConfirmation() {
        assertTrue(DeviceActionPolicy.requiresConfirmation(AgentToolDefinitions.ForceStopAppToolName))
    }

    @Test
    fun automationActionsAreBlockedOnSensitiveScreens() {
        val sensitive = DeviceScreenSnapshot(
            packageName = "com.example.bank",
            activityName = "PayActivity",
            elements = emptyList(),
            sensitive = true,
            sensitivityReason = "payment"
        )

        assertFalse(DeviceActionPolicy.allowsOnScreen(AgentToolDefinitions.TapUiElementToolName, sensitive))
        assertFalse(DeviceActionPolicy.allowsOnScreen(AgentToolDefinitions.InputTextToolName, sensitive))
        assertTrue(DeviceActionPolicy.allowsOnScreen(AgentToolDefinitions.ObserveScreenToolName, sensitive))
        assertTrue(DeviceActionPolicy.allowsOnScreen(AgentToolDefinitions.PressBackToolName, sensitive))
    }
}
