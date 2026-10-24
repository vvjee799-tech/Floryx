package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.presentation.overlay.FloatingAssistantService
import com.flowchat.app.presentation.overlay.FloatingAssistantState
import com.flowchat.app.ui.theme.AppAppearance
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: touches only a disposable fixture, with no provider calls or account actions. */
class FloatingOutputDiagnosticTest {
    @Test fun watchingOutputLeavesTheTargetFocusedAndPassesThroughRealTouches() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOverlayDiagnostics") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readBytes().toString(Charsets.UTF_8) }.also {
            check(!it.contains("Error:") && !it.contains("Exception")) { it }
        }
        fun openFlowChat() = shell("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
        fun window(title: String): View? {
            var found: View? = null
            instrumentation.runOnMainSync {
                found = WindowInspector.getGlobalWindowViews().firstOrNull {
                    (it.layoutParams as? WindowManager.LayoutParams)?.title == title
                }
            }
            return found
        }
        fun bounds(view: View): Rect {
            val rect = Rect()
            instrumentation.runOnMainSync {
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                rect.set(position[0], position[1], position[0] + view.width, position[1] + view.height)
            }
            return rect
        }
        fun tap(x: Float, y: Float) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).let { event ->
                    try { assertTrue("Input injection failed", automation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
            }
        }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(10_000) {
            while (!condition()) delay(100)
        }
        openFlowChat()
        delay(1_000)
        check(Settings.canDrawOverlays(context)) { "FlowChat Dev overlay permission is required" }
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
            } finally { automation.dropShellPermissionIdentity() }
        }
        waitFor { FlowChatAccessibilityService.current() != null }
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, DeviceDiagnosticsEntryPoint::class.java)
        val bridge = dependencies.bridge()
        val gateway = dependencies.gateway()
        val previousState = bridge.state.value
        val previousAppearance = AppAppearance.load(context)
        try {
            if (InstrumentationRegistry.getArguments().getString("overlayAppearance") == "dark") {
                AppAppearance.save(context, AppAppearance.Dark)
            }
            context.startForegroundService(Intent(context, FloatingAssistantService::class.java))
            // Each appearance run needs a fresh fixture; otherwise the previous run's touch
            // counter survives in the separate test APK and makes pass-through checks fail.
            shell("am start -W -f 0x10008000 -n ${instrumentation.context.packageName}/${DeviceAutomationFixtureActivity::class.java.name} --ez overlay_probe true")
            waitFor { !bridge.appInForeground.value }
            delay(500)
            val liveState = FloatingAssistantState(
                conversationId = "overlay-diagnostic", canChat = true, isStreaming = true,
                assistantText = "正在检查当前页面。\n你可以在这里看实时输出，手机操作会继续进行。"
            )
            bridge.publish(liveState)
            waitFor { window("FloryxOutput")?.isShown == true }
            val bubble = requireNotNull(window("FloryxOutput"))
            val bubbleBounds = bounds(bubble)
            fun checkCircleAndCapture(name: String) {
                val actual = bounds(bubble)
                val diameter = (48 * context.resources.displayMetrics.density).toInt()
                assertEquals("No panel-sized invisible window may remain", diameter, actual.width())
                assertEquals(diameter, actual.height())
                val screenshot = requireNotNull(automation.takeScreenshot())
                try {
                    // Corners outside the circle must match the plain fixture, without a shadow tile.
                    val background = screenshot.getPixel(actual.left - 4, actual.centerY())
                    for (fractionX in listOf(0.03f, 0.10f, 0.90f, 0.97f)) {
                        for (fractionY in listOf(0.03f, 0.10f, 0.90f, 0.97f)) {
                            assertEquals("The circular bubble must not paint a square corner", background,
                                screenshot.getPixel(actual.left + (actual.width() * fractionX).toInt(),
                                    actual.top + (actual.height() * fractionY).toInt()))
                        }
                    }
                    File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
            delay(300)
            checkCircleAndCapture("overlay-collapsed")
            var touchCount = 0
            suspend fun assertTouch(count: Int, kind: String = "Tap") {
                val observed = gateway.observeScreen().asToolContent()
                assertTrue(observed, observed.contains("Touch count: $count; $kind completed"))
            }
            bridge.withExternalUiAction {
                delay(300)
                report("Collapsed bubble during an operation: shown=${bubble.isShown}, visibility=${bubble.visibility}, bounds=$bubbleBounds")
                assertTrue("Collapsed bubble must remain visible throughout device operations", bubble.isShown)
                val bubbleParams = bubble.layoutParams as WindowManager.LayoutParams
                assertTrue(bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                val maxAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 1f
                assertTrue(bubbleParams.alpha <= maxAlpha)
                repeat(3) {
                    tap(bubbleBounds.exactCenterX(), bubbleBounds.exactCenterY())
                    delay(100)
                    assertTouch(++touchCount)
                    assertTrue(bubble.isShown)
                }
            }
            waitFor { (bubble.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 }
            val failedOperation = runCatching { bridge.withExternalUiAction { delay(200); error("Synthetic operation failure") } }
            assertTrue(failedOperation.isFailure)
            waitFor { bridge.uiInteractionCount.value == 0 &&
                (bubble.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 }
            val started = CompletableDeferred<Unit>()
            val cancelledOperation = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.withExternalUiAction { started.complete(Unit); delay(20_000) }
            }
            started.await()
            delay(200)
            assertTrue(bubble.isShown)
            cancelledOperation.cancelAndJoin()
            waitFor { bridge.uiInteractionCount.value == 0 &&
                (bubble.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 }
            report("Collapsed bubble stayed visible through repeated real taps, operation failure and cancellation; input was restored.")
            tap(bubbleBounds.exactCenterX(), bubbleBounds.exactCenterY())
            waitFor { window("FloryxControls")?.isShown == true }
            delay(300)
            val output = requireNotNull(window("FloryxOutput"))
            val controls = requireNotNull(window("FloryxControls"))
            val params = output.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            val maxAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 1f
            assertTrue(params.alpha <= maxAlpha)
            assertTrue("Controls must not overlap the pass-through output", bounds(controls).bottom <= bounds(output).top)
            val observed = gateway.observeScreen()
            assertEquals(observed.asToolContent(), instrumentation.context.packageName, observed.screen?.packageName)
            report("Watching output is non-focusable, touch-through, and the underlying fixture is still observed.")

            val outputBounds = bounds(output)
            tap(outputBounds.exactCenterX(), outputBounds.exactCenterY())
            delay(300)
            assertTouch(++touchCount)
            bridge.withExternalUiAction {
                delay(200)
                assertEquals("Touchable controls must yield during the whole operation", View.INVISIBLE, controls.visibility)
                assertTrue("Live output must remain visible", output.isShown)
                tap(outputBounds.exactCenterX(), outputBounds.exactCenterY())
                delay(200)
                assertTouch(++touchCount)
                val swiped = gateway.swipeScreen(DeviceSwipeDirection.Up, 50)
                assertTrue(swiped.asToolContent(), swiped.success)
                assertTrue(swiped.asToolContent().contains("Touch count: ${++touchCount}; Swipe completed"))
            }
            waitFor { controls.isShown }
            assertTrue("Output must remain expanded after an operation", bounds(output).width() > bubbleBounds.width())
            bridge.publish(liveState.copy(assistantText = "点击和滑动已完成。\n浮窗保持展开，输出正常更新。"))
            delay(300)
            val screenshot = requireNotNull(automation.takeScreenshot())
            File(context.getExternalFilesDir(null), "overlay-watching.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            report("Real taps and an accessibility swipe passed through the visible text panel; controls yielded and returned without collapsing output.")

            bridge.publish(liveState.copy(isStreaming = false))
            delay(300)
            assertTrue("Completion must not steal focus", params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            val replyBounds = bounds(controls)
            tap(replyBounds.left + replyBounds.width() / 3f, replyBounds.exactCenterY())
            waitFor { (output.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 }
            assertEquals(0, (output.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            report("After completion, only explicit Reply enters the editable composer.")

            // Repeated expand/collapse must shrink both the surface and its actual window back to the circle.
            val composerBounds = bounds(output)
            tap(composerBounds.right - 24 * context.resources.displayMetrics.density,
                composerBounds.top + 34 * context.resources.displayMetrics.density)
            waitFor { bounds(output).width() == bubbleBounds.width() }
            delay(300)
            checkCircleAndCapture("overlay-collapsed-after-reply")
            repeat(3) {
                val collapsed = bounds(output)
                tap(collapsed.exactCenterX(), collapsed.exactCenterY())
                waitFor { bounds(output).width() > collapsed.width() }
                delay(300)
                val panel = bounds(output)
                tap(panel.right - 24 * context.resources.displayMetrics.density,
                    panel.top + 34 * context.resources.displayMetrics.density)
                waitFor { bounds(output).width() == collapsed.width() }
                delay(300)
            }
            checkCircleAndCapture("overlay-collapsed-after-cycles")
            openFlowChat()
            waitFor { window("FloryxOutput") == null }
            shell("am start -W -n ${instrumentation.context.packageName}/${DeviceAutomationFixtureActivity::class.java.name} --ez overlay_probe true")
            waitFor { window("FloryxOutput")?.isShown == true }
            assertEquals(bubbleBounds.width(), bounds(requireNotNull(window("FloryxOutput"))).width())
            report("Transparent corners, 48dp bounds, repeated collapse and foreground/background restoration passed.")
        } finally {
            AppAppearance.save(context, previousAppearance)
            context.startForegroundService(Intent(context, FloatingAssistantService::class.java))
            bridge.publish(previousState)
            openFlowChat()
        }
    }
}
