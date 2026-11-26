package com.flowchat.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ReplyPhase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomTaskRecoveryTest {
    private lateinit var database: FlowChatDatabase
    private lateinit var repository: RoomChatRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FlowChatDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomChatRepository(database.conversationDao(), database.messageDao())
    }

    @After fun tearDown() { database.close() }

    @Test fun recoversOrphanedReplyToolAndPendingActionWhilePreservingCheckpoint() = runTest {
        val conversation = repository.createConversation("provider", "model")
        repository.appendMessage(conversation.id, MessageRole.User, "检查文件", MessageStatus.Sent, "model")
        val commentary = repository.appendMessage(conversation.id, MessageRole.Assistant,
            "发现文件存在", MessageStatus.Complete, "model")
        repository.updateMessage(commentary.id, commentary.content, MessageStatus.Complete,
            replyPhase = ReplyPhase.Commentary)
        repository.updateExecutionContext(commentary.id, TaskExecutionHistory.encode(listOf(
            ExecutionStep("read_file", "path=a", "returned", "exists"),
            ExecutionStep("write_file", "path=b", "unconfirmed", "No result recorded")
        )))
        val tool = repository.appendMessage(conversation.id, MessageRole.Tool,
            "write_file\nSaved first bytes", MessageStatus.Streaming, "model")
        val assistant = repository.appendMessage(conversation.id, MessageRole.Assistant,
            "部分回复", MessageStatus.Streaming, "model")
        database.messageDao().putContextCheckpoint(conversation.id, "opaque-checkpoint")

        assertEquals(setOf(conversation.id), repository.recoverInterruptedTasks())

        val messages = repository.getMessages(conversation.id).associateBy { it.id }
        assertEquals(MessageStatus.Stopped, messages[commentary.id]?.status)
        assertEquals(ReplyPhase.Commentary, messages[commentary.id]?.replyPhase)
        assertTrue(messages[commentary.id]!!.executionContext!!.contains("interrupted_unconfirmed"))
        assertTrue(messages[commentary.id]!!.executionContext!!.contains("exists"))
        assertEquals(MessageStatus.Cancelled, messages[tool.id]?.status)
        assertTrue(messages[tool.id]!!.content.startsWith("write_file\nSaved first bytes"))
        assertEquals(MessageStatus.Stopped, messages[assistant.id]?.status)
        assertTrue(messages[assistant.id]!!.content.startsWith("部分回复"))
        assertEquals("opaque-checkpoint", database.messageDao().getContextCheckpoint(conversation.id))

        val reopenedRepository = RoomChatRepository(database.conversationDao(), database.messageDao())
        assertEquals(emptySet<String>(), reopenedRepository.recoverInterruptedTasks())
        assertEquals(messages, reopenedRepository.getMessages(conversation.id).associateBy { it.id })

        val newlyActive = repository.appendMessage(conversation.id, MessageRole.Assistant,
            "new process", MessageStatus.Streaming, "model")
        assertEquals(setOf(conversation.id), repository.recoverInterruptedTasks())
        assertEquals(MessageStatus.Streaming, repository.getMessages(conversation.id).single { it.id == newlyActive.id }.status)
    }

    @Test fun leavesCompletedMessagesAndOtherConversationsUntouched() = runTest {
        val active = repository.createConversation("provider", "model")
        repository.appendMessage(active.id, MessageRole.User, "Try", MessageStatus.Sent, "model")
        repository.appendMessage(active.id, MessageRole.Assistant, "partial", MessageStatus.Pending, "model")
        val finished = repository.createConversation("provider", "model")
        val answer = repository.appendMessage(finished.id, MessageRole.Assistant,
            "verified answer", MessageStatus.Complete, "model")

        assertEquals(setOf(active.id), repository.recoverInterruptedTasks())
        assertEquals(answer, repository.getMessages(finished.id).single())
    }
}
