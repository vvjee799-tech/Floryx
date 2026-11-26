package com.flowchat.app.domain.device

/** Dispatch acknowledgement is not evidence of an app-side result. Never replay here. */
object DeviceActionOutcomePolicy {
    data class TextInput(val index: Int, val text: String, val clearExisting: Boolean,
        val mode: DeviceTextEditMode = if (clearExisting) DeviceTextEditMode.Replace else DeviceTextEditMode.Append)

    fun complete(
        before: DeviceScreenSnapshot,
        observed: DeviceToolResult,
        actionSummary: String,
        textInput: TextInput? = null,
        pixelsChanged: Boolean = false,
        selection: DeviceTextSelection? = null,
        scrollIndex: Int? = null,
        rangeAdjustment: DeviceRangeAdjustment? = null,
        ignoreUiChange: Boolean = false
    ): DeviceToolResult {
        val after = observed.screen
        fun unverified(code: String, reason: String) = observed.copy(
            success = false,
            errorCode = code,
            summary = "$actionSummary The action may have taken effect, but $reason. Inspect the current state before retrying; do not repeat blindly.",
            before = before.packageName,
            after = after?.packageName,
            actionOutcome = DeviceActionOutcome.Unverified
        )
        if (after?.sensitive == true) return unverified("sensitive_screen_blocked", "further automation is blocked on the sensitive screen")
        if (!observed.success || after == null) return unverified(
            observed.errorCode ?: observed.imageErrorCode ?: "ui_result_unverified",
            "the resulting screen could not be verified"
        )
        if (textInput != null) {
            val target = before.elements.singleOrNull { it.index == textInput.index && it.editable && !it.password }
            val expected = target?.let { runCatching { DeviceTextEditing.expectedPlan(it,
                DeviceTextEditRequest(textInput.index, textInput.text, textInput.mode)) }.getOrNull() }
            val field = target?.let { findInput(it, before, after) }
            if (expected == null || field == null) return unverified(
                "ui_input_unverified", "the requested text was not confirmed in the original input field"
            )
            if (field.text.orEmpty() != expected.value) {
                val opaqueCursorMatched = expected.value.isNotEmpty() &&
                    field.text.orEmpty().isEmpty() &&
                    DeviceTextEditing.hasUnreadableText(field) &&
                    field.textSelectionStart == expected.value.length &&
                    field.textSelectionEnd == expected.value.length &&
                    (expected.cursor == null || field.textSelectionStart == expected.cursor)
                if (opaqueCursorMatched) {
                    return observed.copy(
                        success = true,
                        errorCode = null,
                        summary = "$actionSummary Cursor selection advanced to ${expected.value.length}, but accessibility reports empty text (custom-drawn/opaque input). Visible text is NOT verified; inspect visually with observe_screen and prefer on-screen buttons rather than repeating input_text.",
                        before = before.packageName,
                        after = after.packageName,
                        actionOutcome = DeviceActionOutcome.ProgressObserved
                    )
                }
                return unverified(
                    "ui_input_unverified", "the requested text was not confirmed in the original input field"
                )
            }
            if (expected.cursor != null && (!field.focused || field.textSelectionStart != expected.cursor || field.textSelectionEnd != expected.cursor))
                return unverified("ui_selection_unverified", "the text is present but the resulting cursor could not be verified")
            return observed.copy(success = true, errorCode = null,
                summary = "$actionSummary The requested text is present in the input field.",
                before = before.packageName, after = after.packageName, actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        if (selection != null) {
            val target = before.elements.singleOrNull { it.index == selection.index && it.editable && !it.password }
            val field = target?.let { findInput(it, before, after) }
            if (target == null || field == null || field.text.orEmpty() != target.text.orEmpty() || !field.focused ||
                field.textSelectionStart != selection.start || field.textSelectionEnd != selection.end)
                return unverified("ui_selection_unverified", "the requested selection and unchanged draft were not confirmed in the original input")
            return observed.copy(success = true, errorCode = null,
                summary = "$actionSummary The selection is verified; the draft is unchanged.",
                before = before.packageName, after = after.packageName, actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        if (rangeAdjustment != null) {
            val target = before.elements.singleOrNull { it.index == rangeAdjustment.index }
            val current = target?.let { findTarget(it, before, after) }
            val originalRange = target?.range
            val range = current?.range
            if (range == null || originalRange == null || !before.hasSameObservationScope(after) ||
                range.type != originalRange.type || range.min != originalRange.min || range.max != originalRange.max ||
                range.current != rangeAdjustment.value)
                return unverified("ui_range_unverified", "the requested value was not confirmed in the original range control")
            return observed.copy(success = true, errorCode = null, summary = "The requested range value is verified in the original control.",
                before = before.packageName, after = after.packageName, actionOutcome = DeviceActionOutcome.TargetVerified)
        }
        if (scrollIndex != null) {
            val target = before.elements.singleOrNull { it.index == scrollIndex && it.scrollable }
            val container = target?.let { findTarget(it, before, after) }
            if (target == null || container == null || !container.scrollable || !before.hasSameObservationScope(after))
                return unverified("ui_scroll_unverified", "the original scroll container could not be identified")
            if (before.truncated || after.truncated || before.geometry != after.geometry || before.interactionContext != after.interactionContext)
                return unverified("ui_scroll_unverified", "the container observation is partial or its interaction context changed; observe a narrower container")
            fun contents(screen: DeviceScreenSnapshot, root: DeviceUiElement): List<String> {
                val included = mutableSetOf(root.index)
                return screen.elements.filter { element ->
                    (element.parentIndex in included).also { if (it) included += element.index }
                }.map { "${it.className}|${it.resourceId}|${it.text}|${it.contentDescription}|${it.checked}|${it.selected}|${it.bounds}" }
            }
            if (contents(before, target) == contents(after, container))
                return unverified("ui_scroll_unverified", "no readable change inside the selected container was verified")
            return observed.copy(success = true, errorCode = null,
                summary = "$actionSummary Readable content changed inside the selected container. Verify the requested item before continuing.",
                before = before.packageName, after = after.packageName, actionOutcome = DeviceActionOutcome.ProgressObserved)
        }
        if (!before.hasSameObservationScope(after) && !pixelsChanged) return unverified(
            "ui_result_unverified", "the before/after observations cover different controls and no independent visual progress was verified"
        )
        if ((before.hasSameActionContent(after) || ignoreUiChange) && !pixelsChanged) return unverified(
            "ui_change_unverified", "the readable UI is unchanged and no visual change was verified"
        )
        return observed.copy(success = true, errorCode = null,
            summary = "$actionSummary The resulting screen changed. Inspect it to verify the intended result.",
            before = before.packageName, after = after.packageName, actionOutcome = DeviceActionOutcome.ProgressObserved)
    }

    private fun findInput(target: DeviceUiElement, before: DeviceScreenSnapshot, after: DeviceScreenSnapshot): DeviceUiElement? {
        return findTarget(target, before, after)?.takeIf { it.editable && !it.password }
    }

    private fun findTarget(target: DeviceUiElement, before: DeviceScreenSnapshot, after: DeviceScreenSnapshot): DeviceUiElement? {
        if (before.packageName != after.packageName || before.windowId != after.windowId ||
            before.geometry?.displayId != after.geometry?.displayId) return null
        val candidates = after.elements.filter { it.className == target.className && it.editable == target.editable && !it.password }
        target.resourceId?.let { id ->
            if (before.elements.count { it.className == target.className && it.resourceId == id } == 1)
                candidates.singleOrNull { it.resourceId == id }?.let { return it }
        }
        target.contentDescription?.takeIf { it.isNotBlank() }?.let { description ->
            if (before.elements.count { it.className == target.className && it.resourceId == target.resourceId && it.contentDescription == description } == 1)
                candidates.singleOrNull { it.resourceId == target.resourceId && it.contentDescription == description }?.let { return it }
        }
        return candidates.singleOrNull { it.index == target.index && it.resourceId == target.resourceId &&
            it.contentDescription == target.contentDescription && it.bounds == target.bounds && it.nodePath == target.nodePath }
    }
}
