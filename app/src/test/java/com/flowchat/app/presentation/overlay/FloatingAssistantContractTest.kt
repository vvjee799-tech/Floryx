package com.flowchat.app.presentation.overlay

import android.graphics.Rect
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class FloatingAssistantContractTest {
    @Test
    fun manifestDeclaresOverlayAndSpecialUseForegroundService() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains(".presentation.overlay.FloatingAssistantService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
    }

    @Test
    fun floatingBallSettingOwnsFloatingAssistantLifecycle() {
        val screen = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val activity = File("src/main/java/com/flowchat/app/MainActivity.kt").readText()
        val manager = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantManager.kt"
        ).readText()

        assertTrue(screen.contains("appSettings.floatingBallEnabled"))
        assertTrue(manager.contains("settingsStore.state.value.floatingBallEnabled"))
        assertTrue(activity.contains("settings.floatingBallEnabled"))
        assertTrue(activity.contains("floatingAssistantManager.onAppForeground()"))
        assertTrue(activity.contains("floatingAssistantManager.onAppBackground()"))
    }

    @Test
    fun floatingBubbleSupportsFree2dDragWithoutVerticalLock() {
        val service = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantService.kt"
        ).readText()

        assertTrue(service.contains("Gravity.START"))
        assertTrue(service.contains("PositionXKey"))
        assertTrue(service.contains("PositionYKey"))
        assertTrue(service.contains("DraggableOverlayContainer"))
        assertFalse(service.contains("Orientation.Vertical"))
        assertFalse(service.contains("detectDragGestures"))
    }

    @Test
    fun expandedPanelLetsLongAssistantMessagesScroll() {
        val service = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantService.kt"
        ).readText()
        val panelStart = service.indexOf("internal fun ExpandedAssistantPanel(")
        val panelEnd = service.indexOf("private fun WatchingAssistantControls(", panelStart)
        val panel = service.substring(panelStart, panelEnd)

        assertTrue(panel.contains("val messageScrollState = rememberScrollState()"))
        assertTrue(panel.contains(".heightIn(min = 112.dp, max = 208.dp)"))
        assertTrue(panel.contains(".verticalScroll(messageScrollState)"))
        assertTrue(panel.contains("messageScrollState.scrollTo(messageScrollState.maxValue)"))
        assertFalse(panel.contains("messageScrollState.animateScrollTo(messageScrollState.maxValue)"))
        assertTrue(!panel.contains("maxLines = 2"))
    }

    @Test
    fun expandedPanelIsAReadableMinimalReplyComposerThatStaysOpenAfterSend() {
        val service = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantService.kt"
        ).readText()
        val panelStart = service.indexOf("internal fun ExpandedAssistantPanel(")
        val panelEnd = service.indexOf("private fun WatchingAssistantControls(", panelStart)
        val panel = service.substring(panelStart, panelEnd)
        val sendHandlerStart = service.indexOf("onSend = { text ->")
        val sendHandlerEnd = service.indexOf("},", sendHandlerStart)
        val sendHandler = service.substring(sendHandlerStart, sendHandlerEnd)

        assertTrue(panel.contains("screenWidthDp - 32"))
        assertTrue(panel.contains("coerceIn(1, 360)"))
        assertTrue(panel.contains(".shadow(4.dp, RoundedCornerShape(20.dp))"))
        assertTrue(panel.contains("color = MaterialTheme.colorScheme.surface,"))
        assertTrue(panel.contains("state.assistantText.ifBlank"))
        assertTrue(panel.contains("OverlayComposer("))
        assertFalse(panel.contains("AssistantAvatar("))
        assertFalse(panel.contains("state.assistantName"))
        assertFalse(panel.contains("overlayHeaderStatus(state)"))
        assertFalse(panel.contains("overlayLiveStatus(state)"))
        assertFalse(service.contains("Icons.Rounded.OpenInFull"))
        assertFalse(service.contains("onOpenFlowChat"))
        assertFalse(sendHandler.contains("updateExpanded(false)"))
    }

    @Test
    fun overlayCrossfadesWithoutAnimatingWindowSizeOrScale() {
        val service = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantService.kt"
        ).readText()
        val overlayStart = service.indexOf("private fun FloatingAssistantOverlay(")
        val overlayEnd = service.indexOf("private fun FloatingAssistantBubble(", overlayStart)
        val overlay = service.substring(overlayStart, overlayEnd)

        assertTrue(overlay.contains("Crossfade("))
        assertTrue(overlay.contains("animationSpec = tween(140, easing = FastOutSlowInEasing)"))
        assertFalse(overlay.contains("AnimatedContent("))
        assertFalse(overlay.contains("SizeTransform("))
        assertFalse(overlay.contains("scaleIn("))
        assertFalse(overlay.contains("scaleOut("))
    }

    @Test
    fun idleFloatingBubbleDoesNotRunAnInfiniteAnimation() {
        val service = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantService.kt"
        ).readText().replace("\r\n", "\n")
        val bubbleStart = service.indexOf("private fun FloatingAssistantBubble(")
        val bubbleEnd = service.indexOf("internal fun ExpandedAssistantPanel(", bubbleStart)
        val bubble = service.substring(bubbleStart, bubbleEnd)

        assertTrue(bubble.contains("val pulse = if (isStreaming)"))
        assertTrue(bubble.contains("rememberInfiniteTransition(label = \"assistant-activity\")"))
        assertTrue(bubble.contains("else {\n        0.58f\n    }"))
    }
}

@RunWith(RobolectricTestRunner::class)
class FloatingAssistantLayoutTest {
    @Test
    fun clampCapsulePositionAllowsFullScreenEdgesAndBottomRange() {
        val safeBounds = Rect(0, 80, 1080, 2280)
        val width = 96
        val height = 162

        assertEquals(0 to 80, clampCapsulePosition(-50, 10, width, height, safeBounds))
        assertEquals((1080 - width) to (2280 - height), clampCapsulePosition(1200, 2400, width, height, safeBounds))
        assertEquals(500 to 1900, clampCapsulePosition(500, 1900, width, height, safeBounds))
    }

    @Test
    fun computeExpandedOverlayPositionsKeepsPanelAndControlsInsideScreenBounds() {
        val safeBounds = Rect(0, 80, 1080, 2280)
        val capsuleWidth = 96
        val panelWidth = 984
        val panelHeight = 720
        val controlsWidth = 420
        val controlsHeight = 144
        val margin = 24

        val fromBottomRight = computeExpandedOverlayPositions(
            anchorX = safeBounds.right - capsuleWidth,
            anchorY = safeBounds.bottom - 162,
            capsuleWidth = capsuleWidth,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            controlsWidth = controlsWidth,
            controlsHeight = controlsHeight,
            passive = true,
            marginPx = margin,
            safeBounds = safeBounds
        )
        assertTrue(fromBottomRight.panelX >= safeBounds.left + margin)
        assertTrue(fromBottomRight.panelX + panelWidth <= safeBounds.right - margin)
        assertTrue(fromBottomRight.controlsY >= safeBounds.top)
        assertTrue(fromBottomRight.panelY == fromBottomRight.controlsY + controlsHeight)
        assertTrue(fromBottomRight.panelY + panelHeight <= safeBounds.bottom)

        val fromBottomLeft = computeExpandedOverlayPositions(
            anchorX = safeBounds.left,
            anchorY = safeBounds.bottom - 162,
            capsuleWidth = capsuleWidth,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            controlsWidth = controlsWidth,
            controlsHeight = controlsHeight,
            passive = false,
            marginPx = margin,
            safeBounds = safeBounds
        )
        assertEquals(safeBounds.left + margin, fromBottomLeft.panelX)
        assertTrue(fromBottomLeft.panelY >= safeBounds.top)
        assertTrue(fromBottomLeft.panelY + panelHeight <= safeBounds.bottom)
    }
}
