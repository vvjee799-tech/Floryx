package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.DeviceSwipeDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in only: runs one user-authorized swipe in Douyin, without calling an AI provider. */
class PhoneAutomationDiagnosticTest {
    @Test fun inspectAuthorizedDouyinSwipe() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPhoneDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(label: String, detail: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$label\n$detail\n") })
        }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                    delay(300)
                } finally {
                    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existing)
                }
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current() == null) delay(200) }
        val root = RootDeviceConnection()
        val gateway = ShizukuDeviceAssistantGateway(context, AppSettingsStore(context), root)
        root.refresh(requestPermission = true)
        check(root.state.value.isConnected) { "Root connection is unavailable." }
        withTimeout(10_000) { while (!gateway.connectionState.value.isConnected) delay(100) }
        report("CONNECTION", gateway.connectionState.value.status.name)
        report("LAUNCH", AndroidAppLauncher(context, gateway).openApp("抖音"))
        delay(2_000)
        val before = gateway.observeScreen()
        report("BEFORE", before.asToolContent())
        assertEquals("Must observe Douyin before any swipe", "com.ss.android.ugc.aweme", before.screen?.packageName)
        assertFalse(before.screen!!.sensitive)
        val result = gateway.swipeScreen(DeviceSwipeDirection.Up, 50)
        report("SWIPE", result.asToolContent())
        delay(1_000)
        val after = gateway.observeScreen()
        report("AFTER_SETTLED", after.asToolContent())
        assertTrue(result.asToolContent(), result.success)
        assertNotEquals("A successful dispatch must also change the observed video content.",
            before.screen.progressKey(), after.screen?.progressKey())
    }
}
