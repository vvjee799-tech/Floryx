package com.flowchat.app.domain.chat

object ConversationTitle {
    fun from(message: String, attachmentName: String?): String {
        val text = message.trim().replace(Regex("\\s+"), " ")
        if (text.isNotEmpty()) {
            val sentenceEnd = text.indexOfFirst { it in setOf('。', '！', '？', '.', '!', '?') }
            val sentence = if (sentenceEnd >= 0) text.take(sentenceEnd + 1) else text
            return sentence.take(MaxLength).trim()
        }
        return attachmentName?.trim()?.takeIf { it.isNotEmpty() }?.take(MaxLength) ?: "新聊天"
    }

    private const val MaxLength = 24
}
