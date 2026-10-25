package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.ChatRequest

object ScreenVisionContext {
    /** Keep two recent frames for comparisons, without repeatedly uploading the entire screen history. */
    fun retainRecent(request: ChatRequest): ChatRequest {
        val keep = request.messages.indices.filter { request.messages[it].screenImageId != null && request.messages[it].images.isNotEmpty() }
            .takeLast(2).toSet()
        return request.copy(messages = request.messages.mapIndexed { index, message ->
            if (message.screenImageId != null && message.images.isNotEmpty() && index !in keep)
                message.copy(images = emptyList(), screenImageId = null,
                    content = message.content.orEmpty() + "\n[Earlier screenshot omitted from this request. Its pixels are unavailable; capture again for current visual details.]")
            else message
        })
    }
}
