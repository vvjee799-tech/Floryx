package com.flowchat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC, id ASC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("SELECT c.id FROM conversations c WHERE c.title LIKE :pattern ESCAPE '\\' OR EXISTS " +
        "(SELECT 1 FROM messages m WHERE m.conversationId = c.id AND " +
        "(m.content LIKE :pattern ESCAPE '\\' OR m.attachmentName LIKE :pattern ESCAPE '\\' " +
        "OR m.attachmentText LIKE :pattern ESCAPE '\\'))")
    fun searchConversationIds(pattern: String): Flow<List<String>>

    @Query(
        "UPDATE conversations SET assistantName = :assistantName, assistantAvatarPath = :assistantAvatarPath, " +
            "showAvatars = :showAvatars, enableThinking = :enableThinking, thinkingEffort = :thinkingEffort, " +
            "systemPrompt = :systemPrompt, temperature = :temperature, " +
            "topP = :topP, maxTokens = :maxTokens, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateSettings(
        id: String,
        assistantName: String,
        assistantAvatarPath: String?,
        showAvatars: Boolean,
        enableThinking: Boolean,
        systemPrompt: String,
        temperature: Double,
        topP: Double,
        maxTokens: Int?,
        updatedAt: Long,
        thinkingEffort: String
    )

    @Query("UPDATE conversations SET enableThinking = :enabled, thinkingEffort = :effort, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateThinkingSettings(id: String, enabled: Boolean, effort: String, updatedAt: Long)

    @Query("UPDATE conversations SET providerId = :providerId, modelName = :modelName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateModel(id: String, providerId: String, modelName: String, updatedAt: Long)

    @Query("UPDATE conversations SET workspaceJson = :workspaceJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateWorkspace(id: String, workspaceJson: String?, updatedAt: Long)

    @Query("UPDATE conversations SET providerId = :providerId, modelName = :modelName, updatedAt = :updatedAt")
    suspend fun pointAllToProvider(providerId: String, modelName: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}
