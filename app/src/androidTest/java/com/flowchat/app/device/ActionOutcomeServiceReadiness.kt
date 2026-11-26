package com.flowchat.app.device

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*

/** Shared recovery of an existing service choice for each new instrumentation process. */
internal class ActionOutcomeServiceReadiness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    fun publicServiceEnabled(expected: ComponentName): Boolean? = runCatching {
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { enabled ->
                val component = enabled.resolveInfo?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
                component == expected || ComponentName.unflattenFromString(enabled.id.orEmpty()) == expected
            }
    }.getOrNull()

    suspend fun ensureService() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
        val original = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("settings get secure enabled_accessibility_services")
        ).bufferedReader().use { it.readText() }.trim().takeUnless { it == "null" }.orEmpty()
        val shellEnabled = original.split(':').any { ComponentName.unflattenFromString(it) == expected }
        val publicEnabled = publicServiceEnabled(expected)
        val targetSettingsEnabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == expected }
        }.getOrNull()
        instrumentation.sendStatus(1, Bundle().apply {
            putString("ACCESSIBILITY_QUERY_SNAPSHOT", "shell_enabled=$shellEnabled public_enabled=$publicEnabled target_settings_enabled=$targetSettingsEnabled service_bound=${FlowChatAccessibilityService.current() != null}")
        })
        if (FlowChatAccessibilityService.current() != null) return
        context.startActivity(Intent(context, com.flowchat.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(shellEnabled) {
            "Enable Floryx Dev accessibility before this diagnostic."
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            try {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    original.split(':').filterNot { ComponentName.unflattenFromString(it) == expected }.joinToString(":"))
                delay(250)
            } finally {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)
            }
        } finally { automation.dropShellPermissionIdentity() }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current() == null) delay(100) }
    }

}
