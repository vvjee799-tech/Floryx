package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export original message text; display formatting and context compaction never alter the archive. */
internal object ConversationExporter {
    fun fileName(title: String): String = title.replace(Regex("""[^\p{L}\p{M}\p{N}._ -]"""), "_")
        .trim().take(80).ifBlank { "conversation" } + ".md"

    fun markdown(conversation: Conversation, messages: List<Message>): String = buildString {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT)
        appendLine("# ${conversation.title.replace('\n', ' ')}")
        appendLine()
        appendLine("Model: ${conversation.modelName}")
        appendLine()
        messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.User -> "User"
                MessageRole.Assistant -> conversation.assistantName.ifBlank { "Assistant" }
                MessageRole.Tool -> "Tool"
                else -> message.role.name
            }
            appendLine("## ${role.replace('\n', ' ')} · ${date.format(Date(message.createdAt))}")
            appendLine()
            if (message.status !in setOf(com.flowchat.app.domain.model.MessageStatus.Complete,
                    com.flowchat.app.domain.model.MessageStatus.Sent)) appendLine("Status: ${message.status.name}\n")
            if (message.reasoningContent.isNotBlank()) {
                appendLine("### Reasoning\n")
                appendLine(message.reasoningContent)
                appendLine("\n### Response\n")
            }
            appendLine(message.content)
            message.attachmentName?.let {
                appendLine("\nAttachment: ${it.replace('\n', ' ')}")
                message.attachmentText?.takeIf(String::isNotBlank)?.let { text ->
                    // Choose a fence longer than any run in user-provided content.
                    val fence = "`".repeat(maxOf(3, Regex("`+").findAll(text).maxOfOrNull { run -> run.value.length + 1 } ?: 3))
                    appendLine("\n${fence}text\n$text\n$fence")
                }
            }
            appendLine()
        }
    }
}
