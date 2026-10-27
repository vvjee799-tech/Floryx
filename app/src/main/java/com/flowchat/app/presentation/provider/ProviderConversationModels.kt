package com.flowchat.app.presentation.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.flowchat.app.R
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.common.FlowDialogSystemBars

@Composable
internal fun ProviderConversationModelsSection(providers: List<ProviderConfig>, onOpen: (ProviderConfig) -> Unit) {
    val configured = providers.filter { it.isEnabled && it.baseUrl.isNotBlank() && it.defaultModel.isNotBlank() }
    if (configured.isEmpty()) return
    Column {
        Text(stringResource(R.string.conversation_models), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        configured.forEach { provider ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("provider-models-${provider.id}")
                .clickable(role = Role.Button) { onOpen(provider) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(provider.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(provider.conversationModels?.let { stringResource(R.string.conversation_models_count, it.size) }
                        ?: stringResource(R.string.conversation_models_automatic),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.choose_models), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun ConversationModelsDialog(
    state: ConversationModelSelection,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onChecked: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onSave: () -> Unit
) {
    var search by rememberSaveable(state.provider.id) { mutableStateOf("") }
    var manual by rememberSaveable(state.provider.id) { mutableStateOf("") }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f
    val visible = remember(state.options, search) { state.options.filter { it.contains(search.trim(), ignoreCase = true) } }
    Dialog(onDismissRequest = { if (!state.isSaving) onDismiss() }) {
        FlowDialogSystemBars()
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(20.dp).testTag("conversation-models-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.conversation_models_title, state.provider.displayName), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.conversation_models_count, state.selected.size), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("conversation-models-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(stringResource(R.string.conversation_models_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_models)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("conversation-model-search"))
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(manual, { manual = it }, label = { Text(stringResource(R.string.add_model_name)) },
                                singleLine = true, enabled = !state.isSaving,
                                modifier = Modifier.weight(1f).testTag("conversation-model-manual"))
                            IconButton(onClick = { onAdd(manual); manual = "" }, enabled = manual.isNotBlank() && !state.isSaving,
                                modifier = Modifier.testTag("conversation-model-add")) {
                                Icon(Icons.Default.Add, stringResource(R.string.add_model))
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onSelectAll(true) }, enabled = !state.isSaving) { Text(stringResource(R.string.select_all_models)) }
                            TextButton(onClick = { onSelectAll(false) }, enabled = !state.isSaving) { Text(stringResource(R.string.clear_model_selection)) }
                            TextButton(onClick = onRefresh, enabled = !state.isLoading && !state.isSaving) { Text(stringResource(R.string.refresh_models)) }
                        }
                    }
                    if (state.isLoading) item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.provider_models_loading, state.provider.displayName), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.error != null) item {
                        Text(localizedProviderMessage(state.error), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    items(visible, key = { it }) { name ->
                        val checked = name in state.selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("conversation-model-$name")
                            .toggleable(value = checked, enabled = !state.isSaving, role = Role.Checkbox, onValueChange = { onChecked(name, it) })
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked, onCheckedChange = null, enabled = !state.isSaving)
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                    if (state.selected.isEmpty()) item {
                        Text(stringResource(R.string.select_at_least_one_model), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    } else if (state.provider.defaultModel !in state.selected) item {
                        Text(stringResource(R.string.conversation_models_new_default, state.selected.first()),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = onSave, enabled = state.selected.isNotEmpty() && !state.isSaving,
                        modifier = Modifier.testTag("conversation-models-save")) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}
