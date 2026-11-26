package com.flowchat.app.device

import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** Explicitly opted-in, cross-UID synthetic fixture. Never uses real accounts or model endpoints. */
class WindowContextDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val results = ConcurrentHashMap<String, Intent>()
    private val gateway by lazy { ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context)) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { results[intent.getStringExtra("kind")!!] = intent }
    }
    private fun report(stage: String) = instrumentation.sendStatus(1, Bundle().apply { putString("WINDOW_CONTEXT_STAGE", stage) })
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)
    ).bufferedReader().use { it.readText() }.trim().also { check(!it.contains("Error:") && !it.contains("Exception")) { it } }
    private fun control(command: String) = context.sendBroadcast(Intent("$fixturePackage.R2_CONTROL").setPackage(fixturePackage).putExtra("command", command))

    @Before fun prepare() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("runWindowContextDiagnostics") == "true") { "Explicit window-context diagnostics opt-in required." }
        val filter = IntentFilter("${context.packageName}.R2_RESULT")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (FlowChatAccessibilityService.current() != null) return@runBlocking
        shell("am start -W -n ${context.packageName}/com.flowchat.app.MainActivity")
        if (withTimeoutOrNull(2_000) { while (FlowChatAccessibilityService.current() == null) delay(100); true } == true) return@runBlocking
        val original = shell("settings get secure enabled_accessibility_services")
        val component = ComponentName(context, FlowChatAccessibilityService::class.java)
        check(original.split(':').any { ComponentName.unflattenFromString(it) == component }) { "The service must already be authorized." }
        val removed = original.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            check(shell("settings get secure enabled_accessibility_services") == original)
            try {
                check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, removed))
                delay(250)
            } finally {
                val current = shell("settings get secure enabled_accessibility_services").let { if (it == "null") "" else it }
                check(current == removed) { "Authorization changed; preserve the user's new selection." }
                check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original))
            }
        } finally { automation.dropShellPermissionIdentity() }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current() == null) delay(100) }
    }

    @After fun cleanup() = runBlocking {
        try {
            control("finish")
            withTimeout(5_000) { while (FlowChatAccessibilityService.current()?.observeScreen()?.packageName == fixturePackage) delay(100) }
        } finally { context.unregisterReceiver(receiver) }
    }

    private suspend fun settled(): DeviceScreenSnapshot = withTimeout(10_000) {
        var previous: DeviceScreenSnapshot? = null
        var stableSince = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val current = gateway.observeScreen().screen
            val now = android.os.SystemClock.elapsedRealtime()
            if (current?.packageName != fixturePackage || current.interactionContext?.complete != true || previous?.hasSameUi(current) != true) stableSince = now
            if (current?.packageName == fixturePackage && now - stableSince >= 600) return@withTimeout current
            previous = current; delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Fixture did not settle")
    }

    private suspend fun start(extras: String = ""): DeviceScreenSnapshot {
        shell("am start -W --display 0 -f 0x10008000 -n $fixturePackage/${WindowContextFixtureActivity::class.java.name} $extras")
        return withTimeout(12_000) {
            var previous: DeviceScreenSnapshot? = null
            var stableSince = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val current = gateway.observeScreen().screen
                val now = android.os.SystemClock.elapsedRealtime()
                if (current?.packageName != fixturePackage || current.elements.none { it.contentDescription == "r2_start" } ||
                    previous?.hasSameUi(current) != true) stableSince = now
                if (current?.packageName == fixturePackage && now - stableSince >= 600) return@withTimeout current
                previous = current; delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Fixture unavailable")
        }
    }

    private fun DeviceScreenSnapshot.index(description: String) = elements.single { it.contentDescription == description }.index
    private suspend fun click(description: String): DeviceToolResult {
        var screen = requireNotNull(gateway.observeScreen().screen)
        repeat(3) {
            val result = gateway.tapUiElement(screen.index(description), false, screen.screenId)
            if (result.errorCode != "stale_screen") return result
            assertEquals(DeviceActionOutcome.NotDispatched, result.actionOutcome)
            screen = requireNotNull(result.screen)
        }
        error("Synthetic fixture did not settle before dispatch")
    }
    private suspend fun count(kind: String, minimum: Int = 1): Int = withTimeout(2_000) {
        while ((results[kind]?.getIntExtra("count", -1) ?: -1) < minimum) delay(25)
        results.getValue(kind).getIntExtra("count", -1)
    }

    private suspend fun delayed(latency: Long) {
        start("--el delay_ms $latency")
        val dispatched = click("r2_start")
        assertTrue(dispatched.asToolContent(), dispatched.success)
        assertEquals(DeviceActionOutcome.ProgressObserved, dispatched.actionOutcome)
        val result = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage,
            element = DeviceUiSelector(text = "Requested result completed")), 10_000))
        assertTrue(result.asToolContent(), result.success)
        assertEquals(true, result.conditionMatched)
        assertEquals(DeviceActionOutcome.TargetVerified, result.actionOutcome)
        assertEquals(1, count("write"))
        report("delay-${latency}ms-condition-verified-single-dispatch")
    }

    @Test fun observesTwoSecondLoadingWithoutReplayingTheWrite() = runBlocking { delayed(2_200) }
    @Test fun observesNearlyTenSecondLoadingWithoutReplayingTheWrite() = runBlocking { delayed(9_800) }

    @Test fun changedPageWithBusinessFailureDoesNotSatisfySuccessCondition() = runBlocking {
        start()
        val dispatched = click("r2_failure")
        assertTrue(dispatched.asToolContent(), dispatched.success)
        assertEquals(DeviceActionOutcome.ProgressObserved, dispatched.actionOutcome)
        val result = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage,
            element = DeviceUiSelector(text = "Requested result completed")), 700))
        assertFalse(result.asToolContent(), result.success)
        assertEquals("screen_condition_timeout", result.errorCode)
        assertEquals(false, result.conditionMatched)
        assertEquals(1, count("write"))
        report("business-failure-not-misreported-and-write-not-replayed")
    }

    @Test fun keyboardChangesInvalidateOldTargetsAndRemainOutsideSwipePaths() = runBlocking {
        val old = start()
        control("keyboard")
        val keyboard = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage, keyboardVisible = true), 5_000))
        assertTrue(keyboard.asToolContent(), keyboard.success)
        val screen = requireNotNull(keyboard.screen)
        val ctx = requireNotNull(screen.interactionContext)
        assertNotNull(ctx.inputFocusIndex)
        val path = requireNotNull(ctx.swipe(screen, DeviceSwipeDirection.Up, 50))
        repeat(101) { i -> assertTrue(ctx.allowsPoint(screen, path.startX + (path.endX - path.startX) * i / 100,
            path.startY + (path.endY - path.startY) * i / 100)) }
        val rejected = gateway.tapUiElement(old.index("r2_start"), false, old.screenId)
        assertEquals(rejected.asToolContent(), "stale_screen", rejected.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, rejected.actionOutcome)
        assertNull(results["write"])
        report("real-ime-focus-action-region-and-old-target-rejection-verified")
    }

    @Test fun popupFocusInvalidatesThePreviousWindowWithoutDispatch() = runBlocking {
        val old = start()
        control("dialog")
        val result = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage,
            element = DeviceUiSelector(text = "Dialog marker")), 5_000))
        assertTrue(result.asToolContent(), result.success)
        assertNotEquals(old.windowId, result.screen!!.windowId)
        val rejected = gateway.tapUiElement(old.index("r2_start"), false, old.screenId)
        assertEquals("stale_screen", rejected.errorCode)
        assertNull(results["write"])
        report("popup-window-focus-rejects-old-plan")
    }

    @Test fun exactQueryFindsAndActsOnTheTargetBeyond160Controls() = runBlocking {
        val initial = start("--ez wide true")
        assertTrue(initial.truncated)
        assertTrue(initial.elements.none { it.contentDescription == "r2_late" })
        val result = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage,
            element = DeviceUiSelector(description = "r2_late"), state = DeviceElementState.Actionable), 3_000))
        assertTrue(result.asToolContent(), result.success)
        val scoped = requireNotNull(result.screen)
        assertEquals(1, scoped.elements.size)
        assertTrue(scoped.searchComplete)
        val action = gateway.tapUiElement(scoped.index("r2_late"), false, scoped.screenId)
        assertFalse(action.asToolContent(), action.success)
        assertEquals("ui_result_unverified", action.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, action.actionOutcome)
        assertEquals(1, count("write"))
        val verified = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage,
            element = DeviceUiSelector(text = "Late target dispatched")), 1_000))
        assertTrue(verified.asToolContent(), verified.success)
        assertEquals(true, verified.conditionMatched)
        assertEquals(1, count("write"))
        report("beyond-160-exact-query-and-fresh-scoped-index-dispatch-verified")
    }

    @Test fun aScopedOpaqueActionIsNotVerifiedByChangingTheObservationScope() = runBlocking {
        start()
        val result = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage,
            element = DeviceUiSelector(description = "r2_opaque"), state = DeviceElementState.Actionable), 1_000))
        assertTrue(result.asToolContent(), result.success)
        val scoped = requireNotNull(result.screen)
        val action = gateway.tapUiElement(scoped.index("r2_opaque"), false, scoped.screenId)
        assertFalse(action.asToolContent(), action.success)
        assertEquals("ui_result_unverified", action.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, action.actionOutcome)
        assertEquals(1, count("write"))
        val verified = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(fixturePackage,
            element = DeviceUiSelector(text = "Requested result completed")), 500))
        assertFalse(verified.asToolContent(), verified.success)
        assertEquals(false, verified.conditionMatched)
        assertEquals(1, count("write"))
        report("scoped-opaque-write-unverified-once-despite-wider-followup-observation")
    }

    @Test fun coordinateFallbackOnAnOffsetWindowUsesItsPhysicalBounds() = runBlocking {
        val initial = start("--ez offset true")
        val geometry = requireNotNull(initial.geometry)
        assertTrue(geometry.windowBounds.left > 0 && geometry.windowBounds.top > 0)
        assertTrue(geometry.windowBounds.width < geometry.displayWidth)
        val action = click("r2_fallback")
        assertTrue(action.asToolContent(), action.success)
        assertEquals(1, count("gesture"))
        val coordinates = results.getValue("gesture")
        assertTrue(geometry.windowBounds.contains(coordinates.getFloatExtra("minX", -1f).toInt(), coordinates.getFloatExtra("minY", -1f).toInt()))
        assertTrue(geometry.windowBounds.contains(coordinates.getFloatExtra("maxX", -1f).toInt(), coordinates.getFloatExtra("maxY", -1f).toInt()))
        val swipe = gateway.swipeScreen(DeviceSwipeDirection.Up, 40)
        assertTrue(swipe.asToolContent(), swipe.success)
        assertEquals(2, count("gesture", 2))
        val swiped = results.getValue("gesture")
        assertTrue(geometry.windowBounds.contains(swiped.getFloatExtra("minX", -1f).toInt(), swiped.getFloatExtra("minY", -1f).toInt()))
        assertTrue(geometry.windowBounds.contains(swiped.getFloatExtra("maxX", -1f).toInt(), swiped.getFloatExtra("maxY", -1f).toInt()))
        report("offset-window-real-coordinate-fallback-within-owned-window")
    }

    @Test fun rotationRejectsTheOldTargetAndHorizontalSwipeStaysInsideTheNewWindow() = runBlocking {
        val old = start()
        control("rotate")
        val rotated = withTimeout(8_000) {
            var current: DeviceScreenSnapshot
            do { delay(150); current = requireNotNull(gateway.observeScreen().screen) }
            while (current.geometry?.rotation == old.geometry?.rotation || current.geometry?.displayWidth!! <= current.geometry.displayHeight)
            current
        }
        val rejected = gateway.tapUiElement(old.index("r2_start"), false, old.screenId)
        assertEquals(rejected.asToolContent(), "stale_screen", rejected.errorCode)
        assertEquals(DeviceActionOutcome.NotDispatched, rejected.actionOutcome)
        val stable = settled()
        report("rotation-pre-swipe: ${stable.asToolContent()}")
        val swipe = gateway.swipeScreen(DeviceSwipeDirection.Right, 40)
        // This fixture only records touch; its static content does not scroll. A dispatched,
        // correctly bounded gesture must not be reported as verified content movement.
        assertFalse(swipe.asToolContent(), swipe.success)
        assertTrue(swipe.asToolContent(), swipe.errorCode in setOf("ui_scroll_motion_unverified", "ui_scroll_no_progress"))
        assertEquals(DeviceActionOutcome.StateObserved, swipe.actionOutcome)
        assertEquals(1, swipe.scrollFeedback?.actions)
        assertEquals(1, count("windowTouch"))
        val geometry = requireNotNull(stable.geometry)
        val coordinates = results.getValue("windowTouch")
        assertFalse("The rotated gesture was cancelled", coordinates.getBooleanExtra("cancelled", true))
        assertTrue(geometry.windowBounds.contains(coordinates.getFloatExtra("minX", -1f).toInt(), coordinates.getFloatExtra("minY", -1f).toInt()))
        assertTrue(geometry.windowBounds.contains(coordinates.getFloatExtra("maxX", -1f).toInt(), coordinates.getFloatExtra("maxY", -1f).toInt()))
        assertNull(results["write"])
        report("real-rotation-invalidates-old-target-horizontal-swipe-remains-owned-no-false-scroll-success")
    }
}
