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

/** Requires the user-authorized MuMu device with FlowChat Dev accessibility enabled. */
class DeviceAutomationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val gateway by lazy {
        ShizukuDeviceAssistantGateway(context,
            AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root)),
            RootDeviceConnection())
    }

    private suspend fun startFixture(sensitive: Boolean = false, deepTree: Boolean = false) {
        ensureService()
        context.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName, DeviceAutomationFixtureActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("sensitive", sensitive)
            putExtra("deep_tree", deepTree)
        })
        delay(800)
    }

    private suspend fun ensureService() {
        if (FlowChatAccessibilityService.current() != null) return
        context.startActivity(Intent(context, com.flowchat.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(500)
        val existing = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, FlowChatAccessibilityService::class.java).flattenToString()
        check(existing.split(':').contains(component)) { "Enable FlowChat Dev accessibility before running device tests." }
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
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
        withTimeout(10_000) { while (FlowChatAccessibilityService.current() == null) delay(200) }
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
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(800)
        val observed = screen()
        assertEquals("com.android.settings", observed.packageName)
        assertTrue(observed.elements.isNotEmpty())
        assertTrue(gateway.swipeScreen(DeviceSwipeDirection.Up, 50).success)
        assertTrue(gateway.pressBack().success)
        assertTrue(gateway.pressHome().success)
    }
}
