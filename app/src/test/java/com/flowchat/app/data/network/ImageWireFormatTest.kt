package com.flowchat.app.data.network

import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.chat.ChatRequestFactory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImageWireFormatTest {
    private val json = Json { explicitNulls = false }
    private val image = ChatImage("image/png", "iVBORw0KGgo=")
    private fun request(model: String, text: String = "Read this") = ChatRequest(model,
        listOf(ChatRequestMessage("user", text, images = listOf(image))), 0.7, 1.0, 256, enableThinking = false)
    private fun provider(host: String, model: String) = ProviderConfig(displayName = "Test", baseUrl = "https://$host/v1", defaultModel = model)

    @Test fun chatCompletionsCarriesTextAndImageBytesAndSupportsImageOnly() {
        val p = provider("api.deepseek.com", "deepseek-flash")
        val parts = request(p.defaultModel).toWireBody(p, true, json)["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("Read this", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(image.dataUrl, parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val onlyImage = request(p.defaultModel, "").toWireBody(p, true, json)["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(1, onlyImage.size)
        assertEquals("image_url", onlyImage.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun responsesCarriesInputImageAndKeepsItDuringToolContinuation() {
        val p = provider("api.openai.com", "gpt-6-astra")
        val r = request(p.defaultModel).let { it.copy(messages = it.messages + listOf(
            ChatRequestMessage("assistant", providerData = buildJsonArray { add(buildJsonObject { put("type", "reasoning"); put("encrypted_content", "opaque") }) }),
            ChatRequestMessage("tool", "done", toolCallId = "call_1"))) }
        val input = r.toWireBody(p, true, json)["input"]!!.jsonArray
        val parts = input[0].jsonObject["content"]!!.jsonArray
        assertEquals("input_text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(image.dataUrl, parts[1].jsonObject["image_url"]!!.jsonPrimitive.content)
        assertEquals("opaque", input[1].jsonObject["encrypted_content"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicUsesBase64SourceAndCombinesToolResultWithoutLosingImage() {
        val p = provider("api.anthropic.com", "claude-sonnet-4-6")
        val r = request(p.defaultModel).let { it.copy(messages = it.messages + ChatRequestMessage("tool", "done", toolCallId = "call_1")) }
        val parts = r.toWireBody(p, true, json)["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val source = parts.first().jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals(image.mimeType, source["media_type"]!!.jsonPrimitive.content)
        assertEquals(image.base64, source["data"]!!.jsonPrimitive.content)
        assertEquals("tool_result", parts.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun plainTextRetainsStringWireFormat() {
        val p = provider("example.test", "test")
        val r = request("test").copy(messages = listOf(ChatRequestMessage("user", "hello")))
        assertEquals(JsonPrimitive("hello"), r.toWireBody(p, true, json)["messages"]!!.jsonArray.single().jsonObject["content"])
    }

    @Test fun imageOnlyHistorySurvivesFollowUpAndOldImagesDoNotRequireLoading() {
        val conversation = Conversation(id = "c", providerId = "p", modelName = "vision")
        val photo = Message(conversationId = "c", role = MessageRole.User, content = "", attachmentImagePath = "image1")
        val history = listOf(photo, Message(conversationId = "c", role = MessageRole.Assistant, content = "A card"),
            Message(conversationId = "c", role = MessageRole.User, content = "What color?"))
        val r = ChatRequestFactory.create(conversation, history, imageAttachments = mapOf("image1" to image))
        assertEquals(image, r.messages.first { it.role == "user" }.images.single())
        val recent = (1..6).map { Message(conversationId = "c", role = MessageRole.User, content = "Turn $it") }
        assertEquals(7, ChatRequestFactory.create(conversation, listOf(photo) + recent).messages.count { it.role == "user" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingImageFailsInsteadOfSilentlySendingOnlyTheCaption() {
        ChatRequestFactory.create(Conversation(id = "c", providerId = "p", modelName = "vision"), listOf(
            Message(conversationId = "c", role = MessageRole.User, content = "Look", attachmentImagePath = "missing")))
    }
}
