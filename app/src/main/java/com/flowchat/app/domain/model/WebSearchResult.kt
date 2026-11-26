package com.flowchat.app.domain.model

data class WebSearchResult(
    val query: String,
    val results: List<WebSearchResultItem>
)

data class WebSearchResultItem(
    val title: String,
    val url: String,
    val content: String
)
