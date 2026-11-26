package com.flowchat.app.domain.device

import kotlinx.serialization.json.*

/** A unique, visible root in the current window; never an index from an earlier read. */
data class DeviceObservationScope(val root: DeviceUiSelector, val kind: Kind = Kind.Subtree) {
    enum class Kind { Subtree, ScrollContainer }

    companion object {
        fun fromToolArguments(arguments: String): DeviceObservationScope? =
            Json.parseToJsonElement(arguments).jsonObject["observation_scope"]?.let { value ->
                val fields = value.jsonObject
                require(fields.keys.all { it in setOf("root", "kind") })
                val kind = when (fields["kind"]?.let { require(it is JsonPrimitive && it.isString); it.content } ?: "subtree") {
                    "subtree" -> Kind.Subtree
                    "scroll_container" -> Kind.ScrollContainer
                    else -> error("Invalid observation scope kind")
                }
                DeviceObservationScope(DeviceUiSelector.fromJson(requireNotNull(fields["root"]).jsonObject), kind)
            }
    }
}
