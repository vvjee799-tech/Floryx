package com.flowchat.app.domain.repository

interface WebSearchSettingsRepository {
    suspend fun saveTavilyApiKey(apiKey: String)
    suspend fun getTavilyApiKey(): String?
    suspend fun hasTavilyApiKey(): Boolean
}
