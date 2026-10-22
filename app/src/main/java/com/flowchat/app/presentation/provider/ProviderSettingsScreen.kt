package com.flowchat.app.presentation.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import com.flowchat.app.presentation.common.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowchat.app.R
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.provider.ProviderPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProviderSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Text(
                        text = stringResource(R.string.provider_configuration),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        ProviderEditor(
            state = state,
            onUpdate = viewModel::updateSelected,
            onSelectProvider = viewModel::select,
            onApiKey = viewModel::updateApiKey,
            onLoadModelOptions = viewModel::loadModelOptions,
            onSelectModelOption = viewModel::selectModelOption,
            onDismissModelOptions = viewModel::dismissModelOptions,
            onApplyPreset = viewModel::applyPreset,
            onPresetApiKey = viewModel::updatePresetApiKey,
            onSavePresetApiKey = viewModel::savePresetApiKey,
            onDismissPresetApiKeyDialog = viewModel::dismissPresetApiKeyDialog,
            onSave = viewModel::save,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = FlowLayout.ContentWidth)
                .padding(horizontal = FlowLayout.Gutter)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProviderEditor(
    state: ProviderSettingsUiState,
    onUpdate: ((ProviderConfig) -> ProviderConfig) -> Unit,
    onSelectProvider: (ProviderConfig) -> Unit,
    onApiKey: (String) -> Unit,
    onLoadModelOptions: () -> Unit,
    onSelectModelOption: (String) -> Unit,
    onDismissModelOptions: () -> Unit,
    onApplyPreset: (ProviderPreset) -> Unit,
    onPresetApiKey: (String) -> Unit,
    onSavePresetApiKey: () -> Unit,
    onDismissPresetApiKeyDialog: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val provider = state.selected
    var customConfigurationExpanded by rememberSaveable {
        mutableStateOf(false)
    }
    val formHeader = remember { BringIntoViewRequester() }
    LaunchedEffect(customConfigurationExpanded, provider?.id) {
        if (customConfigurationExpanded) formHeader.bringIntoView()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()).testTag("provider-editor").padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.current_configuration),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CurrentProviderCard(provider = state.currentProvider)
        if (state.currentProvider != null) TextButton(onClick = {
            onSelectProvider(state.currentProvider)
            customConfigurationExpanded = true
        }) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.provider_edit_current))
        }
        Text(stringResource(R.string.provider_quick_setup),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProviderPresetSection(state = state, onApplyPreset = onApplyPreset)
        if (provider == null) {
            Text(stringResource(R.string.no_provider_selected))
            return@Column
        }
        ProviderSection(
            title = stringResource(R.string.custom_api_configuration),
            expanded = customConfigurationExpanded,
            onToggle = { customConfigurationExpanded = !customConfigurationExpanded },
            headerModifier = Modifier.bringIntoViewRequester(formHeader)
        ) {
            OutlinedTextField(
                value = provider.displayName,
                onValueChange = { value -> onUpdate { it.copy(displayName = value) } },
                label = { Text(stringResource(R.string.provider_name)) },
                shape = RoundedCornerShape(14.dp),
                colors = providerTextFieldColors(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { value -> onUpdate { it.copy(baseUrl = value) } },
                label = { Text(stringResource(R.string.base_url)) },
                shape = RoundedCornerShape(14.dp),
                colors = providerTextFieldColors(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = provider.defaultModel,
                    onValueChange = { value -> onUpdate { it.copy(defaultModel = value) } },
                    label = { Text(stringResource(R.string.model)) },
                    trailingIcon = {
                        IconButton(onClick = onLoadModelOptions) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.model_list)
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = providerTextFieldColors(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = state.modelOptionsExpanded,
                    onDismissRequest = onDismissModelOptions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        state.isLoadingModels -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.load_models)) },
                                onClick = {},
                                enabled = false
                            )
                        }
                        state.modelListError != null -> {
                            DropdownMenuItem(
                                text = { Text(localizedProviderMessage(state.modelListError)) },
                                onClick = {},
                                enabled = false
                            )
                        }
                        state.modelOptions.isEmpty() -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.no_models_found)) },
                                onClick = {},
                                enabled = false
                            )
                        }
                        else -> {
                            state.modelOptions.forEach { modelName ->
                                DropdownMenuItem(
                                    text = { Text(modelName) },
                                    onClick = { onSelectModelOption(modelName) }
                                )
                            }
                        }
                    }
                }
            }
            val savedMaskVisible = state.hasApiKey && state.apiKey.isBlank()
            val displayedApiKey = if (savedMaskVisible) SavedApiKeyDisplayValue else state.apiKey
            OutlinedTextField(
                value = displayedApiKey,
                onValueChange = { value ->
                    val normalizedValue = when {
                        savedMaskVisible && value == SavedApiKeyDisplayValue -> ""
                        savedMaskVisible && value.all { it == '•' } -> ""
                        savedMaskVisible && value.startsWith(SavedApiKeyDisplayValue) -> value.removePrefix(SavedApiKeyDisplayValue)
                        else -> value
                    }
                    onApiKey(normalizedValue)
                },
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.api_key)) },
                shape = RoundedCornerShape(14.dp),
                colors = providerTextFieldColors(),
                supportingText = {
                    if (state.hasApiKey) {
                        Text(stringResource(R.string.api_key_saved_hint))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.message != null) Text(localizedProviderMessage(state.message),
                color = if (state.message == "Saved") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.provider_security_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProviderSaveButton(onSave = onSave)
        }
    }
    state.pendingPreset?.let { preset ->
        PresetApiKeyDialog(
            providerName = preset.displayName,
            errorMessage = state.message?.takeUnless { it == "Saved" },
            apiKey = state.presetApiKey,
            onApiKey = onPresetApiKey,
            onDismiss = onDismissPresetApiKeyDialog,
            onSave = onSavePresetApiKey
        )
    }
}

@Composable
private fun localizedProviderMessage(message: String): String {
    if (message.startsWith("Invalid provider:")) {
        val blankBaseUrl = stringResource(R.string.provider_error_blank_base_url)
        val invalidBaseUrl = stringResource(R.string.provider_error_invalid_base_url)
        val insecureUrl = stringResource(R.string.provider_error_insecure_url)
        val blankModel = stringResource(R.string.provider_error_blank_model)
        val blankName = stringResource(R.string.provider_error_blank_name)
        val details = message.substringAfter(':').split(',').joinToString { error ->
            when (error.trim()) {
                "BlankBaseUrl" -> blankBaseUrl
                "InvalidBaseUrl" -> invalidBaseUrl
                "InsecurePublicUrl" -> insecureUrl
                "BlankModel" -> blankModel
                "BlankName" -> blankName
                else -> error.trim()
            }
        }
        return stringResource(R.string.invalid_provider, details)
    }
    return when (message) {
        "Saved" -> stringResource(R.string.saved)
        "API key is required." -> stringResource(R.string.api_key_required)
        "No models found." -> stringResource(R.string.no_models_found)
        "Failed to load model list." -> stringResource(R.string.model_list_failed)
        else -> message
    }
}

@Composable
private fun PresetApiKeyDialog(
    providerName: String,
    errorMessage: String?,
    apiKey: String,
    onApiKey: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ProviderLogo(providerName, Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.provider_preset_title, providerName)) },
        text = {
            FlowDialogSystemBars()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.preset_api_key_placeholder)) },
                isError = errorMessage != null,
                supportingText = { if (errorMessage != null) Text(localizedProviderMessage(errorMessage)) },
                shape = RoundedCornerShape(14.dp),
                colors = providerTextFieldColors(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.provider_security_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = apiKey.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        titleContentColor = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun CurrentProviderCard(
    provider: ProviderConfig?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProviderLogo(providerName = provider?.displayName, modifier = Modifier.size(36.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider?.displayName ?: stringResource(R.string.none),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = provider?.defaultModel ?: stringResource(R.string.no_provider_selected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (provider != null) {
            Text(
                text = stringResource(R.string.saved),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun ProviderSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    headerModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = headerModifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.thinking_collapse else R.string.thinking_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
            if (expanded) {
                content()
            }
        }
    }
}

@Composable
private fun ProviderSaveButton(onSave: () -> Unit) {
    Button(
        onClick = onSave,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(stringResource(R.string.save))
    }
}

@Composable
private fun ProviderPresetSection(state: ProviderSettingsUiState, onApplyPreset: (ProviderPreset) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 340.dp && LocalDensity.current.fontScale > 1.3f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.providerPresets.chunked(columns).forEach { presets ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    presets.forEach { preset ->
                        val configured = state.providers.any { it.id == preset.id && it.apiKeyAlias != null }
                        Surface(onClick = { onApplyPreset(preset) }, shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = null,
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ProviderLogo(providerName = preset.displayName, modifier = Modifier.size(28.dp))
                                Text(preset.displayName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (configured) Icon(Icons.Default.Check, stringResource(R.string.provider_saved_badge),
                                    Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                    if (presets.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProviderLogo(
    providerName: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (providerLogoRes(providerName) == R.drawable.provider_kimi) androidx.compose.ui.graphics.Color(0xFF111827) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val logoRes = providerLogoRes(providerName)
        if (logoRes != null) {
            Image(
                painter = painterResource(logoRes),
                colorFilter = if (logoRes == R.drawable.provider_grok) androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        }
    }
}

private fun providerLogoRes(providerName: String?): Int? =
    when {
        providerName?.contains("qwen", ignoreCase = true) == true -> R.drawable.provider_qwen
        providerName?.contains("通义", ignoreCase = true) == true -> R.drawable.provider_qwen
        providerName?.contains("glm", ignoreCase = true) == true -> R.drawable.provider_glm
        providerName?.contains("zhipu", ignoreCase = true) == true -> R.drawable.provider_glm
        providerName?.contains("智谱", ignoreCase = true) == true -> R.drawable.provider_glm
        providerName?.contains("kimi", ignoreCase = true) == true -> R.drawable.provider_kimi
        providerName?.contains("moonshot", ignoreCase = true) == true -> R.drawable.provider_kimi
        providerName?.contains("grok", ignoreCase = true) == true -> R.drawable.provider_grok
        providerName?.contains("x.ai", ignoreCase = true) == true -> R.drawable.provider_grok
        providerName?.contains("minimax", ignoreCase = true) == true -> R.drawable.provider_minimax
        providerName?.contains("doubao", ignoreCase = true) == true -> R.drawable.provider_doubao
        providerName?.contains("豆包", ignoreCase = true) == true -> R.drawable.provider_doubao
        providerName?.contains("claude", ignoreCase = true) == true -> R.drawable.provider_claude
        providerName?.contains("deep", ignoreCase = true) == true -> R.drawable.provider_deepseek
        providerName?.contains("gemini", ignoreCase = true) == true -> R.drawable.provider_gemini
        providerName?.contains("chat", ignoreCase = true) == true ||
            providerName?.contains("open", ignoreCase = true) == true -> R.drawable.provider_openai
        else -> null
    }

@Composable
private fun providerTextFieldColors() = flowFieldColors()
