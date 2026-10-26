package com.flowchat.app.data.chat

import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.domain.chat.AttachmentTools
import com.flowchat.app.domain.chat.ChatRequestFactory
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.ContextNote
import com.flowchat.app.domain.chat.TaskCheckpoint
import com.flowchat.app.domain.chat.TaskSummary
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.repository.ChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextCompactionFrequencyTest {
    private val provider = ProviderConfig(id = "p", displayName = "Test", baseUrl = "https://example.test", defaultModel = "model")
    private fun conversation(id: String = "a") = Conversation(id = id, providerId = provider.id, modelName = "model")

    private fun requestNear(tokens: Int, owner: String = "a", model: String = "model") = ChatRequest(
        model = model,
        usageConversationId = owner,
        messages = listOf(ChatRequestMessage("user", "x".repeat((tokens - 1_024) * 3))),
        temperature = 0.7,
        topP = 1.0,
        maxTokens = null
    )

    private fun shortHistory(owner: String, turns: Int) = (1..turns).flatMap { number -> listOf(
        Message(id = "$owner-u$number", conversationId = owner, role = MessageRole.User, content = "Task $number"),
        Message(id = "$owner-a$number", conversationId = owner, role = MessageRole.Assistant, content = "Done $number")
    ) }

    @Test fun highWatermarkWaitsForMeaningfulGrowthButHardLimitStillApplies() {
        val manager = TaskContextManager(Client(), Repo(emptyMap()))
        val at26k = requestNear(26_000)
        assertTrue(ContextBudget.request(at26k) in 25_900..26_100)
        manager.recordCompaction(at26k)

        assertFalse(manager.shouldCompact(requestNear(27_000)))
        assertTrue(manager.shouldCompact(requestNear(30_100)))
        assertThrows(IllegalStateException::class.java) { manager.requireFits(requestNear(32_100), "a") }
    }

    @Test fun watermarksAreIsolatedByConversationModelAndProvider() {
        val manager = TaskContextManager(Client(), Repo(emptyMap()))
        manager.recordCompaction(requestNear(26_000), provider)

        assertFalse(manager.shouldCompact(requestNear(27_000), provider))
        assertTrue(manager.shouldCompact(requestNear(25_000, owner = "b"), provider))
        assertTrue(manager.shouldCompact(requestNear(25_000, model = "different-model"), provider))
        assertTrue(manager.shouldCompact(requestNear(25_000), provider.copy(id = "another-provider")))
    }

    @Test fun aFollowUpPreparationUsesTheSameGrowthWatermarkAsTheRunningTurn() = runTest {
        val rows = shortHistory("a", 4)
        val client = Client { error("One small follow-up must not immediately summarize again") }
        val manager = TaskContextManager(client, Repo(mapOf("a" to rows)))
        manager.recordCompaction(requestNear(26_000), provider)
        val prepared = manager.prepare(conversation(), rows, provider, null,
            projectWindow = { requestNear(27_000) })
        assertEquals(rows, prepared.messages)
        assertTrue(client.requests.isEmpty())
        assertNull(prepared.checkpoint)
    }

    @Test fun twentyThreeSmallTurnsDoNotSummarizeByDefault() = runTest {
        val rows = shortHistory("a", 23)
        val repo = Repo(mapOf("a" to rows))
        val client = Client { error("Short turns must not call the summary model") }

        val prepared = TaskContextManager(client, repo).prepare(conversation(), rows, provider, null)

        assertEquals(rows, prepared.messages)
        assertNull(prepared.checkpoint)
        assertTrue(client.requests.isEmpty())
    }

    @Test fun largeStoredAttachmentDoesNotTriggerWhenItsActualProjectionIsSmall() = runTest {
        val attachment = "x".repeat(80_000)
        val rows = listOf(
            Message(id = "file", conversationId = "a", role = MessageRole.User, content = "Read the file",
                attachmentName = "large.txt", attachmentText = attachment),
            Message(id = "follow-up", conversationId = "a", role = MessageRole.User, content = "Check the final paragraph")
        )
        val repo = Repo(mapOf("a" to rows))
        val client = Client { error("Inline projection fits; summary request would be unnecessary") }
        val chat = conversation()
        val project: (PreparedTaskContext) -> ChatRequest = { candidate ->
            AttachmentTools.attach(ChatRequestFactory.create(chat, candidate.messages, checkpoint = candidate.checkpoint))
        }
        assertTrue(ContextBudget.request(project(PreparedTaskContext(rows, null)), provider) < 24_000)

        val prepared = TaskContextManager(client, repo).prepare(chat, rows, provider, null, projectWindow = project)

        assertTrue(client.requests.isEmpty())
        assertEquals(attachment, prepared.messages.first().attachmentText)
        assertNull(prepared.checkpoint)
    }

    @Test fun nativeReplayKeepsTwoSmallGroupsButOnlyTheLatestFullGroupWhenLarge() {
        val first = ChatToolCall("call-1", "read", "{}")
        val second = ChatToolCall("call-2", "read", "{}")
        fun request(firstResult: String) = ChatRequest(
            model = "model", usageConversationId = "a", temperature = 0.7, topP = 1.0, maxTokens = null,
            messages = listOf(
                ChatRequestMessage("user", "Continue"),
                ChatRequestMessage("assistant", "First action", toolCalls = listOf(first)),
                ChatRequestMessage("tool", firstResult, toolCallId = first.id),
                ChatRequestMessage("assistant", "Second action", toolCalls = listOf(second)),
                ChatRequestMessage("tool", "confirmed", toolCallId = second.id)
            )
        )
        val manager = TaskContextManager(Client(), Repo(emptyMap()))
        assertEquals(1, manager.retainedNativeStart(request("short")))

        val large = request("x".repeat(30_000))
        val start = manager.retainedNativeStart(large)
        assertEquals(3, start)
        val kept = large.messages.drop(start!!)
        assertEquals("assistant", kept.first().role)
        assertEquals(listOf(second.id), kept.flatMap { it.toolCalls }.map { it.id })
        assertEquals(listOf(second.id), kept.filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test fun anotherConversationCanCompactWhileTheFirstSummaryIsBlocked() = runTest {
        val a = shortHistory("a", 4)
        val b = shortHistory("b", 4)
        val repo = Repo(mapOf("a" to a, "b" to b))
        val startedA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val client = Client { request ->
            if (request.usageConversationId == "a") {
                startedA.complete(Unit)
                releaseA.await()
            }
            validSummary(request)
        }
        val manager = TaskContextManager(client, repo, compactLimits())
        val preparingA = async { manager.prepare(conversation("a"), a, provider, null, force = true) }
        startedA.await()
        val preparingB = async { manager.prepare(conversation("b"), b, provider, null, force = true) }
        runCurrent()

        assertTrue(preparingB.isCompleted)
        assertNotNull(preparingB.await().checkpoint)
        assertEquals(1, repo.saves["b"])
        assertFalse(preparingA.isCompleted)
        releaseA.complete(Unit)
        assertNotNull(preparingA.await().checkpoint)
    }

    @Test fun cancellingBlockedSummaryLeavesTheFullTranscriptAndNoCheckpoint() = runTest {
        val rows = shortHistory("a", 4)
        val repo = Repo(mapOf("a" to rows))
        val started = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val client = Client { request ->
            started.complete(Unit)
            neverRelease.await()
            validSummary(request)
        }
        val preparing = launch { TaskContextManager(client, repo, compactLimits()).prepare(conversation(), rows, provider, null, force = true) }
        started.await()

        preparing.cancelAndJoin()

        assertEquals(rows, repo.getMessages("a"))
        assertNull(repo.getContextCheckpoint("a"))
        assertTrue(repo.saves.isEmpty())
    }

    private fun compactLimits() = ContextLimits(softTokens = 100, hardTokens = 10_000,
        keepTurns = 1, batchTokens = 20_000, targetTokens = 50, minGrowthTokens = 20)

    private fun validSummary(request: ChatRequest): List<ChatDelta> {
        val records = CheckpointCodec.json.parseToJsonElement(request.messages.last().content!!).jsonObject["new_records"]!!.jsonArray
        val sourceId = records.first().jsonObject["id"]!!.jsonPrimitive.content
        val summary = TaskSummary(ContextNote("Continue task", listOf(sourceId)))
        return listOf(ChatDelta.Content(CheckpointCodec.json.encodeToString(summary)), ChatDelta.Done)
    }

    private class Client(private val reply: suspend (ChatRequest) -> List<ChatDelta> = { emptyList() }) : ChatCompletionClient {
        val requests = mutableListOf<ChatRequest>()
        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
            requests += request
            reply(request).forEach { emit(it) }
        }
    }

    private class Repo(initial: Map<String, List<Message>>) : ChatRepository {
        private val rows = initial.mapValues { it.value.toList() }
        private val checkpoints = mutableMapOf<String, TaskCheckpoint>()
        val saves = mutableMapOf<String, Int>()

        override fun observeConversations() = flowOf(emptyList<Conversation>())
        override fun observeMessages(conversationId: String) = flowOf(rows[conversationId].orEmpty())
        override suspend fun getConversation(id: String): Conversation? = null
        override suspend fun getMessages(conversationId: String) = rows[conversationId].orEmpty()
        override suspend fun getContextCheckpoint(conversationId: String) = checkpoints[conversationId]
        override suspend fun saveContextCheckpoint(checkpoint: TaskCheckpoint, expected: TaskCheckpoint?): Boolean {
            if (checkpoints[checkpoint.conversationId] != expected || !checkpoint.matches(getMessages(checkpoint.conversationId))) return false
            checkpoints[checkpoint.conversationId] = checkpoint
            saves[checkpoint.conversationId] = saves.getOrDefault(checkpoint.conversationId, 0) + 1
            return true
        }
        override suspend fun updateExecutionContext(id: String, context: String) = Unit
        override suspend fun createConversation(providerId: String, modelName: String): Conversation = error("unused")
        override suspend fun updateConversationSettings(id: String, assistantName: String, assistantAvatarPath: String?,
            showAvatars: Boolean, enableThinking: Boolean, systemPrompt: String, temperature: Double, topP: Double,
            maxTokens: Int?, thinkingEffort: ThinkingEffort) = Unit
        override suspend fun updateThinkingSettings(id: String, enabled: Boolean, effort: ThinkingEffort) = Unit
        override suspend fun updateConversationModel(id: String, providerId: String, modelName: String) = Unit
        override suspend fun updateConversationTitle(id: String, title: String) = Unit
        override suspend fun appendMessage(conversationId: String, role: MessageRole, content: String, status: MessageStatus,
            modelName: String?, attachmentName: String?, attachmentText: String?, attachmentImagePath: String?,
            attachmentMimeType: String?): Message = error("unused")
        override suspend fun updateMessage(id: String, content: String, status: MessageStatus, reasoningContent: String,
            reasoningDurationMillis: Long?, replyPhase: ReplyPhase?) = Unit
        override suspend fun markMessageFailed(id: String, error: String) = Unit
        override suspend fun deleteConversation(id: String) = Unit
    }
}
