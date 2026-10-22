package com.flowchat.app.presentation.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun powerModeOwnsFloatingAssistantLifecycle() {
        val screen = File("src/main/java/com/flowchat/app/presentation/chat/ChatScreen.kt").readText()
        val activity = File("src/main/java/com/flowchat/app/MainActivity.kt").readText()
        val manager = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantManager.kt"
        ).readText()

        assertTrue(screen.contains("appSettings.powerModeEnabled"))
        assertTrue(manager.contains("settingsStore.state.value.powerModeEnabled"))
        assertTrue(activity.contains("floatingAssistantManager.onAppForeground()"))
        assertTrue(activity.contains("floatingAssistantManager.onAppBackground()"))
    }

    @Test
    fun floatingBubbleKeepsTapAndVerticalDragAsIndependentGestures() {
        val service = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantService.kt"
        ).readText()

        assertTrue(service.contains("rememberDraggableState"))
        assertTrue(service.contains("Orientation.Vertical"))
        assertTrue(!service.contains("detectDragGestures"))
    }

    @Test
    fun expandedPanelLetsLongAssistantMessagesScroll() {
        val service = File(
            "src/main/java/com/flowchat/app/presentation/overlay/FloatingAssistantService.kt"
        ).readText()
        val panelStart = service.indexOf("private fun ExpandedAssistantPanel(")
        val panelEnd = service.indexOf("private fun FloatingAssistantBubble(", panelStart)
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
        val panelStart = service.indexOf("private fun ExpandedAssistantPanel(")
        val panelEnd = service.indexOf("private fun FloatingAssistantBubble(", panelStart)
        val panel = service.substring(panelStart, panelEnd)
        val sendHandlerStart = service.indexOf("onSend = { text ->")
        val sendHandlerEnd = service.indexOf("},", sendHandlerStart)
        val sendHandler = service.substring(sendHandlerStart, sendHandlerEnd)

        assertTrue(panel.contains(".width(272.dp)"))
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
        val bubbleEnd = service.indexOf("private fun ExpandedAssistantPanel(", bubbleStart)
        val bubble = service.substring(bubbleStart, bubbleEnd)

        assertTrue(bubble.contains("val pulse = if (isStreaming)"))
        assertTrue(bubble.contains("rememberInfiniteTransition(label = \"assistant-activity\")"))
        assertTrue(bubble.contains("else {\n        0.58f\n    }"))
    }
}
