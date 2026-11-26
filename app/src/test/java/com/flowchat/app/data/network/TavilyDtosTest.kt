package com.flowchat.app.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilyDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun searchRequestUsesBasicSearchWithFiveTextResults() {
        val payload = json.encodeToString(TavilySearchRequest(query = "latest Android news"))

        assertTrue(payload.contains(""""query":"latest Android news""""))
        assertTrue(payload.contains(""""search_depth":"basic""""))
        assertTrue(payload.contains(""""max_results":5"""))
        assertTrue(payload.contains(""""include_answer":false"""))
        assertTrue(payload.contains(""""include_raw_content":false"""))
        assertTrue(payload.contains(""""include_images":false"""))
    }

    @Test
    fun responseMapsOnlyUsefulResultsToDomainModel() {
        val response = json.decodeFromString<TavilySearchResponse>(
            """
            {
              "query": "flow chat",
              "results": [
                {
                  "title": "Result A",
                  "url": "https://example.com/a",
                  "content": "Summary A",
                  "score": 0.9
                },
                {
                  "title": "",
                  "url": "",
                  "content": "",
                  "score": 0.1
                }
              ]
            }
            """.trimIndent()
        ).toDomain()

        assertEquals("flow chat", response.query)
        assertEquals(1, response.results.size)
        assertEquals("Result A", response.results.first().title)
        assertEquals("https://example.com/a", response.results.first().url)
        assertEquals("Summary A", response.results.first().content)
        assertFalse(response.results.any { it.url.isBlank() })
    }
}
