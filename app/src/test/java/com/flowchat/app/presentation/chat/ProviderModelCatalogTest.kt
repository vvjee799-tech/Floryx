package com.flowchat.app.presentation.chat

import com.flowchat.app.data.network.ModelCatalogClient
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.repository.ProviderRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderModelCatalogTest {
    private val gemini = ProviderConfig(id = "g", displayName = "Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai", defaultModel = "gemini-3.5-flash", updatedAt = 1)
    private class Providers(var values: List<ProviderConfig>) : ProviderRepository {
        override fun observeProviders() = MutableStateFlow(values)
        override suspend fun getProvidersOnce() = values
        override suspend fun getProvider(id: String) = values.find { it.id == id }
        override suspend fun getApiKey(config: ProviderConfig) = "secret-test-key"
        override suspend fun ensureTemplates() = Unit
        override suspend fun deleteProvider(id: String) = Unit
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = Unit
    }

    @Test fun menuLoadsOnlyDirectGeminiAndCachesUntilItsConfigurationChanges() = runTest {
        val repo = Providers(listOf(gemini, gemini.copy(id = "gateway", baseUrl = "https://gateway.test/v1")))
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val client = object : ModelCatalogClient {
            override suspend fun listModels(provider: ProviderConfig, apiKey: String?): List<String> {
                calls++; assertEquals("g", provider.id); assertEquals("secret-test-key", apiKey)
                gate.await(); return listOf("gemini-3.5-flash", "gemini-3.8-flash")
            }
        }
        val catalog = ProviderModelCatalog(this, repo, client)
        catalog.load(); runCurrent()
        assertTrue(catalog.state.value["g"] is ProviderModelOptions.Loading)
        catalog.load(); runCurrent(); assertEquals(1, calls)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.8-flash"), (catalog.state.value.getValue("g") as ProviderModelOptions.Ready).names)
        catalog.load(); advanceUntilIdle(); assertEquals(1, calls)
        repo.values = listOf(gemini.copy(updatedAt = 2))
        catalog.load(); advanceUntilIdle(); assertEquals(2, calls)
    }

    @Test fun failureIsRetryableAndNeverExposesTheCredential() = runTest {
        var calls = 0
        val client = object : ModelCatalogClient {
            override suspend fun listModels(provider: ProviderConfig, apiKey: String?): List<String> {
                calls++; if (calls == 1) error("Invalid secret-test-key")
                return listOf("gemini-2.5-pro")
            }
        }
        val catalog = ProviderModelCatalog(this, Providers(listOf(gemini)), client)
        catalog.load(); advanceUntilIdle()
        val failure = catalog.state.value.getValue("g") as ProviderModelOptions.Failed
        assertFalse(failure.message.contains("secret-test-key"))
        catalog.load(); advanceUntilIdle()
        assertTrue(catalog.state.value["g"] is ProviderModelOptions.Ready)
        assertEquals(2, calls)
    }

    @Test fun curatedModelsDoNotTriggerAnotherCatalogRequest() = runTest {
        val client = object : ModelCatalogClient {
            override suspend fun listModels(provider: ProviderConfig, apiKey: String?): List<String> = error("Curated models must not be fetched again")
        }
        val catalog = ProviderModelCatalog(this, Providers(listOf(gemini.copy(conversationModels = listOf("chosen")))), client)
        catalog.load(); advanceUntilIdle()
        assertEquals(listOf("chosen"), (catalog.state.value.getValue("g") as ProviderModelOptions.Ready).names)
    }

    @Test fun aSlowCatalogStopsLoadingAndCanBeRetried() = runTest {
        val client = object : ModelCatalogClient {
            override suspend fun listModels(provider: ProviderConfig, apiKey: String?): List<String> {
                delay(60_000); return emptyList()
            }
        }
        val catalog = ProviderModelCatalog(this, Providers(listOf(gemini)), client)
        catalog.load(); advanceUntilIdle()
        assertTrue(catalog.state.value["g"] is ProviderModelOptions.Failed)
    }
}
