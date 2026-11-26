package com.flowchat.app.device

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.domain.device.DeviceActionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActionOutcomeDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun report(stage: String) = instrumentation.sendStatus(1, Bundle().apply { putString("ACTION_OUTCOME_STAGE", stage) })

    @Test fun waitsForDelayedResultsAndDoesNotConfuseAcknowledgementWithOutcome() = runBlocking<Unit> {
        val readiness = ActionOutcomeServiceReadiness()
        readiness.ensureService()
        val expectedService = ComponentName(context, FlowChatAccessibilityService::class.java)
        assertTrue("The public AccessibilityManager API must report the already-enabled Floryx service",
            readiness.publicServiceEnabled(expectedService) == true)
        report("public-enabled-service-query-verified")
        ActionOutcomeFixtureSession().use { fixture ->
            try {
                fixture.launch()
                val delayed = fixture.action("outcome_fixture_delayed")
                assertTrue(delayed.asToolContent(), delayed.actionOutcome in
                    setOf(DeviceActionOutcome.ProgressObserved, DeviceActionOutcome.Unverified))
                assertTrue(delayed.asToolContent(), delayed.errorCode == null || delayed.errorCode == "ui_change_unverified")
                // Acknowledgement, focus or a generic UI change cannot establish the business result.
                // Even an unverified dispatch gets a read-only exact-condition check, never another tap.
                val completed = fixture.delayedResult()
                assertTrue(completed.asToolContent(), completed.success && completed.conditionMatched == true)
                assertEquals(DeviceActionOutcome.TargetVerified, completed.actionOutcome)
                assertEquals(ActionOutcomeFixtureSession.State(1, 1, 0, 1), fixture.state())
                report("delayed-action-verified-once")

                val opaque = fixture.action("outcome_fixture_opaque")
                assertFalse(opaque.asToolContent(), opaque.success)
                assertEquals("ui_change_unverified", opaque.errorCode)
                assertEquals(DeviceActionOutcome.Unverified, opaque.actionOutcome)
                assertTrue(opaque.summary.contains("may have taken effect"))
                assertEquals("The real side effect must occur exactly once", 1, fixture.state().opaque)
                report("opaque-side-effect-unverified-once")

                val truncated = fixture.action("outcome_fixture_input") { index, id ->
                    fixture.gateway.inputText(index, "abcdef", true, id)
                }
                assertFalse(truncated.asToolContent(), truncated.success)
                assertEquals("ui_input_unverified", truncated.errorCode)
                assertTrue(truncated.screen!!.elements.any { it.editable && it.text == "abcd" })
                report("truncated-input-detected")

                val idempotent = fixture.action("outcome_fixture_input") { index, id ->
                    fixture.gateway.inputText(index, "abcd", true, id)
                }
                assertTrue(idempotent.asToolContent(), idempotent.success)
                assertEquals(ActionOutcomeFixtureSession.State(1, 1, 1, 1), fixture.state())
                report("idempotent-input-verified")
            } catch (error: Throwable) { fixture.failureEvidence(); throw error }
        }
    }
}
