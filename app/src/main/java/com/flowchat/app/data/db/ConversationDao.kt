package com.flowchat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

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
