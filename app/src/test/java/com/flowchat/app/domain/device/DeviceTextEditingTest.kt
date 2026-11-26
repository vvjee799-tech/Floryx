package com.flowchat.app.domain.device

import org.junit.Assert.*
import org.junit.Test

class DeviceTextEditingTest {
    private val field = DeviceUiElement(0, "EditText", "甲😀乙", "draft", "id/draft", DeviceBounds(0, 0, 200, 80),
        true, true, false, true, focused = true, supportedActions = listOf("set_text", "set_selection"),
        textSelectionStart = 3, textSelectionEnd = 3)
    private fun request(text: String, mode: DeviceTextEditMode) = DeviceTextEditRequest(0, text, mode)
    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: DeviceTextEditRejected) { assertEquals(code, error.code) }
    }

    @Test fun replacingAppendingAndInsertionHaveDistinctValuesAndCursorResults() {
        assertEquals(DeviceTextEditPlan("新"), DeviceTextEditing.plan(field, request("新", DeviceTextEditMode.Replace)))
        assertEquals(DeviceTextEditPlan("甲😀乙新"), DeviceTextEditing.plan(field, request("新", DeviceTextEditMode.Append)))
        assertEquals(DeviceTextEditPlan("甲😀新乙", 4), DeviceTextEditing.plan(field, request("新", DeviceTextEditMode.InsertAtCursor)))
        assertEquals(DeviceTextEditPlan("甲\n乙", 2), DeviceTextEditing.plan(field.copy(textSelectionStart = 1), request("\n", DeviceTextEditMode.ReplaceSelection)))
    }

    @Test fun selectionBoundariesCannotSplitEmojiOrUseUnknownReversedOrOutOfRangeOffsets() {
        for ((start, end) in listOf(2 to 2, 0 to 2, -1 to 1, 3 to 1, 0 to 20)) {
            rejected("ui_selection_invalid") { DeviceTextEditing.validateSelection(field, start, end) }
        }
        DeviceTextEditing.validateSelection(field, 1, 3)
        rejected("ui_selection_unknown") { DeviceTextEditing.plan(field.copy(textSelectionStart = null), request("x", DeviceTextEditMode.InsertAtCursor)) }
        rejected("ui_cursor_not_collapsed") { DeviceTextEditing.plan(field.copy(textSelectionStart = 1), request("x", DeviceTextEditMode.InsertAtCursor)) }
        rejected("ui_selection_empty") { DeviceTextEditing.plan(field, request("x", DeviceTextEditMode.ReplaceSelection)) }
    }

    @Test fun limitsApplyToTheResultAndUtf16UnitsForEveryMode() {
        assertEquals(4000, DeviceTextEditing.plan(field, request("😀".repeat(2000), DeviceTextEditMode.Replace)).value.length)
        rejected("ui_text_too_long") { DeviceTextEditing.plan(field, request("😀".repeat(2001), DeviceTextEditMode.Replace)) }
        rejected("ui_text_too_long") { DeviceTextEditing.plan(field.copy(text = "a".repeat(4000)), request("b", DeviceTextEditMode.Append)) }
        rejected("ui_text_too_long") { DeviceTextEditing.plan(field.copy(maxTextLength = 4), request("abcde", DeviceTextEditMode.Replace)) }
        val large = field.copy(text = "a".repeat(4000), textSelectionStart = 3999, textSelectionEnd = 4000)
        assertEquals(4000, DeviceTextEditing.plan(large, request("中", DeviceTextEditMode.ReplaceSelection)).value.length)
    }

    @Test fun cursorEditingRequiresFocusedEditableAndAdvertisedActions() {
        rejected("ui_input_focus_required") { DeviceTextEditing.plan(field.copy(focused = false), request("x", DeviceTextEditMode.InsertAtCursor)) }
        rejected("ui_input_unsupported") { DeviceTextEditing.plan(field.copy(supportedActions = emptyList()), request("x", DeviceTextEditMode.Replace)) }
        rejected("ui_selection_unsupported") { DeviceTextEditing.plan(field.copy(supportedActions = listOf("set_text")), request("x", DeviceTextEditMode.InsertAtCursor)) }
        rejected("ui_input_target_invalid") { DeviceTextEditing.plan(field.copy(password = true), request("x", DeviceTextEditMode.Replace)) }
    }

    @Test fun malformedOrConflictingModesCannotTurnIntoAReplacement() {
        fun parse(extra: String) = DeviceTextEditRequest.fromToolArguments("""{"screen_id":"s","index":0,"text":"x",$extra}""")
        assertEquals(DeviceTextEditMode.Append, parse("\"clear_existing\":false").mode)
        assertEquals(DeviceTextEditMode.ReplaceSelection, parse("\"mode\":\"replace_selection\"").mode)
        for (extra in listOf("\"mode\":\"other\"", "\"clear_existing\":\"false\"", "\"clear_existing\":true,\"mode\":\"append\"", "\"mode\":null", "\"extra\":1")) {
            assertTrue(extra, runCatching { parse(extra) }.isFailure)
        }
    }

    @Test fun snapshotInvalidatesAnOldDraftWhenOnlyTheCursorMoves() {
        val screen = DeviceScreenSnapshot("fixture", "fixture", listOf(field), screenId = "s")
        val guard = DeviceSnapshotGuard().apply { remember(screen) }
        assertFalse(guard.matches("s", screen.copy(elements = listOf(field.copy(textSelectionStart = 1, textSelectionEnd = 1))), 0))
        assertFalse(guard.matches("s", screen.copy(elements = listOf(field.copy(focused = false))), 0))
        assertFalse(guard.matches("s", screen.copy(elements = listOf(field.copy(text = "用户新草稿"))), 0))
        val output = field.copy(text = "x".repeat(100)).asModelLine()
        assertTrue(output.contains("text_length_utf16=100")); assertTrue(output.contains("text_preview_truncated=true"))
    }

    @Test fun insertedTextWithoutTheRequestedCursorIsUnverified() {
        val before = DeviceScreenSnapshot("fixture", "fixture", listOf(field), windowId = 1)
        val after = before.copy(elements = listOf(field.copy(text = "甲😀新乙", textSelectionStart = 5, textSelectionEnd = 5)))
        fun complete(screen: DeviceScreenSnapshot) = DeviceActionOutcomePolicy.complete(before, DeviceToolResult(true, "observed", screen = screen),
            "dispatched", DeviceActionOutcomePolicy.TextInput(0, "新", false, DeviceTextEditMode.InsertAtCursor))
        assertEquals("ui_selection_unverified", complete(after).errorCode)
        val result = complete(after.copy(elements = listOf(after.elements.single().copy(textSelectionStart = 4, textSelectionEnd = 4))))
        assertEquals(DeviceActionOutcome.TargetVerified, result.actionOutcome)
        assertEquals("ui_selection_unverified", complete(result.screen!!.copy(elements = listOf(result.screen!!.elements.single().copy(focused = false)))).errorCode)
    }

    @Test fun selectionMustPreserveTheOriginalDraftAndFieldIdentity() {
        val before = DeviceScreenSnapshot("fixture", "fixture", listOf(field), windowId = 1)
        fun complete(after: DeviceScreenSnapshot) = DeviceActionOutcomePolicy.complete(before, DeviceToolResult(true, "observed", screen = after),
            "dispatched", selection = DeviceTextSelection(0, 1, 3))
        val correct = before.copy(elements = listOf(field.copy(textSelectionStart = 1)))
        assertEquals(DeviceActionOutcome.TargetVerified, complete(correct).actionOutcome)
        assertEquals("ui_selection_unverified", complete(correct.copy(elements = listOf(correct.elements.single().copy(text = "changed")))).errorCode)
        assertFalse(complete(correct.copy(windowId = 2)).success)
    }

    @Test fun scrollingCannotBorrowProgressFromAnotherContainerOrOpaquePixels() {
        val root = field.copy(editable = false, scrollable = true, text = null, index = 0, resourceId = "list")
        val child = field.copy(editable = false, index = 1, parentIndex = 0, text = "A")
        val outside = field.copy(editable = false, index = 2, text = "outside")
        val before = DeviceScreenSnapshot("fixture", "fixture", listOf(root, child, outside))
        fun complete(elements: List<DeviceUiElement>) = DeviceActionOutcomePolicy.complete(before,
            DeviceToolResult(true, "observed", screen = before.copy(elements = elements)), "dispatched", pixelsChanged = true, scrollIndex = 0)
        assertEquals("ui_scroll_unverified", complete(listOf(root, child, outside.copy(text = "outside changed"))).errorCode)
        assertEquals(DeviceActionOutcome.ProgressObserved, complete(listOf(root, child.copy(text = "B"), outside)).actionOutcome)
        assertEquals(DeviceActionOutcome.ProgressObserved, complete(listOf(root, child.copy(bounds = DeviceBounds(0, 10, 100, 40)), outside)).actionOutcome)
        val partial = before.copy(elements = listOf(root), truncated = true)
        assertEquals("ui_scroll_unverified", DeviceActionOutcomePolicy.complete(before,
            DeviceToolResult(true, "observed", screen = partial), "dispatched", scrollIndex = 0).errorCode)
    }

    @Test fun customDrawnOpaqueInputsExposeUnreadableSignalAndRejectSetTextBeforeDispatch() {
        val opaque = DeviceUiElement(
            index = 6,
            className = "android.widget.EditText",
            text = "",
            contentDescription = null,
            resourceId = "com.example.calc:id/formula",
            bounds = DeviceBounds(84, 476, 1176, 606),
            clickable = true,
            editable = true,
            scrollable = false,
            enabled = true,
            focused = true,
            supportedActions = listOf("click", "set_text", "set_selection"),
            textSelectionStart = 4,
            textSelectionEnd = 4
        )
        assertTrue(DeviceTextEditing.hasUnreadableText(opaque))
        assertFalse(DeviceTextEditing.hasUnreadableText(field))
        assertFalse(DeviceTextEditing.hasUnreadableText(opaque.copy(textSelectionStart = 0, textSelectionEnd = 0)))
        val line = opaque.asModelLine()
        assertTrue(line.contains("text_length_utf16=0"))
        assertTrue(line.contains("selection_utf16=4,4"))
        assertTrue(line.contains("text_readable=false"))
        rejected("ui_input_text_unreadable") {
            DeviceTextEditing.plan(opaque, request("25+38", DeviceTextEditMode.Replace))
        }
    }
}
