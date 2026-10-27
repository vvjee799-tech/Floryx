package com.flowchat.app.presentation.provider

import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.provider.ProviderPreset
import com.flowchat.app.domain.provider.ProviderTemplates

const val SavedApiKeyDisplayValue = "••••••••••••••••"

data class ProviderSettingsUiState(
    val providers: List<ProviderConfig> = emptyList(),
    val providerPresets: List<ProviderPreset> = ProviderTemplates.popularPresets(),
    val pendingPreset: ProviderPreset? = null,
    val selected: ProviderConfig? = null,
    val currentProvider: ProviderConfig? = null,
    val apiKey: String = "",
    val presetApiKey: String = "",
    val hasApiKey: Boolean = false,
    val tavilyApiKey: String = "",
    val hasTavilyApiKey: Boolean = false,
    val modelOptions: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    val modelOptionsExpanded: Boolean = false,
    val modelListError: String? = null,
    val message: String? = null,
    val modelSelection: ConversationModelSelection? = null
)

data class ConversationModelSelection(
    val provider: ProviderConfig,
    val options: List<String>,
    val selected: List<String>,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
)
