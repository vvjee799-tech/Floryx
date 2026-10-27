package com.flowchat.app.presentation.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowchat.app.data.network.ModelCatalogClient
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.provider.ProviderPreset
import com.flowchat.app.domain.provider.ProviderTemplates
import com.flowchat.app.domain.provider.cleanModelSelection
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.repository.WebSearchSettingsRepository
import com.flowchat.app.domain.validation.ProviderConfigValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@HiltViewModel
class ProviderSettingsViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val webSearchSettingsRepository: WebSearchSettingsRepository,
    private val modelCatalogClient: ModelCatalogClient
) : ViewModel() {
    private val selected = MutableStateFlow<ProviderConfig?>(null)
    private val pendingPreset = MutableStateFlow<ProviderPreset?>(null)
    private val apiKey = MutableStateFlow("")
    private val presetApiKey = MutableStateFlow("")
    private val hasApiKey = MutableStateFlow(false)
    private val tavilyApiKey = MutableStateFlow("")
    private val hasTavilyApiKey = MutableStateFlow(false)
    private val modelOptions = MutableStateFlow(emptyList<String>())
    private val isLoadingModels = MutableStateFlow(false)
    private val modelOptionsExpanded = MutableStateFlow(false)
    private val modelListError = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val modelOptionsCache = mutableMapOf<String, List<String>>()
    private val modelSelection = MutableStateFlow<ConversationModelSelection?>(null)
    private var modelSelectionJob: Job? = null

    val uiState = combine(
        providerRepository.observeProviders(),
        selected,
        pendingPreset,
        apiKey,
        presetApiKey,
        hasApiKey,
        tavilyApiKey,
        hasTavilyApiKey,
        modelOptions,
        isLoadingModels,
        modelOptionsExpanded,
        modelListError,
        message,
        modelSelection
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val providers = values[0] as List<ProviderConfig>
        val selectedProvider = values[1] as ProviderConfig?
        val preset = values[2] as ProviderPreset?
        val key = values[3] as String
        val presetKey = values[4] as String
        val savedKey = values[5] as Boolean
        val tavilyKey = values[6] as String
        val savedTavilyKey = values[7] as Boolean
        @Suppress("UNCHECKED_CAST")
        val options = values[8] as List<String>
        val loadingModels = values[9] as Boolean
        val optionsExpanded = values[10] as Boolean
        val modelError = values[11] as String?
        val msg = values[12] as String?
        val actualSelected = selectedProvider
            ?: providers.firstOrNull { it.id == ProviderTemplates.CUSTOM_PROVIDER_ID }
            ?: providers.firstOrNull()
        val currentProvider = providers.currentConfiguredProvider()
        if (actualSelected != null && selectedProvider == null) {
            selected.value = actualSelected
            refreshApiKeyState(actualSelected)
        }
        ProviderSettingsUiState(
            providers = providers,
            pendingPreset = preset,
            selected = actualSelected,
            currentProvider = currentProvider,
            apiKey = key,
            presetApiKey = presetKey,
            hasApiKey = savedKey,
            tavilyApiKey = tavilyKey,
            hasTavilyApiKey = savedTavilyKey,
            modelOptions = options,
            isLoadingModels = loadingModels,
            modelOptionsExpanded = optionsExpanded,
            modelListError = modelError,
            message = msg,
            modelSelection = values[13] as ConversationModelSelection?
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProviderSettingsUiState())

    init {
        viewModelScope.launch {
            providerRepository.ensureTemplates()
            val providers = providerRepository.getProvidersOnce()
            val provider = providers.firstOrNull { it.id == ProviderTemplates.CUSTOM_PROVIDER_ID } ?: providers.firstOrNull()
            selected.value = provider
            hasApiKey.value = provider?.let { providerRepository.getApiKey(it) != null } ?: false
            hasTavilyApiKey.value = webSearchSettingsRepository.hasTavilyApiKey()
        }
    }

    fun select(provider: ProviderConfig) {
        selected.value = provider
        apiKey.value = ""
        refreshApiKeyState(provider)
    }

    fun openConversationModels(provider: ProviderConfig) {
        modelSelectionJob?.cancel()
        val names = cleanModelSelection(provider.conversationModels ?: listOf(provider.defaultModel))
        modelSelection.value = ConversationModelSelection(provider, names, names)
        refreshConversationModels()
    }

    fun dismissConversationModels() {
        if (modelSelection.value?.isSaving == true) return
        modelSelectionJob?.cancel()
        modelSelection.value = null
    }

    fun refreshConversationModels() {
        val editor = modelSelection.value ?: return
        if (editor.isSaving) return
        modelSelectionJob?.cancel()
        modelSelection.update { it?.copy(isLoading = true, error = null) }
        modelSelectionJob = viewModelScope.launch {
            var key: String? = null
            try {
                key = providerRepository.getApiKey(editor.provider)
                val names = withTimeout(30_000) { modelCatalogClient.listModels(editor.provider, key) }
                updateModelSelection(editor.provider.id) {
                    it.copy(options = cleanModelSelection(it.options + names), isLoading = false,
                        error = if (names.isEmpty()) "No models found." else null)
                }
            } catch (_: TimeoutCancellationException) {
                updateModelSelection(editor.provider.id) { it.copy(isLoading = false, error = "Failed to load model list.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = failure.message.orEmpty().let { if (key.isNullOrBlank()) it else it.replace(key!!, "[REDACTED]") }
                updateModelSelection(editor.provider.id) { it.copy(isLoading = false, error = detail.ifBlank { "Failed to load model list." }) }
            }
        }
    }

    private fun updateModelSelection(providerId: String, transform: (ConversationModelSelection) -> ConversationModelSelection) {
        modelSelection.update { if (it?.provider?.id == providerId) transform(it) else it }
    }

    fun setConversationModelChecked(name: String, checked: Boolean) {
        modelSelection.update { editor -> editor?.takeUnless { it.isSaving }?.copy(
            selected = if (checked) cleanModelSelection(editor.selected + name) else editor.selected - name)
            ?: editor }
    }

    fun selectAllConversationModels(checked: Boolean) {
        modelSelection.update { it?.takeUnless { value -> value.isSaving }?.copy(selected = if (checked) it.options else emptyList()) ?: it }
    }

    fun addConversationModel(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        modelSelection.update { it?.takeUnless { value -> value.isSaving }?.copy(
            options = cleanModelSelection(it.options + clean), selected = cleanModelSelection(it.selected + clean)) ?: it }
    }

    fun saveConversationModels() {
        val editor = modelSelection.value ?: return
        if (editor.isSaving) return
        val names = cleanModelSelection(editor.selected)
        if (names.isEmpty()) {
            modelSelection.update { it?.copy(error = "Select at least one model.") }
            return
        }
        modelSelection.update { it?.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val latest = providerRepository.getProvider(editor.provider.id) ?: error("Provider not found.")
                val default = latest.defaultModel.takeIf { it in names } ?: names.first()
                providerRepository.upsertProvider(latest.copy(defaultModel = default, conversationModels = names), null)
                selected.update { current -> if (current?.id == latest.id) current.copy(conversationModels = names,
                    defaultModel = if (current.defaultModel == latest.defaultModel) default else current.defaultModel) else current }
                modelSelectionJob?.cancel()
                modelSelection.value = null
                message.value = "Saved"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                modelSelection.update { it?.copy(isSaving = false, error = failure.message ?: "Failed to save models.") }
            }
        }
    }

    fun updateSelected(transform: (ProviderConfig) -> ProviderConfig) {
        selected.update { current -> current?.let(transform) }
        modelListError.value = null
    }

    fun updateApiKey(value: String) {
        apiKey.value = value
        modelListError.value = null
    }

    fun updatePresetApiKey(value: String) {
        presetApiKey.value = value
    }

    fun updateTavilyApiKey(value: String) {
        tavilyApiKey.value = value
    }

    fun applyPreset(preset: ProviderPreset) {
        pendingPreset.value = preset
        presetApiKey.value = ""
        message.value = null
    }

    fun dismissPresetApiKeyDialog() {
        pendingPreset.value = null
        presetApiKey.value = ""
    }

    fun savePresetApiKey() {
        val preset = pendingPreset.value ?: return
        if (presetApiKey.value.isBlank()) {
            message.value = "API key is required."
            return
        }
        viewModelScope.launch {
            val template = preset.toProviderConfig()
            val existing = providerRepository.getProvider(template.id)
            val provider = if (existing?.conversationModels != null) template.copy(
                defaultModel = existing.defaultModel, conversationModels = existing.conversationModels) else template
            providerRepository.upsertProvider(provider, presetApiKey.value.trim())
            pendingPreset.value = null
            presetApiKey.value = ""
            message.value = "Saved"
        }
    }

    fun loadModelOptions() {
        val provider = selected.value ?: return
        modelOptionsExpanded.value = true
        modelListError.value = null
        viewModelScope.launch {
            val draftApiKey = apiKey.value.trim().takeIf { it.isNotBlank() }
            val resolvedApiKey = draftApiKey ?: providerRepository.getApiKey(provider)
            val cacheKey = provider.modelOptionsCacheKey(resolvedApiKey)
            modelOptionsCache[cacheKey]?.let { cached ->
                modelOptions.value = cached
                isLoadingModels.value = false
                return@launch
            }

            isLoadingModels.value = true
            runCatching {
                modelCatalogClient.listModels(provider, resolvedApiKey)
            }.onSuccess { models ->
                modelOptions.value = models
                modelOptionsCache[cacheKey] = models
                modelListError.value = if (models.isEmpty()) "No models found." else null
            }.onFailure { throwable ->
                modelOptions.value = emptyList()
                modelListError.value = throwable.message ?: "Failed to load model list."
            }
            isLoadingModels.value = false
        }
    }

    fun selectModelOption(modelName: String) {
        selected.update { current -> current?.copy(defaultModel = modelName) }
        modelOptionsExpanded.value = false
        modelListError.value = null
    }

    fun dismissModelOptions() {
        modelOptionsExpanded.value = false
    }

    fun save() {
        val provider = selected.value?.copy(customHeadersJson = "{}")
        val tavilyKeyToSave = tavilyApiKey.value.trim()
        val errors = provider?.let { ProviderConfigValidator.validate(it) }.orEmpty()
        if (tavilyKeyToSave.isBlank() && errors.isNotEmpty()) {
            message.value = "Invalid provider: ${errors.joinToString()}"
            return
        }
        if (provider == null && tavilyKeyToSave.isBlank()) {
            return
        }
        viewModelScope.launch {
            var savedTavilyKey = false
            if (tavilyKeyToSave.isNotBlank()) {
                webSearchSettingsRepository.saveTavilyApiKey(tavilyKeyToSave)
                hasTavilyApiKey.value = webSearchSettingsRepository.hasTavilyApiKey()
                tavilyApiKey.value = ""
                savedTavilyKey = true
            }
            if (provider == null) {
                message.value = "Saved"
                return@launch
            }
            if (errors.isNotEmpty()) {
                message.value = if (savedTavilyKey) {
                    "Web search API key saved. Invalid provider: ${errors.joinToString()}"
                } else {
                    "Invalid provider: ${errors.joinToString()}"
                }
                return@launch
            }
            providerRepository.upsertProvider(provider, apiKey.value.ifBlank { null })
            providerRepository.getProvider(provider.id)?.let { savedProvider ->
                selected.value = savedProvider
                hasApiKey.value = providerRepository.getApiKey(savedProvider) != null
            }
            hasTavilyApiKey.value = webSearchSettingsRepository.hasTavilyApiKey()
            message.value = "Saved"
            apiKey.value = ""
        }
    }

    fun deleteSelected() {
        val provider = selected.value ?: return
        viewModelScope.launch {
            providerRepository.deleteProvider(provider.id)
            selected.value = null
            hasApiKey.value = false
        }
    }

    private fun refreshApiKeyState(provider: ProviderConfig) {
        viewModelScope.launch {
            hasApiKey.value = providerRepository.getApiKey(provider) != null
        }
    }

    private fun ProviderConfig.modelOptionsCacheKey(apiKey: String?): String =
        "${baseUrl.trim()}|${!apiKey.isNullOrBlank()}"

    private fun List<ProviderConfig>.currentConfiguredProvider(): ProviderConfig? {
        val validProviders = filter { provider ->
            provider.isEnabled && ProviderConfigValidator.validate(provider).isEmpty()
        }
        return validProviders.firstOrNull { provider -> !provider.apiKeyAlias.isNullOrBlank() }
            ?: validProviders.firstOrNull()
    }

    private fun ProviderPreset.toProviderConfig(): ProviderConfig =
        ProviderConfig(
            id = "provider-${id.removePrefix("preset-")}",
            displayName = displayName,
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            customHeadersJson = "{}"
        )
}
