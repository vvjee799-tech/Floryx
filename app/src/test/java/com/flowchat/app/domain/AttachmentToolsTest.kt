package com.flowchat.app.domain

import com.flowchat.app.data.attachment.AttachmentDocumentParser
import com.flowchat.app.domain.chat.AttachmentTools
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentToolsTest {
    @Test
    fun pagesTheStoredChineseTextThroughItsTail() {
        val storedText = "资料".repeat(19_999) + "结束"
        val message = textMessage("m1", "c1", storedText)

        val first = result("""{"message_id":"m1","offset":6000,"limit":6000}""", "c1", listOf(message))
        assertEquals(storedText.substring(6000, 12_000), first["text"]!!.jsonPrimitive.content)
        assertEquals(12_000, first["next_offset"]!!.jsonPrimitive.content.toInt())
        assertTrue(first["has_more"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(40_000, first["total"]!!.jsonPrimitive.content.toInt())

        val tail = result("""{"message_id":"m1","offset":36000}""", "c1", listOf(message))
        assertEquals(storedText.substring(36_000), tail["text"]!!.jsonPrimitive.content)
        assertFalse(tail["has_more"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(JsonNull, tail["next_offset"])
        assertFalse(tail["source_truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun reportsWhenTheStoredSourceWasTruncatedAtExtraction() {
        val storedText = AttachmentDocumentParser.extract(
            "long.txt", "text/plain", "字".repeat(80_010).toByteArray()
        )
        val result = result("""{"message_id":"m1","offset":79995}""", "c1", listOf(textMessage("m1", "c1", storedText)))

        assertTrue(result["source_truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["text"]!!.jsonPrimitive.content.contains("<content-truncated />"))
        assertEquals(storedText.length, result["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun defaultsToLatestReadableAttachmentInThisConversation() {
        val messages = listOf(
            textMessage("old", "c1", "old text"),
            textMessage("foreign", "c2", "secret text"),
            textMessage("new", "c1", "new text"),
            Message(id = "image", conversationId = "c1", role = MessageRole.User,
                content = "image", attachmentImagePath = "/image.png")
        )

        val result = result("{}", "c1", messages)

        assertEquals("new", result["message_id"]!!.jsonPrimitive.content)
        assertEquals("new text", result["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun rejectsForeignIdsImagesAndMissingTextWithoutExposingReasoning() {
        val messages = listOf(
            textMessage("foreign", "c2", "private attachment"),
            Message(id = "image", conversationId = "c1", role = MessageRole.User,
                content = "image", attachmentImagePath = "/image.png"),
            Message(id = "empty", conversationId = "c1", role = MessageRole.User,
                content = "empty", attachmentName = "empty.txt", reasoningContent = "secret reasoning")
        )

        assertEquals("not_found_in_this_conversation", result("""{"message_id":"foreign"}""", "c1", messages)["error"]!!.jsonPrimitive.content)
        assertEquals("attachment_not_readable", result("""{"message_id":"image"}""", "c1", messages)["error"]!!.jsonPrimitive.content)
        val missing = AttachmentTools.read("""{"message_id":"empty"}""", "c1", messages)
        assertEquals("attachment_not_readable", Json.parseToJsonElement(missing).jsonObject["error"]!!.jsonPrimitive.content)
        assertFalse(missing.contains("secret reasoning"))
    }

    @Test
    fun rejectsInvalidPagingArguments() {
        val messages = listOf(textMessage("m1", "c1", "hello"))
        val cases = mapOf(
            "not json" to "invalid_arguments",
            "[]" to "invalid_arguments",
            """{"unexpected":1}""" to "invalid_arguments",
            """{"message_id":null}""" to "invalid_message_id",
            """{"offset":-1}""" to "invalid_offset",
            """{"offset":"1"}""" to "invalid_offset",
            """{"offset":5}""" to "invalid_offset",
            """{"limit":0}""" to "invalid_limit",
            """{"limit":6001}""" to "invalid_limit"
        )
        cases.forEach { (args, expectedError) ->
            assertEquals(args, expectedError, result(args, "c1", messages)["error"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun attachesReadOnlyToolDefinitionOnce() {
        val request = ChatRequest(model = "test", messages = emptyList(), temperature = 1.0, topP = 1.0, maxTokens = null)
        val attached = AttachmentTools.attach(AttachmentTools.attach(request))

        assertEquals(listOf(AttachmentTools.Read), attached.tools.map { it.name })
        assertEquals("auto", attached.toolChoice)
        assertEquals(false, attached.parallelToolCalls)
    }

    private fun result(arguments: String, conversationId: String, messages: List<Message>) =
        Json.parseToJsonElement(AttachmentTools.read(arguments, conversationId, messages)).jsonObject

    private fun textMessage(id: String, conversationId: String, text: String) = Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.User,
        content = "Read this attachment",
        attachmentName = "notes.txt",
        attachmentText = text
    )
}
