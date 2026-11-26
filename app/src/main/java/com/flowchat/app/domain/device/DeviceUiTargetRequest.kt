package com.flowchat.app.domain.device

import kotlinx.serialization.json.*

/** A click intent anchored to a previously observed window; never an old coordinate/index. */
data class DeviceUiTargetRequest(val screenId: String, val target: DeviceUiSelector, val timeoutMillis: Long = 3_000,
    val observationScope: DeviceObservationScope? = null) {
    init { require(screenId.isNotBlank() && screenId.length <= 512); require(timeoutMillis in 100..5_000) }

    companion object {
        fun fromToolArguments(arguments: String): DeviceUiTargetRequest {
            val fields = Json.parseToJsonElement(arguments).jsonObject
            require(fields.keys.containsAll(setOf("screen_id", "target")) && fields.keys.all { it in setOf("screen_id", "target", "timeout_ms", "observation_scope") })
            val id = fields.getValue("screen_id").jsonPrimitive.also { require(it.isString) }.content
            val timeout = fields["timeout_ms"]?.jsonPrimitive?.let { require(!it.isString); requireNotNull(it.longOrNull) } ?: 3_000L
            return DeviceUiTargetRequest(id, DeviceUiSelector.fromJson(fields.getValue("target").jsonObject), timeout,
                DeviceObservationScope.fromToolArguments(arguments))
        }
    }
}

/** Rechecked at the actual accessibility dispatch boundary, including a label's containing row. */
object DeviceUiTargetPolicy {
    enum class MatchStatus { None, Ready, Ambiguous }

    sealed interface Resolution {
        data class Resolved(val element: DeviceUiElement) : Resolution
        data object Ambiguous : Resolution
        data object NotReady : Resolution
    }

    fun classifyQueryMatches(elements: List<DeviceUiElement>, selector: DeviceUiSelector): MatchStatus {
        val matches = elements.filter(selector::matches)
        if (matches.isEmpty()) return MatchStatus.None
        if (matches.size == 1) return MatchStatus.Ready
        val nonEditable = matches.filterNot { it.editable }
        if (nonEditable.isEmpty()) return MatchStatus.Ambiguous
        if (nonEditable.count { it.clickable || "click" in it.supportedActions } > 1) return MatchStatus.Ambiguous
        return MatchStatus.Ready
    }

    fun queryCandidates(elements: List<DeviceUiElement>, selector: DeviceUiSelector): List<DeviceUiElement> {
        val matches = elements.filter(selector::matches)
        if (matches.size <= 1) return matches
        val nonEditable = matches.filterNot { it.editable }
        if (nonEditable.isEmpty() || nonEditable.count { it.clickable || "click" in it.supportedActions } > 1) return emptyList()
        return nonEditable
    }

    fun resolve(screen: DeviceScreenSnapshot, selector: DeviceUiSelector): DeviceUiElement? =
        (resolveDetailed(screen, selector) as? Resolution.Resolved)?.element

    fun resolveDetailed(screen: DeviceScreenSnapshot, selector: DeviceUiSelector): Resolution {
        if (screen.sensitive || screen.observationErrorCode != null || !screen.searchComplete || screen.truncated ||
            screen.interactionContext?.complete != true || screen.elementQuery != null && screen.elementQuery != selector) {
            return Resolution.NotReady
        }
        val matches = screen.elements.filter(selector::matches)
        if (matches.isEmpty()) return Resolution.NotReady
        if (matches.size == 1) {
            return resolveReadyTarget(screen, matches.single())?.let(Resolution::Resolved) ?: Resolution.NotReady
        }
        val nonEditable = matches.filterNot { it.editable }
        if (nonEditable.isEmpty()) return Resolution.Ambiguous
        val clickableAncestors = nonEditable.mapNotNull { candidate ->
            findClickableSelfOrAncestor(screen, candidate)?.let { candidate to it }
        }
        val distinctAncestors = clickableAncestors.map { it.second }.distinctBy { it.index }
        if (distinctAncestors.size > 1) return Resolution.Ambiguous
        if (distinctAncestors.isEmpty()) {
            return if (nonEditable.size > 1 && screen.elementQuery != null) Resolution.Ambiguous else Resolution.NotReady
        }
        val readyTargets = clickableAncestors.mapNotNull { (candidate, _) ->
            resolveReadyTarget(screen, candidate)
        }.distinctBy { it.index }
        return when {
            readyTargets.size == 1 -> Resolution.Resolved(readyTargets.single())
            readyTargets.size > 1 -> Resolution.Ambiguous
            else -> Resolution.NotReady
        }
    }

    private fun findClickableSelfOrAncestor(screen: DeviceScreenSnapshot, start: DeviceUiElement): DeviceUiElement? {
        var current = start
        val visited = mutableSetOf<Int>()
        while (!current.clickable && "click" !in current.supportedActions && visited.add(current.index)) {
            if (screen.elementQuery != null) return null
            current = current.parentIndex?.let { parent -> screen.elements.singleOrNull { it.index == parent } } ?: return null
        }
        return current.takeIf { it.clickable || "click" in it.supportedActions }
    }

    private fun resolveReadyTarget(screen: DeviceScreenSnapshot, start: DeviceUiElement): DeviceUiElement? {
        var target = start
        if (!target.enabled || target.password || !target.bounds.isVisible) return null
        val visited = mutableSetOf<Int>()
        while ((!target.clickable || "click" !in target.supportedActions) && visited.add(target.index)) {
            // A filtered read has no verified parent chain. Obtain the complete pane first.
            if (screen.elementQuery != null) return null
            target = target.parentIndex?.let { parent -> screen.elements.singleOrNull { it.index == parent } } ?: return null
            if (!target.enabled || target.password) return null
        }
        return target.takeIf { it.clickable && "click" in it.supportedActions &&
            screen.interactionContext?.allowsPoint(screen, it.bounds.centerX, it.bounds.centerY) == true }
    }
}
