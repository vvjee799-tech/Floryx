package com.flowchat.app.domain.repository

import com.flowchat.app.domain.model.ProviderConfig
import kotlinx.coroutines.flow.Flow

interface ProviderRepository {
    fun observeProviders(): Flow<List<ProviderConfig>>
    suspend fun getProvider(id: String): ProviderConfig?
    suspend fun getProvidersOnce(): List<ProviderConfig>
    suspend fun upsertProvider(config: ProviderConfig, apiKey: String?)
    suspend fun deleteProvider(id: String)
    suspend fun ensureTemplates()
    suspend fun getApiKey(config: ProviderConfig): String?
}
