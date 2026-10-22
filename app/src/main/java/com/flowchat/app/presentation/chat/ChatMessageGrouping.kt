package com.flowchat.app.presentation.chat

import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus

internal data class DisplayChatMessage(val message: Message, val reasoning: Message?, val activity: List<Message> = emptyList())

/** A display-only projection. Stored messages and provider reasoning/signatures stay untouched. */
internal fun groupChatMessages(messages: List<Message>): List<DisplayChatMessage> = buildList {
    val turn = mutableListOf<Message>()
    fun flush() {
        if (turn.isEmpty()) return
        val segments = turn.filter { it.role == MessageRole.Assistant && it.reasoningContent.isNotBlank() }
        val anchor = segments.firstOrNull()
        val latest = turn.lastOrNull { it.role == MessageRole.Assistant }
        val thinking = latest?.status == MessageStatus.Streaming && latest.content.isBlank()
        val reasoning = anchor?.copy(
            content = if (thinking) "" else anchor.content,
            reasoningContent = segments.joinToString("\n\n") { it.reasoningContent },
            reasoningDurationMillis = segments.sumOf { it.reasoningDurationMillis.coerceAtLeast(0L) },
            status = when {
                thinking -> MessageStatus.Streaming
                latest?.status in setOf(MessageStatus.Stopped, MessageStatus.Cancelled) -> MessageStatus.Stopped
                else -> MessageStatus.Complete
            }
        )
        val final = turn.lastOrNull { it.role == MessageRole.Assistant }
            ?.takeIf { it.status == MessageStatus.Complete && it.content.isNotBlank() &&
                it.replyPhase == com.flowchat.app.domain.model.ReplyPhase.Answer }
        val activity = final?.let { answer -> turn.takeWhile { it.id != answer.id }
            .filter { it.role == MessageRole.Tool || it.role == MessageRole.Assistant } }.orEmpty()
        val foldActivity = activity.any { it.role == MessageRole.Tool ||
            (it.replyPhase == com.flowchat.app.domain.model.ReplyPhase.Commentary && it.content.isNotBlank()) } &&
            turn.lastOrNull()?.id == final?.id
        if (foldActivity) {
            val activityIds = activity.map { it.id }.toSet()
            turn.forEach { message ->
                when {
                    message.id == activity.first().id -> add(DisplayChatMessage(message, reasoning, activity))
                    message.id !in activityIds -> add(DisplayChatMessage(message, null))
                }
            }
            turn.clear()
            return
        }
        turn.forEach { message ->
            val ownsReasoning = message.id == anchor?.id
            val emptyContinuation = message.role == MessageRole.Assistant && message.content.isBlank() &&
                message.attachmentName == null && message.attachmentImagePath == null && message.attachmentText.isNullOrBlank() &&
                (message.status == MessageStatus.Complete || (message.status == MessageStatus.Streaming && anchor != null))
            if (!ownsReasoning && emptyContinuation) return@forEach
            add(DisplayChatMessage(message, reasoning.takeIf { ownsReasoning }))
        }
        turn.clear()
    }
    messages.forEach { message ->
        if (message.role == MessageRole.User || (turn.isNotEmpty() && turn.first().conversationId != message.conversationId)) flush()
        turn += message
    }
    flush()
}
