package com.flowchat.app.data.network

import com.flowchat.app.domain.model.WebSearchResult

interface WebSearchClient {
    suspend fun search(query: String, fallbackApiKey: String?, installId: String): WebSearchResult
}
