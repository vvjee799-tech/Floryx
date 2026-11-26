package com.flowchat.app.domain.chat

import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.data.network.toWireBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.*
import org.junit.Test

class ScreenObservationContextTest {
    private val geometry = DeviceScreenGeometry(DeviceBounds(0, 0, 1000, 2000), 1000, 2000, 0)
    private fun screen(number: Int, display: Int = 0) = DeviceScreenSnapshot("fixture", "Fixture", List(160) {
        DeviceUiElement(it, "android.widget.TextView", if (it == 0) "Page $number" else "Repeated control $it",
            null, null, DeviceBounds(0, it * 8, 1000, it * 8 + 8), false, false, false, true,
            supportedActions = listOf("set_selection", "show_on_screen"))
    }, screenId = "screen$number", truncated = true, geometry = geometry.copy(displayId = display))
    private fun result(number: Int) = DeviceToolResult(true, "Navigation observed, not a saved effect",
        actionOutcome = DeviceActionOutcome.ProgressObserved, screen = screen(number))
    private fun message(result: DeviceToolResult, id: String) = ChatRequestMessage("tool", result.asToolContent(), toolCallId = id,
        screenControls = ScreenObservationContext.capture(result, result.asToolContent(), id))
    private fun request(vararg messages: ChatRequestMessage) = ChatRequest("model", messages.toList(), 0.0, 1.0, null)

    @Test fun repeatedPassiveRowsAreReferencedAndLatestPreviewIsByteForByteUnchanged() {
        val old = message(result(0), "source0"); val current = message(result(1), "source1")
        val projected = ScreenObservationContext.referenceRepeatedControls(request(old, current))
        val content = requireNotNull(projected.messages[0].content)
        assertTrue(content.length < old.content!!.length / 3)
        assertTrue(content.contains("159 identical passive control rows"))
        assertTrue(content.contains("control_indices=1..159"))
        assertTrue(content.contains("Page 0")); assertTrue(content.contains("screen_id=screen0"))
        assertTrue(content.contains("source_message_id=source1")); assertTrue(content.contains("message_ids=[\"source0\"]"))
        assertTrue(content.contains("partial observation")); assertTrue(content.contains("action_outcome=ProgressObserved"))
        assertFalse(content.contains("[159]"))
        assertEquals(current, projected.messages[1])
        assertEquals(old.content, old.screenControls?.originalContent)
    }

    @Test fun reprojectionRebuildsReferencesAfterTailChangesWithoutDanglingOrChainedReferences() {
        val originals = (0..3).map { message(result(it), "source$it") }
        val projected = ScreenObservationContext.referenceRepeatedControls(request(*originals.toTypedArray()))
        assertTrue(projected.messages.take(3).all { it.content!!.contains("source_message_id=source3") })
        val shortened = ScreenObservationContext.referenceRepeatedControls(projected.copy(messages = projected.messages.take(2)))
        assertEquals(originals[1].content, shortened.messages[1].content)
        assertTrue(shortened.messages[0].content!!.contains("source_message_id=source1"))
        assertFalse(shortened.messages.any { it.content!!.contains("source_message_id=source3") })
        assertEquals(projected, ScreenObservationContext.referenceRepeatedControls(projected))
    }

    @Test fun actionsValuesAndChangedRowsRemainInTheirOwnHistoricalObservation() {
        val original = screen(0)
        val old = original.copy(elements = original.elements.mapIndexed { i, e -> when (i) {
            10 -> e.copy(text = "Original value")
            11 -> e.copy(clickable = true)
            12 -> e.copy(checked = true)
            13 -> e.copy(editable = true)
            else -> e
        } })
        val current = old.copy(screenId = "new", elements = old.elements.mapIndexed { i, e ->
            if (i == 10) e.copy(text = "Changed value") else e })
        val projected = ScreenObservationContext.referenceRepeatedControls(request(
            message(result(0).copy(screen = old), "old"), message(result(1).copy(screen = current), "new")))
        for (index in listOf(10, 11, 12, 13)) assertTrue(projected.messages[0].content!!.contains(old.elements[index].asModelLine()))
        assertTrue(projected.messages[0].content!!.contains("control_indices=0..9,14..159"))
    }

    @Test fun unrelatedScopesPackagesWindowsAndDisplaysCannotBeReferenceSources() {
        val original = screen(0)
        val otherScreens = listOf(original.copy(packageName = "other"), original.copy(activityName = "Other"),
            original.copy(windowId = 9), screen(1, display = 7),
            original.copy(observationScope = DeviceObservationScope(DeviceUiSelector(description = "pane"))),
            original.copy(geometry = geometry.copy(rotation = 1)))
        val old = message(result(0), "old")
        for (other in otherScreens) {
            val projected = ScreenObservationContext.referenceRepeatedControls(request(old, message(result(1).copy(screen = other), "new")))
            assertEquals(old.content, projected.messages[0].content)
        }
    }

    @Test fun failedUnknownSensitiveAndImageErrorsStayWhole() {
        val original = result(0)
        for (unsafe in listOf(original.copy(success = false), original.copy(errorCode = "unknown"),
            original.copy(actionOutcome = DeviceActionOutcome.Unverified), original.copy(actionOutcome = DeviceActionOutcome.NotDispatched),
            original.copy(screen = screen(0).copy(sensitive = true)),
            original.copy(screen = screen(0).copy(observationErrorCode = "root_not_found")),
            original.copy(imageErrorCode = "secure_window_blocked"))) {
            val old = message(unsafe, "old")
            assertNull(old.screenControls)
            assertEquals(old, ScreenObservationContext.referenceRepeatedControls(request(old, message(result(1), "new"))).messages[0])
        }
    }

    @Test fun repeatedTextProjectionPreservesTheTwoRecentFramesAndTheOmittedPixelsNotice() {
        val image = ChatImage("image/png", "PRIVATE_PIXELS")
        val originals = (0..3).map { number ->
            val value = result(number).copy(screenImage = DeviceScreenImage(image, "image$number", 1000, 2000, 0))
            message(value, "source$number").copy(images = listOf(image), screenImageId = "image$number")
        }
        fun project(request: ChatRequest) = ScreenObservationContext.referenceRepeatedControls(ScreenVisionContext.retainRecent(request))
        val projected = project(request(*originals.toTypedArray()))
        assertTrue(projected.messages[0].content!!.contains("identical passive control rows"))
        assertTrue(projected.messages[0].content!!.endsWith(ScreenVisionContext.OmittedImageNotice))
        assertTrue(projected.messages[0].images.isEmpty()); assertNull(projected.messages[0].screenImageId)
        assertEquals(listOf("image2", "image3"), projected.messages.mapNotNull { it.screenImageId })
        assertEquals(originals[3], projected.messages[3])
        assertEquals(projected, project(projected))
        assertFalse(projected.messages.any { it.content!!.contains(image.base64) })
    }

    @Test fun nativeGroupsOpaqueReplayAndUserMessagesAreUntouchedAndSmallChangesDoNotGrow() {
        val old = message(result(0), "old"); val current = message(result(1), "new")
        val call = ChatToolCall("new", "observe_screen", "{}")
        val assistant = ChatRequestMessage("assistant", "Continue", toolCalls = listOf(call),
            reasoningContent = "opaque reasoning", providerData = buildJsonArray { add("opaque replay") })
        val user = ChatRequestMessage("user", old.content)
        val projected = ScreenObservationContext.referenceRepeatedControls(request(user, old, assistant, current))
        assertEquals(user, projected.messages[0]); assertEquals(assistant, projected.messages[2])
        assertEquals("old", projected.messages[1].toolCallId); assertEquals("new", projected.messages[3].toolCallId)
        val small = result(0).copy(screen = screen(0).copy(elements = screen(0).elements.take(2)))
        val smallMessage = message(small, "small")
        assertEquals(smallMessage, ScreenObservationContext.referenceRepeatedControls(request(smallMessage, current)).messages[0])
    }

    @Test fun localProjectionSourcesAreNotSerializedIntoProviderPayloads() {
        val tool = message(result(0), "LOCAL_SOURCE_ONLY").copy(toolCallId = "call0")
        val provider = ProviderConfig(displayName = "QA", baseUrl = "https://example.test", defaultModel = "model")
        val wire = request(tool).toWireBody(provider, stream = true, json = Json).toString()
        assertFalse(wire.contains("LOCAL_SOURCE_ONLY"))
        assertFalse(wire.contains("screenControls")); assertFalse(wire.contains("scopeKey"))
        assertTrue(wire.contains("Repeated control 159"))
    }
}
