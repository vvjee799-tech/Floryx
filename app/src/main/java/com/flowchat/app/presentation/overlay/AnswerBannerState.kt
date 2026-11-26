package com.flowchat.app.presentation.overlay

import android.content.Context
import android.content.Intent
import com.flowchat.app.MainActivity

data class AnswerBannerState(
    val taskId: String,
    val conversationId: String,
    val text: String = "",
    val isStreaming: Boolean = true,
    val isError: Boolean = false,
    val dismissed: Boolean = false
) {
    val hasContent: Boolean get() = !dismissed && text.isNotBlank()
}

object AnswerModeNavigation {
    // Retain the existing route so old notification intents still open their conversation.
    const val OpenAnswerAction = "com.flowchat.app.OPEN_PHOTO_ANSWER"
    const val ConversationExtra = "photo_answer_conversation_id"
    fun intent(context: Context, owner: String): Intent = Intent(context, MainActivity::class.java).apply {
        action = OpenAnswerAction
        putExtra(ConversationExtra, owner)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}
