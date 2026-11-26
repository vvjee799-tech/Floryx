package com.flowchat.app.device

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.UiTargetActionController
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Run against the preceding installed production APK, before installing the fix. */
class UiNavigationBaselineTest : ScrollFixtureDiagnostics() {
    @Test fun wideSourceBlocksContinuationDespiteACompleteDirectedPaneAndOneActualClick() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runNavigationBaseline") == "true")
        val pkg = instrumentation.context.packageName
        val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(
            "am start -W -f 0x10008000 -n $pkg/com.flowchat.app.device.NavigationFixtureActivity --ei start_page 7 --ez wide_page true --ez title_first false"
        )).bufferedReader().use { it.readText() }
        check(!output.contains("Error:"))
        val preview = requireNotNull(gateway.observeScreen().screen)
        assertTrue(preview.truncated); assertEquals(160, preview.elements.size)
        assertTrue(preview.elements.none { it.contentDescription == "nav_page_7" })
        val before = requireNotNull(gateway.observeScreen(DeviceObservationScope(DeviceUiSelector(description = "nav_page_7"))).screen)
        assertFalse(before.truncated); assertTrue(before.searchComplete)
        val result = UiTargetActionController(gateway).tap(DeviceUiTargetRequest(before.screenId, DeviceUiSelector(description = "nav_next_8"), 5000), before)
        assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome); assertFalse(result.success)
        val current = requireNotNull(gateway.observeScreen(DeviceObservationScope(DeviceUiSelector(description = "nav_page_8"))).screen)
        assertEquals("page=8;clicks=1;wrong=0", current.elements.single { it.contentDescription == "nav_ledger" }.text)
        report("batch2d baseline=true full_preview_truncated=true preview_controls=160 source_pane_complete=true actual_clicks=1 destination_reached=true continuation_blocked=true")
    }

    @Test fun replacedRootBlocksTheOldScopedNavigationDespiteOneActualClick() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runNavigationBaseline") == "true")
        val pkg = instrumentation.context.packageName
        val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(
            "am start -W -f 0x10008000 -n $pkg/com.flowchat.app.device.NavigationFixtureActivity"
        )).bufferedReader().use { it.readText() }
        check(!output.contains("Error:"))
        val scope = DeviceObservationScope(DeviceUiSelector(description = "nav_page_0"))
        val before = requireNotNull(gateway.observeScreen(scope).screen)
        val result = UiTargetActionController(gateway).tap(DeviceUiTargetRequest(before.screenId,
            DeviceUiSelector(description = "nav_next_1"), 5000), before)
        assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome)
        assertFalse(result.success)
        val current = requireNotNull(gateway.observeScreen().screen)
        assertEquals("page=1;clicks=1;wrong=0", current.elements.single { it.contentDescription == "nav_ledger" }.text)
        report("batch2c baseline=true navigation_dispatched=1 destination_page=1 returned_outcome=${result.actionOutcome} old_scope_error=${result.screen?.observationErrorCode} task_continuation_blocked=true")
    }
}
