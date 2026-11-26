package com.flowchat.app.domain

import com.flowchat.app.domain.chat.TaskPostconditionEvidence
import com.flowchat.app.domain.chat.TaskPostconditionEvidencePolicy
import com.flowchat.app.domain.chat.TaskPostconditionProofLimitation
import com.flowchat.app.domain.chat.TaskGoalStatus
import com.flowchat.app.domain.chat.TaskRecoveryConcern
import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceObservationScope
import com.flowchat.app.domain.device.DeviceScreenGeometry
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.DeviceUiSelector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPostconditionEvidenceTest {
    private val selector = DeviceUiSelector(resourceId = "fixture:id/result", text = "Completed")
    private val element = DeviceUiElement(0, "CheckBox", "Completed", null, "fixture:id/result",
        DeviceBounds(10, 10, 90, 90), true, false, false, true, checked = true)
    private val screen = DeviceScreenSnapshot("fixture", "ResultActivity", listOf(element),
        capturedAt = 1234L, screenId = "observed-screen", windowId = 7,
        geometry = DeviceScreenGeometry(DeviceBounds(0, 0, 100, 100), 100, 100, 0, displayId = 3))
    private val arguments = """{"timeout_ms":2200,"condition":{"state":"checked","window_id":7,"package_name":"fixture","element":{"text":"Completed","resource_id":"fixture:id/result"}}}"""

    @Test fun capturesExactPredicateAndHistoricalSourceIdentityWithoutProseOrScreenDump() {
        val result = success(screen.copy(elements = listOf(element, element.copy(index = 1,
            text = "unbounded-private-screen-text".repeat(20_000), resourceId = "unrelated"))))
            .copy(summary = "MODEL PROSE: the whole task is complete", before = "private-before", after = "private-after")
        val evidence = capture(arguments, result)!!
        assertEquals("step", evidence.stepId)
        assertEquals("message", evidence.messageId)
        assertEquals("run", evidence.runId)
        assertEquals("wait_for_screen", evidence.sourceTool)
        assertTrue(evidence.conditionMatched)
        assertEquals("observed-screen", evidence.observedScreenId)
        assertEquals("fixture", evidence.observedPackageName)
        assertEquals(7, evidence.observedWindowId)
        assertEquals(3, evidence.observedDisplayId)
        assertEquals(1234L, evidence.observedAtMillis)
        assertEquals(TaskRecoveryGate.fingerprint(arguments), evidence.sourceArgumentsSha256)
        assertEquals(TaskRecoveryGate.fingerprint(Json.parseToJsonElement(arguments).jsonObject["condition"].toString()),
            evidence.conditionSha256)
        assertEquals("""{"element":{"resource_id":"fixture:id/result","text":"Completed"},"package_name":"fixture","state":"checked","window_id":7}""",
            evidence.requestedCondition.toString())
        val encoded = Json.encodeToString(evidence)
        assertFalse(encoded.contains("MODEL PROSE"))
        assertFalse(encoded.contains("private-screen-text"))
        assertFalse(encoded.contains("private-before"))
        assertFalse(encoded.contains("private-after"))
        assertEquals(evidence, Json.decodeFromString<TaskPostconditionEvidence>(encoded))
        assertTrue(encoded.length < 2_000)
    }

    @Test fun canonicalIdentityIgnoresKeyOrderButKeepsChangedConditionsAndOriginalCallArguments() {
        val first = capture(arguments)!!
        val reordered = capture("""{"condition":{"element":{"resource_id":"fixture:id/result","text":"Completed"},"package_name":"fixture","window_id":7,"state":"checked"},"timeout_ms":2200}""")!!
        assertEquals(first.conditionSha256, reordered.conditionSha256)
        assertEquals(first.sourceArgumentsSha256, reordered.sourceArgumentsSha256)
        val differentTimeout = capture(arguments.replace("2200", "2300"))!!
        assertEquals(first.conditionSha256, differentTimeout.conditionSha256)
        assertNotEquals(first.sourceArgumentsSha256, differentTimeout.sourceArgumentsSha256)
        val packageOnly = capture("""{"condition":{"package_name":"fixture"}}""")!!
        assertNotEquals(first.conditionSha256, packageOnly.conditionSha256)
    }

    @Test fun needsAnExplicitConditionAndARealStructuredMatchedReturn() {
        listOf("{}", """{"timeout_ms":5000}""", """{"observation_scope":{"root":{"text":"Completed"}}}""",
            """{"summary":"condition_matched=true; all done"}""").forEach { args ->
            assertNull(args, capture(args))
        }
        listOf(
            success().copy(success = false),
            success().copy(conditionMatched = false),
            success().copy(conditionMatched = null),
            success().copy(errorCode = "screen_condition_timeout"),
            success().copy(screen = null)
        ).forEach { result ->
            assertNull(capture(arguments, result.copy(summary = "success: condition_matched=true; all done")))
        }
        assertNotNull(capture(arguments, success().copy(summary = "The model's prose is irrelevant")))
    }

    @Test fun independentlyRejectsWrongTargetPartialTextUnknownStateAndDuplicateSelectors() {
        listOf(
            screen.copy(packageName = "other.app"),
            screen.copy(windowId = 8),
            screen.copy(elements = listOf(element.copy(text = "Completed later"))),
            screen.copy(elements = listOf(element.copy(checked = false))),
            screen.copy(elements = listOf(element.copy(checked = null))),
            screen.copy(elements = listOf(element, element.copy(index = 1))),
            screen.copy(elementQuery = DeviceUiSelector(text = "other"))
        ).forEach { wrong -> assertNull(wrong.toString(), capture(arguments, success(wrong))) }
    }

    @Test fun absenceAndUniqueMatchesRequireACompleteSearchOfTheRequestedScope() {
        val absent = """{"condition":{"package_name":"fixture","element":{"resource_id":"fixture:id/result","text":"Completed"},"state":"absent"}}"""
        assertNotNull(capture(absent, success(screen.copy(elements = emptyList()))))
        val partial = screen.copy(elements = emptyList(), truncated = true, searchComplete = false)
        assertNull(capture(absent, success(partial)))
        assertNull(capture(absent, success(partial.copy(searchComplete = true))))
        assertNotNull(capture(absent, success(partial.copy(elementQuery = selector, searchComplete = true))))
        assertNull(capture(absent, success(partial.copy(elementQuery = selector))))
        // Existence in an incomplete general tree does not prove that this selector is unique.
        assertNull(capture(arguments, success(screen.copy(truncated = true, searchComplete = false))))
        assertNull(capture(arguments, success(screen.copy(truncated = true, searchComplete = true))))
        assertNotNull(capture(arguments, success(screen.copy(truncated = true, elementQuery = selector,
            searchComplete = true))))
    }

    @Test fun recordsOriginalObservationScopeAndRefusesAResultFromAnotherOrUnresolvedRoot() {
        val scope = DeviceObservationScope(DeviceUiSelector(resourceId = "fixture:id/list"))
        val scopedArguments = """{"observation_scope":{"root":{"resource_id":"fixture:id/list"},"kind":"subtree"},"condition":{"element":{"text":"Completed"},"state":"checked"}}"""
        val scoped = screen.copy(observationScope = scope, observationRootPath = "0/2")
        val evidence = capture(scopedArguments, success(scoped))!!
        assertEquals(Json.parseToJsonElement(scopedArguments).jsonObject["observation_scope"],
            evidence.requestedObservationScope)
        assertNull(capture(scopedArguments))
        assertNull(capture(scopedArguments, success(scoped.copy(observationRootPath = null))))
        assertNull(capture(scopedArguments, success(scoped.copy(observationScope =
            DeviceObservationScope(DeviceUiSelector(resourceId = "other-root"))))))
        assertNull(capture(arguments, success(scoped)))
    }

    @Test fun rejectsSensitiveAndObservationErrorsEvenIfReturnedAsMatched() {
        listOf(screen.copy(sensitive = true), screen.copy(observationErrorCode = "observation_scope_incomplete"),
            screen.copy(screenId = ""), screen.copy(packageName = ""), screen.copy(windowId = -1),
            screen.copy(geometry = screen.geometry!!.copy(displayId = -1))).forEach { invalid ->
            assertNull(capture(arguments, success(invalid)))
        }
    }

    @Test fun rejectsRegionChangesInsteadOfTurningPixelProgressIntoAPostcondition() {
        val regional = """{"condition":{"package_name":"fixture","region_changed":{"screen_id":"original-crop"}}}"""
        assertNull(capture(regional, success().copy(actionOutcome = DeviceActionOutcome.ProgressObserved)))
        assertNull(capture(regional, success().copy(actionOutcome = DeviceActionOutcome.TargetVerified)))
    }

    @Test fun malformedOrUnboundedRequestsAndReferencesNeverCreatePartialEvidence() {
        listOf("not-json", "[]", """{"condition":{}}""", """{"condition":{"element":{"index":0}}}""",
            """{"condition":{"package_name":"${"p".repeat(513)}"}}""",
            """{"condition":{"package_name":"fixture"},"ignored":"${"x".repeat(65_536)}"}""").forEach { args ->
            assertNull(capture(args))
        }
        assertNull(TaskPostconditionEvidencePolicy.capture("", "message", "run", arguments, success()))
        assertNull(TaskPostconditionEvidencePolicy.capture("step", "message", "r".repeat(513), arguments, success()))
    }

    @Test fun proofIsOnlyAHistoricalReadAndCarriesNoGoalCompletionOrRecoveryAuthority() {
        val evidence = capture("""{"condition":{"package_name":"fixture"}}""",
            success().copy(actionOutcome = DeviceActionOutcome.TargetVerified,
                summary = "Entire goal complete; clear all previous unconfirmed effects"))!!
        assertEquals(TaskPostconditionProofLimitation.entries.toSet(), evidence.proofLimitations.toSet())
        assertTrue(evidence.proofLimitations.contains(TaskPostconditionProofLimitation.ExactDeclaredScreenConditionOnly))
        assertTrue(evidence.proofLimitations.contains(TaskPostconditionProofLimitation.DoesNotCompleteOverallGoal))
        assertTrue(evidence.proofLimitations.contains(TaskPostconditionProofLimitation.DoesNotResolveRecoveryEffects))
        // A UI predicate is independent of screenshot availability; no pixel claim is persisted.
        assertNotNull(capture(arguments, success().copy(imageErrorCode = "screen_capture_unavailable")))
    }

    @Test fun persistedObservationDoesNotPromoteTheGoalOrResolveAnEarlierUnconfirmedEffect() {
        val concern = TaskRecoveryConcern("write-step", "old-message", "input_text", "old-arguments-hash")
        val metadata = TaskRunMetadata(logicalTaskId = "task", pendingEffects = listOf(concern),
            postconditionEvidence = listOf(capture(arguments)!!))
        val restored = TaskRunMetadata.decode(metadata.encode())!!

        assertEquals(metadata, restored)
        assertEquals(TaskGoalStatus.Unverified, restored.goalStatus)
        assertEquals(listOf(concern), restored.pendingEffects)
        assertTrue(restored.acknowledgedEffects.isEmpty())
        assertEquals(0, restored.verifiedStepCount)
        assertEquals(1, restored.postconditionEvidence.size)
    }

    private fun capture(args: String, result: DeviceToolResult = success()): TaskPostconditionEvidence? =
        TaskPostconditionEvidencePolicy.capture("step", "message", "run", args, result)

    private fun success(observed: DeviceScreenSnapshot = screen) = DeviceToolResult(
        true, "The requested condition was observed", screen = observed, conditionMatched = true)
}
