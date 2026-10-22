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
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
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
        context.startForegroundService(Intent(context, FloatingAssistantService::class.java))
        shell("am start -W -n ${instrumentation.context.packageName}/${DeviceAutomationFixtureActivity::class.java.name} --ez overlay_probe true")
        waitFor { !bridge.appInForeground.value }
        delay(500)
        val liveState = FloatingAssistantState(
            conversationId = "overlay-diagnostic", canChat = true, isStreaming = true,
            assistantText = "正在检查当前页面。\n你可以在这里看实时输出，手机操作会继续进行。"
        )
        try {
            bridge.publish(liveState)
            waitFor { window("FlowChatOutput")?.isShown == true }
            val bubble = requireNotNull(window("FlowChatOutput"))
            val bubbleBounds = bounds(bubble)
            tap(bubbleBounds.exactCenterX(), bubbleBounds.exactCenterY())
            waitFor { window("FlowChatControls")?.isShown == true }
            delay(300)
            val output = requireNotNull(window("FlowChatOutput"))
            val controls = requireNotNull(window("FlowChatControls"))
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
            assertTrue(gateway.observeScreen().asToolContent().contains("Touch count: 1; Tap completed"))
            bridge.withExternalUiAction {
                delay(200)
                assertEquals("Touchable controls must yield during the whole operation", View.INVISIBLE, controls.visibility)
                assertTrue("Live output must remain visible", output.isShown)
                tap(outputBounds.exactCenterX(), outputBounds.exactCenterY())
                delay(200)
                assertTrue(gateway.observeScreen().asToolContent().contains("Touch count: 2; Tap completed"))
                val swiped = gateway.swipeScreen(DeviceSwipeDirection.Up, 50)
                assertTrue(swiped.asToolContent(), swiped.success)
                assertTrue(swiped.asToolContent().contains("Touch count: 3; Swipe completed"))
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
        } finally {
            bridge.publish(previousState)
            openFlowChat()
        }
    }
}
