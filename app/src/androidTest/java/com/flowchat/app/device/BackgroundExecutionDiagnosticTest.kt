package com.flowchat.app.device

import android.app.UiAutomation
import android.content.*
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.ProcessDeviceCommandExecutor
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.*
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual settings, gateway, Root Binder, accessibility and cross-UID fixture. No model requests. */
class BackgroundExecutionDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun report(key: String, value: Any) = instrumentation.sendStatus(1, Bundle().apply { putString(key, value.toString()) })
    private suspend fun until(timeout: Long = 10_000, condition: suspend () -> Boolean) = withTimeout(timeout) { while (!condition()) delay(100) }

    @Test fun actualGatewayUnicodeVisualIsolationSecureWindowsAndTaskCleanup() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBackgroundExecutionDiagnostics") == "true")
        shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
        val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
        val originalServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            val services = originalServices.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
            if (services.none { ComponentName.unflattenFromString(it) == expected }) services.add(expected.flattenToString())
            if (FlowChatAccessibilityService.current() == null) {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    services.filterNot { ComponentName.unflattenFromString(it) == expected }.joinToString(":"))
                delay(300)
            }
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } finally { automation.dropShellPermissionIdentity() }
        until { FlowChatAccessibilityService.current() != null }
        report("BACKGROUND_STAGE", "accessibility-ready")
        val entry = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        assertTrue("Do not interrupt an existing user task", entry.runtime().activeConversations.value.isEmpty())
        val settings = entry.settings()
        val saved = settings.state.value
        val gateway = entry.deviceGateway()
        val tasks = entry.deviceTasks()
        val root = entry.root()
        val metrics = ConcurrentHashMap<String, JSONObject>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(owner: Context, intent: Intent) {
                metrics[intent.getStringExtra("kind")!!] = JSONObject(intent.getStringExtra("data")!!)
            }
        }
        val filter = IntentFilter("${context.packageName}.EXECUTION_FIXTURE_RESULT")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        val testPackage = "${context.packageName}.test"
        val fixture = "com.flowchat.app.device.ExecutionDisplayFixtureActivity"
        var task: DeviceTaskControl.Task? = null
        try {
            settings.setPowerModeEnabled(true)
            settings.setPowerConnectionMode(PowerConnectionMode.Root)
            settings.setBackgroundExecutionEnabled(true)
            until { gateway.backgroundExecutionRequested }
            root.refresh(requestPermission = true)
            assertTrue(root.state.value.toString(), root.state.value.isConnected)
            report("BACKGROUND_STAGE", "root-ready")
            shell("am start -W -n $testPackage/com.flowchat.app.device.ExecutionDisplayPrimaryFixtureActivity")
            until { metrics.containsKey("primary") }
            fun focus(): String {
                var primary = false
                return shell("dumpsys window displays").lineSequence().filter { line ->
                    if (line.contains("Display: mDisplayId=")) primary = line.contains("mDisplayId=0 ")
                    primary && (line.contains("mCurrentFocus=") || line.contains("mFocusedApp="))
                }.joinToString("\n")
            }
            val focusBefore = focus()
            task = tasks.begin().also(tasks::arm)
            var result = gateway.launchApp(testPackage, fixture)
            assertTrue(result.asToolContent(), result.success)
            val firstId = requireNotNull(result.screen?.geometry).displayId
            assertTrue(firstId > 0)
            assertNotNull(result.screenImage)
            report("BACKGROUND_DISPLAY_ID", firstId)
            assertEquals(1, root.serviceInfo()!!.activeDisplays)
            fun inputIndex(screen: DeviceScreenSnapshot) = screen.elements.single { it.contentDescription == "execution_fixture_input" }.index
            fun applyIndex(screen: DeviceScreenSnapshot) = screen.elements.single { it.contentDescription == "execution_fixture_apply" }.index
            val old = requireNotNull(result.screen)
            result = gateway.inputText(inputIndex(old), "中文后台输入", true, old.screenId)
            assertTrue(result.asToolContent(), result.success)
            val stale = gateway.tapUiElement(applyIndex(old), false, old.screenId)
            assertFalse(stale.asToolContent(), stale.success)
            assertEquals("stale_screen", stale.errorCode)
            result = gateway.captureScreen()
            val typed = requireNotNull(result.screen)
            result = gateway.tapUiElement(applyIndex(typed), false, typed.screenId)
            assertTrue(result.asToolContent(), result.success)
            until { metrics["secondary"]?.optString("text") == "中文后台输入" }
            assertEquals(firstId, metrics.getValue("secondary").getInt("displayId"))
            result = gateway.captureScreen()
            val textScreen = requireNotNull(result.screen)
            result = gateway.inputText(inputIndex(textScreen), "副屏视觉验证", true, textScreen.screenId)
            assertTrue(result.asToolContent(), result.success)
            val screen = requireNotNull(result.screen)
            val image = requireNotNull(result.screenImage)
            val target = screen.elements.single { it.contentDescription == "execution_fixture_apply" }
            val bounds = requireNotNull(image.captureBounds)
            val visual = VisualTapTarget(image.screenId, (target.bounds.centerX - bounds.left) * 1000 / bounds.width,
                (target.bounds.centerY - bounds.top) * 1000 / bounds.height, "APPLY button")
            result = gateway.tapVisualTarget(visual)
            assertTrue(result.asToolContent(), result.success)
            until { metrics["secondary"]?.optString("text") == "副屏视觉验证" }
            report("BACKGROUND_UNICODE_AND_VISUAL", metrics.getValue("secondary"))
            if (InstrumentationRegistry.getArguments().getString("runBackgroundPerformance") == "true") {
                AutomationTimings.clear()
                val elapsed = mutableListOf<Double>()
                repeat(12) {
                    val started = System.nanoTime()
                    val captured = gateway.captureScreen()
                    assertTrue(captured.asToolContent(), captured.success)
                    assertEquals(firstId, captured.screen?.geometry?.displayId)
                    elapsed.add((System.nanoTime() - started) / 1_000_000.0)
                }
                val sorted = elapsed.sorted()
                val performance = JSONObject().put("count", sorted.size).put("samplesMs", org.json.JSONArray(elapsed))
                    .put("p50Ms", sorted[sorted.size / 2]).put("p95Ms", sorted[(sorted.size * .95).toInt().coerceAtMost(sorted.lastIndex)])
                    .put("refreshRateHz", context.getSystemService(DisplayManager::class.java).getDisplay(firstId).refreshRate)
                val controller = gateway.javaClass.getDeclaredField("background").apply { isAccessible = true }.get(gateway)
                performance.put("captureIntervalMs", controller.javaClass.getDeclaredField("captureIntervalMillis")
                    .apply { isAccessible = true }.getLong(controller))
                val stages = JSONObject()
                AutomationTimings.snapshot().groupBy { it.stage }.forEach { (stage, values) ->
                    stages.put(stage, JSONObject().put("count", values.size).put("averageMs", values.map { it.durationMillis }.average()))
                }
                performance.put("stages", stages)
                report("BACKGROUND_CAPTURE_BENCH", performance)
            }
            assertFalse(gateway.setScreenBrightness(50).success)
            assertFalse(gateway.setMediaVolume(50).success)
            assertFalse(gateway.forceStopApp(testPackage).success)
            assertTrue(runCatching { gateway.startShellCommand("input tap 1 1", "/data/local/tmp", 5) }.isFailure)
            assertEquals(focusBefore, focus())
            assertEquals(0, metrics.getValue("primary").getInt("touches"))
            val cleared = gateway.pressHome()
            assertTrue(cleared.asToolContent(), cleared.success)
            assertNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(firstId))
            result = gateway.launchApp(testPackage, fixture)
            assertTrue(result.asToolContent(), result.success)
            tasks.finish(task)
            until { root.serviceInfo()?.activeDisplays == 0 }
            until { metrics.containsKey("secondaryDestroyed") }
            until { context.getSystemService(DisplayManager::class.java).getDisplay(firstId) == null }
            assertEquals(focusBefore, focus())
            report("BACKGROUND_RELEASE", "task-finish:display-removed,activity-destroyed,primary-touches=0,primary-focus-unchanged")

            task = tasks.begin().also(tasks::arm)
            result = gateway.launchApp(testPackage, "com.flowchat.app.device.ExecutionDisplaySecureFixtureActivity")
            assertFalse(result.asToolContent(), result.success)
            assertEquals("secure_window_blocked", result.errorCode)
            assertNull(result.screen)
            assertNull(result.screenImage)
            report("BACKGROUND_SECURE", result.errorCode!!)
            tasks.finish(task)
            until { root.serviceInfo()?.activeDisplays == 0 }

            task = tasks.begin().also(tasks::arm)
            result = gateway.launchApp(testPackage, fixture)
            assertTrue(result.asToolContent(), result.success)
            val lostId = result.screen!!.geometry!!.displayId
            if (InstrumentationRegistry.getArguments().getString("backgroundCrashRoot") == "true") {
                val service = requireNotNull(root.serviceInfo())
                assertEquals(0, service.uid)
                assertTrue(service.pid > 1 && service.pid != android.os.Process.myPid())
                val killed = withContext(Dispatchers.IO) {
                    ProcessDeviceCommandExecutor(root = true).use { it.execute(listOf("/system/bin/kill", "-KILL", service.pid.toString())) }
                }
                assertTrue(killed.output, killed.success)
                until { root.serviceInfo() == null }
                report("BACKGROUND_ROOT_CRASH", "Owned Root service killed; display cleanup follows Binder death")
            } else root.release()
            until { context.getSystemService(DisplayManager::class.java).getDisplay(lostId) == null }
            val lost = gateway.observeScreen()
            assertFalse(lost.asToolContent(), lost.success)
            assertNull(root.serviceInfo())
            assertEquals(focusBefore, focus())
            report("BACKGROUND_LOST", "No reconnect or primary fallback in the interrupted task")
            tasks.finish(task)
            if (InstrumentationRegistry.getArguments().getString("runBackgroundCompatibility") == "true") {
                root.refresh(requestPermission = true)
                task = tasks.begin().also(tasks::arm)
                val component = ComponentName("com.android.bbkcalculator", "com.android.bbkcalculator.Calculator")
                @Suppress("DEPRECATION")
                val activity = context.packageManager.getActivityInfo(component, 0)
                val compatible = gateway.launchApp(component.packageName, component.className)
                if (activity.launchMode <= android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP) {
                    assertTrue(compatible.asToolContent(), compatible.success)
                    assertEquals(component.packageName, compatible.screen?.packageName)
                    assertNotNull(compatible.screenImage)
                    report("BACKGROUND_REAL_APP", "vivo calculator launched and captured on display ${compatible.screen!!.geometry!!.displayId}")
                } else {
                    assertFalse(compatible.success)
                    report("BACKGROUND_REAL_APP", "vivo calculator shared launchMode=${activity.launchMode} rejected before launch")
                }
                assertEquals(focusBefore, focus())
                assertEquals(0, metrics.getValue("primary").getInt("touches"))
                tasks.finish(task)
                until { root.serviceInfo()?.activeDisplays == 0 }
            }
        } finally {
            tasks.finish(task)
            settings.setBackgroundExecutionEnabled(saved.backgroundExecutionEnabled)
            settings.setPowerConnectionMode(saved.powerConnectionMode)
            settings.setPowerModeEnabled(saved.powerModeEnabled)
            shell("am broadcast -a $testPackage.FINISH_EXECUTION_PRIMARY -p $testPackage")
            context.unregisterReceiver(receiver)
            context.getSharedPreferences("app_controls", Context.MODE_PRIVATE).edit().commit()
        }
    }
}
