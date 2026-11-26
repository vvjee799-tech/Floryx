package com.flowchat.app.domain.device

/** Binds model element indexes to the exact UI that was shown to the model. */
class DeviceSnapshotGuard {
    /** Saved snapshot and volatile non-editable slots across transient re-reads on the same window. */
    data class State(
        val observed: DeviceScreenSnapshot?,
        val volatileWindowKey: String?,
        val volatileSlots: Set<String>,
        val volatileSubtreePrefixes: Set<String>
    )

    /** Immutable snapshot of pre-dispatch volatile regions for post-action progress verification. */
    data class VolatileFilter(
        val slots: Set<String> = emptySet(),
        val subtreePrefixes: Set<String> = emptySet(),
        val targetPath: String? = null
    ) {
        /** Returns true when every readable difference between [before] and [after] is live clocks, bounds animation, or known volatile slots. */
        fun matchesOnlyTelemetryOrVolatile(before: DeviceScreenSnapshot, after: DeviceScreenSnapshot): Boolean {
            if (!sameWindowAndScope(before, after)) return false
            return elementsMatchAllowing(
                previous = before.elements,
                current = after.elements,
                slots = slots,
                subtreePrefixes = subtreePrefixes,
                targetPath = targetPath,
                allowUnseenCounters = false
            )
        }
    }

    private var observed: DeviceScreenSnapshot? = null
    private var volatileWindowKey: String? = null
    private val volatileSlots = mutableSetOf<String>()
    private val volatileSubtreePrefixes = mutableSetOf<String>()

    fun latest(): DeviceScreenSnapshot? = observed

    fun exportState(): State = State(
        observed = observed,
        volatileWindowKey = volatileWindowKey,
        volatileSlots = volatileSlots.toSet(),
        volatileSubtreePrefixes = volatileSubtreePrefixes.toSet()
    )

    fun remember(screen: DeviceScreenSnapshot?) {
        val valid = screen?.takeUnless { it.sensitive || it.observationErrorCode != null }
        if (valid == null) {
            observed = null
            clearVolatility()
            return
        }
        val nextKey = windowKey(valid)
        val previous = observed
        if (previous == null || volatileWindowKey != nextKey) {
            clearVolatility()
            volatileWindowKey = nextKey
        } else {
            recordVolatility(previous, valid)
        }
        observed = valid
    }

    /** Restores volatility from [priorState] after a transient null clear when re-observing the same window. */
    fun rememberFrom(screen: DeviceScreenSnapshot?, priorState: State) {
        val valid = screen?.takeUnless { it.sensitive || it.observationErrorCode != null }
        if (valid == null) {
            observed = null
            clearVolatility()
            return
        }
        val nextKey = windowKey(valid)
        val prior = priorState.observed
        if (prior != null && priorState.volatileWindowKey == nextKey) {
            volatileWindowKey = nextKey
            volatileSlots.clear()
            volatileSlots.addAll(priorState.volatileSlots)
            volatileSubtreePrefixes.clear()
            volatileSubtreePrefixes.addAll(priorState.volatileSubtreePrefixes)
            recordVolatility(prior, valid)
        } else {
            clearVolatility()
            volatileWindowKey = nextKey
        }
        observed = valid
    }

    fun matches(screenId: String?, current: DeviceScreenSnapshot, targetIndex: Int? = null): Boolean {
        val previous = observed ?: return false
        return screenId != null && screenId == previous.screenId &&
            matchesSnapshot(previous, current, targetIndex)
    }

    fun matchesScroll(screenId: String?, current: DeviceScreenSnapshot, request: DeviceScrollRequest): Boolean {
        val previous = observed ?: return false
        return screenId != null && screenId == previous.screenId &&
            DeviceAdaptiveScroll.sameScrollContext(previous, current, request)
    }

    /**
     * Validates explicit editor-action submission (`ime_enter`) against [current].
     * Always protects the target input, window, geometry, interaction context and scope,
     * blocks first-time async page updates (such as search lists finishing loading), and
     * allows submission past live clocks/counters/animations or already-observed volatile widgets.
     */
    fun matchesEditorAction(screenId: String?, current: DeviceScreenSnapshot, index: Int): Boolean {
        val previous = observed ?: return false
        if (screenId == null || screenId != previous.screenId) return false
        return matchesEditorSnapshot(previous, current, index)
    }

    fun matchesEditorSnapshot(
        previous: DeviceScreenSnapshot,
        current: DeviceScreenSnapshot,
        index: Int,
        filterOverride: VolatileFilter? = null
    ): Boolean {
        if (!sameWindowAndScope(previous, current)) return false
        val oldTarget = previous.elements.singleOrNull { it.index == index && it.editable && !it.password } ?: return false
        val newTarget = current.elements.singleOrNull { it.index == index && it.editable && !it.password } ?: return false
        if (oldTarget != newTarget) return false
        if (previous.hasSameUi(current)) return true
        val slots = filterOverride?.slots ?: volatileSlots
        val prefixes = filterOverride?.subtreePrefixes ?: volatileSubtreePrefixes
        return elementsMatchAllowing(
            previous = previous.elements,
            current = current.elements,
            slots = slots,
            subtreePrefixes = prefixes,
            targetPath = oldTarget.nodePath,
            targetIndex = index,
            allowUnseenCounters = true
        )
    }

    fun captureVolatileFilter(screen: DeviceScreenSnapshot, index: Int): VolatileFilter {
        val targetPath = screen.elements.singleOrNull { it.index == index }?.nodePath
        return VolatileFilter(
            slots = volatileSlots.toSet(),
            subtreePrefixes = volatileSubtreePrefixes.toSet(),
            targetPath = targetPath
        )
    }

    private fun clearVolatility() {
        volatileWindowKey = null
        volatileSlots.clear()
        volatileSubtreePrefixes.clear()
    }

    private fun recordVolatility(previous: DeviceScreenSnapshot, current: DeviceScreenSnapshot) {
        if (!sameWindowAndScope(previous, current) || previous.hasSameActionContent(current)) return
        val editablePaths = (previous.elements + current.elements)
            .filter { it.editable || it.password }
            .mapNotNull { it.nodePath }
            .toSet()

        if (previous.elements.size == current.elements.size &&
            previous.elements.indices.all { slotKey(previous.elements[it]) == slotKey(current.elements[it]) }
        ) {
            val changedCountByParent = mutableMapOf<String, Int>()
            for (i in previous.elements.indices) {
                val a = previous.elements[i]
                val b = current.elements[i]
                if (a != b && !a.editable && !b.editable && !a.password && !b.password && sameControlState(a, b)) {
                    volatileSlots += slotKey(a)
                    val parent = a.nodePath?.substringBeforeLast('/', "").orEmpty()
                    if (parent.isNotEmpty()) {
                        changedCountByParent[parent] = (changedCountByParent[parent] ?: 0) + 1
                    }
                }
            }
            if (changedCountByParent.any { it.value >= 2 }) {
                val prevByPath = previous.elements.filter { it.nodePath != null }.associateBy { it.nodePath!! }
                val currByPath = current.elements.filter { it.nodePath != null }.associateBy { it.nodePath!! }
                for ((parent, count) in changedCountByParent) {
                    if (count >= 2) {
                        findSafeSubtree(parent, editablePaths, prevByPath, currByPath)?.let {
                            volatileSubtreePrefixes += it
                        }
                    }
                }
            }
            return
        }

        // Container size/order changed (e.g. live feed prepending/rotating rows).
        // Isolate changed non-editable subtrees without touching any ancestor of an editable input.
        val prevByPath = previous.elements.filter { it.nodePath != null }.associateBy { it.nodePath!! }
        val currByPath = current.elements.filter { it.nodePath != null }.associateBy { it.nodePath!! }
        val diffPaths = (prevByPath.keys + currByPath.keys).filter { path ->
            val a = prevByPath[path]?.copy(index = 0, parentIndex = null)
            val b = currByPath[path]?.copy(index = 0, parentIndex = null)
            a != b
        }
        val structuralDiffPaths = diffPaths.filter { path ->
            val a = prevByPath[path]
            val b = currByPath[path]
            a == null || b == null || slotKey(a) != slotKey(b)
        }
        for (path in structuralDiffPaths) {
            val a = prevByPath[path]
            val b = currByPath[path]
            if (a?.editable == true || b?.editable == true || a?.password == true || b?.password == true) continue
            val parent = path.substringBeforeLast('/', "")
            val safeSubtree = findSafeSubtree(parent, editablePaths, prevByPath, currByPath)
            if (safeSubtree != null) {
                volatileSubtreePrefixes += safeSubtree
            } else if (a != null && b != null && slotKey(a) == slotKey(b) && sameControlState(a, b)) {
                volatileSlots += slotKey(a)
            }
        }
        for (path in diffPaths) {
            if (path in structuralDiffPaths || isUnderVolatileSubtree(path, volatileSubtreePrefixes)) continue
            val a = prevByPath[path] ?: continue
            val b = currByPath[path] ?: continue
            if (!a.editable && !b.editable && !a.password && !b.password && sameControlState(a, b)) {
                volatileSlots += slotKey(a)
            }
        }
    }

    private fun findSafeSubtree(
        startParent: String,
        editablePaths: Set<String>,
        prevByPath: Map<String, DeviceUiElement>,
        currByPath: Map<String, DeviceUiElement>
    ): String? {
        if (startParent.isEmpty() || '/' !in startParent) return null
        if (containsAnyEditable(startParent, editablePaths)) return null
        var chosen = startParent
        while (true) {
            val upper = chosen.substringBeforeLast('/', "")
            if (upper.isEmpty() || '/' !in upper || containsAnyEditable(upper, editablePaths)) break
            val prevUpper = prevByPath[upper]?.copy(index = 0, parentIndex = null)
            val currUpper = currByPath[upper]?.copy(index = 0, parentIndex = null)
            if (prevUpper == currUpper) break
            chosen = upper
        }
        return chosen
    }

    private fun containsAnyEditable(prefix: String, editablePaths: Set<String>): Boolean =
        editablePaths.any { it == prefix || it.startsWith("$prefix/") }

    companion object {
        fun matchesSnapshot(previous: DeviceScreenSnapshot, current: DeviceScreenSnapshot, targetIndex: Int? = null): Boolean =
            !current.sensitive && !previous.sensitive &&
                previous.observationErrorCode == null && current.observationErrorCode == null &&
                if (targetIndex == null) previous.hasSameUi(current) else {
                    val oldTarget = previous.elements.firstOrNull { it.index == targetIndex }
                    previous.packageName == current.packageName && previous.activityName == current.activityName &&
                        previous.windowId == current.windowId &&
                        previous.geometry == current.geometry &&
                        previous.interactionContext == current.interactionContext && previous.hasSameObservationScope(current) &&
                        oldTarget != null && oldTarget == current.elements.firstOrNull { it.index == targetIndex }
                }

        private fun windowKey(screen: DeviceScreenSnapshot): String =
            "${screen.packageName}|${screen.activityName}|${screen.windowId}|${screen.geometry}|" +
                "${screen.interactionContext}|${screen.observationScope}|${screen.observationRootPath}"

        private fun sameWindowAndScope(previous: DeviceScreenSnapshot, current: DeviceScreenSnapshot): Boolean =
            !previous.sensitive && !current.sensitive &&
                previous.observationErrorCode == null && current.observationErrorCode == null &&
                previous.packageName == current.packageName && previous.activityName == current.activityName &&
                previous.windowId == current.windowId && previous.geometry == current.geometry &&
                previous.interactionContext == current.interactionContext && previous.hasSameObservationScope(current)

        private fun slotKey(element: DeviceUiElement): String =
            "${element.nodePath ?: "idx:${element.index}"}|${element.resourceId.orEmpty()}|${element.className}"

        private fun isUnderVolatileSubtree(nodePath: String?, prefixes: Set<String>): Boolean =
            nodePath != null && prefixes.any { prefix -> nodePath == prefix || nodePath.startsWith("$prefix/") }

        private fun sameControlState(a: DeviceUiElement, b: DeviceUiElement): Boolean =
            a.className == b.className && a.resourceId == b.resourceId &&
                a.clickable == b.clickable && a.editable == b.editable &&
                a.scrollable == b.scrollable && a.enabled == b.enabled &&
                a.password == b.password && a.checked == b.checked &&
                a.selected == b.selected && a.focused == b.focused &&
                a.editorActionLabel == b.editorActionLabel && a.nodePath == b.nodePath

        private fun elementsMatchAllowing(
            previous: List<DeviceUiElement>,
            current: List<DeviceUiElement>,
            slots: Set<String>,
            subtreePrefixes: Set<String>,
            targetPath: String?,
            targetIndex: Int? = null,
            allowUnseenCounters: Boolean
        ): Boolean {
            if (current.any { isUnderVolatileSubtree(it.nodePath, subtreePrefixes) && (it.editable || it.password) }) {
                return false
            }
            val staticBefore = if (subtreePrefixes.isEmpty()) previous
            else previous.filterNot { isUnderVolatileSubtree(it.nodePath, subtreePrefixes) }
            val staticAfter = if (subtreePrefixes.isEmpty()) current
            else current.filterNot { isUnderVolatileSubtree(it.nodePath, subtreePrefixes) }
            if (staticBefore.size != staticAfter.size) return false

            val ignoreTreeIndexShift = subtreePrefixes.isNotEmpty()
            for (i in staticBefore.indices) {
                val a = staticBefore[i]
                val b = staticAfter[i]
                val isTarget = (targetIndex != null && a.index == targetIndex) ||
                    (targetPath != null && a.nodePath == targetPath)
                if (isTarget) {
                    if (a != b) return false
                    continue
                }
                val exactMatch = if (ignoreTreeIndexShift) a.copy(index = 0, parentIndex = null) == b.copy(index = 0, parentIndex = null)
                else a == b
                if (exactMatch) continue

                if (a.editable || b.editable || a.password || b.password) return false
                if (!sameControlState(a, b)) return false

                val slot = slotKey(a)
                if (slot in slots) continue
                val sameDisplay = a.displayText == b.displayText
                val telemetryChanged = (DeviceScreenSnapshot.isLiveClockText(a.displayText) && DeviceScreenSnapshot.isLiveClockText(b.displayText)) ||
                    (allowUnseenCounters && DeviceScreenSnapshot.isLiveCounterText(a.displayText) && DeviceScreenSnapshot.isLiveCounterText(b.displayText))
                if (!sameDisplay && !telemetryChanged) return false
            }
            return true
        }
    }
}
