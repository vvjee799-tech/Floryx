package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.AutomationTimings
import com.flowchat.app.domain.device.DeviceSwipeDirection
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Repeatable local device timings. Does not contact a model or open personal apps. */
class DevicePerformanceDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device by lazy { EntryPointAccessors.fromApplication(context, DeviceDiagnosticsEntryPoint::class.java) }

    private suspend fun setup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDevicePerformanceDiagnostics") == "true")
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(350)
        val original = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, FlowChatAccessibilityService::class.java).flattenToString()
        check(component in original.split(':')) { "Previously authorized Floryx Dev accessibility is required." }
        if (FlowChatAccessibilityService.current() == null) {
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                try {
                    check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        original.split(':').filterNot { it == component }.joinToString(":")))
                    delay(200)
                } finally { check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)) }
            } finally { automation.dropShellPermissionIdentity() }
        }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current()?.observeScreen() == null) delay(100) }
        device.bridge().deviceTaskControl.begin()
    }

    private suspend fun fixture(canvas: Boolean, ignoreSwipes: Boolean = false) {
        context.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName,
                if (canvas) VisualTapFixtureActivity::class.java.name else DeviceAutomationFixtureActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("portrait", true)
            putExtra("swipe_pages", true)
            putExtra("ignore_swipes", ignoreSwipes)
        })
        withTimeout(8_000) {
            while (FlowChatAccessibilityService.current()?.observeScreen()?.packageName != instrumentation.context.packageName) delay(100)
        }
        delay(500)
    }

    @Test fun measuresStableControlsCanvasAndVerifiedActions() = runBlocking<Unit> {
        setup()
        val results = linkedMapOf<String, List<Long>>()
        AutomationTimings.clear()
        suspend fun measure(name: String, action: suspend () -> DeviceToolResult) {
            val samples = mutableListOf<Long>()
            repeat(7) { index ->
                val started = SystemClock.elapsedRealtime()
                val result = action()
                assertTrue(result.asToolContent(), result.success)
                if (index >= 2) samples += SystemClock.elapsedRealtime() - started
            }
            results[name] = samples
        }
        try {
            fixture(canvas = false)
            measure("controls_observe") { device.gateway().observeScreen() }
            measure("controls_toggle") {
                val screen = requireNotNull(device.gateway().observeScreen().screen)
                val toggle = screen.elements.first { it.displayText == "Test toggle" }
                val result = device.gateway().tapUiElement(toggle.index, false, screen.screenId)
                assertEquals(!requireNotNull(toggle.checked), result.screen?.elements?.first { it.displayText == "Test toggle" }?.checked)
                result
            }
            fixture(canvas = true)
            assertTrue(requireNotNull(device.gateway().observeScreen().screen).elements.isEmpty())
            measure("canvas_observe") { device.gateway().observeScreen() }
            measure("canvas_capture") { device.gateway().captureScreen() }
            val label = InstrumentationRegistry.getArguments().getString("performanceLabel", "current")
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val report = buildJsonObject {
                put("label", label)
                put("device", android.os.Build.MODEL)
                put("scope", "Local synthetic pages; no model/network latency. Five samples after two warmups.")
                putJsonObject("samples_ms") { results.forEach { (name, values) -> put(name, JsonArray(values.map(::JsonPrimitive))) } }
                putJsonObject("median_ms") { results.forEach { (name, values) -> put(name, values.sorted()[values.size / 2]) } }
                putJsonArray("stages") { AutomationTimings.snapshot().forEach { measurement -> add(buildJsonObject {
                    put("stage", measurement.stage); put("duration_ms", measurement.durationMillis); put("completed", measurement.completed)
                }) } }
            }.toString()
            File(context.filesDir, "diagnostics/device-performance").apply { mkdirs() }
                .resolve("$label.json").writeText(report)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nDEVICE_PERFORMANCE $report\n") })
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test fun canvasSwipesVerifyPixelsAndDoNotInventProgressForIgnoredGestures() = runBlocking<Unit> {
        setup()
        try {
            fixture(canvas = true)
            val result = device.gateway().swipeScreen(DeviceSwipeDirection.Up, 50)
            assertTrue(result.asToolContent(), result.success)
            assertNotNull(result.screenImage)
            val image = requireNotNull(result.screenImage)
            val bytes = java.util.Base64.getDecoder().decode(image.image.base64)
            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try { assertEquals(android.graphics.Color.rgb(210, 235, 250), bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
            finally { bitmap.recycle() }
            fixture(canvas = true, ignoreSwipes = true)
            val ignored = device.gateway().swipeScreen(DeviceSwipeDirection.Up, 50)
            assertFalse(ignored.asToolContent(), ignored.success)
            assertEquals("ui_no_change", ignored.errorCode)
            assertNotNull(ignored.screenImage)
        } finally {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test fun comparesNativeDetailPixelsAgainstTheDecodedPath() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDevicePerformanceDiagnostics") == "true")
        val bitmap = android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
            val before = com.flowchat.app.data.device.ScreenImageEncoder.encode(bitmap, "before", 1)
            android.graphics.Canvas(bitmap).drawRect(240f, 240f, 400f, 400f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
            val after = com.flowchat.app.data.device.ScreenImageEncoder.encode(bitmap, "after", 2)
            assertNotNull(before.comparisonPixels)
            val target = com.flowchat.app.domain.device.VisualTapTarget("before", 500, 500, "synthetic target")
            val decodedBefore = before.copy(comparisonPixels = null)
            val decodedAfter = after.copy(comparisonPixels = null)
            val expected = com.flowchat.app.data.device.ScreenImageMatcher.compare(decodedBefore, decodedAfter, target)
            val nativeTimes = mutableListOf<Long>()
            val decodedTimes = mutableListOf<Long>()
            repeat(13) { iteration ->
                fun sample(native: Boolean) {
                    val started = System.nanoTime()
                    val actual = com.flowchat.app.data.device.ScreenImageMatcher.compare(
                        if (native) before else decodedBefore, if (native) after else decodedAfter, target)
                    val elapsed = System.nanoTime() - started
                    assertEquals(expected, actual)
                    if (iteration >= 3) (if (native) nativeTimes else decodedTimes).add(elapsed)
                }
                sample(iteration % 2 == 0); sample(iteration % 2 != 0)
            }
            val report = buildJsonObject {
                put("scope", "640x640 synthetic detail; identical comparison results; ten samples after three warmups")
                put("native_median_ms", nativeTimes.sorted()[nativeTimes.size / 2] / 1_000_000.0)
                put("decoded_median_ms", decodedTimes.sorted()[decodedTimes.size / 2] / 1_000_000.0)
            }.toString()
            File(context.filesDir, "diagnostics/device-performance").apply { mkdirs() }.resolve("pixel-comparison.json").writeText(report)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPIXEL_COMPARISON $report\n") })
        } finally { bitmap.recycle() }
    }
}
