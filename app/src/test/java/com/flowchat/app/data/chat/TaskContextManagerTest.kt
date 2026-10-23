package com.flowchat.app.data.chat

import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.repository.ChatRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskContextManagerTest {
    private val conversation = Conversation(id = "c", providerId = "p", modelName = "mock")
    private val provider = ProviderConfig(id = "p", displayName = "Mock", baseUrl = "https://example.test", defaultModel = "mock")
    private val limits = ContextLimits(softTokens = 2_000, hardTokens = 8_000, triggerTurns = 4, keepTurns = 2, batchTokens = 8_000)

    private fun transcript(turns: Int = 10) = (1..turns).flatMap { index -> listOf(
        Message(id = "u$index", conversationId = "c", role = MessageRole.User,
            content = if (index == 3) "不要再查组件，它已经确认存在" else "任务 $index", createdAt = index * 10L),
        Message(id = "a$index", conversationId = "c", role = MessageRole.Assistant,
            content = "步骤 $index 已返回", createdAt = index * 10L + 1,
            executionContext = TaskExecutionHistory.encode(listOf(TaskExecutionHistory.completed(
                TaskExecutionHistory.pending(ChatToolCall("call-$index", "read_test", "{}")), "RESULT_$index"))))
    ) }

    private class Repo(var rows: List<Message>) : ChatRepository {
        var checkpoint: TaskCheckpoint? = null
        var acceptSave = true
        var saves = 0
        override suspend fun getMessages(conversationId: String) = rows.filter { it.conversationId == conversationId }
        override suspend fun getContextCheckpoint(conversationId: String) = checkpoint?.takeIf { it.conversationId == conversationId }
        override suspend fun saveContextCheckpoint(checkpoint: TaskCheckpoint, expected: TaskCheckpoint?): Boolean {
            if (!acceptSave || this.checkpoint != expected || !checkpoint.matches(rows)) return false
            this.checkpoint = checkpoint; saves++; return true
        }
        override fun observeConversations() = flowOf(emptyList<Conversation>())
        override fun observeMessages(conversationId: String) = flowOf(rows)
        override suspend fun getConversation(id: String): Conversation? = null
        override suspend fun updateExecutionContext(id: String, context: String) = Unit
        override suspend fun createConversation(providerId: String, modelName: String): Conversation = error("unused")
        override suspend fun updateConversationSettings(id: String, assistantName: String, assistantAvatarPath: String?, showAvatars: Boolean,
            enableThinking: Boolean, systemPrompt: String, temperature: Double, topP: Double, maxTokens: Int?, thinkingEffort: ThinkingEffort) = Unit
        override suspend fun updateThinkingSettings(id: String, enabled: Boolean, effort: ThinkingEffort) = Unit
        override suspend fun updateConversationModel(id: String, providerId: String, modelName: String) = Unit
        override suspend fun updateConversationTitle(id: String, title: String) = Unit
        override suspend fun appendMessage(conversationId: String, role: MessageRole, content: String, status: MessageStatus, modelName: String?,
            attachmentName: String?, attachmentText: String?, attachmentImagePath: String?, attachmentMimeType: String?): Message = error("unused")
        override suspend fun updateMessage(id: String, content: String, status: MessageStatus, reasoningContent: String,
            reasoningDurationMillis: Long?, replyPhase: ReplyPhase?) = Unit
        override suspend fun markMessageFailed(id: String, error: String) = Unit
        override suspend fun deleteConversation(id: String) = Unit
    }

    private class Client(val reply: suspend (ChatRequest) -> List<ChatDelta> = { validSummary(it) }) : ChatCompletionClient {
        val requests = mutableListOf<ChatRequest>()
        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = flow {
            requests += request
            reply(request).forEach { emit(it) }
        }
    }

    @Test fun compactsOldTurnsKeepsSourcesAndCorrectionsAndReusesCheckpointAfterRecreation() = runTest {
        val rows = transcript()
        val repo = Repo(rows)
        val client = Client()
        val prepared = TaskContextManager(client, repo, limits).prepare(conversation, rows, provider, null)
        val checkpoint = prepared.checkpoint!!
        assertTrue(checkpoint.matches(rows))
        assertTrue(checkpoint.summary.corrections.any { it.text.contains("不要再查组件") })
        assertTrue(checkpoint.summary.completed.isNotEmpty())
        assertEquals("任务 1", checkpoint.originalRequest.text)
        assertEquals(rows.size, repo.rows.size)
        assertTrue(prepared.messages.size < rows.size)
        assertEquals(rows.last().id, prepared.messages.last().id)
        assertTrue(client.requests.all { it.tools.isEmpty() && !it.enableThinking })
        val before = client.requests.size
        val reopened = TaskContextManager(client, repo, limits).prepare(conversation, rows, provider, null)
        assertEquals(checkpoint, reopened.checkpoint)
        assertEquals(before, client.requests.size)
        val request = ChatRequestFactory.create(conversation, reopened.messages, checkpoint = checkpoint)
        assertTrue(request.messages.any { it.content.orEmpty().contains("Conversation task checkpoint") })
    }

    @Test fun failedOrMalformedSummaryKeepsRawHistoryAndNeverRunsTools() = runTest {
        for (response in listOf(listOf(ChatDelta.Error("offline")), listOf(ChatDelta.Content("not json"), ChatDelta.Done),
            listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("x", "run_command", "{}"))), ChatDelta.Done))) {
            val rows = transcript()
            val repo = Repo(rows)
            val client = Client { response }
            val prepared = TaskContextManager(client, repo, limits).prepare(conversation, rows, provider, null)
            assertNull(prepared.checkpoint)
            assertEquals(rows, prepared.messages)
            assertEquals(0, repo.saves)
            assertEquals(1, client.requests.size)
        }
    }

    @Test fun inventedSourcesAndIncompleteStreamsCannotReplaceTheCheckpoint() = runTest {
        for (complete in listOf(true, false)) {
            val repo = Repo(transcript())
            val client = Client { request ->
                val good = validSummary(request)
                if (complete) listOf(ChatDelta.Content("""{"objective":{"text":"invented","sources":["other-conversation-id"]}}"""), ChatDelta.Done)
                else good.filterNot { it == ChatDelta.Done }
            }
            val prepared = TaskContextManager(client, repo, limits).prepare(conversation, repo.rows, provider, null)
            assertNull(prepared.checkpoint)
            assertEquals(repo.rows, prepared.messages)
        }
    }

    @Test fun cancellingCompactionDoesNotDeleteHistoryOrCommitPartialSummary() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Repo(transcript())
        val original = repo.rows.toList()
        val client = Client { gate.await(); validSummary(it) }
        val job = launch { TaskContextManager(client, repo, limits).prepare(conversation, repo.rows, provider, null) }
        runCurrent()
        assertEquals(1, client.requests.size)
        job.cancelAndJoin()
        assertEquals(original, repo.rows)
        assertNull(repo.checkpoint)
        assertEquals(0, repo.saves)
    }

    @Test fun aChangedSourceInvalidatesTheOldSummaryInsteadOfReplayingIt() = runTest {
        val repo = Repo(transcript())
        val first = TaskContextManager(Client(), repo, limits).prepare(conversation, repo.rows, provider, null).checkpoint!!
        repo.rows = repo.rows.map { if (it.id == "u1") it.copy(content = "新的目标") else it }
        assertFalse(first.matches(repo.rows))
        val client = Client { listOf(ChatDelta.Error("offline")) }
        val prepared = TaskContextManager(client, repo, limits).prepare(conversation, repo.rows, provider, null)
        assertNull(prepared.checkpoint)
        assertEquals(repo.rows, prepared.messages)
        assertFalse(client.requests.first().messages.last().content.orEmpty().contains("previous_checkpoint"))
    }

    @Test fun historyLookupIsConversationScopedReadOnlyAndOmitsPrivateReasoning() {
        val rows = transcript(2).map { it.copy(reasoningContent = "PRIVATE") } +
            Message(id = "secret", conversationId = "other", role = MessageRole.User, content = "OTHER_SECRET")
        val result = TaskContextTools.read("""{"message_ids":["a1","secret"]}""", "c", rows)
        assertTrue(result.contains("RESULT_1"))
        assertTrue(result.contains("not_found_in_this_conversation"))
        assertFalse(result.contains("PRIVATE") || result.contains("OTHER_SECRET"))
        assertTrue(TaskContextTools.read("{}", "c", rows).startsWith("failed:"))
    }

    @Test fun checkpointProtectsNativeSuffixAndAnchorsTheCurrentUserDuringALongSingleTurn() = runTest {
        val user = Message(id = "goal", conversationId = "c", role = MessageRole.User, content = "继续这一个长任务")
        val rows = listOf(user) + (1..12).map { index -> Message(id = "step$index", conversationId = "c", role = MessageRole.Assistant,
            content = "Progress $index " + "x".repeat(500), replyPhase = ReplyPhase.Commentary) }
        val repo = Repo(rows)
        val prepared = TaskContextManager(Client(), repo, limits).prepare(conversation, rows, provider, null,
            protectedMessageId = "step11", force = true)
        val boundary = rows.indexOfFirst { it.id == prepared.checkpoint!!.throughMessageId }
        assertTrue(boundary < rows.indexOfFirst { it.id == "step11" })
        assertEquals("goal", prepared.messages.first().id)
        assertTrue(prepared.messages.any { it.id == "step11" })
        assertTrue(prepared.messages.any { it.id == "step12" })
    }

    @Test fun overBudgetFailureStopsBeforeRequestingAnotherActionAndPreservesEverything() = runTest {
        val rows = transcript().map { it.copy(content = it.content + "x".repeat(5_000)) }
        val repo = Repo(rows)
        val manager = TaskContextManager(Client { listOf(ChatDelta.Error("offline")) }, repo, limits)
        val prepared = manager.prepare(conversation, rows, provider, null)
        val request = ChatRequestFactory.create(conversation, prepared.messages)
        assertThrows(IllegalStateException::class.java) { manager.requireFits(request) }
        assertEquals(rows, repo.rows)
        assertNull(prepared.checkpoint)
    }

    @Test fun failureAfterOneSuccessfulBatchKeepsThatCheckpointAndEveryUncoveredRecord() = runTest {
        val rows = transcript().map { it.copy(content = it.content + "x".repeat(1_000)) }
        val repo = Repo(rows)
        var batches = 0
        val client = Client { request -> if (++batches == 1) validSummary(request) else listOf(ChatDelta.Error("offline")) }
        val manager = TaskContextManager(client, repo, limits.copy(batchTokens = 2_000))
        val prepared = manager.prepare(conversation, rows, provider, null)
        val checkpoint = prepared.checkpoint!!
        assertTrue(checkpoint.matches(rows))
        val boundary = rows.indexOfFirst { it.id == checkpoint.throughMessageId }
        assertEquals(rows.drop(boundary + 1), prepared.messages)
        assertEquals(rows, repo.rows)
        assertEquals(1, repo.saves)
    }

    @Test fun summaryTimeoutFallsBackToRawHistoryWithoutCancellingTheUserTurn() = runTest {
        val repo = Repo(transcript())
        val manager = TaskContextManager(Client { awaitCancellation() }, repo, limits)
        val prepared = manager.prepare(conversation, repo.rows, provider, null)
        assertEquals(repo.rows, prepared.messages)
        assertNull(prepared.checkpoint)
        assertEquals("summary_timeout", manager.lastFailureCode)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun aCombinedFindingMayKeepSixValidSourcesWithoutDiscardingTheWholeSummary() = runTest {
        val repo = Repo(transcript())
        val client = Client { request ->
            val input = Json.parseToJsonElement(request.messages.last().content!!).jsonObject
            val records = input["new_records"]!!.jsonArray.map { it.jsonObject }
            val summary = TaskSummary(ContextNote("Continue the original task", listOf(records.first()["id"]!!.jsonPrimitive.content)),
                findings = listOf(ContextNote("Six recorded results", records.take(6).map { it["id"]!!.jsonPrimitive.content })))
            listOf(ChatDelta.Content(CheckpointCodec.json.encodeToString(summary)), ChatDelta.Done)
        }
        val prepared = TaskContextManager(client, repo, limits).prepare(conversation, repo.rows, provider, null)
        assertEquals(6, prepared.checkpoint!!.summary.findings.single().sources.size)
    }

    private companion object {
        fun validSummary(request: ChatRequest): List<ChatDelta> {
            val input = Json.parseToJsonElement(request.messages.last().content!!).jsonObject
            val previous = input["previous_checkpoint"]?.let { CheckpointCodec.json.decodeFromJsonElement<TaskCheckpoint>(it) }
            val records = input["new_records"]!!.jsonArray.map { it.jsonObject }
            val user = records.firstOrNull { it["role"]!!.jsonPrimitive.content == "User" } ?: records.first()
            val objective = previous?.summary?.objective ?: ContextNote(user["content"]!!.jsonPrimitive.content.take(100), listOf(user["id"]!!.jsonPrimitive.content))
            val corrections = previous?.summary?.corrections.orEmpty() + records.filter { it["content"]!!.jsonPrimitive.content.contains("不要") }
                .map { ContextNote(it["content"]!!.jsonPrimitive.content, listOf(it["id"]!!.jsonPrimitive.content)) }
            val completed = previous?.summary?.completed.orEmpty() + records.filter { it.containsKey("tool_evidence") }.take(2)
                .map { ContextNote("Observed test result", listOf(it["id"]!!.jsonPrimitive.content)) }
            val summary = TaskSummary(objective, corrections = corrections, completed = completed.takeLast(4))
            return listOf(ChatDelta.Content(CheckpointCodec.json.encodeToString(summary)), ChatDelta.Done)
        }
    }
}
