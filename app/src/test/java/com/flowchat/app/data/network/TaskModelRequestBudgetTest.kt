package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskModelRequestBudgetTest {
    private val request = ChatRequest(model = "original-model",
        messages = listOf(ChatRequestMessage("user", "question")), temperature = 0.3, topP = 0.9, maxTokens = 400)
    private val provider = ProviderConfig(id = "provider", displayName = "Fixture",
        baseUrl = "https://fixture.example/v1", defaultModel = "original-model")

    @Test fun absentBudgetForwardsTheOriginalRequestProviderKeyAndStreamExactly() = runTest {
        val client = RecordingClient()
        val stream = client.budgetedStreamChat(request, provider, "secret")
        assertTrue(client.dispatches.isEmpty())

        assertEquals(listOf(ChatDelta.Content("original-model"), ChatDelta.Done), stream.toList())
        assertEquals(1, client.dispatches.size)
        val sent = client.dispatches.single()
        assertSame(request, sent.request)
        assertSame(provider, sent.provider)
        assertEquals("secret", sent.apiKey)
        assertEquals(listOf<TaskModelRequestBudget?>(null), client.collectionBudgets)
    }

    @Test fun preparationRunsOncePerCollectionAndCanTransformTheRequest() = runTest {
        val client = RecordingClient()
        var preparations = 0
        val prepared = request.copy(model = "budgeted-model", maxTokens = 73)
        val budget = TaskModelRequestBudget { original, selectedProvider ->
            preparations += 1
            assertSame(request, original)
            assertSame(provider, selectedProvider)
            assertTrue(client.dispatches.size < preparations)
            prepared
        }
        // The flow may be assembled outside the task coroutine; preparation belongs to collection.
        val stream = client.budgetedStreamChat(request, provider, null)
        assertEquals(0, preparations)
        assertTrue(client.dispatches.isEmpty())

        withContext(budget) {
            assertEquals(listOf(ChatDelta.Content("budgeted-model"), ChatDelta.Done), stream.toList())
        }
        assertEquals(1, preparations)
        assertSame(prepared, client.dispatches.single().request)
        assertEquals(null, client.dispatches.single().apiKey)
        withContext(budget) { stream.toList() }
        assertEquals(2, preparations)
        assertEquals(2, client.dispatches.size)
    }

    @Test fun budgetRefusalPropagatesBeforeUnderlyingClientDispatch() = runTest {
        val client = RecordingClient()
        val refusal = IllegalStateException("model request budget exhausted")
        var preparations = 0
        val budget = TaskModelRequestBudget { _, _ -> preparations += 1; throw refusal }

        val failure = runCatching {
            withContext(budget) { client.budgetedStreamChat(request, provider, "secret").toList() }
        }.exceptionOrNull()
        assertEquals(refusal::class, failure!!::class)
        assertEquals(refusal.message, failure.message)
        assertEquals(1, preparations)
        assertTrue(client.dispatches.isEmpty())
        assertTrue(client.collectionBudgets.isEmpty())
    }

    @Test fun cancellationBeforePreparationCannotReserveBudgetOrDispatch() = runTest {
        val client = RecordingClient()
        var preparations = 0
        val budget = TaskModelRequestBudget { original, _ -> preparations += 1; original }
        val collector = launch(budget, start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("task stopped before collection"))
            client.budgetedStreamChat(request, provider, null).toList()
        }
        collector.join()

        assertTrue(collector.isCancelled)
        assertEquals(0, preparations)
        assertTrue(client.dispatches.isEmpty())
    }

    @Test fun cancellationWhilePreparationIsSuspendedCannotDispatch() = runTest {
        val client = RecordingClient()
        val enteredPreparation = CompletableDeferred<Unit>()
        val finishPreparation = CompletableDeferred<Unit>()
        val budget = TaskModelRequestBudget { original, _ ->
            enteredPreparation.complete(Unit)
            finishPreparation.await()
            original
        }
        val collector = launch(budget) { client.budgetedStreamChat(request, provider, null).toList() }
        enteredPreparation.await()
        assertTrue(client.dispatches.isEmpty())

        collector.cancelAndJoin()
        assertTrue(collector.isCancelled)
        assertTrue(client.dispatches.isEmpty())
    }

    @Test fun preparationThatCancelsWithoutSuspendingCannotDispatchAfterItReturns() = runTest {
        val client = RecordingClient()
        var preparations = 0
        val budget = TaskModelRequestBudget { original, _ ->
            preparations += 1
            currentCoroutineContext().cancel(CancellationException("task stopped during accounting"))
            original
        }
        val collector = launch(budget, start = CoroutineStart.UNDISPATCHED) {
            client.budgetedStreamChat(request, provider, null).toList()
        }
        collector.join()

        assertTrue(collector.isCancelled)
        assertEquals(1, preparations)
        assertTrue(client.dispatches.isEmpty())
    }

    @Test fun flowOnPreservesTheTaskBudgetDuringPreparationAndNetworkCollection() = runTest {
        val client = RecordingClient()
        var preparations = 0
        lateinit var budget: TaskModelRequestBudget
        budget = TaskModelRequestBudget { original, _ ->
            assertSame(budget, currentCoroutineContext()[TaskModelRequestBudget])
            preparations += 1
            original.copy(maxTokens = 29)
        }
        val stream = client.budgetedStreamChat(request, provider, null)
            .flowOn(StandardTestDispatcher(testScheduler))

        withContext(budget) { stream.toList() }
        assertEquals(1, preparations)
        assertEquals(29, client.dispatches.single().request.maxTokens)
        assertEquals(listOf(budget), client.collectionBudgets)
    }

    @Test fun concurrentCollectionsCannotBorrowEachOthersTaskBudget() = runTest {
        val client = RecordingClient()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val firstBudget = TaskModelRequestBudget { original, _ ->
            firstEntered.complete(Unit)
            releaseFirst.await()
            original.copy(model = "first-task", maxTokens = 11, usageConversationId = "first")
        }
        val secondBudget = TaskModelRequestBudget { original, _ ->
            secondEntered.complete(Unit)
            releaseSecond.await()
            original.copy(model = "second-task", maxTokens = 22, usageConversationId = "second")
        }
        // The same cold flow is collected in independent coroutine contexts at overlapping times.
        val stream = client.budgetedStreamChat(request, provider, "key")
        val first = async(firstBudget) { stream.toList() }
        val second = async(secondBudget) { stream.toList() }
        firstEntered.await()
        secondEntered.await()
        assertTrue(client.dispatches.isEmpty())

        releaseSecond.complete(Unit)
        assertEquals(listOf(ChatDelta.Content("second-task"), ChatDelta.Done), second.await())
        assertEquals("second", client.dispatches.single().request.usageConversationId)
        assertEquals(22, client.dispatches.single().request.maxTokens)
        assertSame(secondBudget, client.collectionBudgets.single())

        releaseFirst.complete(Unit)
        assertEquals(listOf(ChatDelta.Content("first-task"), ChatDelta.Done), first.await())
        assertEquals(listOf("second", "first"), client.dispatches.map { it.request.usageConversationId })
        assertEquals(listOf(22, 11), client.dispatches.map { it.request.maxTokens })
        assertEquals(listOf(secondBudget, firstBudget), client.collectionBudgets)
    }

    private data class Dispatch(val request: ChatRequest, val provider: ProviderConfig, val apiKey: String?)

    private class RecordingClient : ChatCompletionClient {
        val dispatches = mutableListOf<Dispatch>()
        val collectionBudgets = mutableListOf<TaskModelRequestBudget?>()

        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?): Flow<ChatDelta> {
            // Construction is counted too: a real implementation may eagerly begin a request here.
            dispatches += Dispatch(request, provider, apiKey)
            return flow {
                collectionBudgets += currentCoroutineContext()[TaskModelRequestBudget]
                emit(ChatDelta.Content(request.model))
                emit(ChatDelta.Done)
            }
        }
    }
}
