package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DeviceActionOutcomePolicyTest {
    private val input = DeviceUiElement(1, "android.widget.EditText", "old", null, "fixture:id/input",
        DeviceBounds(0, 0, 200, 80), clickable = true, editable = true, scrollable = false, enabled = true)
    private val before = DeviceScreenSnapshot("fixture", "Fixture", listOf(input), windowId = 7)
    private fun observed(screen: DeviceScreenSnapshot?) = DeviceToolResult(screen != null, "Observed", screen = screen)
    private fun complete(after: DeviceScreenSnapshot?, text: DeviceActionOutcomePolicy.TextInput? = null, pixelsChanged: Boolean = false) =
        DeviceActionOutcomePolicy.complete(before, observed(after), "Action dispatched.", text, pixelsChanged)

    @Test fun anAcceptedDispatchWithoutAReadableResultIsUnverified() {
        val result = complete(null)
        assertFalse(result.success)
        assertEquals("ui_result_unverified", result.errorCode)
        assertTrue(result.summary.contains("may have taken effect"))
        assertTrue(result.summary.contains("before retrying"))
    }

    @Test fun anUnchangedUiDoesNotProveThatAnOpaqueActionFailedOrSucceeded() {
        val after = before.copy(screenId = "fresh", capturedAt = before.capturedAt + 100)
        val result = complete(after)
        assertFalse(result.success)
        assertEquals("ui_change_unverified", result.errorCode)
        assertSame(after, result.screen)
        assertTrue(result.summary.contains("may have taken effect"))
        assertTrue(result.summary.contains("do not repeat blindly"))
    }

    @Test fun pixelOnlyChangeCanValidateProgressWithoutClaimingTheUsersGoal() {
        val result = complete(before, pixelsChanged = true)
        assertTrue(result.success)
        assertTrue(result.summary.contains("verify the intended result"))
    }

    @Test fun movingAnUnrelatedOverlayInvalidatesTargetingButCannotVerifyAnOpaqueAction() {
        val app = DeviceWindowRegion(7, DeviceWindowKind.Application, 0, DeviceBounds(0, 0, 400, 800), listOf(DeviceBounds(0, 0, 400, 800)), true, true)
        val overlay = DeviceWindowRegion(8, DeviceWindowKind.Overlay, 1, DeviceBounds(300, 600, 400, 700), listOf(DeviceBounds(300, 600, 400, 700)), false, false)
        val original = before.copy(interactionContext = DeviceInteractionContext(listOf(app, overlay)))
        val after = original.copy(interactionContext = DeviceInteractionContext(listOf(app, overlay.copy(bounds = DeviceBounds(300, 500, 400, 600),
            regions = listOf(DeviceBounds(300, 500, 400, 600))))))
        assertFalse(original.hasSameUi(after))
        val result = DeviceActionOutcomePolicy.complete(original, observed(after), "Action dispatched.")
        assertFalse(result.success)
        assertEquals("ui_change_unverified", result.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome)
    }

    @Test fun aChangedControlTreeReturnsFreshEvidence() {
        val after = before.copy(elements = listOf(input.copy(text = "new")))
        val result = complete(after)
        assertTrue(result.success)
        assertSame(after, result.screen)
    }

    @Test fun scopeChangesRequireIndependentEvidenceRatherThanClaimingProgress() {
        val scoped = before.copy(elementQuery = DeviceUiSelector(resourceId = input.resourceId))
        val wider = before.copy(elements = listOf(input, input.copy(index = 2, resourceId = "other", text = "Unrelated control")))
        val result = DeviceActionOutcomePolicy.complete(scoped, observed(wider), "Action dispatched.")
        assertFalse(result.success)
        assertEquals("ui_result_unverified", result.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, result.actionOutcome)
        assertTrue(DeviceActionOutcomePolicy.complete(scoped, observed(wider), "Action dispatched.", pixelsChanged = true).success)
        val typed = DeviceActionOutcomePolicy.complete(scoped, observed(before.copy(elements = listOf(input.copy(text = "new")))) ,
            "Input dispatched.", DeviceActionOutcomePolicy.TextInput(input.index, "new", true))
        assertTrue(typed.success)
        assertEquals(DeviceActionOutcome.TargetVerified, typed.actionOutcome)
    }

    @Test fun failedObservationCannotBeRescuedByAPixelOrTreeChange() {
        val result = DeviceActionOutcomePolicy.complete(before,
            DeviceToolResult(false, "Capture blocked", errorCode = "secure_window_blocked", screen = before),
            "Action dispatched.", pixelsChanged = true)
        assertFalse(result.success)
        assertEquals("secure_window_blocked", result.errorCode)
    }

    @Test fun sensitiveScreensCannotValidateAnActionEvenWithPixels() {
        val result = complete(before.copy(sensitive = true), pixelsChanged = true)
        assertFalse(result.success)
        assertEquals("sensitive_screen_blocked", result.errorCode)
    }

    @Test fun inputMustMatchTheRequestedValueEvenWhenFocusOrOtherUiChanged() {
        val result = complete(before.copy(elements = listOf(input.copy(text = "truncated", focused = true))),
            DeviceActionOutcomePolicy.TextInput(input.index, "requested complete text", true))
        assertFalse(result.success)
        assertEquals("ui_input_unverified", result.errorCode)
    }

    @Test fun exactInputConfirmsAnIdempotentWriteWithoutRequiringChange() {
        val result = complete(before, DeviceActionOutcomePolicy.TextInput(input.index, "old", true))
        assertTrue(result.success)
        assertTrue(result.summary.contains("requested text is present"))
    }

    @Test fun appendIsVerifiedAgainstTheOriginalValue() {
        val request = DeviceActionOutcomePolicy.TextInput(input.index, "中文", false)
        assertTrue(complete(before.copy(elements = listOf(input.copy(text = "old中文"))), request).success)
        assertFalse(complete(before.copy(elements = listOf(input.copy(text = "中文"))), request).success)
    }

    @Test fun aUniqueResourceIdCanFollowAnInputWhoseIndexAndBoundsChanged() {
        val after = before.copy(elements = listOf(input.copy(index = 9, text = "new", bounds = DeviceBounds(10, 20, 210, 100))))
        assertTrue(complete(after, DeviceActionOutcomePolicy.TextInput(input.index, "new", true)).success)
    }

    @Test fun matchingTextInAnotherWindowDoesNotConfirmTheOriginalInput() {
        val after = before.copy(windowId = 8, elements = listOf(input.copy(text = "new")))
        assertFalse(complete(after, DeviceActionOutcomePolicy.TextInput(input.index, "new", true)).success)
    }

    @Test fun duplicateResourceIdsCannotSilentlyRetargetTheInput() {
        val original = before.copy(elements = listOf(input, input.copy(index = 2, bounds = DeviceBounds(0, 100, 200, 180))))
        val after = original.copy(elements = listOf(original.elements[1].copy(text = "new")))
        val result = DeviceActionOutcomePolicy.complete(original, observed(after), "Input dispatched.",
            DeviceActionOutcomePolicy.TextInput(input.index, "new", true))
        assertFalse(result.success)
    }

    @Test fun volatileOnlyUiChangeAfterOpaqueEditorActionRemainsUnverified() {
        val ticker = DeviceUiElement(2, "android.widget.TextView", "实时动态：话题 A", null, "fixture:id/ticker",
            DeviceBounds(0, 100, 400, 160), clickable = false, editable = false, scrollable = false, enabled = true, nodePath = "0/1")
        val beforeScreen = DeviceScreenSnapshot("fixture", "Fixture", listOf(input, ticker), windowId = 7)
        val afterScreen = beforeScreen.copy(screenId = "after", elements = listOf(input, ticker.copy(text = "实时动态：话题 B")))
        val unverified = DeviceActionOutcomePolicy.complete(
            beforeScreen, observed(afterScreen), "Editor action dispatched.", ignoreUiChange = true
        )
        assertFalse(unverified.success)
        assertEquals("ui_change_unverified", unverified.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, unverified.actionOutcome)
    }

    @Test fun customDrawnEmptyInputWithMatchedCursorAdvancementIsRecordedAsProgressWithoutLocking() {
        val emptyCustomInput = input.copy(
            text = "",
            focused = true,
            supportedActions = listOf("set_text", "set_selection"),
            textSelectionStart = 0,
            textSelectionEnd = 0
        )
        val customBefore = DeviceScreenSnapshot("fixture.calc", "Calculator", listOf(emptyCustomInput), windowId = 11)
        val afterCursorAdvanced = customBefore.copy(
            screenId = "after",
            elements = listOf(emptyCustomInput.copy(text = "", textSelectionStart = 5, textSelectionEnd = 5))
        )
        val progressed = DeviceActionOutcomePolicy.complete(
            customBefore,
            DeviceToolResult(true, "Observed", screen = afterCursorAdvanced),
            "Text editing was dispatched to UI element [1].",
            DeviceActionOutcomePolicy.TextInput(1, "25+38", true)
        )
        assertTrue(progressed.success)
        assertNull(progressed.errorCode)
        assertEquals(DeviceActionOutcome.ProgressObserved, progressed.actionOutcome)
        assertTrue(progressed.summary.contains("NOT verified"))

        // If cursor did not advance to match the written length, keep ui_input_unverified.
        val unchanged = DeviceActionOutcomePolicy.complete(
            customBefore,
            DeviceToolResult(true, "Observed", screen = customBefore.copy(screenId = "unchanged")),
            "Text editing was dispatched to UI element [1].",
            DeviceActionOutcomePolicy.TextInput(1, "25+38", true)
        )
        assertFalse(unchanged.success)
        assertEquals("ui_input_unverified", unchanged.errorCode)
        assertEquals(DeviceActionOutcome.Unverified, unchanged.actionOutcome)
    }
}
