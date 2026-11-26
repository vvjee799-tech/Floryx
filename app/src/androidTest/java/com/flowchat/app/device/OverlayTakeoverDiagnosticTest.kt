package com.flowchat.app.device

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.presentation.chat.DeviceActionConfirmationUi
import com.flowchat.app.presentation.overlay.FloatingAssistantService
import com.flowchat.app.presentation.overlay.FloatingAssistantState
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: real WindowManager touch delivery, with synthetic state and no provider/account actions. */
class OverlayTakeoverDiagnosticTest {
    @Test fun outsideTouchesPauseArmedTasksWhileBothOverlayModesPassInputThrough() = runBlocking {
        withFixture {
            val bubble = requireNotNull(window("FloryxOutput"))
            val diameter = (48 * context.resources.displayMetrics.density).toInt()
            assertEquals("The listener must remain the existing small bubble", diameter, bounds(bubble).width())
            assertEquals(diameter, bounds(bubble).height())
            assertTrue(flags(bubble) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0)

            val collapsedTask = beginTask()
            val firstBoundary = control.controlChanges.value
            tap(outsidePoint())
            expectTouches(1)
            await("collapsed outside touch pauses the task") { collapsedTask.interrupted }
            assertEquals(firstBoundary + 1, control.controlChanges.value)
            control.finish(collapsedTask)

            // The bubble itself remains interactive; expanding it must not count as taking control.
            val expandedTask = beginTask()
            tap(PointF(bounds(bubble).exactCenterX(), bounds(bubble).exactCenterY()))
            await("watching controls appear") { window("FloryxControls")?.isShown == true }
            val output = requireNotNull(window("FloryxOutput"))
            val controls = requireNotNull(window("FloryxControls"))
            await("expanded output becomes pass-through") {
                bounds(output).width() > diameter && flags(output) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            }
            assertTrue(flags(output) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(flags(controls) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0)
            assertFalse("An explicit bubble tap must not pause the task", expandedTask.interrupted)
            val secondBoundary = control.controlChanges.value
            tap(PointF(bounds(output).exactCenterX(), bounds(output).exactCenterY()))
            expectTouches(2)
            await("touch through the output pauses the task") { expandedTask.interrupted }
            assertEquals("Multiple observing windows must produce one takeover", secondBoundary + 1,
                control.controlChanges.value)
            report("PASS real outside delivery: collapsed and expanded overlays preserved fixture taps and paused their own armed tasks")
        }
    }

    @Test fun ownGesturesQueuedEventsBackgroundWorkAndConfirmationDoNotPauseTheTask() = runBlocking {
        withFixture {
            val bubble = requireNotNull(window("FloryxOutput"))
            tap(PointF(bounds(bubble).exactCenterX(), bounds(bubble).exactCenterY()))
            await("watching controls appear") { window("FloryxControls")?.isShown == true }
            val task = beginTask()
            val originalBoundary = control.controlChanges.value
            var queuedEventTime = 0L
            bridge.withExternalUiAction {
                await("controls yield during the synthetic Floryx gesture") {
                    window("FloryxControls")?.isShown == false &&
                        window("FloryxOutput")?.let { flags(it) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0 } == true
                }
                val service = requireNotNull(FlowChatAccessibilityService.current())
                val screen = requireNotNull(service.observeScreen())
                assertEquals(instrumentation.context.packageName, screen.packageName)
                val pad = screen.elements.first { it.contentDescription == "Overlay touch target" }
                // The fixture rejects accessibility node actions, so this takes Floryx's actual
                // dispatchGesture fallback and waits for its GestureResultCallback.
                assertTrue("Floryx accessibility gesture must complete", service.tapUiElement(pad.index, false))
                expectTouches(1)
                assertFalse("The bridge must ignore a touch while its UI action is running",
                    bridge.onOutsideTouch(SystemClock.uptimeMillis()))
                queuedEventTime = SystemClock.uptimeMillis()
                delay(80) // Keep the queued event timestamp strictly before the wrapper's completion.
            }
            await("controls return after the gesture") { window("FloryxControls")?.isShown == true }
            // Inject an actual late-delivered gesture with its original timestamp, rather than only
            // calling the bridge directly. The fixture must still receive this old input event.
            tap(outsidePoint(), queuedEventTime)
            expectTouches(2)
            expectUninterrupted(task, "Floryx gesture and late WindowManager event")
            assertEquals(originalBoundary, control.controlChanges.value)

            settings.setBackgroundExecutionEnabled(true)
            publish(liveState)
            tap(outsidePoint())
            expectTouches(3)
            expectUninterrupted(task, "explicit background execution")

            settings.setBackgroundExecutionEnabled(false)
            val confirmation = liveState.copy(pendingDeviceActionConfirmation = DeviceActionConfirmationUi(
                toolName = "overlay_synthetic", title = "Synthetic confirmation", message = "No command is executed."
            ))
            var queuedApprovalEventTime = 0L
            control.withUserConfirmation(task) {
                publish(confirmation)
                tap(outsidePoint())
                expectTouches(4)
                expectUninterrupted(task, "pending user confirmation")
                queuedApprovalEventTime = SystemClock.uptimeMillis()
                delay(20)
                // The synthetic owner/request cannot resolve any real conversation confirmation.
                bridge.respondToConfirmation(requireNotNull(liveState.conversationId),
                    requireNotNull(confirmation.pendingDeviceActionConfirmation).requestId, true)
            }

            publish(liveState)
            tap(outsidePoint(), queuedApprovalEventTime)
            expectTouches(5)
            expectUninterrupted(task, "approval event delivered after confirmation closes")

            publish(liveState.copy(isStreaming = false))
            tap(outsidePoint())
            expectTouches(6)
            expectUninterrupted(task, "completed output")
            assertEquals(originalBoundary, control.controlChanges.value)

            // Suppression must end: a fresh user tap between foreground task steps takes control.
            publish(liveState)
            tap(outsidePoint())
            expectTouches(7)
            await("a fresh user event still pauses the task") { task.interrupted }
            assertEquals(originalBoundary + 1, control.controlChanges.value)
            report("PASS exclusions: real accessibility gesture, queued gesture/approval events, background work, confirmation and idle output stayed unpaused; the next user tap took control")
        }
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOverlayTakeoverDiagnostics") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            fixture.start()
            fixture.block()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try { fixture.close() }
                catch (cleanup: Throwable) { failure?.let { it.addSuppressed(cleanup) } ?: throw cleanup }
            }
        }
    }

    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val bridge = EntryPointAccessors.fromApplication(context, DeviceDiagnosticsEntryPoint::class.java).bridge()
        val control = bridge.deviceTaskControl
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        private val originalSettings = settings.state.value
        private val originalState = bridge.state.value
        private val originalAccessibility = readEnabledAccessibilityServices()
        private val overlayService = Intent(context, FloatingAssistantService::class.java)
        @Suppress("DEPRECATION")
        private val serviceWasRunning = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service == ComponentName(context, FloatingAssistantService::class.java) }
        private val tasks = mutableListOf<DeviceTaskControl.Task>()
        private var fixtureStarted = false
        private var changedSettings = false
        val liveState = FloatingAssistantState(conversationId = "overlay-takeover-${UUID.randomUUID()}",
            canChat = true, isStreaming = true, photoAnswerEnabled = false,
            assistantText = "Synthetic overlay test; no model or device command is running.")

        suspend fun start() {
            check(control.currentTask == null && !bridge.state.value.isStreaming) { "Finish existing tasks before this opt-in diagnostic." }
            check(Settings.canDrawOverlays(context)) { "Floryx Dev overlay permission is required." }
            shell("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
            await("Floryx foreground") { bridge.appInForeground.value }
            rebindAuthorizedAccessibility()
            changedSettings = true
            settings.setPowerModeEnabled(true)
            settings.setBackgroundExecutionEnabled(false)
            context.startForegroundService(overlayService)
            shell("am start -W -f 0x10008000 -n ${instrumentation.context.packageName}/${DeviceAutomationFixtureActivity::class.java.name} --ez overlay_probe true")
            fixtureStarted = true
            await("disposable fixture foreground") {
                !bridge.appInForeground.value && automation.rootInActiveWindow?.packageName?.toString() == instrumentation.context.packageName
            }
            publish(liveState)
            expectTouches(0, "Waiting")
        }

        fun beginTask(): DeviceTaskControl.Task {
            check(control.currentTask == null) { "Only this diagnostic's task may occupy the device." }
            return control.begin().also { tasks += it; control.arm(it) }
        }

        suspend fun publish(state: FloatingAssistantState) {
            // Settings updates can asynchronously republish the app runtime's real conversation.
            // Let those updates settle before applying this renderer-only fixture.
            instrumentation.waitForIdleSync()
            delay(250)
            bridge.publish(state)
            await("synthetic overlay state displayed") { bridge.state.value == state && window("FloryxOutput")?.isShown == true }
            instrumentation.waitForIdleSync()
            delay(100)
        }

        suspend fun expectUninterrupted(task: DeviceTaskControl.Task, label: String) {
            instrumentation.waitForIdleSync()
            delay(150)
            assertFalse("$label must not interrupt the task", task.interrupted)
            control.checkCanAct(task)
        }

        @Suppress("DEPRECATION")
        suspend fun expectTouches(count: Int, result: String = "Tap completed") {
            await("fixture received touch $count") {
                automation.rootInActiveWindow?.let { root ->
                    try {
                        root.packageName?.toString() == instrumentation.context.packageName &&
                            root.findAccessibilityNodeInfosByText("Touch count: $count; $result").let { nodes ->
                                try { nodes.isNotEmpty() } finally { nodes.forEach { it.recycle() } }
                            }
                    } finally { root.recycle() }
                } == true
            }
        }

        fun window(title: String): View? {
            var found: View? = null
            instrumentation.runOnMainSync {
                found = WindowInspector.getGlobalWindowViews().firstOrNull {
                    (it.layoutParams as? WindowManager.LayoutParams)?.title == title
                }
            }
            return found
        }

        fun flags(view: View): Int {
            var flags = 0
            instrumentation.runOnMainSync { flags = (view.layoutParams as WindowManager.LayoutParams).flags }
            return flags
        }

        fun bounds(view: View): Rect = Rect().also { rect ->
            instrumentation.runOnMainSync {
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                rect.set(position[0], position[1], position[0] + view.width, position[1] + view.height)
            }
        }

        @Suppress("DEPRECATION")
        fun outsidePoint(): PointF {
            val fixtureBounds = Rect()
            requireNotNull(automation.rootInActiveWindow).let { root ->
                try {
                    check(root.packageName?.toString() == instrumentation.context.packageName)
                    root.getBoundsInScreen(fixtureBounds)
                } finally { root.recycle() }
            }
            val occupied = listOfNotNull(window("FloryxOutput"), window("FloryxControls"))
                .filter { it.isShown }.map(::bounds)
            for (y in listOf(.8f, .5f, .25f)) for (x in listOf(.2f, .5f, .8f)) {
                val point = PointF(fixtureBounds.left + fixtureBounds.width() * x, fixtureBounds.top + fixtureBounds.height() * y)
                if (occupied.none { it.contains(point.x.toInt(), point.y.toInt()) }) return point
            }
            error("The existing overlay windows leave no fixture touch area: $occupied")
        }

        fun tap(point: PointF, eventTime: Long? = null) {
            val downTime = eventTime ?: SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, eventTime ?: SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue("Input injection failed", automation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
        }

        suspend fun await(label: String, predicate: () -> Boolean) {
            try { withTimeout(15_000) { while (!predicate()) delay(50) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        }

        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })

        private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }.also {
                check(!it.contains("Error:") && !it.contains("Exception")) { it }
            }

        // Some OEMs hide this setting from the target app even when the service is enabled.
        // Read as shell, preserving the exact list and only removing the command's line ending.
        private fun readEnabledAccessibilityServices(): String? =
            shell("settings get secure enabled_accessibility_services").trimEnd('\r', '\n').takeUnless { it == "null" }

        private suspend fun rebindAuthorizedAccessibility() {
            val component = ComponentName(context, FlowChatAccessibilityService::class.java)
            check(originalAccessibility.orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).contains(component)) {
                "Enable Floryx Dev accessibility before running this opt-in diagnostic."
            }
            val existing = requireNotNull(readEnabledAccessibilityServices()) { "The authorized accessibility list disappeared." }
            assertEquals("Preserve the original authorized accessibility list", originalAccessibility, existing)
            if (FlowChatAccessibilityService.current() == null) {
                automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
                try {
                    try {
                        check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            existing.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")))
                        delay(250)
                    } finally {
                        check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existing))
                    }
                } finally { automation.dropShellPermissionIdentity() }
            }
            await("previously enabled accessibility service is bound") { FlowChatAccessibilityService.current() != null }
            assertEquals(originalAccessibility, readEnabledAccessibilityServices())
        }

        suspend fun close() {
            var failure: Throwable? = null
            suspend fun restore(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            restore { tasks.forEach(control::finish) }
            restore {
                if (changedSettings) {
                    bridge.publish(FloatingAssistantState())
                    await("synthetic overlay removed") { window("FloryxOutput") == null }
                }
                if (fixtureStarted && automation.rootInActiveWindow?.packageName?.toString() == instrumentation.context.packageName) {
                    shell("input keyevent 4") // Finish only our disposable foreground fixture.
                }
            }
            restore {
                if (changedSettings) {
                    settings.setBackgroundExecutionEnabled(originalSettings.backgroundExecutionEnabled)
                    settings.setPowerModeEnabled(originalSettings.powerModeEnabled)
                    instrumentation.waitForIdleSync()
                    delay(250)
                    bridge.publish(originalState)
                    if (serviceWasRunning) context.startForegroundService(overlayService) else context.stopService(overlayService)
                }
            }
            restore { if (changedSettings) rebindAuthorizedAccessibility() }
            failure?.let { throw it }
        }
    }
}
