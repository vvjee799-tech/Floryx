package com.flowchat.app.data.applauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackgroundAppLaunchTest {
    @Test fun disconnectedBackgroundLaunchCannotFallThroughToThePhysicalScreen() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.fixture"; name = "com.example.fixture.Main"
                applicationInfo = ApplicationInfo().apply { packageName = "com.example.fixture"; nonLocalizedLabel = "Fixture" }
            }
            nonLocalizedLabel = "Fixture"
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, info)
        var called = false
        val gateway = object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
            override val backgroundExecutionRequested = true
            override suspend fun launchApp(packageName: String, activityName: String): DeviceToolResult {
                called = true
                return DeviceToolResult(false, "background disconnected")
            }
        }
        val result = runCatching { AndroidAppLauncher(context, gateway).openApp("Fixture") }
        assertTrue(result.isFailure)
        assertTrue(called)
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
