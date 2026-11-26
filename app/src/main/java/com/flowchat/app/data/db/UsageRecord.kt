package com.flowchat.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "usage_records", indices = [Index("conversationId")], foreignKeys = [
    ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)
])
data class UsageRecord(
    @PrimaryKey val id: String,
    val conversationId: String,
    val model: String,
    val createdAt: Long,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheHitTokens: Long? = null,
    val costLowNanos: Long? = null,
    val costHighNanos: Long? = null,
    val pricingVersion: String = "2026-10-24"
)

@Dao
interface UsageDao {
    @Insert suspend fun insert(record: UsageRecord)
    // UPDATE cannot resurrect a record after its conversation is deleted.
    @Update suspend fun update(record: UsageRecord)
    @Query("SELECT * FROM usage_records WHERE conversationId = :conversationId ORDER BY createdAt")
    fun observe(conversationId: String): Flow<List<UsageRecord>>
}
