package com.flowchat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

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
