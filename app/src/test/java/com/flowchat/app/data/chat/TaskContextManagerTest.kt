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

    @Test fun restoringDisplayWindowUsesCommittedCheckpointWithoutAnyModelOrWrite() = runTest {
        val rows = transcript()
        val repo = Repo(rows)
        val prepared = TaskContextManager(Client(), repo, limits).prepare(conversation, rows, provider, null)
        val saves = repo.saves
        val client = Client { error("A display estimate must not contact the model") }
        val manager = TaskContextManager(client, repo, limits)
        val foreign = Message(id = "foreign", conversationId = "other", role = MessageRole.User, content = "PRIVATE")
        val restored = manager.restoreWindow(conversation.id, rows + foreign)
        assertEquals(prepared, restored)
        assertEquals(saves, repo.saves)
        assertTrue(client.requests.isEmpty())
        assertEquals(limits.hardTokens, manager.contextWindow(ChatRequestFactory.create(conversation,
            restored.messages, checkpoint = restored.checkpoint), provider).totalTokens)
    }

    @Test fun restoringDisplayWindowRejectsChangedSourcesAndDoesNotCompactEvenOverBudget() = runTest {
        val repo = Repo(transcript())
        TaskContextManager(Client(), repo, limits).prepare(conversation, repo.rows, provider, null)
        val checkpoint = repo.checkpoint
        val saves = repo.saves
        repo.rows = repo.rows.map { if (it.id == "u1") it.copy(content = "Changed " + "大".repeat(40_000)) else it }
        val client = Client { error("Reading the context meter must be free of model requests") }
        val manager = TaskContextManager(client, repo, limits)
        val restored = manager.restoreWindow(conversation.id, repo.rows)
        assertNull(restored.checkpoint)
        assertEquals(repo.rows, restored.messages)
        assertEquals(checkpoint, repo.checkpoint)
        assertEquals(saves, repo.saves)
        assertTrue(manager.contextWindow(ChatRequestFactory.create(conversation, restored.messages), provider).usedTokens > limits.hardTokens)
        assertTrue(client.requests.isEmpty())
    }

    @Test fun displayWindowPreservesLatestUserAnchorAfterSingleTurnCompaction() = runTest {
        val goal = Message(id = "goal", conversationId = "c", role = MessageRole.User, content = "Finish the original task")
        val rows = listOf(goal) + (1..12).map { Message(id = "step$it", conversationId = "c", role = MessageRole.Assistant,
            content = "step $it " + "x".repeat(500), replyPhase = ReplyPhase.Commentary) }
        val repo = Repo(rows)
        val prepared = TaskContextManager(Client(), repo, limits).prepare(conversation, rows, provider, null,
            protectedMessageId = "step11", force = true)
        val manager = TaskContextManager(Client { error("unused") }, repo, limits)
        val restored = manager.restoreWindow(conversation.id, rows)
        assertEquals(prepared, restored)
        assertEquals(goal, restored.messages.first())
        val request = ChatRequestFactory.create(conversation, restored.messages, checkpoint = restored.checkpoint)
        assertEquals(ContextBudget.request(request, provider), manager.contextWindow(request, provider).usedTokens)
    }

    @Test fun aShortFollowUpToALongAttachmentDoesNotSpendASummaryRequest() = runTest {
        val body = "长".repeat(80_000)
        val rows = listOf(
            Message(id = "file", conversationId = "c", role = MessageRole.User, content = "查看文件",
                attachmentName = "long.txt", attachmentText = body),
            Message(id = "follow-up", conversationId = "c", role = MessageRole.User, content = "请读取最后一页")
        )
        val client = Client { error("An attachment preview that fits must not require compaction") }
        val manager = TaskContextManager(client, Repo(rows))
        val prepared = manager.prepare(conversation, rows, provider, null)
        assertTrue(client.requests.isEmpty())
        assertEquals(body, prepared.messages.first().attachmentText)
        assertNull(prepared.checkpoint)
        manager.requireFits(AttachmentTools.attach(ChatRequestFactory.create(conversation, prepared.messages)))
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
            assertEquals(if (response.any { it is ChatDelta.Content }) 2 else 1, client.requests.size)
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

    @Test fun anInvalidSummaryIsRepairedOnceUsingTheSameSourceRecords() = runTest {
        for (invalid in listOf("not json", """{"objective":{"text":"unsourced","sources":[]}}""",
            """{"objective":{"text":"invented","sources":["not-in-this-task"]}}""")) {
            val rows = transcript()
            val repo = Repo(rows)
            var attempts = 0
            val client = Client { request -> if (++attempts == 1) listOf(ChatDelta.Content(invalid), ChatDelta.Done) else validSummary(request) }
            val manager = TaskContextManager(client, repo, limits)
            val prepared = manager.prepare(conversation, rows, provider, null)
            assertNotNull(prepared.checkpoint)
            assertNull(manager.lastFailureCode)
            assertEquals(2, client.requests.size)
            assertEquals(client.requests[0].messages.last(), client.requests[1].messages.last())
            assertTrue(client.requests[1].messages.first().content!!.contains("rejected by validation"))
            assertTrue(client.requests.all { it.tools.isEmpty() && !it.enableThinking })
            assertEquals(rows, repo.rows)
        }
    }

    @Test fun repeatedMalformedSummariesStopRepairingAndRetainTheSpecificFailure() = runTest {
        val rows = transcript().map { it.copy(content = it.content + "x".repeat(5_000)) }
        val repo = Repo(rows)
        val client = Client { listOf(ChatDelta.Content("""{"objective":{"text":"unsourced","sources":[]}}"""), ChatDelta.Done) }
        val manager = TaskContextManager(client, repo, limits)
        val prepared = manager.prepare(conversation, rows, provider, null)
        assertEquals(2, client.requests.size)
        assertEquals("invalid_summary_shape", manager.lastFailureCode)
        assertEquals(rows, prepared.messages)
        assertNull(prepared.checkpoint)
        val failure = assertThrows(IllegalStateException::class.java) {
            manager.requireFits(ChatRequestFactory.create(conversation, prepared.messages), conversation.id)
        }
        assertTrue(failure.message!!.contains("自动修复后仍未通过"))
        manager.prepare(conversation, rows, provider, null, force = true)
        assertEquals(2, client.requests.size)
        assertEquals(rows, repo.rows)
    }

    @Test fun truncatedOrIncompleteSummariesAreRepairedOnceWithoutRepeatingActions() = runTest {
        for (failure in listOf(
            listOf(ChatDelta.Content("{\"objective\":"), ChatDelta.Error("The response reached the model output limit and is incomplete.")),
            listOf(ChatDelta.Content("{\"objective\":"), ChatDelta.Error("The model stream ended before the response completed. Please retry.")),
            listOf(ChatDelta.Error("Malformed stream event")),
            listOf(ChatDelta.Content("{\"objective\":"))
        )) {
            val repo = Repo(transcript())
            var attempts = 0
            val client = Client { request -> if (++attempts == 1) failure else validSummary(request) }
            val manager = TaskContextManager(client, repo, limits)
            val prepared = manager.prepare(conversation, repo.rows, provider, null)
            assertNotNull(prepared.checkpoint)
            assertNull(manager.lastFailureCode)
            assertEquals(2, client.requests.size)
            assertEquals(4_096, client.requests[0].maxTokens)
            assertEquals(8_192, client.requests[1].maxTokens)
            assertEquals(client.requests[0].messages.last(), client.requests[1].messages.last())
            assertTrue(client.requests.all { it.tools.isEmpty() && !it.enableThinking })
            assertEquals(1, repo.saves)
        }
    }

    @Test fun repeatedTruncationPreservesRecordsAndExplainsWhyTheTaskStopped() = runTest {
        val rows = transcript().map { it.copy(content = it.content + "x".repeat(5_000)) }
        val repo = Repo(rows)
        val client = Client { listOf(ChatDelta.Error("The response reached the model output limit and is incomplete.")) }
        val manager = TaskContextManager(client, repo, limits)
        val prepared = manager.prepare(conversation, rows, provider, null)
        assertEquals(2, client.requests.size)
        assertEquals("summary_output_limit", manager.lastFailureCode)
        assertNull(prepared.checkpoint)
        assertEquals(rows, prepared.messages)
        val failure = assertThrows(IllegalStateException::class.java) {
            manager.requireFits(ChatRequestFactory.create(conversation, prepared.messages), conversation.id)
        }
        assertTrue(failure.message!!.contains("摘要输出达到长度限制"))
        manager.prepare(conversation, rows, provider, null, force = true)
        assertEquals(2, client.requests.size)
        assertEquals(0, repo.saves)
    }

    @Test fun truncationFollowedByMissingProvenanceGetsOneDistinctCorrection() = runTest {
        val repo = Repo(transcript())
        var attempts = 0
        val client = Client { request ->
            when (++attempts) {
                1 -> listOf(ChatDelta.Error("The response reached the model output limit and is incomplete."))
                2 -> listOf(ChatDelta.Content(CheckpointCodec.json.encodeToString(TaskSummary(
                    ContextNote("Continue the task", emptyList())
                ))), ChatDelta.Done)
                else -> validSummary(request)
            }
        }
        val manager = TaskContextManager(client, repo, limits)
        assertNotNull(manager.prepare(conversation, repo.rows, provider, null).checkpoint)
        assertNull(manager.lastFailureCode)
        assertEquals(3, client.requests.size)
        assertTrue(client.requests.last().messages.first().content!!.contains("at least one exact source ID"))
        assertEquals(1, client.requests.map { it.messages.last() }.distinct().size)
        assertTrue(client.requests.all { it.tools.isEmpty() && !it.enableThinking })
        assertEquals(1, repo.saves)
    }

    @Test fun oversizedNoteListsArePackedWithoutLosingTextOrderOrSources() = runTest {
        val rows = transcript()
        val findings = (1..52).map { index -> ContextNote("Recorded finding $index", listOf(rows[index % 8].id)) }
        val repo = Repo(rows)
        val client = Client { listOf(ChatDelta.Content(CheckpointCodec.json.encodeToString(TaskSummary(
            ContextNote("Continue the task", listOf("u1")), findings = findings
        ))), ChatDelta.Done) }
        val manager = TaskContextManager(client, repo, limits)
        val summary = manager.prepare(conversation, rows, provider, null).checkpoint!!.summary
        assertNull(manager.lastFailureCode)
        assertEquals(1, client.requests.size)
        assertTrue(summary.notes().size < findings.size)
        assertEquals(findings.joinToString("\n") { it.text }, summary.findings.joinToString("\n") { it.text })
        assertEquals(findings.flatMap { it.sources }.distinct(), summary.findings.flatMap { it.sources }.distinct())
        assertTrue(summary.notes().all { it.text.length <= 500 && it.sources.size in 1..16 })
    }

    @Test fun repeatedSourceIndexesArePackedEvenWhenTheNoteCountIsAlreadySmall() = runTest {
        val rows = transcript(12).map { it.copy(id = it.id.padEnd(36, 'x')) }
        val sources = rows.take(9).map { it.id }
        val findings = (1..28).map { index -> ContextNote("Finding $index: " + "x".repeat(140), sources) }
        val repo = Repo(rows)
        val original = CheckpointCodec.json.encodeToString(TaskSummary(ContextNote("Continue the task", listOf(rows.first().id)), findings = findings))
        assertTrue(original.length > 14_000 && original.length < 24_000)
        val client = Client { listOf(ChatDelta.Content(original), ChatDelta.Done) }
        val manager = TaskContextManager(client, repo, limits)
        val summary = manager.prepare(conversation, rows, provider, null).checkpoint!!.summary
        assertNull(manager.lastFailureCode)
        assertEquals(1, client.requests.size)
        assertTrue(summary.findings.size < findings.size)
        assertEquals(findings.joinToString("\n") { it.text }, summary.findings.joinToString("\n") { it.text })
        assertTrue(summary.findings.all { it.sources == sources })
    }

    @Test fun largeExistingCheckpointsDoNotStartWithTheSmallOutputLimitAgain() = runTest {
        val rows = transcript()
        val previous = TaskCheckpoint("c", "a2", CheckpointCodec.fingerprint(rows.take(4)),
            TaskSummary(ContextNote("Continue the task", listOf("u1")), findings = (1..20).map {
                ContextNote("Recorded finding $it " + "x".repeat(300), listOf("u1"))
            }), ContextNote("Task", listOf("u1")), emptyList(), emptyList())
        val repo = Repo(rows).also { it.checkpoint = previous }
        val client = Client { listOf(ChatDelta.Content(CheckpointCodec.json.encodeToString(previous.summary)), ChatDelta.Done) }
        val manager = TaskContextManager(client, repo, limits)
        val prepared = manager.prepare(conversation, rows, provider, null, force = true)
        assertNull(manager.lastFailureCode)
        assertNotEquals(previous.throughMessageId, prepared.checkpoint!!.throughMessageId)
        assertTrue(client.requests.isNotEmpty())
        assertTrue(client.requests.all { it.maxTokens == 8_192 })
    }

    @Test fun validSourceRichSummariesAreMeasuredAgainstTheTokenBudgetInsteadOfACharacterCliff() = runTest {
        val rows = transcript(12).map { it.copy(id = it.id.padEnd(36, 'x')) }
        val sources = rows.take(9).map { it.id }
        val findings = (1..20).map { index -> ContextNote("Finding $index: " + "x".repeat(350), sources) }
        val original = CheckpointCodec.json.encodeToString(TaskSummary(ContextNote("Continue", listOf(rows.first().id)), findings = findings))
        assertTrue(original.length > 12_000 && original.length < 24_000)
        assertTrue(ContextBudget.text(original) < 8_000)
        val repo = Repo(rows)
        val client = Client { listOf(ChatDelta.Content(original), ChatDelta.Done) }
        val manager = TaskContextManager(client, repo, limits)
        val prepared = manager.prepare(conversation, rows, provider, null)
        assertNull(manager.lastFailureCode)
        assertEquals(findings, prepared.checkpoint!!.summary.findings)
        assertEquals(1, client.requests.size)
        assertEquals(rows, repo.rows)
    }

    @Test fun formattingTargetsDoNotDiscardValidBoundedEvidence() = runTest {
        val rows = transcript(12).map { it.copy(id = it.id.padEnd(36, 'x')) }
        val objective = ContextNote("Continue", listOf(rows.first().id))
        val summaries = listOf(
            TaskSummary(objective, findings = listOf(ContextNote("x".repeat(510), rows.take(17).map { it.id }))),
            TaskSummary(objective, findings = (1..32).map { ContextNote("Finding $it: " + "x".repeat(260), objective.sources) })
        )
        for (summary in summaries) {
            val encoded = CheckpointCodec.json.encodeToString(summary)
            assertTrue(ContextBudget.text(encoded) < 8_000)
            val repo = Repo(rows)
            val client = Client { listOf(ChatDelta.Content(encoded), ChatDelta.Done) }
            val manager = TaskContextManager(client, repo, limits)
            val prepared = manager.prepare(conversation, rows, provider, null)
            assertNull(manager.lastFailureCode)
            assertEquals(summary, prepared.checkpoint!!.summary)
            assertEquals(1, client.requests.size)
            assertEquals(rows, repo.rows)
        }
    }

    @Test fun serviceRejectionsAreNotRetriedAsSummaryFormattingProblems() = runTest {
        for ((status, explanation) in listOf(402 to "额度不足", 429 to "请求频率", 503 to "503")) {
            val rows = transcript().map { it.copy(content = it.content + "x".repeat(5_000)) }
            val repo = Repo(rows)
            val client = Client { listOf(ChatDelta.Error("HTTP $status: service error")) }
            val manager = TaskContextManager(client, repo, limits)
            val prepared = manager.prepare(conversation, rows, provider, null)
            assertEquals(1, client.requests.size)
            assertEquals("service_$status", manager.lastFailureCode)
            assertEquals(rows, prepared.messages)
            val failure = assertThrows(IllegalStateException::class.java) {
                manager.requireFits(ChatRequestFactory.create(conversation, prepared.messages), conversation.id)
            }
            assertTrue(failure.message!!, failure.message!!.contains(explanation))
        }
    }

    @Test fun compactionProgressIsReportedOnlyWhenCompactionActuallyStarts() = runTest {
        val repo = Repo(transcript())
        val client = Client { listOf(ChatDelta.Error("offline")) }
        val manager = TaskContextManager(client, repo, limits)
        var notifications = 0
        manager.prepare(conversation, transcript(1), provider, null, onCompacting = { notifications++ })
        assertEquals(0, notifications)
        manager.prepare(conversation, repo.rows, provider, null, onCompacting = { notifications++ })
        assertEquals(1, notifications)
        manager.prepare(conversation, repo.rows, provider, null, force = true, onCompacting = { notifications++ })
        assertEquals(1, notifications)
        assertEquals(1, client.requests.size)
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

    @Test fun officialDeepSeekRetainsHundredThousandTokensWithoutPrematureSummary() = runTest {
        val official = provider.copy(baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash")
        val chat = conversation.copy(modelName = official.defaultModel)
        val rows = (1..20).flatMap { index -> listOf(
            Message(id = "long-u$index", conversationId = chat.id, role = MessageRole.User, content = "Q $index"),
            Message(id = "long-a$index", conversationId = chat.id, role = MessageRole.Assistant, content = "x".repeat(15_000))) }
        val client = Client { error("100k must not summarize") }
        val repo = Repo(rows)
        val manager = TaskContextManager(client, repo)
        val prepared = manager.prepare(chat, rows, official, null,
            projectWindow = { ChatRequestFactory.create(chat, it.messages, checkpoint = it.checkpoint) })
        val request = ChatRequestFactory.create(chat, prepared.messages)
        val usage = manager.contextWindow(request, official)
        assertEquals(rows, prepared.messages)
        assertNull(prepared.checkpoint)
        assertTrue(usage.usedTokens > 100_000)
        assertEquals(1_000_000, usage.modelWindowTokens)
        assertEquals(65_536, usage.outputReserveTokens)
        assertEquals(884_464, usage.totalTokens)
        assertFalse(manager.shouldCompact(request, official))
        manager.requireFits(request, chat.id, official)
        assertTrue(client.requests.isEmpty())
        assertThrows(IllegalStateException::class.java) { manager.requireFits(request, chat.id, provider) }
    }

    @Test fun deepSeekWatermarksFollowOutputAndThinkingChangesAndHardBudgetIsEnforced() {
        val official = provider.copy(baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash")
        val manager = TaskContextManager(Client(), Repo(emptyList()))
        fun near(tokens: Int) = ChatRequest(official.defaultModel,
            listOf(ChatRequestMessage("user", "x".repeat((tokens - 1_024) * 3))), 0.7, 1.0, null, usageConversationId = "c")
        manager.recordCompaction(near(800_000), official)
        val at720k = near(720_000)
        assertFalse(manager.shouldCompact(at720k, official))
        assertTrue(manager.shouldCompact(at720k.copy(thinkingEffort = ThinkingEffort.Max), official))
        assertTrue(manager.shouldCompact(at720k.copy(maxTokens = 393_216), official))
        assertFalse(manager.shouldCompact(at720k.copy(enableThinking = false), official))
        assertThrows(IllegalStateException::class.java) { manager.requireFits(near(900_000), "c", official) }
        manager.requireFits(near(900_000).copy(enableThinking = false), "c", official)
        assertTrue(manager.shouldCompact(near(845_000), official))
        assertEquals(120_000L, manager.limitsFor(at720k, official).summaryTimeoutMillis)
    }

    @Test fun deepSeekCompactsByTokenSuffixWithBoundedLargeBatchesAndPreservesMoreThanFourTurns() = runTest {
        val official = provider.copy(baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash")
        val chat = conversation.copy(modelName = official.defaultModel)
        val rows = (1..100).flatMap { index -> listOf(
            Message(id = "large-u$index", conversationId = chat.id, role = MessageRole.User, content = "Q$index " + "u".repeat(15_000)),
            Message(id = "large-a$index", conversationId = chat.id, role = MessageRole.Assistant, content = "a".repeat(15_000))) }
        val repo = Repo(rows)
        val client = Client()
        val manager = TaskContextManager(client, repo)
        val prepared = manager.prepare(chat, rows, official, null,
            projectWindow = { ChatRequestFactory.create(chat, it.messages, checkpoint = it.checkpoint) })
        assertNotNull(prepared.checkpoint)
        assertTrue(prepared.checkpoint!!.matches(rows))
        assertTrue("Token suffix must retain substantially more than four short turns", prepared.messages.count { it.role == MessageRole.User } > 40)
        assertTrue(client.requests.size in 1..4)
        client.requests.forEach { summary ->
            assertFalse(summary.enableThinking)
            assertTrue(ContextBudget.request(summary, official) <= 128_000)
            assertEquals(summary.maxTokens, manager.limitsFor(summary, official).outputReserveTokens)
        }
        val after = ChatRequestFactory.create(chat, prepared.messages, checkpoint = prepared.checkpoint)
        assertTrue(manager.contextWindow(after, official).usedTokens <= manager.limitsFor(after, official).targetTokens)
        manager.recordCompaction(after, official)
        assertFalse(manager.shouldCompact(after, official))
        assertEquals(rows, repo.rows)
    }

    @Test fun officialSummaryAllowsMoreThanThirtySecondsAndStillCancelsWithoutSavingPartialState() = runTest {
        val official = provider.copy(baseUrl = "https://api.deepseek.com", defaultModel = "deepseek-flash")
        val chat = conversation.copy(modelName = official.defaultModel)
        val rows = transcript()
        val repo = Repo(rows)
        val slow = Client { delay(31_000); validSummary(it) }
        val result = TaskContextManager(slow, repo).prepare(chat, rows, official, null, force = true)
        assertNotNull(result.checkpoint)
        val cancelledRepo = Repo(rows)
        val blocked = Client { awaitCancellation() }
        val job = launch { TaskContextManager(blocked, cancelledRepo).prepare(chat, rows, official, null, force = true) }
        runCurrent()
        job.cancelAndJoin()
        assertNull(cancelledRepo.checkpoint)
        assertEquals(rows, cancelledRepo.rows)
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
