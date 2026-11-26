package com.flowchat.app.domain.device

import kotlinx.serialization.json.*
import kotlin.math.abs

sealed interface DeviceAnchoredGesture {
    val index: Int
    data class Drag(override val index: Int, val targetIndex: Int, val holdMillis: Long = 600,
        val durationMillis: Long = 600) : DeviceAnchoredGesture {
        init { require(index >= 0 && targetIndex >= 0 && index != targetIndex); require(holdMillis in 450..1000); require(durationMillis in 200..1200) }
    }
    data class DoubleTap(override val index: Int) : DeviceAnchoredGesture { init { require(index >= 0) } }
    companion object {
        fun fromToolArguments(arguments: String, drag: Boolean): DeviceAnchoredGesture {
            val args = Json.parseToJsonElement(arguments).jsonObject
            require(args.keys.all { it in if (drag) setOf("screen_id", "index", "target_index", "hold_ms", "duration_ms") else setOf("screen_id", "index") })
            args["screen_id"].let { require(it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 512) }
            fun integer(key: String, default: Long? = null): Long = args[key]?.let {
                require(it is JsonPrimitive && !it.isString); requireNotNull(it.longOrNull)
            } ?: requireNotNull(default)
            fun index(key: String) = integer(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            return if (drag) Drag(index("index"), index("target_index"), integer("hold_ms", 600), integer("duration_ms", 600)) else DoubleTap(index("index"))
        }
    }
}

data class DeviceRangeAdjustment(val index: Int, val value: Float) {
    init { require(index >= 0 && value.isFinite()) }
    fun validate(target: DeviceUiElement): Boolean {
        val range = target.range ?: return false
        return target.enabled && !target.password && "set_progress" in target.supportedActions &&
            range.type in 0..2 && value in range.min..range.max && (range.type != 0 || value % 1f == 0f)
    }
    companion object {
        fun fromToolArguments(arguments: String): DeviceRangeAdjustment {
            val args = Json.parseToJsonElement(arguments).jsonObject
            require(args.keys == setOf("screen_id", "index", "value"))
            args["screen_id"].let { require(it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 512) }
            val index = args["index"].let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.intOrNull) }
            val value = args["value"].let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.doubleOrNull) }
            require(value.isFinite() && value.toFloat().isFinite())
            if (value % 1.0 == 0.0) require(value.toFloat().toDouble() == value)
            return DeviceRangeAdjustment(index, value.toFloat())
        }
    }
}

data class DeviceGesturePoint(val x: Float, val y: Float)
data class DeviceGestureLeg(val from: DeviceGesturePoint, val to: DeviceGesturePoint, val durationMillis: Long, val keepDown: Boolean)

object DeviceAnchoredGesturePolicy {
    /** Prove coverage by rectangle intervals; endpoint checks alone miss an overlay in the middle. */
    fun coversLine(regions: List<DeviceBounds>, from: DeviceGesturePoint, to: DeviceGesturePoint): Boolean {
        val intervals = regions.mapNotNull { bounds ->
            var low = 0.0; var high = 1.0
            for ((start, end, min, max) in listOf(doubleArrayOf(from.x.toDouble(), to.x.toDouble(), bounds.left.toDouble(), bounds.right.toDouble()),
                doubleArrayOf(from.y.toDouble(), to.y.toDouble(), bounds.top.toDouble(), bounds.bottom.toDouble()))) {
                val delta = end - start
                if (max < min || (abs(delta) < 1e-8 && start !in min..max)) return@mapNotNull null
                if (abs(delta) >= 1e-8) {
                    val a = (min - start) / delta; val b = (max - start) / delta
                    low = maxOf(low, minOf(a, b)); high = minOf(high, maxOf(a, b))
                }
            }
            if (low <= high) low to high else null
        }.sortedBy { it.first }
        // Membership is constant between rectangle boundary crossings. Check every crossing as
        // well as its interval interior to preserve Android's exclusive right/bottom bounds.
        val boundaries = (listOf(0.0, 1.0) + intervals.flatMap { listOf(it.first, it.second) }).distinct().sorted()
        fun covered(t: Double): Boolean {
            val x = from.x + (to.x - from.x) * t; val y = from.y + (to.y - from.y) * t
            return regions.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
        }
        return boundaries.all(::covered) && boundaries.zipWithNext().all { (a, b) -> covered((a + b) / 2) }
    }

    fun points(screen: DeviceScreenSnapshot, request: DeviceAnchoredGesture): Pair<DeviceGesturePoint, DeviceGesturePoint>? {
        if (screen.sensitive || screen.observationErrorCode != null || screen.interactionContext?.complete != true) return null
        fun point(index: Int) = screen.elements.singleOrNull { it.index == index && it.enabled && !it.password && it.bounds.isVisible }
            ?.bounds?.let { DeviceGesturePoint(it.centerX.toFloat(), it.centerY.toFloat()) }
        val from = point(request.index) ?: return null
        val to = if (request is DeviceAnchoredGesture.Drag) point(request.targetIndex) ?: return null else from
        if (request is DeviceAnchoredGesture.Drag && abs(from.x - to.x) + abs(from.y - to.y) < 8f) return null
        return (from to to).takeIf { coversLine(screen.interactionContext.availableRegions(screen), from, to) }
    }

    /** Each held segment is short so cancellation can release the owned continuation promptly. */
    fun legs(request: DeviceAnchoredGesture, from: DeviceGesturePoint, to: DeviceGesturePoint): List<DeviceGestureLeg> {
        if (request is DeviceAnchoredGesture.DoubleTap) return List(2) { DeviceGestureLeg(from, from, 60, false) }
        request as DeviceAnchoredGesture.Drag
        val result = mutableListOf<DeviceGestureLeg>()
        fun stage(start: DeviceGesturePoint, end: DeviceGesturePoint, duration: Long) {
            val count = ((duration + 49) / 50).toInt()
            var previous = start
            repeat(count) { step ->
                val fraction = (step + 1f) / count
                val next = DeviceGesturePoint(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
                result += DeviceGestureLeg(previous, next, duration / count + if (step < duration % count) 1 else 0, true)
                previous = next
            }
        }
        stage(from, from, request.holdMillis); stage(from, to, request.durationMillis)
        // Finish with an explicit release at the last accepted point, including after cancellation.
        return result
    }
}
