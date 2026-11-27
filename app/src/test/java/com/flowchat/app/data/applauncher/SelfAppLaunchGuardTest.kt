package com.flowchat.app.data.applauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.UnavailableDeviceAssistantGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelfAppLaunchGuardTest {
    @Test
    fun refusesLaunchingFloryxItselfDuringAutomation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val selfInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = context.packageName
                name = "${context.packageName}.MainActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = context.packageName
                    nonLocalizedLabel = "Floryx"
                }
            }
            nonLocalizedLabel = "Floryx"
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, selfInfo)
        val gateway = object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {}

        val result = runCatching { AndroidAppLauncher(context, gateway).openApp("Floryx") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
