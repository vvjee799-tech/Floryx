package com.flowchat.app.domain.device

import kotlinx.serialization.json.*

/** Coordinates are normalized to the exact attached image, including any image_region crop. */
data class VisualTapTarget(
    val screenId: String,
    val x: Int,
    val y: Int,
    val targetDescription: String,
    val longPress: Boolean = false
) {
    init {
        require(screenId.isNotBlank() && screenId.length <= 128) { "A current image screen_id is required." }
        require(x in 0..1000 && y in 0..1000) { "x and y must be integers from 0 to 1000 in the attached image." }
        require(targetDescription.isNotBlank() && targetDescription.length <= 120) { "Describe the visible target in 1 to 120 characters." }
    }

    companion object {
        fun fromToolArguments(arguments: String): VisualTapTarget {
            val value = Json.parseToJsonElement(arguments).jsonObject
            require(value.keys == setOf("screen_id", "x", "y", "target_description", "long_press")) {
                "Expected screen_id, x, y, target_description and long_press."
            }
            fun text(key: String): String {
                val primitive = value[key] as? JsonPrimitive
                require(primitive?.isString == true) { "$key must be a string." }
                return primitive.content
            }
            fun coordinate(key: String): Int {
                val primitive = value[key] as? JsonPrimitive
                require(primitive != null && !primitive.isString) { "$key must be an integer." }
                return requireNotNull(primitive.intOrNull) { "$key must be an integer." }
            }
            val press = value["long_press"] as? JsonPrimitive
            require(press != null && !press.isString && press.booleanOrNull != null) { "long_press must be a boolean." }
            return VisualTapTarget(text("screen_id"), coordinate("x"), coordinate("y"),
                text("target_description"), requireNotNull(press.booleanOrNull))
        }
    }
}

data class VisualTapPoint(val x: Int, val y: Int)

/** Large screenshots are for finding a region; the final point is chosen again from native detail pixels. */
val DeviceScreenImage.needsVisualRefinement: Boolean
    get() {
        val crop = region?.pixels(sourceWidth, sourceHeight) ?: DeviceBounds(0, 0, sourceWidth, sourceHeight)
        return maxOf(crop.width, crop.height) > 800
    }

fun DeviceScreenImage.visualRefinementRegion(target: VisualTapTarget): ScreenImageRegion {
    require(sourceWidth > 0 && sourceHeight > 0)
    val base = region ?: ScreenImageRegion(0, 0, 100, 100)
    val spanX = minOf(base.right - base.left, (64000 / sourceWidth).coerceAtLeast(1))
    val spanY = minOf(base.bottom - base.top, (64000 / sourceHeight).coerceAtLeast(1))
    val centerX = base.left + (base.right - base.left) * target.x / 1000.0
    val centerY = base.top + (base.bottom - base.top) * target.y / 1000.0
    val left = (centerX - spanX / 2.0).toInt().coerceIn(base.left, base.right - spanX)
    val top = (centerY - spanY / 2.0).toInt().coerceIn(base.top, base.bottom - spanY)
    return ScreenImageRegion(left, top, left + spanX, top + spanY)
}

/** Maps image-relative coordinates through its source crop and actual window offset. Never clamps outside a window. */
fun DeviceScreenImage.visualTapPoint(target: VisualTapTarget): VisualTapPoint? {
    val display = geometry ?: return null
    val bounds = captureBounds ?: return null
    if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0 ||
        bounds.width != sourceWidth || bounds.height != sourceHeight) return null
    val crop = region?.pixels(sourceWidth, sourceHeight) ?: DeviceBounds(0, 0, sourceWidth, sourceHeight)
    val x = bounds.left + crop.left + (target.x.toLong() * crop.width / 1000).toInt().coerceAtMost(crop.width - 1)
    val y = bounds.top + crop.top + (target.y.toLong() * crop.height / 1000).toInt().coerceAtMost(crop.height - 1)
    if (!display.windowBounds.contains(x, y) || x !in 0 until display.displayWidth || y !in 0 until display.displayHeight) return null
    return VisualTapPoint(x, y)
}

/** The last image is an in-memory, expiring capability. Text-only observations and actions invalidate it. */
class VisualFrameGuard {
    data class Frame(val screen: DeviceScreenSnapshot, val image: DeviceScreenImage, val rememberedAt: Long,
        val owner: DeviceTaskControl.Task?)
    private var frame: Frame? = null

    fun clear() { frame = null }

    fun remember(result: DeviceToolResult, elapsedRealtime: Long, owner: DeviceTaskControl.Task? = null) {
        val screen = result.screen
        val image = result.screenImage
        frame = if (result.success && screen != null && !screen.sensitive && image != null &&
            image.screenId == screen.screenId && image.geometry != null && image.geometry == screen.geometry &&
            image.captureBounds != null) Frame(screen, image, elapsedRealtime, owner) else null
    }

    fun current(screenId: String, screen: DeviceScreenSnapshot, elapsedRealtime: Long,
        owner: DeviceTaskControl.Task? = null): Frame? = frame?.takeIf {
        it.owner === owner && screenId == it.screen.screenId && !screen.sensitive &&
            it.screen.packageName == screen.packageName && it.screen.windowId != null &&
            it.screen.windowId == screen.windowId && it.screen.geometry == screen.geometry &&
            elapsedRealtime - it.rememberedAt in 0..MaxAgeMillis
    }

    companion object { const val MaxAgeMillis = 120_000L }
}
