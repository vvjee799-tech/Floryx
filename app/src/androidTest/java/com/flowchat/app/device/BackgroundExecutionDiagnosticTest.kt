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
import com.flowchat.app.data.device.RootDeviceConnection
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

    private suspend fun awaitDisplayCleanup(label: String, displayId: Int, root: RootDeviceConnection) {
        val manager = context.getSystemService(DisplayManager::class.java)
        var evidence = "not sampled"
        try {
            until {
                val info = root.serviceInfo()
                val removed = manager.getDisplay(displayId) == null
                evidence = "id=$displayId removed=$removed service=$info state=${root.state.value} visibleIds=${manager.displays.map { it.displayId }}"
                removed && (info == null || info.activeDisplays == 0)
            }
        } catch (error: TimeoutCancellationException) {
            report("BACKGROUND_CLEANUP_TIMEOUT", "$label: $evidence")
            throw AssertionError("Timed out waiting for $label cleanup: $evidence", error)
        }
        report("BACKGROUND_CLEANUP", "$label: $evidence")
    }

    @Test fun actualGatewayUnicodeVisualIsolationSecureWindowsAndTaskCleanup() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBackgroundExecutionDiagnostics") == "true")
        shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
        val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
        val originalServices = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        check(originalServices.split(':').any { ComponentName.unflattenFromString(it) == expected }) {
            "Enable Floryx Dev accessibility before running background diagnostics."
        }
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            if (FlowChatAccessibilityService.current() == null) {
                try {
                    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        originalServices.split(':').filterNot { ComponentName.unflattenFromString(it) == expected }.joinToString(":"))
                    delay(300)
                } finally {
                    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices)
                }
            }
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
            val condition = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(testPackage,
                element = DeviceUiSelector(text = "RESULT=副屏视觉验证")), 3_000))
            assertTrue(condition.asToolContent(), condition.success)
            assertEquals(true, condition.conditionMatched)
            assertEquals(DeviceActionOutcome.TargetVerified, condition.actionOutcome)
            assertEquals(firstId, condition.screen?.geometry?.displayId)
            assertNotNull(condition.screenImage)
            report("BACKGROUND_CONDITION", "exact-result-verified-on-owned-display;primary-not-observed")
            result = gateway.launchApp(testPackage, "com.flowchat.app.device.ScopedObservationFixtureActivity")
            assertTrue(result.asToolContent(), result.success)
            val scopedRoot = DeviceObservationScope(DeviceUiSelector(description = "r2b_scope"))
            val scoped = gateway.observeScreen(scopedRoot)
            assertTrue(scoped.asToolContent(), scoped.success)
            assertEquals(firstId, scoped.screen?.geometry?.displayId)
            assertTrue(scoped.screen!!.elements.none { it.text.orEmpty().startsWith("Outside") })
            val scopedInput = scoped.screen!!.elements.single { it.contentDescription == "r2b_action" }
            val scopedAction = gateway.tapUiElement(scopedInput.index, false, scoped.screen!!.screenId)
            assertTrue(scopedAction.asToolContent(), scopedAction.success)
            assertEquals(scopedRoot, scopedAction.screen!!.observationScope)
            val regionalBaseline = gateway.captureScreen(ScreenImageRegion(10, 72, 90, 87))
            assertTrue(regionalBaseline.asToolContent(), regionalBaseline.success)
            context.sendBroadcast(Intent("$testPackage.R2B_CONTROL").setPackage(testPackage).putExtra("command", "delayed"))
            val regional = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(
                regionChangedFrom = regionalBaseline.screenImage!!.screenId), 5000, scopedRoot))
            assertTrue(regional.asToolContent(), regional.success)
            assertEquals(DeviceActionOutcome.ProgressObserved, regional.actionOutcome)
            assertEquals(firstId, regional.screen?.geometry?.displayId)
            assertEquals(0, metrics.getValue("primary").getInt("touches"))
            report("BACKGROUND_SCOPED_REGION", "scoped-action-and-delayed-crop-verified-on-owned-display;primary-touches=0")
            result = gateway.launchApp(testPackage, ControlledInteractionFixtureActivity::class.java.name)
            assertTrue(result.asToolContent(), result.success)
            var editorScreen = requireNotNull(result.screen)
            fun editorIndex(screen: DeviceScreenSnapshot) = screen.elements.single { it.contentDescription == "r3_first" }.index
            suspend fun freshBackgroundAction(operation: suspend (DeviceScreenSnapshot) -> DeviceToolResult): DeviceToolResult {
                var current = requireNotNull(gateway.observeScreen().screen)
                repeat(3) {
                    val outcome = operation(current)
                    // A late selection popup may invalidate the observation. Only a guaranteed
                    // no-dispatch rejection permits re-targeting; never repeat an uncertain write.
                    if (outcome.errorCode != "stale_screen" || outcome.actionOutcome != DeviceActionOutcome.NotDispatched) return outcome
                    current = requireNotNull(gateway.observeScreen().screen)
                }
                error("The background window did not settle before dispatch")
            }
            result = gateway.setTextSelection(DeviceTextSelection(editorIndex(editorScreen), 1, 3), editorScreen.screenId)
            assertTrue(result.asToolContent(), result.success)
            editorScreen = requireNotNull(result.screen)
            result = freshBackgroundAction { fresh -> gateway.editText(DeviceTextEditRequest(editorIndex(fresh), "后台中", DeviceTextEditMode.ReplaceSelection), fresh.screenId) }
            assertTrue(result.asToolContent(), result.success)
            assertEquals("甲后台中乙", result.screen!!.elements.single { it.contentDescription == "r3_first" }.text)
            assertEquals(4, result.screen!!.elements.single { it.contentDescription == "r3_first" }.textSelectionStart)
            editorScreen = requireNotNull(result.screen)
            val overLimit = gateway.editText(DeviceTextEditRequest(editorIndex(editorScreen), "x".repeat(4001), DeviceTextEditMode.Replace), editorScreen.screenId)
            assertEquals("ui_text_too_long", overLimit.errorCode)
            assertEquals(DeviceActionOutcome.NotDispatched, overLimit.actionOutcome)
            editorScreen = requireNotNull(gateway.observeScreen().screen)
            val label = requireNotNull(editorScreen.elements.single { it.contentDescription == "r3_first" }.editorActionLabel)
            val editorAction = freshBackgroundAction { fresh -> gateway.performEditorAction(editorIndex(fresh), label, fresh.screenId) }
            assertTrue(editorAction.asToolContent(), editorAction.success)
            assertEquals(DeviceActionOutcome.ProgressObserved, editorAction.actionOutcome)
            assertEquals(firstId, editorAction.screen?.geometry?.displayId)
            context.sendBroadcast(Intent("$testPackage.R3_CONTROL").setPackage(testPackage).putExtra("command", "lists"))
            delay(250)
            val containerScope = DeviceObservationScope(DeviceUiSelector(description = "r3_inner"), DeviceObservationScope.Kind.ScrollContainer)
            val containerScreen = requireNotNull(gateway.observeScreen(containerScope).screen)
            val containerResult = gateway.scrollContainer(containerScreen.elements.single { it.contentDescription == "r3_inner" }.index,
                DeviceScrollDirection.Forward, containerScreen.screenId)
            assertTrue(containerResult.asToolContent(), containerResult.success)
            assertEquals(DeviceActionOutcome.ProgressObserved, containerResult.actionOutcome)
            assertEquals(firstId, containerResult.screen?.geometry?.displayId)
            assertEquals(containerScope, containerResult.screen!!.observationScope)
            assertEquals(0, metrics.getValue("primary").getInt("touches"))
            assertEquals(focusBefore, focus())
            report("BACKGROUND_CONTROLLED_INTERACTION", "selection-text-cursor-editor-and-container=owned-display;4000-limit=blocked;primary-touches=0")
            if (InstrumentationRegistry.getArguments().getString("runAdvancedInteractionDiagnostics") == "true") {
                val advancedPackage = "com.flowchat.fixture.r3"
                val advancedMetrics = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
                val advancedReplies = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
                val advancedReceiver = object : BroadcastReceiver() {
                    override fun onReceive(owner: Context, intent: Intent) {
                        val state = JSONObject(intent.getStringExtra("data")!!)
                        advancedMetrics.set(state)
                        state.optString("requestId").takeIf { it.isNotBlank() }?.let { advancedReplies[it] = state }
                    }
                }
                val advancedFilter = IntentFilter("${context.packageName}.R3B_RESULT")
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(advancedReceiver, advancedFilter, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(advancedReceiver, advancedFilter)
                try {
                    suspend fun advancedControl(command: String): JSONObject {
                        val requestId = java.util.UUID.randomUUID().toString()
                        context.sendBroadcast(Intent("$advancedPackage.R3B_CONTROL").setPackage(advancedPackage)
                            .putExtra("command", command).putExtra("requestId", requestId))
                        // Touch metrics are asynchronous broadcasts. Require this command's reply,
                        // rather than accepting an older queued movement as the post-cancel state.
                        until { advancedReplies.containsKey(requestId) }
                        return requireNotNull(advancedReplies.remove(requestId))
                    }
                    result = gateway.launchApp(advancedPackage, "$advancedPackage.FixtureActivity")
                    assertTrue(result.asToolContent(), result.success)
                    fun advancedIndex(screen: DeviceScreenSnapshot, description: String) = screen.elements.single { it.contentDescription == description }.index
                    var advancedScreen = requireNotNull(result.screen)
                    result = gateway.adjustRange(DeviceRangeAdjustment(advancedIndex(advancedScreen, "r3b_slider"), 7f), advancedScreen.screenId)
                    assertTrue(result.asToolContent(), result.success)
                    assertEquals(DeviceActionOutcome.TargetVerified, result.actionOutcome)
                    assertEquals(firstId, result.screen!!.geometry!!.displayId)
                    advancedScreen = requireNotNull(result.screen)
                    result = gateway.anchoredGesture(DeviceAnchoredGesture.DoubleTap(advancedIndex(advancedScreen, "r3b_double")), advancedScreen.screenId)
                    assertTrue(result.asToolContent(), result.success)
                    advancedScreen = requireNotNull(result.screen)
                    result = gateway.anchoredGesture(DeviceAnchoredGesture.Drag(advancedIndex(advancedScreen, "r3b_source"),
                        advancedIndex(advancedScreen, "r3b_drop")), advancedScreen.screenId)
                    assertTrue(result.asToolContent(), result.success)
                    until { advancedMetrics.get()?.optInt("drops") == 1 }
                    assertEquals(firstId, advancedMetrics.get()!!.getInt("displayId"))
                    assertFalse(advancedMetrics.get()!!.getBoolean("active"))
                    assertEquals(1, advancedMetrics.get()!!.getInt("downs")); assertEquals(1, advancedMetrics.get()!!.getInt("ups"))
                    assertEquals(1, advancedMetrics.get()!!.getInt("doubles")); assertEquals(2, advancedMetrics.get()!!.getInt("doubleDowns"))
                    assertEquals(0, advancedControl("reset").getInt("downs"))
                    advancedScreen = requireNotNull(gateway.observeScreen().screen)
                    val source = advancedScreen.elements.single { it.contentDescription == "r3b_source" }
                    val dragging = async { gateway.anchoredGesture(DeviceAnchoredGesture.Drag(source.index,
                        advancedIndex(advancedScreen, "r3b_drop"), 600, 1200), advancedScreen.screenId) }
                    until { advancedMetrics.get()?.optDouble("lastY")?.let { it > source.bounds.centerY + 10 } == true }
                    dragging.cancelAndJoin()
                    val cancelled = advancedControl("report")
                    assertFalse(cancelled.toString(), cancelled.getBoolean("active")); assertEquals(0, cancelled.getInt("drops"))
                    assertEquals(1, cancelled.getInt("ups") + cancelled.getInt("cancels"))
                    assertEquals(firstId, cancelled.getInt("displayId"))
                    val resumedObservation = gateway.observeScreen()
                    assertTrue(resumedObservation.asToolContent(), resumedObservation.success)
                    assertEquals(0, metrics.getValue("primary").getInt("touches")); assertEquals(focusBefore, focus())
                    report("BACKGROUND_ADVANCED_INTERACTION", "range-double-drag-and-cancel=owned-display;pointer-released;primary-touches=0;focus-preserved")
                } finally { context.unregisterReceiver(advancedReceiver) }
            }
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
            awaitDisplayCleanup("ordinary-task-finish", firstId, root)
            until { metrics.containsKey("secondaryDestroyed") }
            assertEquals(focusBefore, focus())
            report("BACKGROUND_RELEASE", "task-finish:display-removed,activity-destroyed,primary-touches=0,primary-focus-unchanged")

            val displaysBeforeSecure = context.getSystemService(DisplayManager::class.java).displays.map { it.displayId }.toSet()
            task = tasks.begin().also(tasks::arm)
            result = gateway.launchApp(testPackage, "com.flowchat.app.device.ExecutionDisplaySecureFixtureActivity")
            assertFalse(result.asToolContent(), result.success)
            assertEquals("secure_window_blocked", result.errorCode)
            assertNull(result.screen)
            assertNull(result.screenImage)
            report("BACKGROUND_SECURE", result.errorCode!!)
            val secureWait = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(packageName = testPackage), 500))
            assertFalse(secureWait.asToolContent(), secureWait.success)
            assertEquals("secure_window_blocked", secureWait.errorCode)
            assertNull(secureWait.screen)
            assertNull(secureWait.screenImage)
            report("BACKGROUND_SECURE_CONDITION", "no-text-or-image-exposed-by-conditional-wait")
            val secureDisplays = context.getSystemService(DisplayManager::class.java).displays.filter {
                it.displayId !in displaysBeforeSecure && it.name == "Floryx execution display"
            }
            assertEquals("The secure fixture must own exactly one new execution display", 1, secureDisplays.size)
            val secureId = secureDisplays.single().displayId
            report("BACKGROUND_SECURE_DISPLAY_ID", secureId)
            tasks.finish(task)
            awaitDisplayCleanup("secure-task-finish", secureId, root)
            report("BACKGROUND_SECURE_RELEASE", "display-removed; Root service null is accepted only after DisplayManager confirms removal")

            task = tasks.begin().also(tasks::arm)
            result = gateway.launchApp(testPackage, fixture)
            assertTrue(result.asToolContent(), result.success)
            val lostId = result.screen!!.geometry!!.displayId
            report("BACKGROUND_LOST_DISPLAY_ID", lostId)
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
            awaitDisplayCleanup("root-transport-loss", lostId, root)
            val lost = gateway.observeScreen()
            assertFalse(lost.asToolContent(), lost.success)
            assertNull(root.serviceInfo())
            assertEquals(focusBefore, focus())
            report("BACKGROUND_LOST", "No reconnect or primary fallback in the interrupted task")
            tasks.finish(task)
            if (InstrumentationRegistry.getArguments().getString("runBackgroundCompatibility") == "true") {
                root.refresh(requestPermission = true)
                val displaysBeforeCompatibility = context.getSystemService(DisplayManager::class.java).displays.map { it.displayId }.toSet()
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
                val compatibilityDisplayId = compatible.screen?.geometry?.displayId ?: context.getSystemService(DisplayManager::class.java)
                    .displays.single { it.displayId !in displaysBeforeCompatibility && it.name == "Floryx execution display" }.displayId
                tasks.finish(task)
                awaitDisplayCleanup("compatibility-task-finish", compatibilityDisplayId, root)
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
