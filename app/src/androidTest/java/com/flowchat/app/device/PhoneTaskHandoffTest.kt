package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly enabled on the user's phone. No provider calls, swipes or account actions. */
class PhoneTaskHandoffTest {
    @Test fun returningToFlowChatBlocksTheOldTaskFromReopeningDouyin() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPhoneHandoffDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$message\n")
        })
        fun openFlowChat() {
            val result = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "am start -W -n ${context.packageName}/${MainActivity::class.java.name}"
            )).use { it.readBytes().toString(Charsets.UTF_8) }
            report("Select FlowChat: $result")
            check(!result.contains("Error:") && !result.contains("Exception")) { result }
        }
        val activityMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        openFlowChat()
        checkNotNull(instrumentation.waitForMonitorWithTimeout(activityMonitor, 10_000)) { "FlowChat Activity did not start" }
        instrumentation.removeMonitor(activityMonitor)
        delay(800)
        if (FlowChatAccessibilityService.current() == null) {
            val existing = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val component = ComponentName(context, FlowChatAccessibilityService::class.java)
            check(existing.split(':').mapNotNull(ComponentName::unflattenFromString).contains(component))
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                try {
                    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        existing.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":"))
                    delay(250)
                } finally {
                    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existing)
                }
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, DeviceDiagnosticsEntryPoint::class.java)
        val bridge = dependencies.bridge()
        val gateway = dependencies.gateway()
        gateway.requestPermission()
        withTimeout(15_000) { while (!gateway.connectionState.value.isConnected || !bridge.appInForeground.value) delay(100) }
        report("FlowChat started; Power connection ready. Foreground: ${gateway.getForegroundApp().summary}")
        val control = bridge.deviceTaskControl
        val task = control.begin()
        try {
            control.arm(task)
            dependencies.launcher().openApp("抖音")
            report("Douyin launch returned; appInForeground=${bridge.appInForeground.value}")
            withTimeout(10_000) { while (bridge.appInForeground.value) delay(100) }
            assertEquals("foreground_package=com.ss.android.ugc.aweme", gateway.getForegroundApp().summary)
            report("Douyin is foreground; selecting FlowChat")
            // Select FlowChat as a user would. Its real Activity lifecycle must latch the takeover.
            openFlowChat()
            withTimeout(10_000) { while (!task.interrupted) delay(100) }
            report("FlowChat lifecycle recorded user takeover")
            var rejected = false
            try {
                dependencies.launcher().openApp("抖音")
            } catch (_: CancellationException) {
                rejected = true
            }
            assertTrue("Old task must not reopen Douyin after the user returns", rejected)
            delay(1_000)
            assertEquals("FlowChat must retain focus", "foreground_package=${context.packageName}", gateway.getForegroundApp().summary)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nOpened Douyin -> returned to FlowChat -> old relaunch rejected -> FlowChat retained foreground. No model API calls or swipes.\n")
            })
        } finally {
            control.finish(task)
        }
    }
}
