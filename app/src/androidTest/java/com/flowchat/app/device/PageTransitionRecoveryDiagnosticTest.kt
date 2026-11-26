package com.flowchat.app.device

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.*
import com.flowchat.app.domain.device.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Controlled physical page changes exercise the production verifier; no fabricated screen digests. */
class PageTransitionRecoveryDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun scope(page: Int) = DeviceObservationScope(DeviceUiSelector(description = "nav_page_$page"))
    private fun enabled() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runNavigationRecovery") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
    }
    private fun primaryWindow(): String {
        val primary = shell("dumpsys window displays").substringAfter("Display: mDisplayId=0 ").substringBefore("Display: mDisplayId=")
        val token = requireNotNull(primary.lineSequence().firstOrNull { it.trim().startsWith("mTopFullscreenOpaqueWindowState=Window{") }
            ?.substringAfter('=')?.trim())
        check(token.contains("com.android.settings")) { token }
        val record = shell("dumpsys window windows").split(Regex("\\n\\s*Window #"))
            .single { it.substringBefore('\n').contains(token.substringBefore(' ')) }
        check(record.contains("mDisplayId=0") && record.contains("mHasSurface=true") && record.contains("isOnScreen=true"))
        return token
    }

    @Test fun foregroundTransientPairRecoversAndContinuesTheOriginalRoute() = runBlocking {
        enabled(); exercise(background = false, persistent = false)
    }
    @Test fun foregroundPersistentContradictionsStayUnknownWithoutRepeatingTheTap() = runBlocking {
        enabled(); exercise(background = false, persistent = true)
    }
    @Test fun backgroundTransientPairAndPersistentFailureKeepThePrimaryWindowUntouched() = runBlocking {
        enabled(); exercise(background = true, persistent = false); exercise(background = true, persistent = true)
    }

    private suspend fun exercise(background: Boolean, persistent: Boolean) {
        ActionOutcomeServiceReadiness().ensureService()
        val tasks = DeviceTaskControl()
        val root = RootDeviceConnection(app)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root,
            backgroundExecutionEnabled = background))
        val gateway = ShizukuDeviceAssistantGateway(app, settings, root, tasks)
        var task: DeviceTaskControl.Task? = null
        val caseId = "${if (background) "background" else "foreground"}-${if (persistent) "persistent" else "transient"}"
        val label = InstrumentationRegistry.getArguments().getString("navigationRecoveryRun", "diagnostic")!!
        check(label.matches(Regex("[a-z0-9-]{1,64}")))
        val directory = File(app.getExternalFilesDir(null), "navigation-d2/$label").apply { mkdirs() }
        val started = SystemClock.elapsedRealtime()
        try {
            root.refresh(requestPermission = true); gateway.refreshConnection()
            withTimeout(15_000) { while (!root.state.value.isConnected) delay(100) }
            task = tasks.begin(); tasks.arm(task)
            val primary = if (background) {
                check(!shell("am start -W --display 0 -a android.settings.SETTINGS").contains("Error:"))
                primaryWindow()
            } else null
            if (background) assertTrue(gateway.launchApp(fixturePackage, NavigationFixtureActivity::class.java.name).success)
            else check(!shell("am start -W --display 0 -f 0x10008000 -n $fixturePackage/${NavigationFixtureActivity::class.java.name} --ei start_page 99 --ez rearranged true").contains("Error:"))
            suspend fun tapPage(page: Int, next: Int): DeviceToolResult {
                val before = requireNotNull(gateway.observeScreen(scope(page)).screen)
                val target = DeviceUiSelector(description = if (page == 99) "nav_return" else "nav_next_$next")
                return UiTargetActionController(gateway).tap(DeviceUiTargetRequest(before.screenId, target, 5000), before).also {
                    assertTrue(it.asToolContent(), it.success); assertNotNull(it.pageTransition)
                    if (primary != null) assertEquals(primary, primaryWindow())
                }
            }
            if (!background) tapPage(99, 0)
            tapPage(0, 1)
            val source = requireNotNull(gateway.observeScreen(scope(1)).screen)
            val witness = requireNotNull(gateway.observeScreen(scope(1)).screen)
            assertTrue(DevicePageTransitionPolicy.sourceMatches(source, witness))
            val index = witness.elements.single { it.contentDescription == "nav_next_2" }.index
            // This diagnostic owns the one dispatch. The verifier receives only read callbacks.
            val accepted = requireNotNull(FlowChatAccessibilityService.current()).tapUiElement(index, false, witness,
                targetAnchor = DeviceUiSelector(description = "nav_next_2")) { tasks.checkCanAct(task) }
            assertTrue(accepted)
            var departureReads = 0
            val departure = withTimeout(3_000) {
                var observed = gateway.observeScreen(scope(1))
                departureReads++
                while (observed.screen?.observationErrorCode != "observation_root_not_found") {
                    File(directory, "$caseId-departure-$departureReads.txt").writeText(observed.asToolContent())
                    check(observed.screen?.sensitive != true)
                    delay(40)
                    observed = gateway.observeScreen(scope(1)); departureReads++
                }
                observed
            }
            assertEquals("observation_root_not_found", departure.screen?.observationErrorCode)
            var destinationReads = 0
            var oldComparisonRejected = false
            val display = requireNotNull(source.geometry).displayId
            val result = PageTransitionObservationVerifier.complete(source, witness, departure, accepted,
                readDeparture = { gateway.observeScreen(scope(1)) },
                readDestination = {
                    destinationReads++
                    if (persistent || destinationReads == 1) {
                        val response = shell("am broadcast -a $fixturePackage.NAVIGATION_EVIDENCE -p $fixturePackage --ei display_id $display")
                        check(response.contains("Broadcast completed")) { response }
                    }
                    gateway.observeScreen().also { current ->
                        if (destinationReads == 1) {
                            oldComparisonRejected = DevicePageTransitionPolicy.complete(source, witness, departure.screen, current) == null
                            assertEquals("content_changed_between_reads", DevicePageTransitionPolicy.rejectionReason(source, witness, departure.screen, current))
                        }
                        if (primary != null) assertEquals(primary, primaryWindow())
                    }
                }, check = { tasks.checkCanAct(task) })
            File(directory, "$caseId-proof.txt").writeText(result.asToolContent())
            assertTrue("The old single-pair check must actually reject the injected observation", oldComparisonRejected)
            assertEquals(if (persistent) DeviceActionOutcome.Unverified else DeviceActionOutcome.ProgressObserved, result.actionOutcome)
            if (persistent) {
                assertFalse(result.success); assertNull(result.pageTransition)
            } else {
                assertTrue(result.asToolContent(), result.success); assertNotNull(result.pageTransition)
                assertTrue(result.summary.contains("navigation_observation_groups=2"))
                for (page in 2..7) tapPage(page, page + 1)
            }
            val current = requireNotNull(gateway.observeScreen().screen)
            val clicks = (if (background) 0 else 1) + (if (persistent) 2 else 8)
            val finalPage = if (persistent) 2 else 8
            assertEquals("page=$finalPage;clicks=$clicks;wrong=0", current.elements.single { it.contentDescription == "nav_ledger" }.text)
            assertEquals(display, current.geometry?.displayId)
            if (!persistent) assertEquals("Result: ORCHID-42", current.elements.single { it.contentDescription == "nav_result" }.text)
            if (primary != null) assertEquals(primary, primaryWindow())
            val metrics = JSONObject().put("case", caseId).put("mode", "physical-controls-with-controlled-read-transition")
                .put("completed_goal", !persistent).put("old_comparison_rejected", oldComparisonRejected)
                .put("single_injected_tap_dispatches", 1).put("total_actual_clicks", clicks).put("wrong_clicks", 0)
                .put("extra_confirmations", 0).put("manual_steps", 0).put("destination_reads", destinationReads)
                .put("initial_departure_reads", departureReads)
                .put("unknown_protection_expected", persistent).put("primary_window_unchanged", primary != null)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(directory, "$caseId.json").writeText(metrics.toString(2))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nNAVIGATION_RECOVERY $metrics\n") })
        } finally {
            tasks.finish(task)
            if (background) withTimeout(15_000) { while (root.serviceInfo()?.activeDisplays != 0) delay(100) }
            root.release()
            shell("am force-stop $fixturePackage")
        }
    }
}
