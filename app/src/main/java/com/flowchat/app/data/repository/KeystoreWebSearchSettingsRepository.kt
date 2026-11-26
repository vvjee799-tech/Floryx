package com.flowchat.app.data.repository

import com.flowchat.app.data.security.ApiKeyStore
import com.flowchat.app.domain.repository.WebSearchSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreWebSearchSettingsRepository @Inject constructor(
    private val apiKeyStore: ApiKeyStore
) : WebSearchSettingsRepository {
    override suspend fun saveTavilyApiKey(apiKey: String) {
        apiKeyStore.save(TavilyApiKeyAlias, apiKey.trim())
    }

    override suspend fun getTavilyApiKey(): String? =
        apiKeyStore.read(TavilyApiKeyAlias)

    override suspend fun hasTavilyApiKey(): Boolean =
        !getTavilyApiKey().isNullOrBlank()

    private companion object {
        const val TavilyApiKeyAlias = "web_search:tavily"
    }
}
