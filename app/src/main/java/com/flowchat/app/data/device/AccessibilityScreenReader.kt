package com.flowchat.app.data.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.SensitiveScreenDetector
import com.flowchat.app.domain.device.DeviceUiSelector
import com.flowchat.app.domain.device.DeviceObservationScope
import com.flowchat.app.domain.device.DeviceCollectionInfo
import com.flowchat.app.domain.device.DeviceCollectionItemInfo
import com.flowchat.app.domain.device.DeviceRangeInfo
import com.flowchat.app.domain.device.DeviceDocumentText

internal data class AccessibilityReadResult(
    val snapshot: DeviceScreenSnapshot,
    val indexedNodes: Map<Int, AccessibilityNodeInfo>,
    val inputFocusWindowId: Int? = null
)

internal object AccessibilityScreenReader {
    fun read(root: AccessibilityNodeInfo, activityName: String, viewport: Rect, selector: DeviceUiSelector? = null,
        scope: DeviceObservationScope? = null, includeDocumentText: Boolean = false): AccessibilityReadResult {
        val elements = mutableListOf<DeviceUiElement>()
        val securityElements = mutableListOf<DeviceUiElement>()
        val indexedNodes = linkedMapOf<Int, AccessibilityNodeInfo>()
        var visitedNodes = 0
        var truncated = false
        var inputFocusWindowId: Int? = null
        var rootMatches = 0
        var rootPath: String? = null
        var rootScrollable = false
        val document = StringBuilder()
        var documentRoots = 0
        var documentRootPath: String? = null
        var documentChars = 0L
        var documentOffscreenNodes = 0
        var documentPassword = false

        fun visit(node: AccessibilityNodeInfo, depth: Int, path: String, insideScope: Boolean = false,
            scopeDepth: Int = 0, parentIndex: Int? = null, insideDocument: Boolean = false,
            insideEditor: Boolean = false) {
            if (visitedNodes >= MaxVisitedNodes) {
                truncated = true
                return
            }
            visitedNodes++
            if (node.isFocused && node.isEditable) inputFocusWindowId = node.windowId
            if (depth > MaxTreeDepth) {
                truncated = true
                return
            }
            val bounds = Rect().also(node::getBoundsInScreen)
            val hasMeaningfulText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val interactive = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isCheckable
            // Android can report visible nodes translated beyond the display during app transitions.
            val visible = node.isVisibleToUser && bounds.intersect(viewport)
            val newDocument = includeDocumentText && !insideDocument && visible && node.className?.endsWith("WebView") == true
            if (newDocument) { documentRoots++; if (documentRoots == 1) documentRootPath = path }
            val inDocument = insideDocument || newDocument
            val inEditor = insideEditor || node.isEditable || node.isPassword
            if (includeDocumentText && inDocument) {
                if (node.isPassword) documentPassword = true
                if (!inEditor) node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    documentChars += text.length.toLong() + 1
                    if (!visible) documentOffscreenNodes++
                    if (document.length < MaxDocumentChars) {
                        document.append((text + "\n").take(MaxDocumentChars - document.length))
                    }
                }
            }
            var nextParent = parentIndex
            var nextScopeDepth = scopeDepth
            var inScope = insideScope
            if (visible) {
                val collection = node.collectionInfo?.let { DeviceCollectionInfo(it.rowCount, it.columnCount, it.isHierarchical, it.selectionMode) }
                // CollectionItemInfo is the heading source on API 26/27; newer releases expose it on the node.
                @Suppress("DEPRECATION")
                val item = node.collectionItemInfo?.let { DeviceCollectionItemInfo(it.rowIndex, it.rowSpan, it.columnIndex, it.columnSpan,
                    if (android.os.Build.VERSION.SDK_INT >= 28) node.isHeading else it.isHeading) }
                val range = node.rangeInfo?.takeIf { it.min.isFinite() && it.max.isFinite() && it.current.isFinite() &&
                    it.min <= it.current && it.current <= it.max }?.let { DeviceRangeInfo(it.type, it.min, it.max, it.current) }
                // Android EditText triggers for search/pickers set focusable=false so taps open the real editor.
                val directlyEditable = node.isEditable && (node.isFocusable || node.isFocused)
                val actions = node.actionList.mapNotNull { ActionNames[it.id] }
                    .filter { directlyEditable || it !in EditorOnlyActions }
                    .distinct().sorted()
                val element = DeviceUiElement(
                    index = elements.size,
                    className = node.className?.toString().orEmpty(),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    resourceId = node.viewIdResourceName,
                    bounds = DeviceBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    clickable = node.isClickable || node.isLongClickable,
                    editable = directlyEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    password = node.isPassword,
                    checked = node.isChecked.takeIf { node.isCheckable },
                    selected = node.isSelected,
                    focused = node.isFocused,
                    hint = node.hintText?.toString(),
                    supportedActions = actions,
                    collection = collection,
                    collectionItem = item,
                    range = range,
                    textSelectionStart = node.textSelectionStart.takeIf { directlyEditable && it >= 0 },
                    textSelectionEnd = node.textSelectionEnd.takeIf { directlyEditable && it >= 0 },
                    maxTextLength = node.maxTextLength.takeIf { directlyEditable && it >= 0 },
                    editorActionLabel = if (directlyEditable && android.os.Build.VERSION.SDK_INT >= 30) node.actionList
                        .firstOrNull { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
                        ?.label?.toString()?.takeIf { it.isNotBlank() } else null,
                    nodePath = path,
                    granularScrolling = android.os.Build.VERSION.SDK_INT >= 35 && node.isGranularScrollingSupported
                )
                // Security always sees the entire window, including siblings outside the requested root
                // and non-focusable EditText nodes that still carry text or sensitive labels.
                securityElements += if (directlyEditable == node.isEditable) element else element.copy(editable = node.isEditable)
                val matchesRoot = scope?.root?.matches(element) == true
                if (matchesRoot) {
                    rootMatches++
                    if (rootMatches == 1) { rootPath = path; rootScrollable = node.isScrollable }
                    if (!inScope) { nextScopeDepth = depth; nextParent = null }
                    inScope = true
                }
                val meaningful = interactive || hasMeaningfulText || collection != null || item != null || range != null || matchesRoot
                if (meaningful && (scope == null || inScope) && (selector == null || selector.matches(element))) {
                    val index = elements.size
                    elements += element.copy(index = index, parentIndex = nextParent,
                        depth = depth - if (scope == null) 0 else nextScopeDepth)
                    nextParent = index
                    if (index < MaxObservedElements) {
                        @Suppress("DEPRECATION")
                        indexedNodes[index] = AccessibilityNodeInfo.obtain(node)
                    }
                }
            }
            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex)
                if (child == null) { truncated = true; continue }
                try {
                    visit(child, depth + 1, "$path/$childIndex", inScope, nextScopeDepth, nextParent, inDocument, inEditor)
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

        visit(root, 0, "0")
        val packageName = root.packageName?.toString().orEmpty()
        val detection = SensitiveScreenDetector.detect(packageName, activityName, securityElements)
        // The scan was already bounded and global for sensitivity checks. Keep only
        // a digest for navigation; do not expose extra controls or issue extra indices.
        // Exclude scope-only wrappers so a missing-root read hashes the same window
        // as a full or directed read. Output truncation is separate from scan failure.
        val windowDigest = if (!truncated && !detection.sensitive) {
            val semantic = securityElements.filter { it.clickable || it.editable || it.scrollable || it.checked != null ||
                !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() ||
                it.collection != null || it.collectionItem != null || it.range != null }
            val content = DeviceScreenSnapshot(packageName, activityName, semantic).progressKey()
            java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } else null
        val scopeError = if (scope == null) null else when {
            truncated -> "observation_scope_incomplete"
            rootMatches == 0 -> "observation_root_not_found"
            rootMatches > 1 -> "observation_root_ambiguous"
            scope.kind == DeviceObservationScope.Kind.ScrollContainer && !rootScrollable -> "observation_root_not_scrollable"
            else -> null
        }
        if (detection.sensitive || scopeError != null) {
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
                elements = if (detection.sensitive || scopeError != null) emptyList() else elements.take(MaxObservedElements),
                sensitive = detection.sensitive,
                sensitivityReason = detection.reason,
                truncated = truncated || elements.size > MaxObservedElements,
                windowId = root.windowId,
                elementQuery = selector,
                searchComplete = !truncated && scopeError == null,
                observationScope = scope,
                observationRootPath = rootPath.takeIf { scopeError == null },
                observationErrorCode = scopeError,
                windowContentSha256 = windowDigest,
                documentText = if (!includeDocumentText) null else {
                    val error = when {
                        detection.sensitive || documentPassword -> "document_sensitive_content"
                        scope != null || selector != null -> "document_scope_unsupported"
                        documentRoots == 0 -> "document_root_not_found"
                        documentRoots != 1 -> "document_root_ambiguous"
                        documentChars == 0L -> "document_text_unavailable"
                        else -> null
                    }
                    DeviceDocumentText(documentRootPath, if (error == null) document.toString() else "",
                        treeComplete = error == null && !truncated,
                        truncated = documentChars > MaxDocumentChars,
                        offscreenTextNodes = documentOffscreenNodes, totalChars = documentChars, errorCode = error)
                }
            ),
            indexedNodes = indexedNodes,
            inputFocusWindowId = inputFocusWindowId
        )
    }

    const val MaxObservedElements = 160
    private const val MaxDocumentChars = 24_000
    // Real Compose screens can place their input and send controls more than 40 levels deep.
    private const val MaxTreeDepth = 128
    private const val MaxVisitedNodes = 4_096

    private val EditorOnlyActions = setOf("set_text", "set_selection", "ime_enter")

    @Suppress("DEPRECATION")
    private val ActionNames = mapOf(
        AccessibilityNodeInfo.ACTION_CLICK to "click", AccessibilityNodeInfo.ACTION_LONG_CLICK to "long_click",
        AccessibilityNodeInfo.ACTION_SET_TEXT to "set_text", AccessibilityNodeInfo.ACTION_SET_SELECTION to "set_selection",
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "scroll_forward", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "scroll_backward",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id to "scroll_up",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id to "scroll_down",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id to "scroll_left",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id to "scroll_right",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id to "show_on_screen",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id to "set_progress"
    ) + if (android.os.Build.VERSION.SDK_INT >= 30) mapOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id to "ime_enter") else emptyMap()
}
