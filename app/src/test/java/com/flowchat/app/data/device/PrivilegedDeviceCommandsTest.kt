package com.flowchat.app.data.device

import com.flowchat.app.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegedDeviceCommandsTest {
    @Test fun launchRequiresAValidComponentAndVerifiesForegroundRatherThanExitCode() {
        var foreground = "com.android.launcher"
        val commands = mutableListOf<List<String>>()
        val service = FlowChatShizukuService { command ->
            commands += command
            DeviceCommandResult(true, if (command.take(3) == listOf("dumpsys", "activity", "activities"))
                "topResumedActivity=ActivityRecord{123 u0 $foreground/.MainActivity t1}" else "Status: ok")
        }
        assertEquals("app_not_foreground", JSONObject(service.launchApp("com.test.video", "com.test.video.MainActivity")).getString("errorCode"))
        foreground = "com.test.video"
        assertTrue(JSONObject(service.launchApp(foreground, "com.test.video.MainActivity")).getBoolean("success"))
        assertTrue(commands.contains(listOf("am", "start", "-W", "-n", "com.test.video/com.test.video.MainActivity")))
        val size = commands.size
        assertFalse(JSONObject(service.launchApp("com.test.video; reboot", "MainActivity")).getBoolean("success"))
        assertEquals(size, commands.size)
    }
    @Test
    fun powerModePreservesOtherAccessibilityServicesAndTargetsCurrentApp() {
        val commands = mutableListOf<List<String>>()
        val service = FlowChatShizukuService { command ->
            commands.add(command)
            DeviceCommandResult(true, if (command == listOf("settings", "get", "secure", "enabled_accessibility_services")) {
                "other.app/.Reader"
            } else "")
        }
        assertTrue(JSONObject(service.enablePowerMode()).getBoolean("success"))
        assertTrue(commands.contains(listOf("cmd", "appops", "set", BuildConfig.APPLICATION_ID, "GET_USAGE_STATS", "allow")))
        assertEquals(
            "other.app/.Reader:${BuildConfig.APPLICATION_ID}/com.flowchat.app.data.device.FlowChatAccessibilityService",
            commands.first { it.take(4) == listOf("settings", "put", "secure", "enabled_accessibility_services") }.last()
        )
    }

    @Test
    fun failedSettingsReadNeverOverwritesAccessibilityServices() {
        val commands = mutableListOf<List<String>>()
        val service = FlowChatShizukuService { command ->
            commands.add(command)
            DeviceCommandResult(false, "Permission denied")
        }
        assertFalse(JSONObject(service.enablePowerMode()).getBoolean("success"))
        assertFalse(commands.any { it.take(2) == listOf("settings", "put") })
        assertFalse(JSONObject(service.getDeviceStatus()).getBoolean("success"))
    }

    @Test
    fun forceStopRejectsShellInjectionAndSelfStop() {
        val service = FlowChatShizukuService { error("No command should run") }
        assertFalse(JSONObject(service.forceStopPackage("com.example; reboot")).getBoolean("success"))
        assertFalse(JSONObject(service.forceStopPackage(BuildConfig.APPLICATION_ID)).getBoolean("success"))
        assertEquals("'a'\\''b'", ProcessDeviceCommandExecutor.quoteShellArgument("a'b"))
        assertEquals("'$(id); reboot'", ProcessDeviceCommandExecutor.quoteShellArgument("$(id); reboot"))
    }
}
