package com.flowchat.app.domain.device

import kotlinx.serialization.json.*

enum class DeviceTextEditMode { Replace, Append, InsertAtCursor, ReplaceSelection }

data class DeviceTextEditRequest(val index: Int, val text: String, val mode: DeviceTextEditMode) {
    init { require(index >= 0) }

    companion object {
        /** Legacy clear_existing remains supported; conflicting or malformed choices never default. */
        fun fromToolArguments(arguments: String): DeviceTextEditRequest {
            val args = Json.parseToJsonElement(arguments).jsonObject
            require(args.keys.all { it in setOf("screen_id", "index", "text", "clear_existing", "mode") })
            args["screen_id"].let { require(it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 512) }
            val index = args["index"].let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.intOrNull) }
            val text = args["text"].let { require(it is JsonPrimitive && it.isString); it.content }
            val legacy = args["clear_existing"]?.let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.booleanOrNull) }
            val mode = args["mode"]?.let {
                require(it is JsonPrimitive && it.isString)
                when (it.content) {
                    "replace" -> DeviceTextEditMode.Replace
                    "append" -> DeviceTextEditMode.Append
                    "insert_at_cursor" -> DeviceTextEditMode.InsertAtCursor
                    "replace_selection" -> DeviceTextEditMode.ReplaceSelection
                    else -> error("Invalid text edit mode")
                }
            } ?: if (legacy == false) DeviceTextEditMode.Append else DeviceTextEditMode.Replace
            require(legacy == null || mode == if (legacy) DeviceTextEditMode.Replace else DeviceTextEditMode.Append)
            require(text.length <= DeviceTextEditing.MaxLength)
            return DeviceTextEditRequest(index, text, mode)
        }
    }
}

data class DeviceTextSelection(val index: Int, val start: Int, val end: Int)
data class DeviceTextEditPlan(val value: String, val cursor: Int? = null)
class DeviceTextEditRejected(val code: String) : IllegalArgumentException(code)

/** All lengths and selection offsets use Android/Kotlin UTF-16 units, including the resulting value. */
object DeviceTextEditing {
    const val MaxLength = 4_000

    fun hasUnreadableText(target: DeviceUiElement): Boolean {
        val length = target.text.orEmpty().length
        val start = target.textSelectionStart
        val end = target.textSelectionEnd
        return (start != null && start > length) || (end != null && end > length)
    }

    fun plan(target: DeviceUiElement, request: DeviceTextEditRequest): DeviceTextEditPlan {
        requireEditable(target)
        if ("set_text" !in target.supportedActions) reject("ui_input_unsupported")
        if (hasUnreadableText(target)) reject("ui_input_text_unreadable")
        if (request.mode in setOf(DeviceTextEditMode.InsertAtCursor, DeviceTextEditMode.ReplaceSelection) &&
            "set_selection" !in target.supportedActions) reject("ui_selection_unsupported")
        return expectedPlan(target, request)
    }

    /** Pure expected result, reused for verification independently of the action acknowledgement. */
    fun expectedPlan(target: DeviceUiElement, request: DeviceTextEditRequest): DeviceTextEditPlan {
        val old = target.text.orEmpty()
        if (request.text.length > MaxLength) reject("ui_text_too_long")
        val plan = when (request.mode) {
            DeviceTextEditMode.Replace -> DeviceTextEditPlan(request.text)
            DeviceTextEditMode.Append -> DeviceTextEditPlan(old + request.text)
            DeviceTextEditMode.InsertAtCursor, DeviceTextEditMode.ReplaceSelection -> {
                if (!target.focused) reject("ui_input_focus_required")
                val start = target.textSelectionStart ?: reject("ui_selection_unknown")
                val end = target.textSelectionEnd ?: reject("ui_selection_unknown")
                validateRange(old, start, end)
                if (request.mode == DeviceTextEditMode.InsertAtCursor && start != end) reject("ui_cursor_not_collapsed")
                if (request.mode == DeviceTextEditMode.ReplaceSelection && start == end) reject("ui_selection_empty")
                DeviceTextEditPlan(old.substring(0, start) + request.text + old.substring(end), start + request.text.length)
            }
        }
        if (plan.value.length > MaxLength || target.maxTextLength?.let { plan.value.length > it } == true) reject("ui_text_too_long")
        return plan
    }

    fun validateSelection(target: DeviceUiElement, start: Int, end: Int) {
        requireEditable(target)
        if (!target.focused) reject("ui_input_focus_required")
        if ("set_selection" !in target.supportedActions) reject("ui_selection_unsupported")
        validateRange(target.text.orEmpty(), start, end)
    }

    fun requireEditable(target: DeviceUiElement) {
        if (!target.editable || !target.enabled || target.password) reject("ui_input_target_invalid")
    }

    private fun validateRange(text: String, start: Int, end: Int) {
        if (start !in 0..text.length || end !in start..text.length || !text.isBoundary(start) || !text.isBoundary(end))
            reject("ui_selection_invalid")
    }

    private fun String.isBoundary(offset: Int): Boolean = offset == 0 || offset == length ||
        !(this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())
    private fun reject(code: String): Nothing = throw DeviceTextEditRejected(code)
}

enum class DeviceScrollDirection(val actionName: String) {
    Forward("scroll_forward"), Backward("scroll_backward"), Up("scroll_up"), Down("scroll_down"),
    Left("scroll_left"), Right("scroll_right")
}
