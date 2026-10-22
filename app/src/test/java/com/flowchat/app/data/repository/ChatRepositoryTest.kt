package com.flowchat.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private lateinit var database: FlowChatDatabase
    private lateinit var repository: RoomChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FlowChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomChatRepository(database.conversationDao(), database.messageDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createsConversationAndAppendsMessages() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "gpt-4o-mini")
        repository.appendMessage(conversation.id, MessageRole.User, "Hello", MessageStatus.Sent, "gpt-4o-mini")

        val messages = repository.observeMessages(conversation.id).first()

        assertEquals(1, messages.size)
        assertEquals("Hello", messages.single().content)
        assertEquals(MessageRole.User, messages.single().role)
    }

    @Test
    fun persistsTextAttachmentAndUpdatesConversationTitle() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "gpt-test")

        repository.appendMessage(
            conversationId = conversation.id,
            role = MessageRole.User,
            content = "请总结",
            status = MessageStatus.Sent,
            modelName = "gpt-test",
            attachmentName = "notes.txt",
            attachmentText = "附件正文"
        )
        repository.updateConversationTitle(conversation.id, "请总结")

        val savedMessage = repository.getMessages(conversation.id).single()
        assertEquals("notes.txt", savedMessage.attachmentName)
        assertEquals("附件正文", savedMessage.attachmentText)
        assertEquals("请总结", repository.getConversation(conversation.id)?.title)
    }

    @Test
    fun marksAssistantMessageFailedForRetry() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "gpt-4o-mini")
        val assistant = repository.appendMessage(
            conversation.id,
            MessageRole.Assistant,
            "",
            MessageStatus.Streaming,
            "gpt-4o-mini"
        )

        repository.markMessageFailed(assistant.id, "Unauthorized")

        val messages = repository.observeMessages(conversation.id).first()
        assertEquals(MessageStatus.Failed, messages.single().status)
        assertEquals("Unauthorized", messages.single().content)
    }

    @Test
    fun updatesAssistantMessageWithSeparateReasoningContent() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "deepseek-v4-pro")
        val assistant = repository.appendMessage(
            conversation.id,
            MessageRole.Assistant,
            "",
            MessageStatus.Streaming,
            "deepseek-v4-pro"
        )

        repository.updateMessage(
            id = assistant.id,
            content = "Final answer",
            reasoningContent = "Visible reasoning",
            status = MessageStatus.Complete
        )

        val messages = repository.observeMessages(conversation.id).first()
        assertEquals("Final answer", messages.single().content)
        assertEquals("Visible reasoning", messages.single().reasoningContent)
        assertEquals(MessageStatus.Complete, messages.single().status)
    }

    @Test
    fun preservesReasoningDurationWhenOnlyFinalContentChanges() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "deepseek-v4-pro")
        val assistant = repository.appendMessage(
            conversation.id,
            MessageRole.Assistant,
            "",
            MessageStatus.Streaming,
            "deepseek-v4-pro"
        )

        repository.updateMessage(
            id = assistant.id,
            content = "",
            reasoningContent = "Visible reasoning",
            status = MessageStatus.Streaming,
            reasoningDurationMillis = 2400L
        )
        repository.updateMessage(
            id = assistant.id,
            content = "Final answer",
            reasoningContent = "Visible reasoning",
            status = MessageStatus.Complete
        )

        val message = repository.observeMessages(conversation.id).first().single()
        assertEquals("Final answer", message.content)
        assertEquals("Visible reasoning", message.reasoningContent)
        assertEquals(2400L, message.reasoningDurationMillis)
        assertEquals(MessageStatus.Complete, message.status)
    }

    @Test
    fun deletesConversationWithMessages() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "gpt-4o-mini")
        repository.appendMessage(conversation.id, MessageRole.User, "Hello", MessageStatus.Sent, "gpt-4o-mini")

        repository.deleteConversation(conversation.id)

        assertEquals(emptyList<Any>(), repository.observeConversations().first())
        assertEquals(emptyList<Any>(), repository.observeMessages(conversation.id).first())
    }

    @Test
    fun updatesAssistantProfileWithConversationSettings() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "gpt-4o-mini")

        repository.updateConversationSettings(
            id = conversation.id,
            assistantName = "毅凯",
            assistantAvatarPath = "/avatar/custom.jpg",
            showAvatars = false,
            enableThinking = false,
            systemPrompt = "用轻松语气回复",
            temperature = 0.8,
            topP = 0.9,
            maxTokens = 1024
        )

        val saved = repository.getConversation(conversation.id)!!
        assertEquals("毅凯", saved.assistantName)
        assertEquals("/avatar/custom.jpg", saved.assistantAvatarPath)
        assertEquals("用轻松语气回复", saved.systemPrompt)
        assertEquals(0.8, saved.temperature, 0.0)
        assertEquals(0.9, saved.topP, 0.0)
        assertEquals(1024, saved.maxTokens)
    }

    @Test
    fun updatesAvatarVisibilityWithConversationSettings() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "gpt-4o-mini")
        assertEquals(false, conversation.showAvatars)

        repository.updateConversationSettings(
            id = conversation.id,
            assistantName = "",
            assistantAvatarPath = null,
            showAvatars = true,
            enableThinking = false,
            systemPrompt = "",
            temperature = 0.7,
            topP = 1.0,
            maxTokens = null
        )

        val saved = repository.getConversation(conversation.id)!!
        assertEquals(true, saved.showAvatars)
    }

    @Test
    fun updatesThinkingModeWithConversationSettings() = runTest {
        val conversation = repository.createConversation(providerId = "p1", modelName = "deepseek-v4-pro")
        assertEquals(true, conversation.enableThinking)

        repository.updateConversationSettings(
            id = conversation.id,
            assistantName = "",
            assistantAvatarPath = null,
            showAvatars = false,
            enableThinking = true,
            systemPrompt = "",
            temperature = 0.7,
            topP = 1.0,
            maxTokens = null
        )

        val saved = repository.getConversation(conversation.id)!!
        assertEquals(true, saved.enableThinking)
    }
}
