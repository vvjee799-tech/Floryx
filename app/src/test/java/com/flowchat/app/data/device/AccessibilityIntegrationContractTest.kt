package com.flowchat.app.data.device

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityIntegrationContractTest {
    @Test
    fun appDeclaresAStructuredUiAutomationService() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val config = File("src/main/res/xml/accessibility_service_config.xml")
        val service = File("src/main/java/com/flowchat/app/data/device/FlowChatAccessibilityService.kt")
        val gateway = File("src/main/java/com/flowchat/app/data/device/ShizukuDeviceAssistantGateway.kt")

        assertTrue(config.exists())
        assertTrue(service.exists())
        assertTrue(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(manifest.contains(".data.device.FlowChatAccessibilityService"))

        val configSource = config.readText()
        assertTrue(configSource.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(configSource.contains("android:canPerformGestures=\"true\""))

        val serviceSource = service.readText()
        assertTrue(serviceSource.contains("rootInActiveWindow"))
        assertTrue(serviceSource.contains("AccessibilityNodeInfo.ACTION_SET_TEXT"))
        assertTrue(serviceSource.contains("dispatchGesture"))
        assertTrue(serviceSource.contains("GLOBAL_ACTION_BACK"))
        assertTrue(serviceSource.contains("GLOBAL_ACTION_HOME"))
        assertTrue(serviceSource.contains("MaxObservedElements"))
        assertFalse(serviceSource.contains("Log.d"))
        assertFalse(serviceSource.contains("Log.i"))

        val gatewaySource = gateway.readText()
        assertTrue(gatewaySource.contains("override suspend fun observeScreen()"))
        assertTrue(gatewaySource.contains("override suspend fun tapUiElement("))
        assertTrue(gatewaySource.contains("override suspend fun inputText("))
        assertTrue(gatewaySource.contains("override suspend fun swipeScreen("))
    }
}
