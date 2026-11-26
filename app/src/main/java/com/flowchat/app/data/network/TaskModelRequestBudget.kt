package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ProviderConfig
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Includes context-compaction requests in the same task's persisted dispatch budget. */
class TaskModelRequestBudget(val prepare: suspend (ChatRequest, ProviderConfig) -> ChatRequest) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskModelRequestBudget>
}

suspend fun prepareTaskModelRequest(request: ChatRequest, provider: ProviderConfig): ChatRequest {
    currentCoroutineContext().ensureActive()
    val prepared = currentCoroutineContext()[TaskModelRequestBudget]?.prepare?.invoke(request, provider) ?: request
    currentCoroutineContext().ensureActive()
    return prepared
}

fun ChatCompletionClient.budgetedStreamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> = flow {
    val prepared = prepareTaskModelRequest(request, provider)
    emitAll(streamChat(prepared, provider, apiKey))
}
