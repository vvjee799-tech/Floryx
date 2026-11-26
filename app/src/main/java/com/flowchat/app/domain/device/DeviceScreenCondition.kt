package com.flowchat.app.domain.device

import kotlinx.serialization.json.*

/** Exact predicates, combined with AND. An index from an earlier screen is never a selector. */
data class DeviceUiSelector(val resourceId: String? = null, val text: String? = null, val description: String? = null) {
    init {
        require(listOf(resourceId, text, description).any { it != null })
        require(listOfNotNull(resourceId, text, description).all { it.isNotBlank() && it.length <= 512 })
    }
    fun matches(element: DeviceUiElement): Boolean = (resourceId == null || resourceId == element.resourceId) &&
        (text == null || text == element.text) && (description == null || description == element.contentDescription)

    companion object {
        fun fromJson(values: JsonObject): DeviceUiSelector {
            require(values.keys.all { it in setOf("resource_id", "text", "description") })
            fun string(key: String): String? = values[key]?.let { require(it is JsonPrimitive && it.isString); it.content }
            return DeviceUiSelector(string("resource_id"), string("text"), string("description"))
        }
    }
}

enum class DeviceElementState { Exists, Actionable, Absent, Checked, Unchecked, Selected, Unselected }

data class DeviceScreenCondition(
    val packageName: String? = null,
    val windowId: Int? = null,
    val element: DeviceUiSelector? = null,
    val state: DeviceElementState = DeviceElementState.Exists,
    val keyboardVisible: Boolean? = null,
    /** The exact, previously returned cropped image. Its region and window must stay identical. */
    val regionChangedFrom: String? = null,
    internal val allowTapTargetDisambiguation: Boolean = false
) {
    init {
        require(packageName != null || windowId != null || element != null || keyboardVisible != null || regionChangedFrom != null)
        require(regionChangedFrom == null || (regionChangedFrom.isNotBlank() && regionChangedFrom.length <= 512))
        require(element != null || state == DeviceElementState.Exists)
        require(element != null || !allowTapTargetDisambiguation)
        require(windowId == null || windowId >= 0)
    }

    fun matches(screen: DeviceScreenSnapshot): Boolean = matches(screen, regionChanged = false)

    fun matches(screen: DeviceScreenSnapshot, regionChanged: Boolean): Boolean {
        if (screen.sensitive || screen.observationErrorCode != null || (regionChangedFrom != null && !regionChanged) ||
            (packageName != null && screen.packageName != packageName) ||
            (windowId != null && screen.windowId != windowId) ||
            (keyboardVisible != null && screen.interactionContext?.takeIf { it.complete }?.keyboardVisible != keyboardVisible)) return false
        val selector = element ?: return true
        if (screen.elementQuery != null && screen.elementQuery != selector) return false
        val matches = screen.elements.filter(selector::matches)
        if (state == DeviceElementState.Absent) return matches.isEmpty() && screen.searchComplete &&
            (!screen.truncated || screen.elementQuery == selector)
        if (allowTapTargetDisambiguation && state == DeviceElementState.Exists) {
            return DeviceUiTargetPolicy.classifyQueryMatches(screen.elements, selector) == DeviceUiTargetPolicy.MatchStatus.Ready
        }
        // Ambiguous controls cannot verify a specific target.
        val target = matches.singleOrNull() ?: return false
        return when (state) {
            DeviceElementState.Exists -> true
            DeviceElementState.Actionable -> target.enabled && (target.clickable || target.editable || target.scrollable) &&
                screen.interactionContext?.allowsPoint(screen, target.bounds.centerX, target.bounds.centerY) == true
            DeviceElementState.Checked -> target.checked == true
            DeviceElementState.Unchecked -> target.checked == false
            DeviceElementState.Selected -> target.selected
            DeviceElementState.Unselected -> !target.selected
            DeviceElementState.Absent -> false
        }
    }
}

data class DeviceScreenWaitRequest(val condition: DeviceScreenCondition? = null, val timeoutMillis: Long = 5_000,
    val observationScope: DeviceObservationScope? = null, val imageRegion: ScreenImageRegion? = null) {
    init { require(timeoutMillis in 100..10_000) }
    companion object {
        fun fromToolArguments(arguments: String): DeviceScreenWaitRequest? {
            val args = Json.parseToJsonElement(arguments).jsonObject
            val rawCondition = args["condition"]
            val rawTimeout = args["timeout_ms"]
            if (rawCondition == null && rawTimeout == null && args["observation_scope"] == null) return null
            val timeout = rawTimeout?.let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.longOrNull) } ?: 5_000L
            val condition = rawCondition?.let {
                val fields = it.jsonObject
                require(fields.keys.all { key -> key in setOf("package_name", "window_id", "element", "state", "keyboard_visible", "region_changed") })
                fun JsonObject.string(key: String): String? = get(key)?.let { value ->
                    require(value is JsonPrimitive && value.isString)
                    value.content.also { text -> require(text.isNotBlank() && text.length <= 512) }
                }
                val selector = fields["element"]?.jsonObject?.let { values ->
                    DeviceUiSelector.fromJson(values)
                }
                val state = when (fields.string("state") ?: "exists") {
                    "exists" -> DeviceElementState.Exists; "actionable" -> DeviceElementState.Actionable
                    "absent" -> DeviceElementState.Absent; "checked" -> DeviceElementState.Checked
                    "unchecked" -> DeviceElementState.Unchecked; "selected" -> DeviceElementState.Selected
                    "unselected" -> DeviceElementState.Unselected; else -> error("Invalid element state")
                }
                DeviceScreenCondition(fields.string("package_name"), fields["window_id"]?.let { value ->
                    require(value is JsonPrimitive && !value.isString); requireNotNull(value.intOrNull)
                }, selector, state, fields["keyboard_visible"]?.let { value ->
                    require(value is JsonPrimitive && !value.isString); requireNotNull(value.booleanOrNull)
                }, fields["region_changed"]?.jsonObject?.let { region ->
                    require(region.keys == setOf("screen_id")); requireNotNull(region.string("screen_id"))
                })
            }
            return DeviceScreenWaitRequest(condition, timeout, DeviceObservationScope.fromToolArguments(arguments),
                ScreenImageRegion.fromToolArguments(arguments))
        }
    }
}

enum class DeviceActionOutcome { NotDispatched, Dispatched, ProgressObserved, TargetVerified, Unverified,
    /** A completed scroll was observed locally, but made no verified progress. Safe to observe/reroute, never success. */
    StateObserved }
