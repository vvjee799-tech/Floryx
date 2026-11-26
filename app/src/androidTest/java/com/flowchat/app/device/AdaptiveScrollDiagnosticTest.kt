package com.flowchat.app.device

import android.app.UiAutomation
import android.content.*
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.*
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

abstract class ScrollFixtureDiagnostics {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    protected val gateway by lazy { ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context)) }
    @Volatile private var metrics: JSONObject? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { metrics = JSONObject(intent.getStringExtra("data")!!) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
    protected fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nADAPTIVE_SCROLL $value\n") })
    protected suspend fun control(command: String, y: Int = 0): JSONObject {
        metrics = null
        shell("am broadcast -a $fixturePackage.SCROLL_CONTROL -p $fixturePackage --es command $command --ei y $y")
        delay(100)
        try { withTimeout(5000) { while (metrics == null) {
            // OriginOS can suppress the return broadcast while the target app is in the background.
            // The same independent measurements are also exposed on the fixture header.
            val visible = gateway.observeScreen().screen?.elements?.singleOrNull { it.contentDescription?.startsWith("scroll_metrics:") == true }
            visible?.contentDescription?.substringAfter("scroll_metrics:")?.let { metrics = JSONObject(it) }
            if (metrics == null) delay(100)
        } } }
        catch (error: TimeoutCancellationException) {
            val latest = gateway.observeScreen()
            report("control_timeout command=$command success=${latest.success} error=${latest.errorCode} package=${latest.screen?.packageName} descriptions=${latest.screen?.elements?.mapNotNull { it.contentDescription }?.take(3)} focus=" +
                shell("dumpsys activity activities").lineSequence().firstOrNull { it.contains("topResumedActivity") })
            throw error
        }
        delay(100)
        return requireNotNull(metrics)
    }
    @Before fun prepare() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("runAdaptiveScrollDiagnostics") == "true")
        val filter = IntentFilter("${context.packageName}.SCROLL_RESULT")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (FlowChatAccessibilityService.current() == null) {
            shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
            if (withTimeoutOrNull(2000) { while (FlowChatAccessibilityService.current() == null) delay(100); true } != true) {
                val original = shell("settings --user 0 get secure enabled_accessibility_services")
                val component = ComponentName(context, FlowChatAccessibilityService::class.java)
                check(original.split(':').any { ComponentName.unflattenFromString(it) == component }) { "Service must already be authorized" }
                val remaining = original.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
                automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
                try {
                    check(shell("settings --user 0 get secure enabled_accessibility_services") == original)
                    try { check(android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, remaining)); delay(250) }
                    finally { check(android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)) }
                } finally { automation.dropShellPermissionIdentity() }
            }
        }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current() == null) delay(100) }
    }
    @After fun finish() = runBlocking<Unit> {
        try { context.sendBroadcast(Intent("$fixturePackage.SCROLL_CONTROL").setPackage(fixturePackage).putExtra("command", "finish")); delay(150) }
        finally { context.unregisterReceiver(receiver) }
    }
    protected suspend fun start(mode: String = "list", targetRow: Int = -1, initialY: Int = -1, gainTenths: Int = 58) {
        val output = shell("am start -W --display 0 -f 0x10008000 -n $fixturePackage/" +
            "${AdaptiveScrollFixtureActivity::class.java.name} --es mode $mode --ei partial_row $targetRow --ei initial_y $initialY --ei gain_tenths $gainTenths")
        check(!output.contains("Error:")) { output }
        withTimeout(12_000) {
            while (gateway.observeScreen().screen?.packageName != fixturePackage) delay(100)
        }
        delay(300)
        report("fixture-start mode=$mode package=${gateway.observeScreen().screen?.packageName}")
        control("report")
    }
}

class AdaptiveScrollDiagnosticTest : ScrollFixtureDiagnostics() {
    @Test fun recordLegacyDistanceAndSemanticScrollBaseline() = runBlocking {
        start()
        val percent = InstrumentationRegistry.getArguments().getString("baselinePercent", "2").toInt()
        gateway.observeScreen()
        val started = SystemClock.elapsedRealtime()
        val result = gateway.swipeScreen(DeviceSwipeDirection.Up, percent)
        delay(500); val measured = control("report")
        report("baseline percent=$percent elapsed_ms=${SystemClock.elapsedRealtime() - started} result=${result.success} metrics=$measured")
    }
    @Test fun recordLegacySemanticBaseline() = runBlocking {
        start()
        val before = requireNotNull(gateway.observeScreen().screen)
        val container = before.elements.single { it.contentDescription == "scroll_list" }
        val result = gateway.scrollContainer(container.index, DeviceScrollDirection.Forward, before.screenId)
        delay(500); report("baseline semantic=${result.success} metrics=${control("report")}")
    }
}
