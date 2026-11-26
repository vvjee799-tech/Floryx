package com.flowchat.app.domain.device

import kotlinx.serialization.json.*

/** Percent coordinates relative to the full captured app window, never tap coordinates. */
data class ScreenImageRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left in 0..99 && top in 0..99 && right in 1..100 && bottom in 1..100 && right > left && bottom > top) {
            "image_region requires 0 <= left < right <= 100 and 0 <= top < bottom <= 100."
        }
    }

    fun pixels(width: Int, height: Int): DeviceBounds {
        require(width > 0 && height > 0)
        return DeviceBounds(width * left / 100, height * top / 100,
            (width * right + 99) / 100, (height * bottom + 99) / 100)
    }

    companion object {
        fun fromToolArguments(arguments: String): ScreenImageRegion? {
            val value = Json.parseToJsonElement(arguments).jsonObject["image_region"] ?: return null
            if (value == JsonNull) return null
            require(value is JsonObject) { "image_region must be an object." }
            val keys = setOf("left", "top", "right", "bottom")
            require(value.keys == keys) { "image_region needs left, top, right and bottom." }
            fun coordinate(key: String): Int {
                val number = value[key] as? JsonPrimitive
                require(number != null && !number.isString) { "image_region coordinates must be integers." }
                return requireNotNull(number.intOrNull) { "image_region coordinates must be integers." }
            }
            return ScreenImageRegion(coordinate("left"), coordinate("top"), coordinate("right"), coordinate("bottom"))
        }
    }
}
