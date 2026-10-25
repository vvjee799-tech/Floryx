package com.flowchat.app.domain.chat

import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.provider.ScreenVisionSupport
import org.junit.Assert.*
import org.junit.Test

class ScreenVisionContextTest {
    private val image = ChatImage("image/jpeg", "TEST_SCREEN_IMAGE_BYTES")

    @Test fun oldScreensArePrunedAndUserAttachmentsRemainAvailable() {
        val messages = listOf(ChatRequestMessage("user", "photo", images = listOf(image))) + (1..4).map {
            ChatRequestMessage("tool", "frame $it", toolCallId = "call$it", images = listOf(image), screenImageId = "screen$it")
        }
        val result = ScreenVisionContext.retainRecent(ChatRequest("vision", messages, 0.0, 1.0, 100))
        assertEquals(listOf("screen3", "screen4"), result.messages.filter { it.screenImageId != null }.map { it.screenImageId })
        assertEquals(1, result.messages.first().images.size)
        assertTrue(result.messages[1].content!!.contains("pixels are unavailable"))
        assertEquals(listOf("call1", "call2", "call3", "call4"), result.messages.drop(1).map { it.toolCallId })
    }

    @Test fun persistentToolEvidenceNeverContainsScreenshotBytes() {
        val screen = DeviceScreenSnapshot("fixture", "Fixture", emptyList())
        val frame = DeviceScreenImage(image, screen.screenId, 400, 800, 123)
        val content = DeviceToolResult(true, "captured", screen = screen, screenImage = frame).asToolContent()
        assertTrue(content.contains("image_available=true"))
        assertTrue(content.contains(screen.screenId))
        assertFalse(content.contains(image.base64))
        assertFalse(frame.toString().contains(image.base64))
        assertTrue(DeviceToolResult(true, "controls", screen = screen, imageErrorCode = "secure_window_blocked").asToolContent()
            .contains("Do not claim to have seen"))
    }

    @Test fun knownTextModelsAreBlockedWithoutAssumingCustomProviderCapabilities() {
        val deepseek = ProviderConfig(displayName = "DeepSeek", baseUrl = "https://api.deepseek.com/v1", defaultModel = "deepseek-pro")
        assertTrue(ScreenVisionSupport.knownTextOnly(deepseek, "deepseek-v4-pro"))
        assertFalse(ScreenVisionSupport.knownTextOnly(deepseek, "deepseek-flash"))
        assertFalse(ScreenVisionSupport.knownTextOnly(deepseek, "deepseek-v4-flash"))
        assertFalse(ScreenVisionSupport.knownTextOnly(deepseek.copy(baseUrl = "https://custom.example/v1"), "custom-vision"))
    }
}
