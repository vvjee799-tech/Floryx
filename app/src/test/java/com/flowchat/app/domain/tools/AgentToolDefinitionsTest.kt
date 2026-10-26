package com.flowchat.app.domain.tools

import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.device.DeviceCapability
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsTest {
    @Test fun visualTapRequiresBothScreenCaptureAndVisualActionCapabilities() {
        val request = ChatRequest(model = "vision", messages = emptyList(), temperature = 1.0, topP = 1.0, maxTokens = null)
        fun tools(capabilities: Set<DeviceCapability>) = AgentToolDefinitions.withLifestyleTools(request,
            includeWebSearch = false, includeAppUsage = false, includeRecentActivity = false,
            includeOpenApp = false, includeDeviceAssistant = true, deviceCapabilities = capabilities).tools
        assertFalse(tools(setOf(DeviceCapability.ScreenObservation, DeviceCapability.UiElementAction)).any { it.name == AgentToolDefinitions.TapVisualTargetToolName })
        assertFalse(tools(setOf(DeviceCapability.VisualTargetAction)).any { it.name == AgentToolDefinitions.TapVisualTargetToolName })
        val tap = tools(setOf(DeviceCapability.ScreenObservation, DeviceCapability.VisualTargetAction))
            .single { it.name == AgentToolDefinitions.TapVisualTargetToolName }
        assertTrue(tap.description.contains("including any crop"))
        assertTrue(tap.description.contains("returns a new image"))
    }
    private val json = Json { explicitNulls = false }

    @Test
    fun exposesAppUsageToolsAlongsideOptionalWebSearch() {
        val request = ChatRequest(
            model = "gpt-test",
            messages = emptyList(),
            temperature = 1.0,
            topP = 1.0,
            maxTokens = null
        )

        val withLifeTools = AgentToolDefinitions.withLifestyleTools(
            request = request,
            includeWebSearch = true,
            includeAppUsage = true,
            includeRecentActivity = false,
            includeOpenApp = true
        )

        assertEquals(
            listOf(
                AgentToolDefinitions.WebSearchToolName,
                AgentToolDefinitions.AppUsageSummaryToolName,
                "open_app"
            ),
            withLifeTools.tools.map { it.name }
        )
        assertEquals("auto", withLifeTools.toolChoice)
        assertEquals(false, withLifeTools.parallelToolCalls)
    }

    @Test
    fun appUsageToolSchemasDeclareSafeArguments() {
        val payload = json.encodeToString(AgentToolDefinitions.appUsageSummary().parameters)
        val recentPayload = json.encodeToString(AgentToolDefinitions.recentAppActivity().parameters)

        assertTrue(payload.contains(""""range""""))
        assertTrue(payload.contains(""""today""""))
        assertTrue(payload.contains(""""yesterday""""))
        assertTrue(payload.contains(""""last_7_days""""))
        assertTrue(recentPayload.contains(""""hours""""))
        assertTrue(recentPayload.contains(""""minimum":1"""))
        assertTrue(recentPayload.contains(""""maximum":24"""))
    }

    @Test
    fun openAppToolRequiresAnAppNameAndExplicitUserIntent() {
        val request = ChatRequest(
            model = "gpt-test",
            messages = emptyList(),
            temperature = 1.0,
            topP = 1.0,
            maxTokens = null
        )
        val openApp = AgentToolDefinitions.withLifestyleTools(
            request = request,
            includeWebSearch = false,
            includeAppUsage = false,
            includeRecentActivity = false,
            includeOpenApp = true
        )
            .tools
            .firstOrNull { it.name == "open_app" }

        assertNotNull(openApp)
        openApp ?: return
        val payload = json.encodeToString(openApp.parameters)
        assertTrue(openApp.description.contains("explicitly asks", ignoreCase = true))
        assertTrue(payload.contains("\"app_name\""))
        assertTrue(payload.contains("\"required\":[\"app_name\"]"))
        assertTrue(payload.contains("\"additionalProperties\":false"))
    }

    @Test
    fun omitsEveryDisabledLifestyleTool() {
        val request = ChatRequest(
            model = "gpt-test",
            messages = emptyList(),
            temperature = 1.0,
            topP = 1.0,
            maxTokens = null
        )

        val result = AgentToolDefinitions.withLifestyleTools(
            request = request,
            includeWebSearch = false,
            includeAppUsage = false,
            includeRecentActivity = false,
            includeOpenApp = false
        )

        assertTrue(result.tools.isEmpty())
        assertEquals(null, result.toolChoice)
        assertEquals(null, result.parallelToolCalls)
    }

    @Test
    fun exposesOnlyDeviceToolsSupportedByTheConnectedDevice() {
        val request = ChatRequest(
            model = "gpt-test",
            messages = emptyList(),
            temperature = 1.0,
            topP = 1.0,
            maxTokens = null
        )

        val result = AgentToolDefinitions.withLifestyleTools(
            request = request,
            includeWebSearch = false,
            includeAppUsage = false,
            includeRecentActivity = false,
            includeOpenApp = false,
            includeDeviceAssistant = true,
            includeForceStop = true,
            deviceCapabilities = setOf(
                DeviceCapability.DeviceStatus,
                DeviceCapability.ScreenBrightness,
                DeviceCapability.ForceStopApp
            )
        )

        assertEquals(
            listOf(
                AgentToolDefinitions.DeviceStatusToolName,
                AgentToolDefinitions.SetBrightnessToolName,
                AgentToolDefinitions.ForceStopAppToolName
            ),
            result.tools.map { it.name }
        )
        assertFalse(result.tools.any { it.name.contains("shell", ignoreCase = true) })
    }

    @Test
    fun forceStopToolDelegatesConfirmationToTheApp() {
        val description = AgentToolDefinitions.forceStopApp().description

        assertTrue(description.contains("invoke this tool directly", ignoreCase = true))
        assertTrue(description.contains("app presents", ignoreCase = true))
        assertTrue(description.contains("do not ask", ignoreCase = true))
    }

    @Test
    fun toolEnabledRequestsGuideTheModelToUseToolsNaturally() {
        val request = ChatRequest(
            model = "gpt-test",
            messages = listOf(ChatRequestMessage(role = "user", content = "How was my day?")),
            temperature = 1.0,
            topP = 1.0,
            maxTokens = null
        )

        val result = AgentToolDefinitions.withLifestyleTools(
            request = request,
            includeWebSearch = false,
            includeAppUsage = true,
            includeRecentActivity = false,
            includeOpenApp = false
        )

        val guidance = result.messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        assertTrue(guidance.contains("use available tools naturally", ignoreCase = true))
        assertTrue(guidance.contains("do not mention internal tool names", ignoreCase = true))
        assertTrue(guidance.contains("brief conversational transition", ignoreCase = true))
    }

    @Test
    fun exposesStructuredUiAutomationToolsOnlyForAvailableCapabilities() {
        val request = ChatRequest(
            model = "deepseek-test",
            messages = listOf(ChatRequestMessage(role = "user", content = "Open display settings")),
            temperature = 1.0,
            topP = 1.0,
            maxTokens = null
        )

        val result = AgentToolDefinitions.withLifestyleTools(
            request = request,
            includeWebSearch = false,
            includeAppUsage = false,
            includeRecentActivity = false,
            includeOpenApp = false,
            includeDeviceAssistant = true,
            deviceCapabilities = setOf(
                DeviceCapability.ScreenObservation,
                DeviceCapability.UiElementAction,
                DeviceCapability.TextInput,
                DeviceCapability.ScreenSwipe,
                DeviceCapability.Navigation
            )
        )

        assertEquals(
            listOf(
                AgentToolDefinitions.ObserveScreenToolName,
                AgentToolDefinitions.WaitForScreenToolName,
                AgentToolDefinitions.TapUiElementToolName,
                AgentToolDefinitions.InputTextToolName,
                AgentToolDefinitions.SwipeScreenToolName,
                AgentToolDefinitions.PressBackToolName,
                AgentToolDefinitions.PressHomeToolName
            ),
            result.tools.map { it.name }
        )
        val tapPayload = json.encodeToString(
            result.tools.first { it.name == AgentToolDefinitions.TapUiElementToolName }.parameters
        )
        val inputPayload = json.encodeToString(
            result.tools.first { it.name == AgentToolDefinitions.InputTextToolName }.parameters
        )
        assertTrue(tapPayload.contains("\"index\""))
        assertTrue(tapPayload.contains("\"screen_id\""))
        assertTrue(inputPayload.contains("\"screen_id\""))
        assertTrue(tapPayload.contains("\"long_press\""))
        assertTrue(inputPayload.contains("\"clear_existing\""))
        val guidance = result.messages.first { it.role == "system" }.content.orEmpty()
        assertTrue(guidance.contains("untrusted screen data", ignoreCase = true))
        assertTrue(guidance.contains("password", ignoreCase = true))
    }
}
