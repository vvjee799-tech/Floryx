package com.flowchat.app.data.device

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuIntegrationContractTest {
    @Test
    fun projectUsesOfficialProviderAndTypedUserService() {
        val build = File("build.gradle.kts").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aidl = File("src/main/aidl/com/flowchat/app/shizuku/IFlowChatShizukuService.aidl")
        val gateway = File("src/main/java/com/flowchat/app/data/device/ShizukuDeviceAssistantGateway.kt")
        val service = File("src/main/java/com/flowchat/app/data/device/FlowChatShizukuService.kt")
        val toolDefinitions = File("src/main/java/com/flowchat/app/domain/tools/AgentToolDefinitions.kt").readText()

        assertTrue(build.contains("buildFeatures"))
        assertTrue(build.contains("aidl = true"))
        assertTrue(build.contains("dev.rikka.shizuku:api:13.1.5"))
        assertTrue(build.contains("dev.rikka.shizuku:provider:13.1.5"))
        assertTrue(manifest.contains("rikka.shizuku.ShizukuProvider"))
        assertTrue(aidl.exists())
        assertTrue(gateway.exists())
        assertTrue(service.exists())

        val aidlSource = aidl.readText()
        val gatewaySource = gateway.readText()
        val serviceSource = service.readText()
        assertTrue(aidlSource.contains("String getDeviceStatus()"))
        assertTrue(aidlSource.contains("String setScreenBrightness(int percent)"))
        assertTrue(aidlSource.contains("String setMediaVolume(int percent)"))
        assertTrue(aidlSource.contains("String forceStopPackage(String packageName)"))
        assertTrue(aidlSource.contains("String enablePowerMode()"))
        assertTrue(gatewaySource.contains("Shizuku.UserServiceArgs"))
        assertTrue(gatewaySource.contains("Shizuku.bindUserService"))
        assertTrue(gatewaySource.contains("hasLiveUserService()"))
        assertTrue(gatewaySource.contains("probeConnectedService()"))
        assertTrue(gatewaySource.contains("override suspend fun enablePowerMode()"))
        assertFalse(gatewaySource.contains("Shizuku.newProcess"))
        assertTrue(serviceSource.contains("runCommand(\"am\", \"get-current-user\")"))
        assertTrue(serviceSource.contains("override fun enablePowerMode()"))
        assertTrue(serviceSource.contains("GET_USAGE_STATS"))
        assertTrue(serviceSource.contains("SYSTEM_ALERT_WINDOW"))
        assertTrue(serviceSource.contains("enabled_accessibility_services"))
        assertFalse(serviceSource.contains("runCommand(\"am\", \"help\")"))
        assertFalse(toolDefinitions.contains("run_shell"))
        assertFalse(toolDefinitions.contains("execute_shell"))
    }

    @Test
    fun activityRefreshesDeviceConnectionsWheneverItReturnsToForeground() {
        val activity = File("src/main/java/com/flowchat/app/MainActivity.kt").readText()

        assertTrue(activity.contains("lateinit var deviceAssistantGateway: DeviceAssistantGateway"))
        assertTrue(activity.contains("deviceAssistantGateway.refreshConnection()"))
        assertTrue(activity.contains("deviceAssistantGateway.refreshAccessibilityConnection()"))
    }
}
