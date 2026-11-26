package com.flowchat.app.device

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Controlled boundary cases; not a claim about the cause of an earlier OEM failure. */
class ActionOutcomeReliabilityDiagnosticTest {
    private fun exercise(block: suspend (ActionOutcomeFixtureSession) -> Unit) = runBlocking<Unit> {
        assumeTrue("Explicit phone diagnostics only", InstrumentationRegistry.getArguments()
            .getString("runActionOutcomeReliabilityDiagnostics") == "true")
        ActionOutcomeServiceReadiness().ensureService()
        ActionOutcomeFixtureSession().use { fixture ->
            try { block(fixture) }
            catch (error: Throwable) { fixture.failureEvidence(); throw error }
        }
    }

    @Test fun progressDoesNotReplaceTheDelayedBusinessCondition() = exercise { fixture ->
        fixture.launch(delayMillis = 2_000, showProgress = true)
        val dispatch = fixture.action("outcome_fixture_delayed")
        assertEquals(dispatch.asToolContent(), DeviceActionOutcome.ProgressObserved, dispatch.actionOutcome)
        assertEquals(0, fixture.state().completed)
        val result = fixture.delayedResult()
        assertTrue(result.asToolContent(), result.success && result.conditionMatched == true)
        assertEquals(DeviceActionOutcome.TargetVerified, result.actionOutcome)
        assertEquals(ActionOutcomeFixtureSession.State(1, 1, 0, 1), fixture.state())
    }

    @Test fun unverifiedDispatchIsResolvedByReadOnlyConditionWithoutReplay() = exercise { fixture ->
        fixture.launch(delayMillis = 2_000)
        val dispatch = fixture.action("outcome_fixture_delayed")
        assertFalse(dispatch.asToolContent(), dispatch.success)
        assertEquals("ui_change_unverified", dispatch.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, dispatch.actionOutcome)
        assertEquals(ActionOutcomeFixtureSession.State(1, 1, 0, 0), fixture.state())
        val result = fixture.delayedResult()
        assertTrue(result.asToolContent(), result.success && result.conditionMatched == true)
        assertEquals(ActionOutcomeFixtureSession.State(1, 1, 0, 1), fixture.state())
    }

    @Test fun acknowledgedRequestWithoutSideEffectRemainsUnverified() = exercise { fixture ->
        fixture.launch(acknowledgeOnly = true)
        val dispatch = fixture.action("outcome_fixture_delayed")
        assertFalse(dispatch.asToolContent(), dispatch.success)
        assertEquals("ui_change_unverified", dispatch.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, dispatch.actionOutcome)
        val result = fixture.delayedResult(timeoutMillis = 1_000)
        assertFalse(result.asToolContent(), result.success)
        assertEquals(false, result.conditionMatched)
        assertEquals("screen_condition_timeout", result.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome)
        assertEquals(ActionOutcomeFixtureSession.State(1, 0, 0, 0), fixture.state())
    }

    @Test fun occludedControlIsNotDispatchedAndOnlyFreshUncoveredTargetCanRun() = exercise { fixture ->
        fixture.launch()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = requireNotNull(FlowChatAccessibilityService.current())
        val manager = service.getSystemService(WindowManager::class.java)
        val cover = View(service).apply { setBackgroundColor(Color.DKGRAY) }
        instrumentation.runOnMainSync {
            manager.addView(cover, WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, 900,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT })
        }
        try {
            val covered = withTimeout(5_000) {
                var screen: DeviceScreenSnapshot
                while (true) {
                    screen = requireNotNull(fixture.gateway.observeScreen().screen)
                    val target = screen.elements.singleOrNull { it.contentDescription == "outcome_fixture_delayed" }
                    if (target != null && screen.interactionContext?.complete == true &&
                        !screen.interactionContext!!.allowsPoint(screen, target.bounds.centerX, target.bounds.centerY)) break
                    delay(100)
                }
                screen
            }
            val target = covered.elements.single { it.contentDescription == "outcome_fixture_delayed" }
            val result = fixture.gateway.tapUiElement(target.index, false, covered.screenId)
            assertFalse(result.asToolContent(), result.success)
            assertEquals("ui_target_obscured", result.errorCode)
            assertEquals(DeviceActionOutcome.NotDispatched, result.actionOutcome)
            assertEquals(ActionOutcomeFixtureSession.State(0, 0, 0, 0), fixture.state())
            fixture.report("controlled-occlusion outcome=${result.actionOutcome} state=${fixture.state()}\n${covered.asToolContent()}")
        } finally { instrumentation.runOnMainSync { manager.removeViewImmediate(cover) } }
        val dispatch = fixture.action("outcome_fixture_delayed")
        assertTrue(dispatch.asToolContent(), dispatch.actionOutcome in setOf(DeviceActionOutcome.ProgressObserved, DeviceActionOutcome.Unverified))
        val result = fixture.delayedResult()
        assertTrue(result.asToolContent(), result.success && result.conditionMatched == true)
        assertEquals(ActionOutcomeFixtureSession.State(1, 1, 0, 1), fixture.state())
    }
}
