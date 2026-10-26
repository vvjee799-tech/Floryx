package com.flowchat.app.presentation.provider

import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.data.network.ModelCatalogClient
import com.flowchat.app.domain.provider.ProviderTemplates
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.repository.WebSearchSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savesTavilyApiKeyEvenWhenSelectedProviderIsInvalid() = runTest(dispatcher) {
        val providerRepository = FakeProviderRepository(
            ProviderConfig(
                id = "invalid-provider",
                displayName = "",
                baseUrl = "",
                defaultModel = ""
            )
        )
        val webSearchSettingsRepository = FakeWebSearchSettingsRepository()
        val modelCatalogClient = FakeModelCatalogClient()
        val viewModel = ProviderSettingsViewModel(providerRepository, webSearchSettingsRepository, modelCatalogClient)
        advanceUntilIdle()

        viewModel.updateTavilyApiKey("tvly-test")
        viewModel.save()
        advanceUntilIdle()

        assertEquals("tvly-test", webSearchSettingsRepository.savedTavilyApiKey)
        assertEquals(0, providerRepository.upsertCount)
    }

    @Test
    fun loadsModelOptionsUsingDraftApiKeyBeforeSavedApiKey() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "p1",
            displayName = "Custom",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "manual-model",
            apiKeyAlias = "provider:p1"
        )
        val providerRepository = FakeProviderRepository(provider, savedApiKey = "saved-key")
        val webSearchSettingsRepository = FakeWebSearchSettingsRepository()
        val modelCatalogClient = FakeModelCatalogClient(models = listOf("alpha", "beta"))
        val viewModel = ProviderSettingsViewModel(providerRepository, webSearchSettingsRepository, modelCatalogClient)
        advanceUntilIdle()

        viewModel.updateApiKey("draft-key")
        viewModel.loadModelOptions()
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.modelOptions == listOf("alpha", "beta") }
        assertEquals("draft-key", modelCatalogClient.lastApiKey)
        assertEquals(provider.baseUrl, modelCatalogClient.lastProvider?.baseUrl)
        assertEquals(listOf("alpha", "beta"), state.modelOptions)
        assertEquals(true, state.modelOptionsExpanded)
        assertEquals(false, state.isLoadingModels)
        assertNull(state.modelListError)
    }

    @Test
    fun loadsModelOptionsUsingSavedApiKeyWhenDraftApiKeyIsBlank() = runTest(dispatcher) {
        val provider = ProviderConfig(
            id = "p1",
            displayName = "Custom",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "manual-model",
            apiKeyAlias = "provider:p1"
        )
        val providerRepository = FakeProviderRepository(provider, savedApiKey = "saved-key")
        val webSearchSettingsRepository = FakeWebSearchSettingsRepository()
        val modelCatalogClient = FakeModelCatalogClient(models = listOf("gamma"))
        val viewModel = ProviderSettingsViewModel(providerRepository, webSearchSettingsRepository, modelCatalogClient)
        advanceUntilIdle()

        viewModel.loadModelOptions()
        advanceUntilIdle()

        assertEquals("saved-key", modelCatalogClient.lastApiKey)
        assertEquals(listOf("gamma"), viewModel.uiState.first { it.modelOptions == listOf("gamma") }.modelOptions)
    }

    @Test
    fun selectingModelOptionUpdatesSelectedProviderModel() = runTest(dispatcher) {
        val providerRepository = FakeProviderRepository(
            ProviderConfig(
                id = "p1",
                displayName = "Custom",
                baseUrl = "https://api.example.com/v1",
                defaultModel = "manual-model"
            )
        )
        val viewModel = ProviderSettingsViewModel(
            providerRepository,
            FakeWebSearchSettingsRepository(),
            FakeModelCatalogClient(models = listOf("selected-model"))
        )
        advanceUntilIdle()

        viewModel.loadModelOptions()
        advanceUntilIdle()
        viewModel.selectModelOption("selected-model")

        val state = viewModel.uiState.first { it.selected?.defaultModel == "selected-model" }
        assertEquals("selected-model", state.selected?.defaultModel)
        assertEquals(false, state.modelOptionsExpanded)
    }

    @Test
    fun modelListFailureKeepsCurrentManualModel() = runTest(dispatcher) {
        val providerRepository = FakeProviderRepository(
            ProviderConfig(
                id = "p1",
                displayName = "Custom",
                baseUrl = "https://api.example.com/v1",
                defaultModel = "manual-model"
            )
        )
        val viewModel = ProviderSettingsViewModel(
            providerRepository,
            FakeWebSearchSettingsRepository(),
            FakeModelCatalogClient(error = IllegalStateException("network failed"))
        )
        advanceUntilIdle()

        viewModel.loadModelOptions()
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.modelListError == "network failed" }
        assertEquals("manual-model", state.selected?.defaultModel)
        assertTrue(state.modelOptions.isEmpty())
        assertEquals("network failed", state.modelListError)
        assertEquals(true, state.modelOptionsExpanded)
    }

    @Test
    fun firstLaunchShowsNoCurrentConfigurationWhenOnlyBlankCustomDraftExists() = runTest(dispatcher) {
        val providerRepository = FakeProviderRepository(ProviderTemplates.defaultCustomProvider())
        val viewModel = ProviderSettingsViewModel(
            providerRepository,
            FakeWebSearchSettingsRepository(),
            FakeModelCatalogClient()
        )
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.selected?.id == "provider-custom" }
        assertEquals("provider-custom", state.selected?.id)
        assertNull(state.currentProvider)
    }

    @Test
    fun currentConfigurationPrefersSavedValidProviderOverBlankCustomDraft() = runTest(dispatcher) {
        val customDraft = ProviderTemplates.defaultCustomProvider()
        val deepSeek = ProviderConfig(
            id = "provider-deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash",
            apiKeyAlias = "provider:provider-deepseek"
        )
        val providerRepository = FakeProviderRepository(
            listOf(customDraft, deepSeek),
            savedApiKeys = mapOf(deepSeek.id to "deepseek-key")
        )
        val viewModel = ProviderSettingsViewModel(
            providerRepository,
            FakeWebSearchSettingsRepository(),
            FakeModelCatalogClient()
        )
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.currentProvider?.id == "provider-deepseek" }
        assertEquals("provider-custom", state.selected?.id)
        assertEquals("provider-deepseek", state.currentProvider?.id)
        assertEquals("DeepSeek", state.currentProvider?.displayName)
    }

    @Test
    fun applyingPresetOpensApiKeyDialogWithoutChangingCustomProvider() = runTest(dispatcher) {
        val original = ProviderConfig(
            id = "provider-custom",
            displayName = "Custom configuration",
            baseUrl = "https://old.example.com/v1",
            defaultModel = "old-model",
            apiKeyAlias = "provider:provider-custom"
        )
        val providerRepository = FakeProviderRepository(original, savedApiKey = "saved-key")
        val viewModel = ProviderSettingsViewModel(
            providerRepository,
            FakeWebSearchSettingsRepository(),
            FakeModelCatalogClient()
        )
        advanceUntilIdle()

        val preset = ProviderTemplates.popularPresets().first { it.id == "preset-deepseek" }
        viewModel.applyPreset(preset)
        advanceUntilIdle()

        val state = viewModel.uiState.first { it.pendingPreset?.id == "preset-deepseek" }
        assertEquals("provider-custom", state.selected?.id)
        assertEquals("Custom configuration", state.selected?.displayName)
        assertEquals("https://old.example.com/v1", state.selected?.baseUrl)
        assertEquals("old-model", state.selected?.defaultModel)
        assertEquals("provider:provider-custom", state.selected?.apiKeyAlias)
        assertEquals("", state.apiKey)
        assertEquals("", state.presetApiKey)
        assertEquals(true, state.hasApiKey)
        assertEquals(0, providerRepository.upsertCount)

        viewModel.updatePresetApiKey("sk-deepseek")
        viewModel.savePresetApiKey()
        advanceUntilIdle()

        val savedPreset = providerRepository.getProvider("provider-deepseek")
        assertEquals("DeepSeek", savedPreset?.displayName)
        assertEquals("https://api.deepseek.com", savedPreset?.baseUrl)
        assertEquals("deepseek-flash", savedPreset?.defaultModel)
        assertEquals("sk-deepseek", providerRepository.getApiKey(requireNotNull(savedPreset)))
        assertEquals("Custom configuration", viewModel.uiState.value.selected?.displayName)
        assertEquals(null, viewModel.uiState.value.pendingPreset)
        assertEquals("", viewModel.uiState.value.presetApiKey)
        assertEquals(1, providerRepository.upsertCount)
    }

    private class FakeProviderRepository(
        initialProviders: List<ProviderConfig>,
        savedApiKeys: Map<String, String> = emptyMap()
    ) : ProviderRepository {
        constructor(provider: ProviderConfig, savedApiKey: String? = null) : this(
            listOf(provider),
            savedApiKey?.let { mapOf(provider.id to it) }.orEmpty()
        )

        private val providers = MutableStateFlow(initialProviders)
        private val apiKeys = savedApiKeys.toMutableMap()
        var upsertCount = 0
            private set

        override fun observeProviders(): Flow<List<ProviderConfig>> = providers

        override suspend fun getProvider(id: String): ProviderConfig? =
            providers.value.firstOrNull { it.id == id }

        override suspend fun getProvidersOnce(): List<ProviderConfig> = providers.value

        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) {
            upsertCount += 1
            val savedConfig = config.copy(apiKeyAlias = if (!apiKey.isNullOrBlank()) "provider:${config.id}" else config.apiKeyAlias)
            if (!apiKey.isNullOrBlank()) {
                apiKeys[config.id] = apiKey.trim()
            }
            providers.value = providers.value.filterNot { it.id == config.id } + savedConfig
        }

        override suspend fun deleteProvider(id: String) {
            providers.value = providers.value.filterNot { it.id == id }
        }

        override suspend fun ensureTemplates() = Unit

        override suspend fun getApiKey(config: ProviderConfig): String? = apiKeys[config.id]
    }

    private class FakeWebSearchSettingsRepository : WebSearchSettingsRepository {
        var savedTavilyApiKey: String? = null
            private set

        override suspend fun saveTavilyApiKey(apiKey: String) {
            savedTavilyApiKey = apiKey
        }

        override suspend fun getTavilyApiKey(): String? = savedTavilyApiKey

        override suspend fun hasTavilyApiKey(): Boolean = !savedTavilyApiKey.isNullOrBlank()
    }

    private class FakeModelCatalogClient(
        private val models: List<String> = emptyList(),
        private val error: Throwable? = null
    ) : ModelCatalogClient {
        var lastProvider: ProviderConfig? = null
            private set
        var lastApiKey: String? = null
            private set

        override suspend fun listModels(provider: ProviderConfig, apiKey: String?): List<String> {
            lastProvider = provider
            lastApiKey = apiKey
            error?.let { throw it }
            return models
        }
    }
}
