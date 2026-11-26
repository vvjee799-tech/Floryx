package com.flowchat.app.data.network

import com.flowchat.app.domain.model.WebSearchResult
import com.flowchat.app.domain.model.WebSearchResultItem
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TavilySearchRequest(
    val query: String,
    @EncodeDefault @SerialName("search_depth") val searchDepth: String = "basic",
    @EncodeDefault @SerialName("max_results") val maxResults: Int = 5,
    @EncodeDefault @SerialName("include_answer") val includeAnswer: Boolean = false,
    @EncodeDefault @SerialName("include_raw_content") val includeRawContent: Boolean = false,
    @EncodeDefault @SerialName("include_images") val includeImages: Boolean = false
)

@Serializable
data class TavilySearchResponse(
    val query: String = "",
    val results: List<TavilySearchResultDto> = emptyList()
)

@Serializable
data class TavilySearchResultDto(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val score: Double? = null
)

fun TavilySearchResponse.toDomain(): WebSearchResult =
    WebSearchResult(
        query = query,
        results = results
            .filter { it.url.isNotBlank() && (it.title.isNotBlank() || it.content.isNotBlank()) }
            .take(5)
            .map { result ->
                WebSearchResultItem(
                    title = result.title.ifBlank { result.url },
                    url = result.url,
                    content = result.content
                )
            }
    )
