package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContextToolsTest {
    private val body = "a".repeat(3_999) + "😀" + "许可编号北岸-4729"
    private val rows = listOf(
        Message(id = "local", conversationId = "chat", role = MessageRole.User, content = body,
            reasoningContent = "PRIVATE_REASONING", attachmentText = "ATTACHMENT", executionContext = "TOOL_RESULT"),
        Message(id = "foreign", conversationId = "other", role = MessageRole.User, content = "OTHER_CHAT")
    )

    @Test fun pagesJoinToExactlyTheStoredBodyWithoutSplittingASupplementaryCharacter() {
        val first = record("""{"message_ids":["local"],"content_offset":0}""")
        assertEquals(0, first.getValue("content_offset").jsonPrimitive.int)
        assertEquals(body.length, first.getValue("content_length").jsonPrimitive.int)
        val next = first.getValue("next_content_offset").jsonPrimitive.int
        assertEquals(3_999, next)
        val second = record("""{"message_ids":["local"],"content_offset":$next}""")
        assertEquals(next, second.getValue("content_offset").jsonPrimitive.int)
        assertEquals(body, first.getValue("content").jsonPrimitive.content + second.getValue("content").jsonPrimitive.content)
        assertEquals(JsonNull, second.getValue("next_content_offset"))
        assertFalse(first.toString().contains("PRIVATE_REASONING") || second.toString().contains("PRIVATE_REASONING"))
    }

    @Test fun excerptsRemainTheDefaultAndShowTheActualLengthOfClippedContent() {
        val excerpt = record("""{"message_ids":["local"]}""")
        assertTrue(excerpt.getValue("content").jsonPrimitive.content.contains("middle omitted"))
        assertEquals(body.length, excerpt.getValue("content_length").jsonPrimitive.int)
        assertEquals("ATTACHMENT", excerpt.getValue("attachment_excerpt").jsonPrimitive.content)
        assertEquals("TOOL_RESULT", excerpt.getValue("tool_evidence").jsonPrimitive.content)
        assertFalse("next_content_offset" in excerpt)
    }

    @Test fun pagingRejectsForeignRecordsAndInvalidOffsets() {
        val foreign = record("""{"message_ids":["foreign"],"content_offset":0}""")
        assertEquals("not_found_in_this_conversation", foreign.getValue("error").jsonPrimitive.content)
        assertFalse("content" in foreign)
        for (offset in listOf(4_000, body.length + 1, Int.MAX_VALUE)) {
            assertEquals("invalid_content_offset", record("""{"message_ids":["local"],"content_offset":$offset}""")
                .getValue("error").jsonPrimitive.content)
        }
        for (arguments in listOf("""{"message_ids":["local","foreign"],"content_offset":0}""",
            """{"message_ids":["local"],"content_offset":-1}""",
            """{"message_ids":["local"],"content_offset":"0"}""")) {
            assertTrue(TaskContextTools.read(arguments, "chat", rows).startsWith("failed:"))
        }
    }

    private fun record(arguments: String) =
        Json.parseToJsonElement(TaskContextTools.read(arguments, "chat", rows)).jsonObject
            .getValue("records").jsonArray.single().jsonObject
}
