package com.flowchat.app.data.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.domain.device.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Suppress("DEPRECATION")
class AccessibilityScreenReaderTest {
    @Test fun globalWindowDigestIsQueryIndependentAndSeesChangesBeyondTheUnchanged160ControlPreview() {
        val root = node()
        repeat(180) { child(root, node("Outside $it")) }
        val group = child(root, node().apply { viewIdResourceName = "fixture:id/group" })
        val goal = child(group, node("Goal A").apply { isClickable = true })
        val scope = DeviceObservationScope(DeviceUiSelector(resourceId = "fixture:id/group"))
        val full = AccessibilityScreenReader.read(root, "Wide", viewport).snapshot
        val directed = AccessibilityScreenReader.read(root, "Wide", viewport, DeviceUiSelector(text = "Goal A")).snapshot
        val pane = AccessibilityScreenReader.read(root, "Wide", viewport, scope = scope).snapshot
        val missing = AccessibilityScreenReader.read(root, "Wide", viewport, scope = DeviceObservationScope(DeviceUiSelector(text = "Missing"))).snapshot
        assertTrue(full.truncated); assertEquals(160, full.elements.size); assertTrue(full.searchComplete)
        assertNotNull(full.windowContentSha256)
        assertEquals(full.windowContentSha256, directed.windowContentSha256)
        assertEquals(full.windowContentSha256, pane.windowContentSha256)
        assertEquals(full.windowContentSha256, missing.windowContentSha256)
        assertEquals("observation_root_not_found", missing.observationErrorCode)
        goal.text = "Goal B"
        val changed = AccessibilityScreenReader.read(root, "Wide", viewport).snapshot
        assertEquals(full.elements, changed.elements)
        assertNotEquals(full.windowContentSha256, changed.windowContentSha256)
    }

    private val viewport = Rect(0, 0, 1260, 2800)

    private fun node(text: String? = null, bounds: Rect = viewport) = AccessibilityNodeInfo.obtain().apply {
        packageName = "fixture.app"
        className = "android.view.View"
        isVisibleToUser = true
        isEnabled = true
        this.text = text
        setBoundsInScreen(bounds)
    }

    private fun child(parent: AccessibilityNodeInfo, next: AccessibilityNodeInfo): AccessibilityNodeInfo {
        shadowOf(parent).addChild(next)
        return next
    }

    @Test fun documentOptInReadsOffscreenTextWithoutExposingOffscreenActionIndices() {
        val root = node()
        val web = child(root, node().apply { className = "android.webkit.WebView"; isScrollable = true })
        child(web, node("Visible beginning"))
        val ending = "Long ending " + "body ".repeat(40) + "complete"
        child(web, node(ending, Rect(0, 5000, 1000, 5200)).apply { isClickable = true; isVisibleToUser = false })
        val normal = AccessibilityScreenReader.read(root, "Article", viewport)
        val read = AccessibilityScreenReader.read(root, "Article", viewport, includeDocumentText = true)
        val document = requireNotNull(read.snapshot.documentText)
        assertNull(normal.snapshot.documentText)
        assertEquals(normal.snapshot.elements, read.snapshot.elements)
        assertEquals(normal.indexedNodes.keys, read.indexedNodes.keys)
        assertFalse(read.snapshot.elements.any { it.text == ending })
        assertTrue(document.text.contains(ending))
        assertEquals(1, document.offscreenTextNodes)
        assertTrue(document.treeComplete); assertFalse(document.truncated)
        assertTrue(read.snapshot.asToolContent().contains("document_read_range_utf16=0:${document.text.length}"))
    }

    @Test fun documentReadsExcludeEditorsAndFailClosedForOffscreenPasswordsAndAmbiguousRoots() {
        val root = node()
        val web = child(root, node().apply { className = "android.webkit.WebView" })
        child(web, node("Public body"))
        val editor = child(web, node("Private input").apply { isEditable = true; isVisibleToUser = false })
        child(editor, node("Editor descendant").apply { isVisibleToUser = false })
        val good = AccessibilityScreenReader.read(root, "Article", viewport, includeDocumentText = true).snapshot.documentText!!
        assertTrue(good.text.contains("Public body")); assertFalse(good.text.contains("Private")); assertFalse(good.text.contains("descendant"))
        editor.isPassword = true
        val blocked = AccessibilityScreenReader.read(root, "Article", viewport, includeDocumentText = true).snapshot.documentText!!
        assertEquals("document_sensitive_content", blocked.errorCode); assertTrue(blocked.text.isEmpty())
        editor.isPassword = false
        child(root, node().apply { className = "android.webkit.WebView" })
        val ambiguous = AccessibilityScreenReader.read(root, "Article", viewport, includeDocumentText = true).snapshot.documentText!!
        assertEquals("document_root_ambiguous", ambiguous.errorCode); assertTrue(ambiguous.text.isEmpty())
    }

    @Test fun documentTextAndTreeLimitsRemainExplicitAndScopedReadsNeverFallBackToTheWholeDocument() {
        val root = node()
        val web = child(root, node().apply { className = "android.webkit.WebView"; viewIdResourceName = "fixture:id/body" })
        child(web, node("x".repeat(26_000)).apply { isVisibleToUser = false })
        val document = AccessibilityScreenReader.read(root, "Article", viewport, includeDocumentText = true).snapshot.documentText!!
        assertEquals(24_000, document.text.length); assertTrue(document.truncated); assertTrue(document.treeComplete)
        assertEquals(26_001L, document.totalChars)
        val scoped = AccessibilityScreenReader.read(root, "Article", viewport,
            scope = DeviceObservationScope(DeviceUiSelector(resourceId = "fixture:id/body")), includeDocumentText = true).snapshot.documentText!!
        assertEquals("document_scope_unsupported", scoped.errorCode); assertTrue(scoped.text.isEmpty())
        repeat(4_200) { child(web, node()) }
        assertFalse(AccessibilityScreenReader.read(root, "Article", viewport, includeDocumentText = true).snapshot.documentText!!.treeComplete)
    }

    @Test fun readsInputAndActionsBelowTheRecordedChatGptDepth() {
        val root = node()
        var parent = root
        repeat(44) { parent = child(parent, node()) }
        child(parent, node().apply { isFocusable = true; isEditable = true; hintText = "Ask" })
        child(parent, node("Send").apply { isClickable = true })
        val result = AccessibilityScreenReader.read(root, "Chat", viewport)
        assertTrue(result.snapshot.elements.any { it.editable && it.hint == "Ask" })
        assertTrue(result.snapshot.elements.any { it.clickable && it.text == "Send" })
        assertEquals(2, result.indexedNodes.size)
        assertFalse(result.snapshot.truncated)
    }

    @Test fun excludesTranslatedOffscreenTargetsAndClipsPartiallyVisibleBounds() {
        val root = node()
        child(root, node("Proxy", Rect(1412, 796, 2457, 1093)).apply { isClickable = true })
        child(root, node("Delay test", Rect(2188, 171, 2293, 276)).apply { isClickable = true })
        child(root, node("Visible", Rect(-20, 100, 200, 200)).apply { isClickable = true })
        val result = AccessibilityScreenReader.read(root, "Clash", viewport)
        assertEquals(listOf("Visible"), result.snapshot.elements.map { it.text })
        assertEquals(0, result.snapshot.elements.single().bounds.left)
        assertEquals(setOf(0), result.indexedNodes.keys)
    }

    @Test fun sensitiveInputsBeyondTheOutputLimitStillBlockTheScreen() {
        val root = node()
        repeat(160) { child(root, node("Label $it")) }
        child(root, node("Private").apply { isEditable = true; isPassword = true })
        val result = AccessibilityScreenReader.read(root, "Form", viewport)
        assertTrue(result.snapshot.sensitive)
        assertNull(result.snapshot.windowContentSha256)
        assertTrue(result.snapshot.elements.isEmpty())
        assertTrue(result.indexedNodes.isEmpty())
    }

    @Test fun excessiveDepthIsBoundedAndReportedInsteadOfClaimingACompleteScreen() {
        val root = node()
        var parent = root
        repeat(140) { parent = child(parent, node()) }
        child(parent, node("Beyond limit"))
        val result = AccessibilityScreenReader.read(root, "Deep", viewport)
        assertTrue(result.snapshot.truncated)
        assertNull(result.snapshot.windowContentSha256)
        assertTrue(result.snapshot.asToolContent().contains("partial observation"))
    }

    @Test fun wideEmptyTreesAreBoundedByVisitedNodes() {
        val root = node()
        repeat(4_200) { child(root, node()) }
        val result = AccessibilityScreenReader.read(root, "Wide", viewport)
        assertTrue(result.snapshot.truncated)
        assertTrue(result.indexedNodes.isEmpty())
    }

    @Test fun exactQueryReachesBeyond160WithoutLosingTheGlobalSensitiveGate() {
        val root = node()
        repeat(180) { child(root, node("Label $it")) }
        child(root, node("Target").apply { viewIdResourceName = "fixture:id/target"; isClickable = true })
        val selector = com.flowchat.app.domain.device.DeviceUiSelector(resourceId = "fixture:id/target")
        val result = AccessibilityScreenReader.read(root, "Wide", viewport, selector)
        assertEquals("Target", result.snapshot.elements.single().text)
        assertEquals(setOf(0), result.indexedNodes.keys)
        assertTrue(result.snapshot.searchComplete)
        child(root, node("Secret").apply { isEditable = true; isPassword = true })
        val blocked = AccessibilityScreenReader.read(root, "Wide", viewport, selector)
        assertTrue(blocked.snapshot.sensitive)
        assertTrue(blocked.snapshot.elements.isEmpty())
        assertTrue(blocked.indexedNodes.isEmpty())
    }

    @Test fun subtreeBeyondOutputLimitRetainsHierarchyAndExcludesSiblings() {
        val root = node()
        repeat(180) { child(root, node("Outside $it")) }
        val group = child(root, node().apply { viewIdResourceName = "fixture:id/group" })
        val nested = child(group, node("Nested").apply { isScrollable = true })
        child(nested, node("Inside").apply { isClickable = true; addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) })
        child(root, node("Other pane"))
        val scope = DeviceObservationScope(DeviceUiSelector(resourceId = "fixture:id/group"))
        val read = AccessibilityScreenReader.read(root, "Wide", viewport, scope = scope)
        assertNull(read.snapshot.observationErrorCode)
        assertEquals(listOf(null, "Nested", "Inside"), read.snapshot.elements.map { it.text })
        assertEquals(listOf(null, 0, 1), read.snapshot.elements.map { it.parentIndex })
        assertEquals(listOf(0, 1, 2), read.snapshot.elements.map { it.depth })
        assertEquals(listOf("click"), read.snapshot.elements.last().supportedActions)
        assertTrue(read.snapshot.searchComplete)
        assertEquals(3, read.indexedNodes.size)
    }

    @Test fun invalidOrUnresolvedRootsNeverFallBackOrIssueActionIndices() {
        val root = node()
        val group = child(root, node("One").apply { contentDescription = "group" })
        val scope = DeviceObservationScope(DeviceUiSelector(description = "group"), DeviceObservationScope.Kind.ScrollContainer)
        assertEquals("observation_root_not_scrollable", AccessibilityScreenReader.read(root, "", viewport, scope = scope).snapshot.observationErrorCode)
        group.isScrollable = true
        child(root, node("Duplicate").apply { contentDescription = "group"; isScrollable = true })
        val duplicate = AccessibilityScreenReader.read(root, "", viewport, scope = scope)
        assertEquals("observation_root_ambiguous", duplicate.snapshot.observationErrorCode)
        assertTrue(duplicate.snapshot.elements.isEmpty()); assertTrue(duplicate.indexedNodes.isEmpty())
        val missing = AccessibilityScreenReader.read(root, "", viewport, scope = DeviceObservationScope(DeviceUiSelector(text = "Missing")))
        assertEquals("observation_root_not_found", missing.snapshot.observationErrorCode)
        assertFalse(missing.snapshot.searchComplete)
        assertFalse(DeviceScreenCondition(element = DeviceUiSelector(text = "Anything"), state = DeviceElementState.Absent).matches(missing.snapshot))
    }

    @Test fun scopeStillChecksSensitiveSiblingsAndCannotCertifyIncompleteTraversal() {
        val root = node()
        child(root, node("Group").apply { contentDescription = "group" })
        val scope = DeviceObservationScope(DeviceUiSelector(description = "group"))
        child(root, node("Secret").apply { isPassword = true; isEditable = true })
        val sensitive = AccessibilityScreenReader.read(root, "", viewport, scope = scope)
        assertTrue(sensitive.snapshot.sensitive); assertTrue(sensitive.indexedNodes.isEmpty())
        val incompleteRoot = node()
        child(incompleteRoot, node("Group").apply { contentDescription = "group" })
        repeat(4200) { child(incompleteRoot, node()) }
        val incomplete = AccessibilityScreenReader.read(incompleteRoot, "", viewport, scope = scope)
        assertEquals("observation_scope_incomplete", incomplete.snapshot.observationErrorCode)
        assertNull(incomplete.snapshot.windowContentSha256)
        assertTrue(incomplete.snapshot.elements.isEmpty()); assertTrue(incomplete.indexedNodes.isEmpty())
    }

    @Test fun readsCollectionItemAndRangeSemanticsWithoutRequiringText() {
        val root = node()
        val list = child(root, node().apply { collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(20, 2, false, 1) })
        child(list, node("Cell").apply { collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(3, 1, 1, 1, true) })
        child(root, node().apply {
            rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, 100f, 42f)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        })
        val screen = AccessibilityScreenReader.read(root, "", viewport).snapshot
        assertEquals(20, screen.elements[0].collection!!.rows)
        assertEquals(3, screen.elements[1].collectionItem!!.row)
        assertEquals(0, screen.elements[1].parentIndex)
        assertEquals(42f, screen.elements[2].range!!.current)
        assertTrue(screen.elements[2].asModelLine().contains("set_progress"))
    }

    @Test fun clickableNonFocusableEditTextTriggerIsNotExposedAsDirectlyEditable() {
        val root = node()
        val searchBar = child(root, node("Search tips").apply {
            className = "android.widget.EditText"
            viewIdResourceName = "fixture:id/search_edit"
            isClickable = true
            isEditable = true
            isFocusable = false
            hintText = "Search tips"
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        })
        val read = AccessibilityScreenReader.read(root, "Home", viewport)
        val exposed = read.snapshot.elements.single()
        assertTrue(exposed.clickable)
        assertFalse(exposed.editable)
        assertEquals(listOf("click"), exposed.supportedActions)
        assertFalse(exposed.asModelLine().contains("editable"))
        assertFalse(exposed.asModelLine().contains("set_text"))
        assertEquals("ui_input_target_invalid", runCatching {
            DeviceTextEditing.plan(exposed, DeviceTextEditRequest(0, "clock", DeviceTextEditMode.Replace))
        }.exceptionOrNull()?.let { (it as DeviceTextEditRejected).code })

        searchBar.isFocusable = true
        searchBar.isFocused = true
        val active = AccessibilityScreenReader.read(root, "Search", viewport).snapshot.elements.single()
        assertTrue(active.editable)
        assertEquals(listOf("click", "set_selection", "set_text"), active.supportedActions)

        val sensitiveRoot = node()
        child(sensitiveRoot, node("Password").apply {
            isEditable = true; isFocusable = false; isPassword = true
        })
        assertTrue(AccessibilityScreenReader.read(sensitiveRoot, "Auth", viewport).snapshot.sensitive)
    }
}
