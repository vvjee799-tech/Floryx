package com.flowchat.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTitleTest {
    @Test
    fun createsCompactTitleFromFirstMessageOrAttachment() {
        assertEquals("今天适合去哪里玩？", ConversationTitle.from("今天适合去哪里玩？请结合天气回答", null))
        assertEquals("travel-notes.txt", ConversationTitle.from("", "travel-notes.txt"))
        assertEquals("新聊天", ConversationTitle.from("", null))
    }
}
