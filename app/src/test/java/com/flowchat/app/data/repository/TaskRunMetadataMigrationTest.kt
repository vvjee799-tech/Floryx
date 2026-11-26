package com.flowchat.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.db.MIGRATION_16_17
import com.flowchat.app.data.task.TaskRunJournal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskRunMetadataMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tables = listOf("providers", "conversations", "messages", "usage_records", "task_runs")
    private val initialMetadata = """{"probe":"synthetic-original","note":"保留原文\\n\\\""}"""
    private val reviewedMetadata = """{"probe":"synthetic-reviewed","confirmed":true}"""

    @Test fun version16MigratesWithoutChangingExistingRowsOrRelations() = runTest {
        val name = "r4-old.db"
        val before = createVersion16(name)
        val database = open(name)
        try {
            val run = requireNotNull(database.taskRunDao().get("legacy-run"))
            assertNull(run.metadataJson)
            val sqlite = database.openHelper.writableDatabase
            assertEquals(17, sqlite.version)
            assertEquals(before, snapshot(sqlite))
            assertTrue(columns(sqlite, "task_runs").contains("metadataJson"))
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals("legacy-message", run.assistantMessageId)
            assertEquals("WaitingForUser", run.state)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun metadataRoundTripsForActiveAndTerminalRunsWithoutReactivatingThemAndCascadesOnDelete() = runTest {
        val name = "r4-rt.db"
        var database = open(name)
        try {
            val repository = RoomChatRepository(database.conversationDao(), database.messageDao())
            val conversation = repository.createConversation("synthetic-provider", "synthetic-model")
            var journal = TaskRunJournal(database.taskRunDao())
            journal.recoverInterrupted()
            journal.begin("default", conversation.id, "Synthetic instruction", "synthetic-model")
            assertNull(journal.get("default")!!.metadataJson)
            journal.begin("active", conversation.id, "Synthetic instruction", "synthetic-model", initialMetadata)
            assertEquals(initialMetadata, journal.get("active")!!.metadataJson)
            journal.updateMetadata("active", reviewedMetadata)
            assertEquals(reviewedMetadata, journal.get("active")!!.metadataJson)
            journal.state("active", "WaitingForUser")
            journal.updateMetadata("active", null)
            assertNull(journal.get("active")!!.metadataJson)
            journal.updateMetadata("active", initialMetadata)

            val terminalStates = listOf("Completed", "Cancelled", "Failed", "Paused", "Interrupted")
            for (state in terminalStates) {
                val id = "terminal-$state"
                journal.begin(id, conversation.id, "Synthetic terminal instruction", "synthetic-model", initialMetadata)
                journal.state(id, state)
                val beforeReview = requireNotNull(journal.get(id))
                journal.updateMetadata(id, reviewedMetadata)
                val reviewed = requireNotNull(journal.get(id))
                assertEquals(reviewedMetadata, reviewed.metadataJson)
                assertEquals(beforeReview.copy(metadataJson = reviewedMetadata, updatedAt = reviewed.updatedAt), reviewed)
                journal.state(id, "Running")
                journal.attachMessage(id, "late-message")
                assertEquals(reviewed, journal.get(id))
            }
            journal.updateMetadata("missing-run", reviewedMetadata)
            assertNull(journal.get("missing-run"))
            database.close()
            database = open(name)
            journal = TaskRunJournal(database.taskRunDao())
            assertEquals(initialMetadata, journal.get("active")!!.metadataJson)
            for (state in terminalStates) {
                assertEquals(state, journal.get("terminal-$state")!!.state)
                assertEquals(reviewedMetadata, journal.get("terminal-$state")!!.metadataJson)
            }
            RoomChatRepository(database.conversationDao(), database.messageDao()).deleteConversation(conversation.id)
            assertNull(journal.get("active"))
            assertNull(journal.get("default"))
            terminalStates.forEach { assertNull(journal.get("terminal-$it")) }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun interruptedMigrationRollsBackBothSchemaAndRowsAndCanBeRetried() = runTest {
        val name = "r4-tx.db"
        val before = createVersion16(name)
        var reachedMigration = false
        val interrupted = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_16_17.migrate(db)
                reachedMigration = true
                // A throw after the ALTER exercises Room/SQLite's real migration transaction.
                throw IllegalStateException("Synthetic interruption after metadata column was added")
            }
        }
        val failed = open(name, interrupted)
        try {
            val failure = runCatching { failed.openHelper.writableDatabase }.exceptionOrNull()
            assertNotNull("The simulated interruption must fail opening the database", failure)
            assertTrue("The failure must occur inside the migration", reachedMigration)
        } finally {
            failed.close()
        }
        try {
            version16Helper(name).use { helper ->
                val sqlite = helper.writableDatabase
                assertEquals(16, sqlite.version)
                assertFalse(columns(sqlite, "task_runs").contains("metadataJson"))
                assertEquals(before, snapshot(sqlite))
            }
            val retried = open(name)
            try {
                assertNull(retried.taskRunDao().get("legacy-run")!!.metadataJson)
                assertEquals(17, retried.openHelper.writableDatabase.version)
                assertEquals(before, snapshot(retried.openHelper.writableDatabase))
            } finally {
                retried.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun open(name: String, migration: Migration = MIGRATION_16_17): FlowChatDatabase =
        Room.databaseBuilder(context, FlowChatDatabase::class.java, name)
            .addMigrations(migration).allowMainThreadQueries().build()

    @Test fun latestRunSurvivesClockRollback() = runTest {
        val name = "r4-clock.db"
        val database = open(name)
        try {
            val owner = RoomChatRepository(database.conversationDao(), database.messageDao())
                .createConversation("synthetic-provider", "synthetic-model").id
            val dao = database.taskRunDao()
            dao.insert(com.flowchat.app.data.db.TaskRunEntity("earlier", owner, "Synthetic", "model", "Completed", 500, 500))
            dao.insert(com.flowchat.app.data.db.TaskRunEntity("newer", owner, "Synthetic", "model", "Interrupted", 100, 100,
                metadataJson = initialMetadata))
            assertEquals("newer", dao.latest(owner)?.id)
            assertEquals("newer", dao.observeLatest(owner).first()?.id)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    /** A real on-disk version-16 schema, independent of the current generated Room schema. */
    private fun version16Helper(name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(16) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE providers (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, baseUrl TEXT NOT NULL, defaultModel TEXT NOT NULL, customHeadersJson TEXT NOT NULL, apiKeyAlias TEXT, isEnabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, conversationModelsJson TEXT DEFAULT NULL)")
                    db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, providerId TEXT NOT NULL, modelName TEXT NOT NULL, assistantName TEXT NOT NULL, assistantAvatarPath TEXT, showAvatars INTEGER NOT NULL, enableThinking INTEGER NOT NULL, systemPrompt TEXT NOT NULL, temperature REAL NOT NULL, topP REAL NOT NULL, maxTokens INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, thinkingEffort TEXT NOT NULL DEFAULT 'auto', contextCheckpoint TEXT, workspaceJson TEXT, isPinned INTEGER NOT NULL DEFAULT 0, isArchived INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE INDEX index_conversations_providerId ON conversations(providerId)")
                    db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, attachmentName TEXT, attachmentText TEXT, reasoningContent TEXT NOT NULL, reasoningDurationMillis INTEGER NOT NULL, status TEXT NOT NULL, modelName TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, attachmentImagePath TEXT, attachmentMimeType TEXT, replyPhase TEXT NOT NULL DEFAULT 'Answer', executionContext TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
                    db.execSQL("CREATE INDEX index_messages_conversationId_createdAt ON messages(conversationId, createdAt)")
                    db.execSQL("CREATE TABLE usage_records (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL, inputTokens INTEGER, outputTokens INTEGER, cacheHitTokens INTEGER, costLowNanos INTEGER, costHighNanos INTEGER, pricingVersion TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_usage_records_conversationId ON usage_records(conversationId)")
                    db.execSQL("CREATE TABLE task_runs (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, instruction TEXT NOT NULL, modelName TEXT NOT NULL, state TEXT NOT NULL, startedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, assistantMessageId TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_task_runs_conversationId ON task_runs(conversationId)")
                    db.execSQL("CREATE INDEX index_task_runs_state ON task_runs(state)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("The version-16 fixture must never perform an upgrade: $oldVersion -> $newVersion")
                }
            }).build())

    private fun createVersion16(name: String): Map<String, List<Map<String, String?>>> =
        version16Helper(name).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO providers VALUES ('legacy-provider', 'Synthetic provider', 'https://example.test', 'synthetic-model', '{\"keep\":\"header\"}', 'synthetic-alias', 1, 11, 12, '[\"synthetic-model\"]')")
            db.execSQL("INSERT INTO conversations VALUES ('legacy-chat', 'Synthetic retained chat', 'legacy-provider', 'synthetic-model', 'Synthetic assistant', 'synthetic-avatar', 1, 1, 'Retain prompt', 0.5, 0.8, 1024, 21, 22, 'max', 'opaque-checkpoint', '{\"synthetic\":true}', 1, 1)")
            db.execSQL("INSERT INTO messages VALUES ('legacy-message', 'legacy-chat', 'Assistant', 'SAVED_SYNTHETIC_PARTIAL', 'fixture.txt', 'Retain attachment', 'Retain reasoning', 123, 'Streaming', 'synthetic-model', 31, 32, 'synthetic/image.png', 'image/png', 'Commentary', '[{\"outcome\":\"unconfirmed\"}]')")
            db.execSQL("INSERT INTO usage_records VALUES ('legacy-usage', 'legacy-chat', 'synthetic-model', 41, 100, 20, 5, 10, 15, 'synthetic-pricing')")
            db.execSQL("INSERT INTO task_runs VALUES ('legacy-run', 'legacy-chat', 'Retain original instruction', 'synthetic-model', 'WaitingForUser', 51, 52, 'legacy-message')")
            snapshot(db)
        }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<Map<String, String?>>> =
        tables.associateWith { table ->
            db.query("SELECT * FROM `$table` ORDER BY id").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.columnNames.withIndex().filter { it.value != "metadataJson" }.associate { (index, column) ->
                            column to if (cursor.isNull(index)) null else cursor.getString(index)
                        })
                    }
                }
            }
        }
}
