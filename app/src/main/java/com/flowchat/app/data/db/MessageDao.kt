package com.flowchat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.TaskCheckpoint
import com.flowchat.app.domain.chat.TaskRecovery
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE (role IN ('Assistant', 'Tool') AND status IN ('Pending', 'Streaming')) " +
        "OR (role = 'Assistant' AND executionContext IS NOT NULL AND executionContext != '')")
    suspend fun getRecoveryCandidates(): List<MessageEntity>

    @Query("SELECT content FROM messages WHERE conversationId = :conversationId AND role = 'User' " +
        "ORDER BY createdAt DESC, updatedAt DESC LIMIT 1")
    suspend fun getLatestUserText(conversationId: String): String?

    @Query("UPDATE messages SET content = :content, status = :status, executionContext = :context, " +
        "updatedAt = :updatedAt WHERE id = :id AND status = :oldStatus AND content = :oldContent " +
        "AND executionContext IS :oldContext")
    suspend fun updateRecoveredMessage(
        id: String,
        content: String,
        status: String,
        context: String?,
        updatedAt: Long,
        oldStatus: String,
        oldContent: String,
        oldContext: String?
    ): Int

    @Transaction
    suspend fun recoverInterruptedTasks(now: Long): Set<String> {
        val userText = mutableMapOf<String, String?>()
        val changed = linkedSetOf<String>()
        for (row in getRecoveryCandidates()) {
            val previous = row.toDomain()
            val latestUser = if (userText.containsKey(row.conversationId)) userText[row.conversationId]
                else getLatestUserText(row.conversationId).also { userText[row.conversationId] = it }
            val recovered = TaskRecovery.recover(previous, latestUser, now)
            if (recovered == previous) continue
            if (updateRecoveredMessage(row.id, recovered.content, recovered.status.name,
                    recovered.executionContext, recovered.updatedAt, row.status, row.content, row.executionContext) == 1) {
                changed += row.conversationId
            }
        }
        return changed
    }

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
