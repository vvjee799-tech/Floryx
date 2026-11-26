package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationExporterTest {
    @Test fun exportsOriginalHistoryAndUsesAnUnambiguousAttachmentFence() {
        val conversation = Conversation(id = "c", title = "项目记录", providerId = "p", modelName = "local")
        val original = "## Result\n```kotlin\nval x = 1\n```"
        val file = "before\n````\nafter"
        val result = ConversationExporter.markdown(conversation, listOf(
            Message(conversationId = "c", role = MessageRole.User, content = "read file", attachmentName = "note.md", attachmentText = file),
            Message(conversationId = "c", role = MessageRole.Assistant, content = original, reasoningContent = "evidence", status = MessageStatus.Stopped)
        ))
        assertTrue(result.contains(original))
        assertTrue(result.contains("`````text\n$file\n`````"))
        assertTrue(result.contains("Status: Stopped"))
        assertTrue(result.contains("evidence"))
        assertEquals("报告_测试_2026.md", ConversationExporter.fileName("报告/测试:2026"))
    }
}
