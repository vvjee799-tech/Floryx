package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ProviderConfig

interface ModelCatalogClient {
    suspend fun listModels(provider: ProviderConfig, apiKey: String?): List<String>
}
