package com.flowchat.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY displayName ASC")
    fun observeProviders(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProvider(id: String): ProviderConfigEntity?

    @Query("SELECT * FROM providers ORDER BY createdAt ASC")
    suspend fun getAllProvidersOnce(): List<ProviderConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(providers: List<ProviderConfigEntity>)

    @Query("DELETE FROM providers WHERE id != :id")
    suspend fun deleteAllExcept(id: String)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)
}
