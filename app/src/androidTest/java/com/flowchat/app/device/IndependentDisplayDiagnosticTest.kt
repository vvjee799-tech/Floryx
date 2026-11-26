package com.flowchat.app.device

import android.app.ActivityManager
import android.app.Presentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Private, own-content display only: no mirroring, personal apps, input injection, or screen-off claims. */
class IndependentDisplayDiagnosticTest {
    @Test fun createsPrivateDisplayRendersOwnContentAndCapturesAFrameThenReleasesEverything() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runSystemAssistantDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(DisplayManager::class.java)
        val before = manager.displays.map { it.displayId }.toSet()
        val reader = ImageReader.newInstance(320, 480, PixelFormat.RGBA_8888, 2)
        val color = Color.rgb(35, 108, 69)
        val display = withContext(Dispatchers.Main) {
            manager.createVirtualDisplay("Floryx private display QA", 320, 480, 160, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        }
        assertNotNull(display)
        val id = display.display.displayId
        var presentation: Presentation? = null
        val report = JSONObject().apply {
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("secondaryActivityFeature", context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
            put("privateDisplayCreated", true)
            put("displayId", id)
            put("ownActivityLaunchAllowed", context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, id, Intent(context, AssistantPanelActivity::class.java)))
            put("crossAppLaunch", "unverified")
            put("displayInputInjection", "unverified")
            put("screenOffExecution", "unverified")
        }
        try {
            withContext(Dispatchers.Main) {
                presentation = Presentation(context, display.display).apply {
                    setContentView(TextView(context).apply {
                        text = "FLORYX PRIVATE DISPLAY QA"; setTextColor(Color.WHITE); textSize = 18f
                        gravity = Gravity.CENTER; setBackgroundColor(color)
                    })
                    show()
                }
            }
            withTimeout(8_000) {
                var matches = false
                while (!matches) {
                    val frame = reader.acquireLatestImage()
                    if (frame != null) frame.use { image ->
                        val plane = image.planes[0]
                        val offset = 10 * plane.rowStride + 10 * plane.pixelStride
                        val buffer = plane.buffer
                        matches = (buffer.get(offset).toInt() and 255) == Color.red(color) &&
                            (buffer.get(offset + 1).toInt() and 255) == Color.green(color) &&
                            (buffer.get(offset + 2).toInt() and 255) == Color.blue(color)
                    }
                    if (!matches) delay(50)
                }
            }
            report.put("ownContentFrameVerified", true)
        } finally {
            withContext(Dispatchers.Main) { presentation?.dismiss(); display.release() }
            reader.close()
            withTimeout(5_000) { while (manager.displays.any { it.displayId == id }) delay(50) }
            report.put("released", true)
            assertEquals(before, manager.displays.map { it.displayId }.toSet())
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nINDEPENDENT_DISPLAY $report\n") })
        }
    }
}
