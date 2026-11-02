package com.flowchat.app.diagnostics

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import com.flowchat.app.data.device.*
import com.flowchat.app.shizuku.IFlowChatRootService
import com.flowchat.app.shizuku.IFlowChatShizukuService
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import org.json.JSONObject

@Keep
class RootDisplayProbeService : RootService() {
    private var display: RootExecutionDisplay? = null
    private var cached: String? = null
    private var beforeDisplays = emptyList<Int>()
    private val endpoint = object : IFlowChatRootService.Stub() {
        override fun getServiceInfo(): String {
            check(Binder.getCallingUid() == applicationInfo.uid)
            cached?.let { return it }
            val result = JSONObject().put("uid", Process.myUid()).put("pid", Process.myPid())
                .put("protocol", RootServiceInfo.Protocol).put("activeRequests", 0).put("activeCommands", 0)
            try {
                ProcessDeviceCommandExecutor().use { commands ->
                    val fixturePackage = "${packageName}.test"
                    commands.execute(listOf("am", "start", "-W", "-n", "$fixturePackage/com.flowchat.app.device.ExecutionDisplayPrimaryFixtureActivity"))
                    fun focus(): String {
                        val dump = commands.execute(listOf("dumpsys", "window", "displays")).output
                        var primary = false
                        return dump.lineSequence().filter { line ->
                            if (line.contains("Display: mDisplayId=")) primary = line.contains("mDisplayId=0 ")
                            primary && (line.contains("mCurrentFocus=") || line.contains("mFocusedApp="))
                        }.joinToString("\n")
                    }
                    result.put("focusBefore", focus())
                    beforeDisplays = getSystemService(DisplayManager::class.java).displays.map { it.displayId }.sorted()
                    val screen = RootExecutionDisplay.create(this@RootDisplayProbeService).also { display = it }
                    result.put("display", JSONObject(screen.info()))
                    val launched = screen.launch(ComponentName("${packageName}.test", "com.flowchat.app.device.ExecutionDisplayFixtureActivity"))
                    result.put("launch", launched.output).put("launchSuccess", launched.success)
                    Thread.sleep(500)
                    result.put("tapInput", screen.tap(180, 120).output)
                    result.put("text", screen.asciiText("ROOT4").output)
                    result.put("tapApply", screen.tap(180, 200).output)
                    Thread.sleep(500)
                    val bitmap = screen.bitmap()
                    result.put("pixel", bitmap.getPixel(10, 300))
                    File(applicationInfo.dataDir, "cache/execution-display-probe.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    result.put("focusAfter", focus())
                }
            } catch (error: Throwable) {
                result.put("error", error.stackTraceToString().take(8000))
            }
            File(applicationInfo.dataDir, "cache/execution-display-probe.json").writeText(result.toString(2))
            return result.toString().also { cached = it }
        }
        override fun openRequest(requestId: String): IFlowChatShizukuService = error("Probe only")
        override fun finishRequest(requestId: String, cancelled: Boolean) = Unit
        override fun openExecutionDisplay(ownerId: String): com.flowchat.app.shizuku.IFlowChatExecutionDisplay = error("Probe only")
        override fun closeExecutionDisplay(ownerId: String) = Unit
    }
    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onDestroy() {
        display?.close()
        val after = getSystemService(DisplayManager::class.java).displays.map { it.displayId }.sorted()
        File(applicationInfo.dataDir, "cache/execution-display-release.json").writeText(JSONObject().apply {
            put("before", org.json.JSONArray(beforeDisplays)); put("after", org.json.JSONArray(after))
        }.toString())
        super.onDestroy()
    }
}
