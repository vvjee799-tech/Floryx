package com.flowchat.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** Execution metadata; tool arguments and verified results remain in message executionContext. */
@Entity(
    tableName = "task_runs",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index("state")]
)
data class TaskRunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val instruction: String,
    val modelName: String,
    val state: String,
    val startedAt: Long,
    val updatedAt: Long,
    val assistantMessageId: String? = null
)

@Dao
interface TaskRunDao {
    @Insert suspend fun insert(run: TaskRunEntity)
    @Query("SELECT * FROM task_runs WHERE id = :id") suspend fun get(id: String): TaskRunEntity?
    @Query("SELECT * FROM task_runs WHERE conversationId = :conversationId ORDER BY startedAt DESC, rowid DESC LIMIT 1")
    suspend fun latest(conversationId: String): TaskRunEntity?
    @Query("UPDATE task_runs SET state = :state, updatedAt = :now WHERE id = :id AND state IN ('Running', 'WaitingForUser')")
    suspend fun updateState(id: String, state: String, now: Long)
    @Query("UPDATE task_runs SET assistantMessageId = :messageId, updatedAt = :now WHERE id = :id AND state IN ('Running', 'WaitingForUser')")
    suspend fun attachMessage(id: String, messageId: String, now: Long)
    @Query("UPDATE task_runs SET state = 'Interrupted', updatedAt = :now WHERE state IN ('Running', 'WaitingForUser')")
    suspend fun recoverInterrupted(now: Long)
}
