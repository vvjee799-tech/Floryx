package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogUrlSafetyTest {
    @Test
    fun publicCleartextAndMalformedUrlsNeverReachTheHttpEngineWithCredentials() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            error("An invalid provider must never send a request")
        }
        val unsafeUrls = listOf(
            "http://api.example.com/v1", "http://10.1.2.3.example/v1",
            "http://127.0.0.1.example/v1", "http://127.0.0.1@public.example/v1",
            "http://generativelanguage.googleapis.com/v1beta/openai", "not a URL", ""
        )

        HttpClient(engine).use { http ->
            val catalog = KtorModelCatalogClient(http, Json)
            unsafeUrls.forEach { url ->
                val provider = ProviderConfig(displayName = "Test", baseUrl = url, defaultModel = "")
                val failure = runCatching { catalog.listModels(provider, "test-secret") }.exceptionOrNull()

                assertTrue("url=$url failure=$failure", failure is ModelCatalogException)
                assertTrue(failure?.message.orEmpty().startsWith("Invalid provider:"))
                assertFalse(failure?.message.orEmpty().contains("test-secret"))
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun httpsAndLocalHttpContinueToSendAuthenticatedModelRequests() = runTest {
        val safeUrls = listOf("https://api.example.com/v1", "http://localhost:11434/v1",
            "http://10.1.2.3:11434/v1", "http://[::1]:11434/v1")
        var calls = 0
        val engine = MockEngine { request ->
            calls += 1
            assertEquals("Bearer test-secret", request.headers[HttpHeaders.Authorization])
            assertEquals("/v1/models", request.url.encodedPath)
            respond("""{"data":[{"id":"local-model"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

        HttpClient(engine).use { http ->
            val catalog = KtorModelCatalogClient(http, Json)
            safeUrls.forEach { url ->
                val provider = ProviderConfig(displayName = "", baseUrl = url, defaultModel = "")
                assertEquals("url=$url", listOf("local-model"), catalog.listModels(provider, "test-secret"))
            }
        }
        assertEquals(safeUrls.size, calls)
    }
}
