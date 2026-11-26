package com.flowchat.app.data.network

import com.flowchat.app.BuildConfig
import com.flowchat.app.domain.model.WebSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TavilySearchClient @Inject constructor(
    private val httpClient: HttpClient
) : WebSearchClient {
    override suspend fun search(
        query: String,
        fallbackApiKey: String?,
        installId: String
    ): WebSearchResult {
        var proxyFailure: Throwable? = null
        if (BuildConfig.SEARCH_PROXY_URL.isNotBlank()) {
            runCatching {
                return searchBuiltInProxy(query, installId)
            }.onFailure { proxyFailure = it }
        }
        if (!fallbackApiKey.isNullOrBlank()) {
            return searchDirectTavily(query, fallbackApiKey)
        }
        throw IllegalStateException(
            if (BuildConfig.SEARCH_PROXY_URL.isBlank()) {
                "Built-in web search is not configured."
            } else {
                "Built-in web search is temporarily unavailable."
            },
            proxyFailure
        )
    }

    private suspend fun searchBuiltInProxy(query: String, installId: String): WebSearchResult {
        val response = httpClient.post("${BuildConfig.SEARCH_PROXY_URL.trimEnd('/')}/v1/search") {
            header(InstallIdHeader, installId)
            contentType(ContentType.Application.Json)
            setBody(TavilySearchRequest(query = query))
        }
        check(response.status.isSuccess()) { "Search proxy returned ${response.status.value}." }
        return response.body<TavilySearchResponse>().toDomain()
    }

    private suspend fun searchDirectTavily(query: String, apiKey: String): WebSearchResult {
        val response = httpClient.post(TavilySearchEndpoint) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(TavilySearchRequest(query = query))
        }
        check(response.status.isSuccess()) { "Tavily returned ${response.status.value}." }
        return response.body<TavilySearchResponse>().toDomain()
    }

    private companion object {
        const val TavilySearchEndpoint = "https://api.tavily.com/search"
        const val InstallIdHeader = "X-FlowChat-Install-ID"
    }
}
