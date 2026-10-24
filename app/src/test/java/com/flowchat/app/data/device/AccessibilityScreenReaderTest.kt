package com.flowchat.app.data.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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

    @Test fun readsInputAndActionsBelowTheRecordedChatGptDepth() {
        val root = node()
        var parent = root
        repeat(44) { parent = child(parent, node()) }
        child(parent, node().apply { isEditable = true; hintText = "Ask" })
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
        assertTrue(result.snapshot.asToolContent().contains("partial observation"))
    }

    @Test fun wideEmptyTreesAreBoundedByVisitedNodes() {
        val root = node()
        repeat(4_200) { child(root, node()) }
        val result = AccessibilityScreenReader.read(root, "Wide", viewport)
        assertTrue(result.snapshot.truncated)
        assertTrue(result.indexedNodes.isEmpty())
    }
}
