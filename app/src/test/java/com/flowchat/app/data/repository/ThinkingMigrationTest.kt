package com.flowchat.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.db.MIGRATION_6_7
import com.flowchat.app.data.db.MIGRATION_8_9
import com.flowchat.app.domain.model.ReplyPhase
import com.flowchat.app.data.db.MIGRATION_7_8
import com.flowchat.app.domain.model.ThinkingEffort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThinkingMigrationTest {
    @Test fun migrationPreservesHistoryAndSettingsSurviveDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "thinking-migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE providers (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, baseUrl TEXT NOT NULL, defaultModel TEXT NOT NULL, customHeadersJson TEXT NOT NULL, apiKeyAlias TEXT, isEnabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, providerId TEXT NOT NULL, modelName TEXT NOT NULL, assistantName TEXT NOT NULL, assistantAvatarPath TEXT, showAvatars INTEGER NOT NULL, enableThinking INTEGER NOT NULL, systemPrompt TEXT NOT NULL, temperature REAL NOT NULL, topP REAL NOT NULL, maxTokens INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE INDEX index_conversations_providerId ON conversations(providerId)")
                    db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, attachmentName TEXT, attachmentText TEXT, reasoningContent TEXT NOT NULL, reasoningDurationMillis INTEGER NOT NULL, status TEXT NOT NULL, modelName TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
                    db.execSQL("INSERT INTO providers VALUES ('p', 'DeepSeek', 'https://api.deepseek.com', 'deepseek-v4-pro', '{}', 'existing-alias', 1, 1, 1)")
                    db.execSQL("INSERT INTO conversations VALUES ('c', 'Existing chat', 'p', 'deepseek-v4-pro', 'Assistant', NULL, 1, 0, 'Keep this prompt', 0.7, 1.0, NULL, 1, 1)")
                    db.execSQL("INSERT INTO messages VALUES ('m', 'c', 'User', 'Keep this message', NULL, NULL, '', 0, 'Sent', NULL, 1, 1)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        fun open() = Room.databaseBuilder(context, FlowChatDatabase::class.java, name).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val first = open()
            try {
                val db = first
                val repository = RoomChatRepository(db.conversationDao(), db.messageDao())
                val migrated = repository.getConversation("c")!!
                assertTrue(migrated.enableThinking)
                assertEquals(ThinkingEffort.Auto, migrated.thinkingEffort)
                assertEquals("Keep this prompt", migrated.systemPrompt)
                assertEquals("Keep this message", repository.getMessages("c").single().content)
                assertNull(repository.getMessages("c").single().attachmentImagePath)
                assertEquals(ReplyPhase.Answer, repository.getMessages("c").single().replyPhase)
                repository.updateMessage("m", "Keep this message", com.flowchat.app.domain.model.MessageStatus.Sent, replyPhase = ReplyPhase.Commentary)
                repository.updateThinkingSettings("c", false, ThinkingEffort.Max)
                repository.appendMessage("c", com.flowchat.app.domain.model.MessageRole.User, "Image caption",
                    com.flowchat.app.domain.model.MessageStatus.Sent, "deepseek-flash", "photo.png", null,
                    "attachments/images/migration.png", "image/png")
            } finally { first.close() }
            val second = open()
            try {
                val db = second
                val repository = RoomChatRepository(db.conversationDao(), db.messageDao())
                assertFalse(repository.getConversation("c")!!.enableThinking)
                assertEquals(ThinkingEffort.Max, repository.getConversation("c")!!.thinkingEffort)
                assertEquals("Keep this prompt", repository.getConversation("c")!!.systemPrompt)
                assertEquals("Keep this message", repository.getMessages("c").first { it.id == "m" }.content)
                val image = repository.getMessages("c").last()
                assertEquals("attachments/images/migration.png", image.attachmentImagePath)
                assertEquals("image/png", image.attachmentMimeType)
                assertEquals("existing-alias", db.providerDao().getProvider("p")?.apiKeyAlias)
                assertEquals(ReplyPhase.Commentary, repository.getMessages("c").first { it.id == "m" }.replyPhase)
            } finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }
}
