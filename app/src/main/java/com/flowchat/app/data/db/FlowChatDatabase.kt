package com.flowchat.app.data.db

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderConfigEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        UsageRecord::class
    ],
    version = 15,
    exportSchema = false
)
abstract class FlowChatDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun usageDao(): UsageDao
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE providers ADD COLUMN conversationModelsJson TEXT DEFAULT NULL")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_createdAt ON messages(conversationId, createdAt)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN workspaceJson TEXT")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN assistantName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversations ADD COLUMN assistantAvatarPath TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN showAvatars INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN enableThinking INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoningDurationMillis INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentName TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentText TEXT")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN thinkingEffort TEXT NOT NULL DEFAULT 'auto'")
        // Previous builds always enabled thinking on outgoing requests, regardless of this dormant flag.
        db.execSQL("UPDATE conversations SET enableThinking = 1")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentImagePath TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentMimeType TEXT")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN replyPhase TEXT NOT NULL DEFAULT 'Answer'")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN executionContext TEXT")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN contextCheckpoint TEXT")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_records (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL, inputTokens INTEGER, outputTokens INTEGER, cacheHitTokens INTEGER, costLowNanos INTEGER, costHighNanos INTEGER, pricingVersion TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_records_conversationId ON usage_records(conversationId)")
    }
}
