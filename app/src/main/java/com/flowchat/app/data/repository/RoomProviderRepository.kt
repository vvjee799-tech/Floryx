package com.flowchat.app.data.repository

import com.flowchat.app.data.db.ProviderDao
import com.flowchat.app.data.db.ConversationDao
import com.flowchat.app.data.db.toDomain
import com.flowchat.app.data.db.toEntity
import com.flowchat.app.data.security.ApiKeyStore
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.provider.ProviderTemplates
import com.flowchat.app.domain.provider.cleanModelSelection
import com.flowchat.app.domain.repository.ProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class RoomProviderRepository @Inject constructor(
    private val providerDao: ProviderDao,
    private val conversationDao: ConversationDao,
    private val apiKeyStore: ApiKeyStore
) : ProviderRepository {
    override fun observeProviders(): Flow<List<ProviderConfig>> =
        providerDao.observeProviders().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getProvider(id: String): ProviderConfig? = providerDao.getProvider(id)?.toDomain()

    override suspend fun getProvidersOnce(): List<ProviderConfig> =
        providerDao.getAllProvidersOnce().map { it.toDomain() }

    override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) {
        val existingAlias = providerDao.getProvider(config.id)?.apiKeyAlias
        val alias = if (!apiKey.isNullOrBlank()) {
            config.apiKeyAlias ?: existingAlias ?: "provider:${config.id}"
        } else {
            config.apiKeyAlias ?: existingAlias
        }
        if (!apiKey.isNullOrBlank()) {
            apiKeyStore.save(requireNotNull(alias), apiKey.trim())
        }
        val models = config.conversationModels?.let { cleanModelSelection(it + config.defaultModel) }
        providerDao.upsert(config.copy(apiKeyAlias = alias, conversationModels = models, updatedAt = System.currentTimeMillis()).toEntity())
    }

    override suspend fun deleteProvider(id: String) {
        providerDao.getProvider(id)?.apiKeyAlias?.let { apiKeyStore.delete(it) }
        providerDao.delete(id)
    }

    override suspend fun ensureTemplates() {
        val existing = providerDao.getAllProvidersOnce().map { it.toDomain() }
        val seed = existing.firstOrNull { it.id == ProviderTemplates.CUSTOM_PROVIDER_ID }
            ?: existing.firstOrNull { it.id == "template-custom" }
            ?: ProviderTemplates.defaultCustomProvider()
        val custom = seed.copy(
            id = ProviderTemplates.CUSTOM_PROVIDER_ID,
            displayName = seed.displayName.ifBlank { "Custom OpenAI-compatible" },
            baseUrl = seed.baseUrl,
            defaultModel = seed.defaultModel,
            updatedAt = System.currentTimeMillis()
        )
        providerDao.upsert(custom.toEntity())
    }

    override suspend fun getApiKey(config: ProviderConfig): String? =
        config.apiKeyAlias?.let { apiKeyStore.read(it) }
}
