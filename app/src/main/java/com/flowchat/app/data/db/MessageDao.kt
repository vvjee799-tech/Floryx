package com.flowchat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.TaskCheckpoint
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    @Query("UPDATE messages SET executionContext = :context WHERE id = :id")
    suspend fun updateExecutionContext(id: String, context: String)

    @Query("SELECT contextCheckpoint FROM conversations WHERE id = :conversationId")
    suspend fun getContextCheckpoint(conversationId: String): String?

    @Query("UPDATE conversations SET contextCheckpoint = :value WHERE id = :conversationId")
    suspend fun putContextCheckpoint(conversationId: String, value: String): Int

    @Transaction
    suspend fun saveContextCheckpoint(checkpoint: TaskCheckpoint, expected: TaskCheckpoint?): Boolean {
        if (CheckpointCodec.decode(getContextCheckpoint(checkpoint.conversationId)) != expected) return false
        if (!checkpoint.matches(getMessages(checkpoint.conversationId).map { it.toDomain() })) return false
        return putContextCheckpoint(checkpoint.conversationId, CheckpointCodec.encode(checkpoint)) == 1
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query(
        "UPDATE messages SET content = :content, reasoningContent = :reasoningContent, " +
            "reasoningDurationMillis = COALESCE(:reasoningDurationMillis, reasoningDurationMillis), " +
            "status = :status, updatedAt = :updatedAt, replyPhase = COALESCE(:replyPhase, replyPhase) WHERE id = :id"
    )
    suspend fun updateContentReasoningAndStatus(
        id: String,
        content: String,
        reasoningContent: String,
        reasoningDurationMillis: Long?,
        status: String,
        updatedAt: Long,
        replyPhase: String?
    )

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}
