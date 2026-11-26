package com.flowchat.app.device

import android.app.UiAutomation
import android.content.*
import android.os.*
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.*
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.*

class ScopedObservationDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val gateway by lazy { ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context)) }
    private val scope = DeviceObservationScope(DeviceUiSelector(description = "r2b_scope"))
    private val crop = ScreenImageRegion(10, 72, 90, 87)
    @Volatile private var writes = -1
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { writes = intent.getIntExtra("writes", -1) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }.trim()
    private fun control(command: String) = context.sendBroadcast(Intent("$fixturePackage.R2B_CONTROL").setPackage(fixturePackage).putExtra("command", command))
    private fun report(stage: String) = instrumentation.sendStatus(1, Bundle().apply { putString("R2B_STAGE", stage) })

    @Before fun prepare() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("runScopedObservationDiagnostics") == "true") { "Explicit diagnostics opt-in required" }
        val filter = IntentFilter("${context.packageName}.R2B_RESULT")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (FlowChatAccessibilityService.current() != null) return@runBlocking
        shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
        if (withTimeoutOrNull(2000) { while (FlowChatAccessibilityService.current() == null) delay(100); true } == true) return@runBlocking
        val original = shell("settings get secure enabled_accessibility_services")
        val component = ComponentName(context, FlowChatAccessibilityService::class.java)
        check(original.split(':').any { ComponentName.unflattenFromString(it) == component }) { "Service must already be authorized" }
        val removed = original.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            check(shell("settings get secure enabled_accessibility_services") == original)
            try { check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, removed)); delay(250) }
            finally {
                check(shell("settings get secure enabled_accessibility_services").let { if (it == "null") "" else it } == removed)
                check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original))
            }
        } finally { automation.dropShellPermissionIdentity() }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current() == null) delay(100) }
    }

    @After fun finish() = runBlocking {
        try { control("finish"); delay(300) } finally { context.unregisterReceiver(receiver) }
    }

    private suspend fun start() {
        val output = shell("am start -W --display 0 -f 0x10008000 -n $fixturePackage/${ScopedObservationFixtureActivity::class.java.name}")
        check(!output.contains("Error:")) { output }
        withTimeout(12_000) {
            var previous: DeviceScreenSnapshot? = null
            var stableSince = SystemClock.elapsedRealtime()
            while (true) {
                val current = gateway.observeScreen(scope).screen
                if (current?.packageName != fixturePackage || current.observationErrorCode != null || previous?.hasSameUi(current) != true) stableSince = SystemClock.elapsedRealtime()
                if (current?.packageName == fixturePackage && current.observationErrorCode == null && SystemClock.elapsedRealtime() - stableSince >= 600) break
                previous = current; delay(100)
            }
        }
        writes = -1; control("report"); withTimeout(2000) { while (writes < 0) delay(20) }; assertEquals(0, writes)
    }

    private suspend fun baseline(): DeviceToolResult {
        val result = gateway.captureScreen(crop)
        assertTrue(result.asToolContent(), result.success); assertNotNull(result.screenImage)
        return result
    }
    private fun waitRequest(result: DeviceToolResult, timeout: Long = 3000) = DeviceScreenWaitRequest(
        DeviceScreenCondition(regionChangedFrom = result.screenImage!!.screenId), timeout, scope)

    @Test fun subtreeBeyond160HasSemanticsAndDispatchesOnceUsingItsOwnIndices() = runBlocking {
        start()
        val full = gateway.observeScreen().screen!!
        assertTrue(full.truncated); assertFalse(full.elements.any { it.contentDescription == "r2b_action" })
        val scopedImage = gateway.captureScreen(null, scope)
        assertTrue(scopedImage.asToolContent(), scopedImage.success)
        assertEquals(scope, scopedImage.screen!!.observationScope)
        val fullImage = gateway.captureScreen(null, null)
        assertTrue(fullImage.asToolContent(), fullImage.success)
        assertNull(fullImage.screen!!.observationScope)
        assertFalse(fullImage.screen!!.elements.any { it.contentDescription == "r2b_action" })
        val scoped = gateway.observeScreen(scope).screen!!
        assertTrue(scoped.elements.none { it.text.orEmpty().startsWith("Outside") })
        assertEquals(42f, scoped.elements.single { it.contentDescription == "r2b_range" }.range!!.current)
        assertEquals(12, scoped.elements.single { it.contentDescription == "r2b_scroll" }.collection!!.rows)
        assertTrue(scoped.elements.any { it.collectionItem != null && it.parentIndex != null })
        val button = scoped.elements.single { it.contentDescription == "r2b_action" }
        assertTrue(button.supportedActions.contains("click"))
        val tapped = gateway.tapUiElement(button.index, false, scoped.screenId)
        assertTrue(tapped.asToolContent(), tapped.success)
        val checked = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(element = DeviceUiSelector(
            description = "r2b_status", text = "Scoped completed")), observationScope = scope))
        assertTrue(checked.asToolContent(), checked.conditionMatched == true)
        withTimeout(2000) { while (writes != 1) delay(20) }; report("scoped-action-writes=$writes semantics-and-late-root=verified")
    }

    @Test fun scrollContainerAndScopedAbsenceAreDistinctFromOtherPanes() = runBlocking {
        start()
        val scrollScope = DeviceObservationScope(DeviceUiSelector(description = "r2b_scroll"), DeviceObservationScope.Kind.ScrollContainer)
        val scoped = gateway.observeScreen(scrollScope)
        assertTrue(scoped.asToolContent(), scoped.success)
        assertTrue(scoped.screen!!.elements.none { it.contentDescription == "r2b_action" })
        val absent = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(
            element = DeviceUiSelector(description = "r2b_action"), state = DeviceElementState.Absent), 1000, scrollScope))
        assertTrue(absent.asToolContent(), absent.conditionMatched == true)
        val invalid = gateway.observeScreen(scope.copy(kind = DeviceObservationScope.Kind.ScrollContainer))
        assertEquals("observation_root_not_scrollable", invalid.errorCode); assertTrue(invalid.screen!!.elements.isEmpty())
        assertEquals(0, writes)
    }

    @Test fun duplicateAndDisappearedRootsCannotReuseIndicesOrFallBack() = runBlocking {
        start()
        val screen = gateway.observeScreen(scope).screen!!
        val index = screen.elements.single { it.contentDescription == "r2b_action" }.index
        control("remove"); delay(150)
        val stale = gateway.tapUiElement(index, false, screen.screenId)
        assertEquals("stale_screen", stale.errorCode); assertEquals(DeviceActionOutcome.NotDispatched, stale.actionOutcome)
        assertTrue(stale.screen!!.elements.isEmpty())
        start(); control("duplicate"); delay(150)
        val duplicate = gateway.observeScreen(scope)
        assertEquals("observation_root_ambiguous", duplicate.errorCode); assertTrue(duplicate.screen!!.elements.isEmpty())
        assertEquals(0, writes)
    }

    @Test fun sensitiveSiblingBlocksBothScopedControlsAndPixelObservation() = runBlocking {
        start(); val before = baseline(); control("sensitive"); delay(200)
        val waited = gateway.waitForScreen(waitRequest(before))
        assertFalse(waited.success); assertEquals(false, waited.conditionMatched); assertNull(waited.screenImage)
        val observed = gateway.observeScreen(scope)
        assertEquals("sensitive_screen_blocked", observed.errorCode); assertTrue(observed.screen!!.elements.isEmpty())
        assertEquals(0, writes)
    }

    @Test fun outsideAnimationDoesNotSatisfyRegionalWaitAndDelayedPixelsProveOnlyProgress() = runBlocking {
        start(); control("animate"); delay(200)
        val unchanged = gateway.waitForScreen(waitRequest(baseline(), 1500))
        assertEquals("screen_condition_timeout", unchanged.errorCode); assertEquals(false, unchanged.conditionMatched)
        val before = baseline(); control("delayed")
        val started = SystemClock.elapsedRealtime()
        val changed = gateway.waitForScreen(waitRequest(before, 5000))
        assertTrue(changed.asToolContent(), changed.success); assertEquals(true, changed.conditionMatched)
        assertEquals(DeviceActionOutcome.ProgressObserved, changed.actionOutcome)
        assertTrue(SystemClock.elapsedRealtime() - started >= 1200)
        assertEquals(crop, changed.screenImage!!.region); assertEquals(0, writes)
        report("outside-animation=timeout delayed-crop=progress writes=$writes")
    }

    @Test fun missingBaselineAndCancelledWaitCannotWriteOrKeepWaiting() = runBlocking {
        start()
        val missing = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(regionChangedFrom = "old"), observationScope = scope))
        assertEquals("screen_region_baseline_unavailable", missing.errorCode)
        val before = baseline()
        val waiter = async { gateway.waitForScreen(waitRequest(before, 10_000)) }
        delay(250); val started = SystemClock.elapsedRealtime(); waiter.cancelAndJoin()
        assertTrue(SystemClock.elapsedRealtime() - started < 1500)
        assertTrue(gateway.observeScreen(scope).success); assertEquals(0, writes)
        report("cancelled-region-wait=stopped-and-mutex-released writes=$writes")
    }

    @Test fun secureWindowDiscardsRegionalPixels() = runBlocking {
        start(); val before = baseline(); control("secure"); delay(200)
        val result = gateway.waitForScreen(waitRequest(before))
        assertFalse(result.success); assertEquals(false, result.conditionMatched); assertNull(result.screenImage)
        assertEquals(0, writes)
    }
}
