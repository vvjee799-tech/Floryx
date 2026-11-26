package com.flowchat.app.device

import android.content.ComponentName
import android.content.Intent
import android.app.UiAutomation
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Requires the selected authorized device with Floryx Dev accessibility enabled. */
class DeviceAutomationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val gateway by lazy {
        ShizukuDeviceAssistantGateway(context,
            AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root)),
            RootDeviceConnection(context))
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)
    ).bufferedReader().use { it.readText() }.also {
        check(!it.contains("Error:") && !it.contains("Exception")) { it }
    }

    private suspend fun startFixture(sensitive: Boolean = false, deepTree: Boolean = false) {
        ensureService()
        val command = "am start -W -f 0x10008000 -n ${instrumentation.context.packageName}/${DeviceAutomationFixtureActivity::class.java.name} --ez sensitive $sensitive --ez deep_tree $deepTree"
        shell(command)
        // OEM transitions can still show the launcher after am start reports success.
        // Do not begin any action until the requested synthetic page is actually readable.
        withTimeout(15_000) {
            var previous: DeviceScreenSnapshot? = null
            var stableSince = android.os.SystemClock.elapsedRealtime()
            val launchedAt = stableSince
            var retriedLaunch = false
            while (true) {
                val current = gateway.observeScreen().screen
                val now = android.os.SystemClock.elapsedRealtime()
                val expected = current?.packageName == instrumentation.context.packageName && current.sensitive == sensitive &&
                    (sensitive || (current.elements.any { it.editable } && current.elements.any { it.displayText == "Test toggle" } &&
                        current.elements.filter { it.editable || it.displayText == "Test toggle" || it.displayText == "Gesture pad" }
                            .all { element -> current.interactionContext?.allowsPoint(current,
                                element.bounds.centerX, element.bounds.centerY) == true }))
                // A stable control tree can still be covered by a transient system window.
                // Wait before the first fixture write; never hide a blocker or weaken dispatch checks.
                if (!expected || current == null || previous?.hasSameUi(current) != true || previous?.geometry != current.geometry) {
                    stableSince = now
                }
                if (expected && now - stableSince >= 800) break
                previous = current.takeIf { expected }
                if (!expected && !retriedLaunch && now - launchedAt >= 1_500) {
                    // Only setup is retried: no test input or application action has run yet.
                    shell(command)
                    retriedLaunch = true
                    previous = null
                    stableSince = android.os.SystemClock.elapsedRealtime()
                }
                delay(100)
            }
        }
    }

    private suspend fun ensureService() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (FlowChatAccessibilityService.current()?.observeScreen() != null) return
        shell("am start -W -n ${context.packageName}/${com.flowchat.app.MainActivity::class.java.name}")
        delay(500)
        // Recent Android/OEM builds can hide this secure setting from the target app itself.
        val existing = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "settings get secure enabled_accessibility_services"
        )).bufferedReader().use { it.readText().trimEnd('\r', '\n') }
        val component = ComponentName(context, FlowChatAccessibilityService::class.java).flattenToString()
        check(existing.split(':').contains(component)) { "Enable FlowChat Dev accessibility before running device tests." }
        fun writeServices(value: String) = check(Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value
        ))
        // Instrumentation restarts the target process; rebind only the already-authorized service.
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            try {
                writeServices(existing.split(':').filterNot { it == component }.joinToString(":"))
                delay(250)
            } finally {
                writeServices(existing)
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current()?.observeScreen() == null) delay(200) }
    }

    private suspend fun screen(): DeviceScreenSnapshot =
        requireNotNull(gateway.observeScreen().screen)

    @Test fun readsAndOperatesControlsInADeeplyNestedScreen() = runBlocking {
        startFixture(deepTree = true)
        val initial = screen()
        assertFalse(initial.truncated)
        val input = initial.elements.first { it.editable }
        val typed = gateway.inputText(input.index, "Deep screen 中文", true, initial.screenId)
        assertTrue(typed.asToolContent(), typed.success)
        val current = requireNotNull(typed.screen)
        assertTrue(current.elements.any { it.editable && it.text == "Deep screen 中文" })
        val toggle = current.elements.first { it.text == "Test toggle" }
        val tapped = gateway.tapUiElement(toggle.index, false, current.screenId)
        assertTrue(tapped.asToolContent(), tapped.success)
        assertTrue(tapped.screen!!.elements.any { it.text == "Test toggle" && it.checked == true })
    }

    @Test fun repeatedUnicodeInputAppendAndToggleKeepTheirActualState() = runBlocking {
        startFixture()
        var current = screen()
        repeat(20) { round ->
            val value = "Floryx 第${round + 1}轮 中文 ✅"
            val typed = gateway.inputText(current.elements.single { it.editable }.index, value, true, current.screenId)
            assertTrue("round=$round ${typed.asToolContent()}", typed.success)
            current = requireNotNull(typed.screen)
            assertEquals(value, current.elements.single { it.editable }.text)
            val appended = gateway.inputText(current.elements.single { it.editable }.index, " +1", false, current.screenId)
            assertTrue("round=$round ${appended.asToolContent()}", appended.success)
            current = requireNotNull(appended.screen)
            assertEquals("$value +1", current.elements.single { it.editable }.text)
            val toggle = current.elements.single { it.text == "Test toggle" }
            val toggled = gateway.tapUiElement(toggle.index, false, current.screenId)
            assertTrue("round=$round ${toggled.asToolContent()}", toggled.success)
            current = requireNotNull(toggled.screen)
            assertEquals(round % 2 == 0, current.elements.single { it.text == "Test toggle" }.checked)
        }
    }

    @Test fun observesInputsChecksStateRejectsStaleTargetsAndScrolls() = runBlocking {
        startFixture()
        val initial = screen()
        assertEquals(instrumentation.context.packageName, initial.packageName)
        val toggle = initial.elements.first { it.displayText == "Test toggle" }
        assertEquals(false, toggle.checked)
        val stale = gateway.tapUiElement(toggle.index, false, "expired")
        assertFalse(stale.success)
        assertEquals("stale_screen", stale.errorCode)
        val current = requireNotNull(stale.screen)
        val tapped = gateway.tapUiElement(current.elements.first { it.displayText == "Test toggle" }.index, false, current.screenId)
        assertTrue(tapped.asToolContent(), tapped.success)
        assertEquals(true, tapped.screen?.elements?.first { it.displayText == "Test toggle" }?.checked)

        val form = requireNotNull(tapped.screen)
        val edit = form.elements.first { it.editable }
        val typed = gateway.inputText(edit.index, "FlowChat 中文 test", true, form.screenId)
        assertTrue(typed.asToolContent(), typed.success)
        assertTrue(typed.screen!!.elements.any { it.editable && it.text == "FlowChat 中文 test" })
        val appended = gateway.inputText(typed.screen.elements.first { it.editable }.index, " 123", false, typed.screen.screenId)
        assertTrue(appended.screen!!.elements.any { it.text == "FlowChat 中文 test 123" })
        val swiped = gateway.swipeScreen(DeviceSwipeDirection.Up, 50)
        assertTrue(swiped.asToolContent(), swiped.success)
        assertFalse(requireNotNull(swiped.screen).hasSameUi(requireNotNull(appended.screen)))
        // Reusing a real earlier screen ID is blocked even though it once pointed to a valid input.
        val oldInput = gateway.inputText(edit.index, "must not be entered", true, form.screenId)
        assertEquals("stale_screen", oldInput.errorCode)
        assertFalse(oldInput.screen!!.elements.any { it.text == "must not be entered" })
    }

    @Test fun awaitsFallbackLongPressBeforeObservingItsResult() = runBlocking {
        startFixture()
        val before = screen()
        val pad = before.elements.first { it.displayText == "Gesture pad" }
        val result = gateway.tapUiElement(pad.index, true, before.screenId)
        assertTrue(result.asToolContent(), result.success)
        assertTrue("BEFORE: ${before.asToolContent()}\nAFTER: ${result.asToolContent()}",
            result.screen!!.elements.any { it.text == "Gesture completed" })
    }

    @Test fun sensitivePageHidesTextAndBlocksInputButAllowsLeaving() = runBlocking {
        startFixture(sensitive = true)
        val observed = gateway.observeScreen()
        assertFalse(observed.success)
        assertTrue(observed.screen!!.sensitive)
        assertTrue(observed.screen.elements.isEmpty())
        assertEquals("sensitive_screen_blocked", gateway.inputText(0, "blocked", true, "expired").errorCode)
        assertTrue(gateway.pressHome().success)
    }

    @Test fun readsRealAndroidSettingsAndNavigatesBackHome() = runBlocking {
        ensureService()
        shell("am start -W -a ${Settings.ACTION_SETTINGS}")
        delay(800)
        val observed = screen()
        assertEquals("com.android.settings", observed.packageName)
        assertTrue(observed.elements.isNotEmpty())
        val swiped = gateway.swipeScreen(DeviceSwipeDirection.Up, 50)
        assertEquals(swiped.asToolContent(), "com.android.settings", swiped.screen?.packageName)
        // Settings retains its scroll position across launches; an already-reached edge is valid.
        if (!swiped.success) assertTrue(swiped.asToolContent(),
            swiped.errorCode == "ui_change_unverified" || swiped.errorCode == "ui_no_change")
        assertTrue(gateway.pressBack().success)
        val home = gateway.pressHome()
        // Back may already have reached Home: verify the destination instead of demanding a change.
        val homePackage = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        assertNotNull("The device must have a default Home application", homePackage)
        assertEquals(home.asToolContent(), homePackage, home.screen?.packageName)
        if (!home.success) assertEquals("ui_change_unverified", home.errorCode)
    }
}
