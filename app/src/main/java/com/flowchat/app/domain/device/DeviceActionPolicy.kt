package com.flowchat.app.domain.device

import com.flowchat.app.domain.tools.AgentToolDefinitions

object DeviceActionPolicy {
    fun requiresConfirmation(toolName: String): Boolean =
        toolName == AgentToolDefinitions.ForceStopAppToolName

    fun allowsOnScreen(toolName: String, screen: DeviceScreenSnapshot): Boolean =
        !screen.sensitive || toolName == AgentToolDefinitions.ObserveScreenToolName ||
            toolName == AgentToolDefinitions.PressBackToolName ||
            toolName == AgentToolDefinitions.PressHomeToolName
}
