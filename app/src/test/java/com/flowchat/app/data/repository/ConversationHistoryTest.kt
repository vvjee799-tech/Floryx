package com.flowchat.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationHistoryTest {
    private lateinit var database: FlowChatDatabase
    private lateinit var repository: RoomChatRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FlowChatDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomChatRepository(database.conversationDao(), database.messageDao())
    }
    @After fun close() = database.close()

    @Test fun searchFindsBodyAndTreatsWildcardCharactersLiterally() = runTest {
        val a = repository.createConversation("p", "local")
        val b = repository.createConversation("p", "local")
        repository.updateConversationTitle(a.id, "项目 Alpha")
        repository.appendMessage(b.id, MessageRole.User, "结果完成 100%_saved", MessageStatus.Sent, "local")
        assertEquals(setOf(a.id), repository.searchConversationIds("alpha").first())
        assertEquals(setOf(b.id), repository.searchConversationIds("结果").first())
        assertEquals(setOf(b.id), repository.searchConversationIds("%_").first())
        assertEquals(emptySet<String>(), repository.searchConversationIds("100__").first())
    }

    @Test fun pinArchiveAndRenameSurviveRepositoryRecreation() = runTest {
        val a = repository.createConversation("p", "local")
        val b = repository.createConversation("p", "local")
        repository.setConversationPinned(a.id, true)
        repository.setConversationArchived(a.id, true)
        repository.updateConversationTitle(a.id, "保留记录")
        val reopened = RoomChatRepository(database.conversationDao(), database.messageDao())
        val saved = reopened.getConversation(a.id)!!
        assertTrue(saved.isPinned); assertTrue(saved.isArchived)
        assertEquals("保留记录", saved.title)
        assertEquals(a.id, reopened.observeConversations().first().first().id)
        assertNotNull(reopened.getConversation(b.id))
        reopened.setConversationArchived(a.id, false)
        assertFalse(reopened.getConversation(a.id)!!.isArchived)
    }

    @Test fun pagesAreBoundedStableAndNeverDiscardModelHistory() = runTest {
        val chat = repository.createConversation("p", "local")
        repeat(205) { index -> repository.appendMessage(chat.id, MessageRole.User, "message-$index", MessageStatus.Sent, "local") }
        val all = repository.getMessages(chat.id)
        val first = repository.observeMessageWindow(chat.id, 80).first()
        val second = repository.observeMessageWindow(chat.id, 160).first()
        val final = repository.observeMessageWindow(chat.id, 240).first()
        assertEquals(all.takeLast(80).map { it.id }, first.messages.map { it.id })
        assertTrue(first.hasOlder); assertTrue(second.hasOlder); assertFalse(final.hasOlder)
        assertEquals(first.messages.map { it.id }, second.messages.takeLast(80).map { it.id })
        assertEquals(205, final.messages.size)
        assertEquals(all, repository.getMessages(chat.id))
    }
}
