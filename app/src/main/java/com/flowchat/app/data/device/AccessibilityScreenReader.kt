package com.flowchat.app.data.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.SensitiveScreenDetector

internal data class AccessibilityReadResult(
    val snapshot: DeviceScreenSnapshot,
    val indexedNodes: Map<Int, AccessibilityNodeInfo>
)

internal object AccessibilityScreenReader {
    fun read(root: AccessibilityNodeInfo, activityName: String, viewport: Rect): AccessibilityReadResult {
        val elements = mutableListOf<DeviceUiElement>()
        val indexedNodes = linkedMapOf<Int, AccessibilityNodeInfo>()
        var visitedNodes = 0
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visitedNodes >= MaxVisitedNodes) {
                truncated = true
                return
            }
            visitedNodes++
            if (depth > MaxTreeDepth) {
                truncated = true
                return
            }
            val bounds = Rect().also(node::getBoundsInScreen)
            val hasMeaningfulText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val interactive = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isCheckable
            // Android can report visible nodes translated beyond the display during app transitions.
            val include = node.isVisibleToUser && bounds.intersect(viewport) &&
                (interactive || hasMeaningfulText)
            if (include) {
                val index = elements.size
                elements += DeviceUiElement(
                    index = index,
                    className = node.className?.toString().orEmpty(),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    resourceId = node.viewIdResourceName,
                    bounds = DeviceBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    clickable = node.isClickable || node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    password = node.isPassword,
                    checked = node.isChecked.takeIf { node.isCheckable },
                    selected = node.isSelected,
                    focused = node.isFocused,
                    hint = node.hintText?.toString()
                )
                if (index < MaxObservedElements) {
                    @Suppress("DEPRECATION")
                    indexedNodes[index] = AccessibilityNodeInfo.obtain(node)
                }
            }
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
                if (visitedNodes >= MaxVisitedNodes && childIndex < node.childCount - 1) {
                    truncated = true
                    break
                }
            }
        }

        visit(root, 0)
        val packageName = root.packageName?.toString().orEmpty()
        val detection = SensitiveScreenDetector.detect(packageName, activityName, elements)
        if (detection.sensitive) {
            indexedNodes.values.forEach { node ->
                @Suppress("DEPRECATION")
                node.recycle()
            }
            indexedNodes.clear()
        }
        return AccessibilityReadResult(
            snapshot = DeviceScreenSnapshot(
                packageName = packageName,
                activityName = activityName,
                elements = if (detection.sensitive) emptyList() else elements.take(MaxObservedElements),
                sensitive = detection.sensitive,
                sensitivityReason = detection.reason,
                truncated = truncated || elements.size > MaxObservedElements
            ),
            indexedNodes = indexedNodes
        )
    }

    const val MaxObservedElements = 160
    // Real Compose screens can place their input and send controls more than 40 levels deep.
    private const val MaxTreeDepth = 128
    private const val MaxVisitedNodes = 4_096
}
