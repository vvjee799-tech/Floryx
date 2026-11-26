package com.flowchat.app.data.network

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun openAiCompatibleModelsResponseMapsDataIds() {
        val response = json.decodeFromString<OpenAiModelsResponse>(
            """
            {
              "object": "list",
              "data": [
                { "id": "gpt-4o-mini", "object": "model" },
                { "id": "deepseek-v4-pro", "object": "model" },
                { "id": "", "object": "model" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("deepseek-v4-pro", "gpt-4o-mini"), response.toModelIds())
    }

    @Test
    fun ollamaTagsResponseMapsModelNames() {
        val response = json.decodeFromString<OllamaTagsResponse>(
            """
            {
              "models": [
                { "name": "llama3.2", "model": "llama3.2" },
                { "name": "qwen2.5:7b", "model": "qwen2.5:7b" },
                { "name": "" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("llama3.2", "qwen2.5:7b"), response.toModelNames())
    }

    @Test
    fun modelMappingRemovesDuplicatesAndBlankValues() {
        val response = OpenAiModelsResponse(
            data = listOf(
                OpenAiModelDto(id = "beta"),
                OpenAiModelDto(id = "alpha"),
                OpenAiModelDto(id = "beta"),
                OpenAiModelDto(id = " ")
            )
        )

        assertEquals(listOf("alpha", "beta"), response.toModelIds())
    }

    @Test
    fun malformedModelListJsonFailsParsing() {
        val result = runCatching {
            json.decodeFromString<OpenAiModelsResponse>("not-json")
        }

        assertTrue(result.exceptionOrNull() is SerializationException)
    }

    @Test
    fun ktorModelCatalogClientHandlesAuthErrorsAndOllamaFallback() {
        val source = File("src/main/java/com/flowchat/app/data/network/KtorModelCatalogClient.kt").readText()

        assertTrue(source.contains("baseUrl.trimEnd('/') + \"/models\""))
        assertTrue(source.contains("HttpStatusCode.Unauthorized"))
        assertTrue(source.contains("HttpStatusCode.Forbidden"))
        assertTrue(source.contains("请先填写或保存 API Key"))
        assertTrue(source.contains("header(HttpHeaders.Authorization, \"Bearer ${'$'}apiKey\")"))
        assertTrue(source.contains("shouldTryOllamaTags()"))
        assertTrue(source.contains("\"${'$'}withoutApi/api/tags\""))
    }
}
