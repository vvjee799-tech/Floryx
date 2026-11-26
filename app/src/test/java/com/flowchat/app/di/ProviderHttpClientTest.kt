package com.flowchat.app.di

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderHttpClientTest {
    @Test
    fun providerClientNeverForwardsCredentialsAfterRedirects() = runTest {
        val statuses = listOf(HttpStatusCode.Found, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect)
        val destinations = listOf("http://public.example/stolen", "https://another.example/stolen")

        statuses.forEach { status ->
            destinations.forEach { destination ->
                val visited = mutableListOf<String>()
                val originalUrl = "http://127.0.0.1:11434/v1/models"
                val engineFactory = object : HttpClientEngineFactory<MockEngineConfig> {
                    override fun create(block: MockEngineConfig.() -> Unit): MockEngine = MockEngine {
                        visited += it.url.toString()
                        assertEquals("Bearer test-bearer", it.headers[HttpHeaders.Authorization])
                        assertEquals("test-api-key", it.headers["x-api-key"])
                        assertEquals("test-custom-key", it.headers["X-Custom-Key"])
                        respond("", status, headersOf(HttpHeaders.Location, destination))
                    }
                }

                AppModule.createProviderHttpClient(Json, engineFactory).use { client ->
                    val response = client.get(originalUrl) {
                        header(HttpHeaders.Authorization, "Bearer test-bearer")
                        header("x-api-key", "test-api-key")
                        header("X-Custom-Key", "test-custom-key")
                    }

                    assertEquals(status, response.status)
                }
                assertEquals("status=$status destination=$destination", listOf(originalUrl), visited)
            }
        }
    }
}
