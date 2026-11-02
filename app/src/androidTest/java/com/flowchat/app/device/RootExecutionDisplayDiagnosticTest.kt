package com.flowchat.app.device

import android.graphics.Color
import android.app.UiAutomation
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.content.ComponentName
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.data.device.FlowChatAccessibilityService
import android.hardware.display.DisplayManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.LibsuRootServiceConnector
import com.flowchat.app.diagnostics.RootDisplayProbeService
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class RootExecutionDisplayDiagnosticTest {
    @Test fun rootDisplayFeasibilityProbe() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runExecutionDisplayDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val start = automation.executeShellCommand("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(start).bufferedReader().use { it.readText() }
        delay(500)
        if (FlowChatAccessibilityService.current() == null) {
            val original = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
            check(original.split(':').mapNotNull(ComponentName::unflattenFromString).contains(expected))
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    original.split(':').filterNot { ComponentName.unflattenFromString(it) == expected }.joinToString(":"))
                delay(300)
            } finally {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)
                automation.dropShellPermissionIdentity()
            }
        }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current() == null) delay(100) }
        val manager = context.getSystemService(DisplayManager::class.java)
        val before = manager.displays.map { it.displayId }.toSet()
        val results = ConcurrentHashMap<String, JSONObject>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(owner: Context, intent: Intent) {
                results[intent.getStringExtra("kind")!!] = JSONObject(intent.getStringExtra("data")!!)
            }
        }
        val filter = IntentFilter("${context.packageName}.EXECUTION_FIXTURE_RESULT")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        val session = LibsuRootServiceConnector(context, RootDisplayProbeService::class.java).connect {}
        try {
            withContext(Dispatchers.IO) { session.info() }
            val report = JSONObject(File(context.cacheDir, "execution-display-probe.json").readText())
            instrumentation.sendStatus(1, Bundle().apply { putString("EXECUTION_DISPLAY_PROBE", report.toString()) })
            assertFalse(report.toString(), report.has("error"))
            assertTrue(report.toString(), report.getBoolean("launchSuccess"))
            assertEquals(Color.rgb(20, 150, 75), report.getInt("pixel"))
            assertEquals(report.getString("focusBefore"), report.getString("focusAfter"))
            withTimeout(5_000) { while (!results.containsKey("primary") || !results.containsKey("secondary")) delay(50) }
            assertEquals(0, results.getValue("primary").getInt("touches"))
            assertEquals("ROOT4", results.getValue("secondary").getString("text"))
            val displayId = results.getValue("secondary").getInt("displayId")
            assertTrue(displayId > 0)
            if (Build.VERSION.SDK_INT >= 30) withContext(Dispatchers.Main) {
                val service = FlowChatAccessibilityService.current()
                val windows = service?.windowsOnAllDisplays?.get(displayId).orEmpty()
                instrumentation.sendStatus(1, Bundle().apply { putString("EXECUTION_ACCESSIBILITY", "connected=${service != null},windows=${windows.size}") })
                for (window in windows) {
                    val root = window.root ?: continue
                    fun act(node: AccessibilityNodeInfo) {
                        if (node.contentDescription?.toString() == "execution_fixture_input") {
                            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "中文后台输入") }
                            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                        }
                        repeat(node.childCount) { index -> node.getChild(index)?.let { act(it); it.recycle() } }
                    }
                    act(root)
                    fun apply(node: AccessibilityNodeInfo) {
                        if (node.contentDescription?.toString() == "execution_fixture_apply") assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        repeat(node.childCount) { index -> node.getChild(index)?.let { apply(it); it.recycle() } }
                    }
                    apply(root); root.recycle()
                }
            }
            withTimeout(5_000) { while (results.getValue("secondary").getString("text") != "中文后台输入") delay(50) }
            instrumentation.sendStatus(1, Bundle().apply { putString("EXECUTION_UNICODE", results.getValue("secondary").toString()) })
        } finally {
            session.close()
            context.sendBroadcast(Intent("${context.packageName}.test.FINISH_EXECUTION_PRIMARY").setPackage("${context.packageName}.test"))
            context.unregisterReceiver(receiver)
        }
        withTimeout(5_000) { while (manager.displays.map { it.displayId }.toSet() != before) delay(100) }
    }
}
