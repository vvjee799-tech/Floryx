package com.flowchat.app.domain.device

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

/** Finger direction and requested distance are distinct from measured content displacement. */
data class DeviceScrollRequest(
    val direction: DeviceSwipeDirection,
    val distancePercent: Int? = null,
    val durationMillis: Int? = null,
    val screenId: String? = null,
    val containerIndex: Int? = null,
    val target: DeviceUiSelector? = null
) {
    init {
        require(distancePercent == null || distancePercent in 1..80)
        require(durationMillis == null || durationMillis in 150..1200)
        require(containerIndex == null || containerIndex >= 0 && !screenId.isNullOrBlank())
        require(screenId == null || screenId.isNotBlank() && screenId.length <= 512)
    }
    val vertical: Boolean get() = direction == DeviceSwipeDirection.Up || direction == DeviceSwipeDirection.Down
    val contentSign: Int get() = if (direction == DeviceSwipeDirection.Up || direction == DeviceSwipeDirection.Left) 1 else -1

    companion object {
        fun fromSwipeArguments(arguments: String): DeviceScrollRequest {
            val args = Json.parseToJsonElement(arguments).jsonObject
            require(args.keys.all { it in setOf("direction", "distance_percent", "duration_ms", "screen_id", "container_index", "target") })
            return DeviceScrollRequest(DeviceSwipeDirection.entries.single { it.name.equals(args.string("direction"), true) },
                args.integer("distance_percent"), args.integer("duration_ms"), args.optionalString("screen_id"),
                args.integer("container_index"), args["target"]?.jsonObject?.let(DeviceUiSelector::fromJson))
        }
    }
}

data class DeviceContainerScrollRequest(
    val index: Int, val direction: DeviceScrollDirection, val screenId: String,
    val distancePercent: Int? = null, val durationMillis: Int? = null, val target: DeviceUiSelector? = null
) {
    val controlled: Boolean get() = distancePercent != null || durationMillis != null || target != null
    fun gesture(): DeviceScrollRequest = DeviceScrollRequest(when (direction) {
        DeviceScrollDirection.Down -> DeviceSwipeDirection.Up; DeviceScrollDirection.Up -> DeviceSwipeDirection.Down
        DeviceScrollDirection.Right -> DeviceSwipeDirection.Left; DeviceScrollDirection.Left -> DeviceSwipeDirection.Right
        else -> error("Controlled scrolling needs a physical content direction: up/down/left/right.")
    }, distancePercent, durationMillis, screenId, index, target)

    companion object {
        fun fromArguments(arguments: String): DeviceContainerScrollRequest {
            val args = Json.parseToJsonElement(arguments).jsonObject
            require(args.keys.all { it in setOf("screen_id", "index", "direction", "distance_percent", "duration_ms", "target") })
            return DeviceContainerScrollRequest(requireNotNull(args.integer("index")).also { require(it >= 0) },
                DeviceScrollDirection.entries.single { it.name.equals(args.string("direction"), true) }, args.string("screen_id"),
                args.integer("distance_percent"), args.integer("duration_ms"), args["target"]?.jsonObject?.let(DeviceUiSelector::fromJson))
                .also { if (it.controlled) it.gesture() }
        }
    }
}

private fun JsonObject.optionalString(key: String): String? = get(key)?.let {
    require(it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 512); it.content
}
private fun JsonObject.string(key: String): String = requireNotNull(optionalString(key))
private fun JsonObject.integer(key: String): Int? = get(key)?.let { require(it is JsonPrimitive && !it.isString); requireNotNull(it.intOrNull) }

data class DeviceScrollArea(
    val container: DeviceUiElement?, val bounds: DeviceBounds, val excluded: List<DeviceBounds>,
    val clearRegions: List<DeviceBounds> = listOf(bounds)
)
data class DeviceScrollMotion(val contentPixels: Int, val source: String, val anchors: Int = 0)
data class DeviceScrollFeedback(
    val gesturePixels: Int, val durationMillis: Int, val motion: DeviceScrollMotion?,
    val targetState: String, val actions: Int, val corrections: Int, val elapsedMillis: Long,
    val settled: Boolean,
    val inputMode: String = "gesture"
) {
    fun asToolContent(): String = buildString {
        append("scroll_feedback: finger_pixels=").append(gesturePixels).append(" duration_ms=").append(durationMillis)
        append(" content_delta_pixels=").append(motion?.contentPixels ?: "unknown")
        append(" measurement=").append(motion?.source ?: "unknown").append(" anchors=").append(motion?.anchors ?: 0)
        append(" target=").append(targetState).append(" actions=").append(actions).append(" corrections=").append(corrections)
        append(" elapsed_ms=").append(elapsedMillis).append(" settled=").append(settled)
        append(" input_mode=").append(inputMode)
        append("\nContent delta is signed: positive reveals lower/right content. Finger distance is not content distance. Distance/time refer to the final movement; actions counts all bounded movements, including semantic actions.")
    }
}

/** Pure geometry/evidence policy, shared by physical and background display executors. */
object DeviceAdaptiveScroll {
    fun descendants(screen: DeviceScreenSnapshot, root: DeviceUiElement?): List<DeviceUiElement> {
        if (root == null) return screen.elements
        val byIndex = screen.elements.associateBy { it.index }
        return screen.elements.filter { candidate ->
            var current: DeviceUiElement? = candidate
            val visited = mutableSetOf<Int>()
            while (current != null && visited.add(current.index)) {
                if (current.index == root.index) return@filter true
                current = current.parentIndex?.let(byIndex::get)
            }
            false
        }
    }

    fun area(screen: DeviceScreenSnapshot, request: DeviceScrollRequest, original: DeviceUiElement? = null): DeviceScrollArea? {
        val geometry = screen.geometry ?: return null
        val controls = screen.elements.filter { it.scrollable && it.enabled && it.bounds.isVisible }
        val target = request.target?.let { selector -> screen.elements.singleOrNull(selector::matches) }
        val root = when {
            original != null -> resolveContainer(screen, original) ?: return null
            request.containerIndex != null -> controls.singleOrNull { it.index == request.containerIndex } ?: return null
            target != null -> controls.filter { candidate -> descendants(screen, candidate).any { it.index == target.index } }.maxByOrNull { it.depth }
                ?: controls.singleOrNull()
            controls.size == 1 -> controls.single()
            controls.isNotEmpty() -> return null // Nested/sibling lists need an explicit choice, never a central-window guess.
            else -> null
        }
        // A custom, non-interactive content surface can expose bounds without claiming scrollability.
        // Keep swipes off its surrounding toolbar; do not invent a semantic container capability.
        val surface = if (root == null) screen.elements.filter { candidate ->
            !candidate.editable && !candidate.clickable && !candidate.scrollable && candidate.bounds.isVisible &&
                candidate.bounds.width.toLong() * candidate.bounds.height >= geometry.windowBounds.width.toLong() * geometry.windowBounds.height * 3 / 5 &&
                screen.elements.none { it.parentIndex == candidate.index }
        }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height } else null
        val bounds = (root?.bounds ?: surface?.bounds ?: geometry.windowBounds).intersect(geometry.windowBounds) ?: return null
        val clearRegions = screen.interactionContext?.availableRegions(screen)?.mapNotNull { it.intersect(bounds) } ?: return null
        val largestRegion = clearRegions.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val cross = if (request.vertical) bounds.centerX else bounds.centerY
        val extent = if (request.vertical) largestRegion.height else largestRegion.width
        // A side overlay can split the rectangles while leaving the center lane continuous.
        // Use that lane's viewport edges for planning. Swipe paths still use the actual clear regions.
        val central = clearRegions.axisSpans(cross, request.vertical).singleOrNull()
            ?.takeIf { (start, end) -> end - start > extent }
            ?.let { (start, end) ->
                if (request.vertical) bounds.copy(top = start, bottom = end) else bounds.copy(left = start, right = end)
            } ?: largestRegion
        val descendantList = descendants(screen, root)
        val descendantIds = descendantList.map { it.index }.toSet()
        val ancestorIds = buildSet {
            val byIndex = screen.elements.associateBy { it.index }
            var current = root?.parentIndex?.let(byIndex::get)
            while (current != null && add(current.index)) {
                current = current.parentIndex?.let(byIndex::get)
            }
        }
        var visible = central
        val siblingBlockers = if (root == null) emptyList() else screen.elements.filter { el ->
            el.index > root.index && el.enabled && (el.clickable || el.editable || el.scrollable) &&
                el.index !in descendantIds && el.index !in ancestorIds &&
                el.bounds.intersect(visible)?.let { hit -> hit.height < visible.height / 2 } == true
        }
        val extraExcluded = mutableListOf<DeviceBounds>()
        for (blocker in siblingBlockers) {
            val hit = blocker.bounds.intersect(visible) ?: continue
            val trimsVerticalBar = request.vertical && hit.width >= visible.width * 3 / 4
            val trimsHorizontalBar = !request.vertical && hit.height >= visible.height * 3 / 4
            if (trimsVerticalBar && hit.top <= visible.top + visible.height / 4 && visible.bottom - hit.bottom >= visible.height / 3) {
                visible = visible.copy(top = maxOf(visible.top, hit.bottom))
            } else if (trimsVerticalBar && hit.bottom >= visible.bottom - visible.height / 4 && hit.top - visible.top >= visible.height / 3) {
                visible = visible.copy(bottom = minOf(visible.bottom, hit.top))
            } else if (trimsHorizontalBar && hit.left <= visible.left + visible.width / 4 && visible.right - hit.right >= visible.width / 3) {
                visible = visible.copy(left = maxOf(visible.left, hit.right))
            } else if (trimsHorizontalBar && hit.right >= visible.right - visible.width / 4 && hit.left - visible.left >= visible.width / 3) {
                visible = visible.copy(right = minOf(visible.right, hit.left))
            } else {
                extraExcluded += hit
            }
        }
        val nested = descendantList.filter { it.scrollable && it.index != root?.index }.map { it.bounds } + extraExcluded
        return DeviceScrollArea(root, visible, nested, clearRegions.mapNotNull { it.intersect(visible) }.ifEmpty { listOf(visible) })
    }

    fun resolveContainer(screen: DeviceScreenSnapshot, original: DeviceUiElement): DeviceUiElement? =
        screen.elements.singleOrNull {
            it.scrollable && it.className == original.className && it.resourceId == original.resourceId &&
                it.contentDescription == original.contentDescription && it.nodePath == original.nodePath
        } ?: screen.elements.singleOrNull {
            it.scrollable && it.className == original.className && it.resourceId == original.resourceId &&
                it.contentDescription == original.contentDescription && it.bounds == original.bounds &&
                (original.resourceId != null || original.contentDescription != null ||
                    screen.elements.count { candidate -> candidate.scrollable && candidate.bounds == original.bounds } == 1)
        }

    fun targets(screen: DeviceScreenSnapshot, area: DeviceScrollArea, selector: DeviceUiSelector?): List<DeviceUiElement> =
        if (selector == null) emptyList() else descendants(screen, area.container).filter(selector::matches)

    fun targetReady(screen: DeviceScreenSnapshot, area: DeviceScrollArea, target: DeviceUiElement, vertical: Boolean): Boolean {
        if (!target.enabled || screen.interactionContext?.allowsPoint(screen, target.bounds.centerX, target.bounds.centerY) != true)
            return false
        val extent = if (vertical) area.bounds.height else area.bounds.width
        val margin = (extent * .12).roundToInt().coerceAtLeast(8)
        val start = if (vertical) target.bounds.top else target.bounds.left
        val end = if (vertical) target.bounds.bottom else target.bounds.right
        val low = (if (vertical) area.bounds.top else area.bounds.left) + margin
        val high = (if (vertical) area.bounds.bottom else area.bounds.right) - margin
        if (start >= low && end <= high) return true

        // The visible bounds fit the unobscured area and do not touch a scroll viewport edge.
        // Stop here once the row can be operated without moving it further into the viewport.
        val viewport = area.container?.bounds ?: return false
        val viewportStart = if (vertical) viewport.top else viewport.left
        val viewportEnd = if (vertical) viewport.bottom else viewport.right
        return start > viewportStart && end < viewportEnd && target.bounds.intersect(area.bounds) == target.bounds
    }

    fun shownTargetReady(screen: DeviceScreenSnapshot, area: DeviceScrollArea, target: DeviceUiElement, vertical: Boolean): Boolean {
        val peers = descendants(screen, area.container).filter { !it.scrollable && it.className == target.className &&
            it.bounds.intersect(area.bounds) != null && it.displayText.isNotBlank() }.map { if (vertical) it.bounds.height else it.bounds.width }.sorted()
        val size = if (vertical) target.bounds.height else target.bounds.width
        val typical = peers.getOrNull(peers.size / 2) ?: return false
        return size >= typical * .9 && target.enabled &&
            area.bounds.contains(target.bounds.centerX, target.bounds.centerY) &&
            screen.interactionContext?.allowsPoint(screen, target.bounds.centerX, target.bounds.centerY) == true
    }

    /** Null means exploratory browsing. A visible edge target supplies an evidence-based distance/direction. */
    fun targetDelta(area: DeviceScrollArea, target: DeviceUiElement, vertical: Boolean): Int {
        val bounds = if (area.clearRegions.any { it.contains(target.bounds.centerX, target.bounds.centerY) }) area.bounds
        else area.clearRegions.maxByOrNull { it.width.toLong() * it.height } ?: area.bounds
        val extent = if (vertical) bounds.height else bounds.width
        val low = if (vertical) bounds.top else bounds.left
        val center = if (vertical) target.bounds.centerY else target.bounds.centerX
        val half = (if (vertical) target.bounds.height else target.bounds.width) / 2
        val margin = maxOf((extent * .18).roundToInt(), half + 12).coerceAtMost(extent / 3)
        return center - center.coerceIn(low + margin, low + extent - margin)
    }

    /** Unique, stable labels/collection rows must agree; changing counters or clipped bounds cannot estimate travel. */
    fun measure(before: DeviceScreenSnapshot, after: DeviceScreenSnapshot, area: DeviceScrollArea, vertical: Boolean): DeviceScrollMotion? {
        if (before.packageName != after.packageName || before.windowId != after.windowId || before.geometry != after.geometry ||
            !before.hasSameObservationScope(after) || before.interactionContext != after.interactionContext) return null
        val newRoot = area.container?.let { resolveContainer(after, it) ?: return null }
        val beforeViewport = area.container?.bounds ?: before.geometry?.windowBounds
        val afterViewport = newRoot?.bounds ?: after.geometry?.windowBounds
        fun anchors(screen: DeviceScreenSnapshot, root: DeviceUiElement?): Map<String, DeviceUiElement> =
            descendants(screen, root).filter { element ->
                !element.scrollable && !element.editable && !element.password && element.bounds.intersect(area.bounds) != null &&
                    area.clearRegions.any { element.bounds.intersect(it) != null } &&
                    (element.displayText.isNotBlank() || element.collectionItem != null) &&
                    area.excluded.none { excluded -> excluded.contains(element.bounds.centerX, element.bounds.centerY) }
            }.groupBy { "${it.className}|${it.resourceId}|${it.text}|${it.contentDescription}|${it.collectionItem}" }
                .filterValues { it.size == 1 }.mapValues { it.value.single() }
        val old = anchors(before, area.container); val current = anchors(after, newRoot)
        val entries = old.mapNotNull { (key, previous) ->
            current[key]?.let { anchorDelta(previous.bounds, it.bounds, beforeViewport, afterViewport, vertical) }
        }
        if (entries.size < 2) return null
        val shifts = entries.map { it.first }.sorted()
        val median = shifts[shifts.size / 2]
        val agreeing = entries.count { abs(it.first - median) <= maxOf(4, abs(median) / 20) }
        if (agreeing < 2 || agreeing * 3 < shifts.size * 2) return null
        return DeviceScrollMotion(median, "anchors", agreeing)
    }

    private fun anchorDelta(
        previous: DeviceBounds, current: DeviceBounds,
        beforeViewport: DeviceBounds?, afterViewport: DeviceBounds?, vertical: Boolean
    ): Pair<Int, Boolean>? {
        val crossDiff = if (vertical) abs(current.width - previous.width) else abs(current.height - previous.height)
        if (crossDiff > 3) return null
        val pStart = if (vertical) previous.top else previous.left
        val pEnd = if (vertical) previous.bottom else previous.right
        val cStart = if (vertical) current.top else current.left
        val cEnd = if (vertical) current.bottom else current.right
        val pSize = pEnd - pStart
        val cSize = cEnd - cStart
        if (abs(cSize - pSize) <= 3) {
            return ((if (vertical) previous.centerY - current.centerY else previous.centerX - current.centerX) to true)
        }
        if (beforeViewport == null || afterViewport == null) return null
        val bMin = if (vertical) beforeViewport.top else beforeViewport.left
        val bMax = if (vertical) beforeViewport.bottom else beforeViewport.right
        val aMin = if (vertical) afterViewport.top else afterViewport.left
        val aMax = if (vertical) afterViewport.bottom else afterViewport.right
        val minSize = minOf(pSize, cSize)
        val maxSize = maxOf(pSize, cSize)
        if (minSize < maxOf(16, maxSize / 3)) return null
        val pClipStart = pStart <= bMin + 3
        val cClipStart = cStart <= aMin + 3
        val pClipEnd = pEnd >= bMax - 3
        val cClipEnd = cEnd >= aMax - 3
        if (!pClipEnd && !cClipEnd && (pClipStart || cClipStart) &&
            (!pClipStart || pSize <= cSize + 3) && (!cClipStart || cSize <= pSize + 3)) {
            return ((pEnd - cEnd) to false)
        }
        if (!pClipStart && !cClipStart && (pClipEnd || cClipEnd) &&
            (!pClipEnd || pSize <= cSize + 3) && (!cClipEnd || cSize <= pSize + 3)) {
            return ((pStart - cStart) to false)
        }
        return null
    }

    fun contentsChanged(before: DeviceScreenSnapshot, after: DeviceScreenSnapshot, area: DeviceScrollArea): Boolean {
        val root = area.container ?: return false
        val newRoot = resolveContainer(after, root) ?: return false
        if (root.bounds != newRoot.bounds || before.packageName != after.packageName || before.windowId != after.windowId ||
            before.geometry != after.geometry || !before.hasSameObservationScope(after)) return false
        fun labels(screen: DeviceScreenSnapshot, container: DeviceUiElement) = descendants(screen, container).filter {
            !it.scrollable && !it.editable && it.displayText.isNotBlank() &&
                !it.displayText.matches(Regex("[\\d.,:% ]+")) &&
                area.excluded.none { other -> other.contains(it.bounds.centerX, it.bounds.centerY) }
        }.map { "${it.className}|${it.displayText}|${it.collectionItem}" }.toSet()
        val old = labels(before, root); val current = labels(after, newRoot)
        return old.isNotEmpty() && current.isNotEmpty() && old != current
    }

    fun isSameAxisScrollableAncestor(className: String?, actions: Collection<String>, vertical: Boolean): Boolean {
        val hasVertical = "scroll_up" in actions || "scroll_down" in actions
        val hasHorizontal = "scroll_left" in actions || "scroll_right" in actions
        val simpleClass = className?.substringAfterLast('.').orEmpty()
        return if (vertical) {
            if (hasHorizontal && !hasVertical) false
            else !(!hasVertical && simpleClass in setOf("ViewPager", "HorizontalScrollView"))
        } else {
            if (hasVertical && !hasHorizontal) false
            else !(!hasHorizontal && simpleClass in setOf("ScrollView", "ListView"))
        }
    }

    fun sameScrollContext(previous: DeviceScreenSnapshot, current: DeviceScreenSnapshot, request: DeviceScrollRequest): Boolean {
        val index = request.containerIndex ?: return previous.hasSameUi(current)
        if (previous.sensitive || current.sensitive || previous.observationErrorCode != null || current.observationErrorCode != null ||
            previous.packageName != current.packageName || previous.activityName != current.activityName ||
            previous.windowId != current.windowId || previous.geometry != current.geometry ||
            previous.interactionContext != current.interactionContext || !previous.hasSameObservationScope(current)) return false
        val oldContainer = previous.elements.firstOrNull { it.index == index && it.scrollable && it.enabled } ?: return false
        val newContainer = current.elements.firstOrNull { it.index == index && it.scrollable && it.enabled } ?: return false
        if (oldContainer != newContainer) return false
        val selector = request.target ?: return true
        val oldMatches = descendants(previous, oldContainer).filter(selector::matches)
        return oldMatches.isEmpty() || descendants(current, newContainer).filter(selector::matches) == oldMatches
    }

    fun direction(vertical: Boolean, contentPixels: Int): DeviceSwipeDirection =
        if (vertical) if (contentPixels >= 0) DeviceSwipeDirection.Up else DeviceSwipeDirection.Down
        else if (contentPixels >= 0) DeviceSwipeDirection.Left else DeviceSwipeDirection.Right
}
