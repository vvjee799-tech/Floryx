package com.flowchat.app.domain.device

enum class DeviceWindowKind { Application, InputMethod, System, Overlay, Other }

/** Only geometry and ownership metadata; never text from a different window. */
data class DeviceWindowRegion(
    val id: Int,
    val kind: DeviceWindowKind,
    val layer: Int,
    val bounds: DeviceBounds,
    val regions: List<DeviceBounds>,
    val focused: Boolean,
    val active: Boolean
)

data class DeviceInteractionContext(
    val windows: List<DeviceWindowRegion>,
    val cutouts: List<DeviceBounds> = emptyList(),
    val inputFocusIndex: Int? = null,
    val complete: Boolean = true,
    val inputFocusWindowId: Int? = null
) {
    val keyboardVisible: Boolean get() = windows.any { it.kind == DeviceWindowKind.InputMethod && it.regions.isNotEmpty() }

    fun availableRegions(screen: DeviceScreenSnapshot): List<DeviceBounds> {
        if (!complete) return emptyList()
        val geometry = screen.geometry ?: return emptyList()
        if (geometry.windowBounds.left < 0 || geometry.windowBounds.top < 0 ||
            geometry.windowBounds.right > geometry.displayWidth || geometry.windowBounds.bottom > geometry.displayHeight) return emptyList()
        val target = windows.singleOrNull { it.id == screen.windowId } ?: return emptyList()
        val display = DeviceBounds(0, 0, geometry.displayWidth, geometry.displayHeight)
        var regions = target.regions.mapNotNull { it.intersect(geometry.windowBounds)?.intersect(display) }
        val blockers = windows.filter { it.id != target.id && it.layer > target.layer }.flatMap { it.regions } + cutouts
        for (blocker in blockers) {
            regions = regions.flatMap { it.subtract(blocker) }
            // Fail closed on pathological region fragmentation.
            if (regions.size > 256) return emptyList()
        }
        return regions
    }

    fun allowsPoint(screen: DeviceScreenSnapshot, x: Int, y: Int): Boolean =
        availableRegions(screen).any { it.contains(x, y) }

    fun swipe(screen: DeviceScreenSnapshot, direction: DeviceSwipeDirection, distancePercent: Int,
        within: DeviceBounds? = null, excluded: List<DeviceBounds> = emptyList(), distancePixels: Int? = null): DeviceSwipePath? {
        val geometry = screen.geometry ?: return null
        if (distancePercent !in 1..80 || distancePixels != null && distancePixels <= 0) return null
        val vertical = direction == DeviceSwipeDirection.Up || direction == DeviceSwipeDirection.Down
        val bounds = within?.intersect(geometry.windowBounds) ?: if (within == null) geometry.windowBounds else return null
        var regions = availableRegions(screen).mapNotNull { it.intersect(bounds) }
        for (blocker in excluded) regions = regions.flatMap { it.subtract(blocker) }
        regions = regions.filter { if (vertical) it.width >= 48 else it.height >= 48 }
        val center = if (vertical) bounds.centerX else bounds.centerY
        val axisCenter = if (vertical) bounds.centerY else bounds.centerX
        fun lane(cross: Int): Pair<Int, Int>? {
            val usable = regions.axisSpans(cross, vertical).filter { it.second - it.first >= 48 }
            return usable.firstOrNull { axisCenter in it.first until it.second } ?: usable.maxByOrNull { it.second - it.first }
        }
        val central = lane(center)
        val (cross, interval) = if (central != null) center to central else regions.map {
            if (vertical) it.centerX else it.centerY
        }.distinct().mapNotNull { candidate -> lane(candidate)?.let { candidate to it } }
            .maxByOrNull { it.second.second - it.second.first } ?: return null
        val middle = interval.first + (interval.second - interval.first) / 2
        val length = interval.second - interval.first
        val pixels = (distancePixels ?: (length * distancePercent / 100)).coerceIn(2, (length * .8).toInt())
        val delta = (pixels / 2).coerceAtLeast(1)
        return when (direction) {
            DeviceSwipeDirection.Up -> DeviceSwipePath(cross, middle + delta, cross, middle - delta)
            DeviceSwipeDirection.Down -> DeviceSwipePath(cross, middle - delta, cross, middle + delta)
            DeviceSwipeDirection.Left -> DeviceSwipePath(middle + delta, cross, middle - delta, cross)
            DeviceSwipeDirection.Right -> DeviceSwipePath(middle - delta, cross, middle + delta, cross)
        }
    }
}

data class DeviceSwipePath(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

/** Join adjacent pieces on one axis without bridging a gap covered by another window. */
internal fun List<DeviceBounds>.axisSpans(cross: Int, vertical: Boolean): List<Pair<Int, Int>> {
    val intervals = filter { if (vertical) cross in it.left until it.right else cross in it.top until it.bottom }
        .map { if (vertical) it.top to it.bottom else it.left to it.right }.sortedBy { it.first }
    val merged = mutableListOf<Pair<Int, Int>>()
    for (interval in intervals) {
        val previous = merged.lastOrNull()
        if (previous != null && interval.first <= previous.second) {
            merged[merged.lastIndex] = previous.first to maxOf(previous.second, interval.second)
        } else {
            merged += interval
        }
    }
    return merged
}

internal fun DeviceBounds.intersect(other: DeviceBounds): DeviceBounds? =
    DeviceBounds(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
        .takeIf { it.isVisible }

private fun DeviceBounds.subtract(other: DeviceBounds): List<DeviceBounds> {
    val overlap = intersect(other) ?: return listOf(this)
    return listOf(
        DeviceBounds(left, top, right, overlap.top), DeviceBounds(left, overlap.bottom, right, bottom),
        DeviceBounds(left, overlap.top, overlap.left, overlap.bottom), DeviceBounds(overlap.right, overlap.top, right, overlap.bottom)
    ).filter { it.isVisible }
}
