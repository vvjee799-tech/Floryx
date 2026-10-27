package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.memory.MemoryPolicy
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.presentation.common.*

@Composable
internal fun MemoryToggle(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, modifier: Modifier = Modifier, leadingIcon: Boolean = false) {
    FlowToggleRow(stringResource(R.string.memory_enabled),
        stringResource(if (enabled) R.string.memory_enabled_description else R.string.memory_disabled_description),
        enabled, onEnabledChange, modifier.testTag("memory-toggle"),
        icon = if (leadingIcon) Icons.Outlined.Memory else null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySettingsScreen(
    enabled: Boolean, memories: List<MemoryRecord>, onEnabledChange: (Boolean) -> Unit,
    onUpdate: (String, String) -> Unit, onDelete: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    FlowSettingsPage(title = stringResource(R.string.memory), onBack = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).testTag("memory-list"), contentPadding = PaddingValues(FlowLayout.Gutter),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { FlowGroup { MemoryToggle(enabled, onEnabledChange) } }
                item { FlowSectionLabel(stringResource(R.string.saved_memories_count, memories.size)) }
                if (memories.isEmpty()) item {
                    FlowEmptyState(Icons.Outlined.Memory, stringResource(R.string.no_memories), stringResource(R.string.memory_empty_description))
                }
                items(memories, key = { it.id }) { memory ->
                    FlowGroup {
                        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                            Text(stringResource(when (memory.category) {
                                "profile" -> R.string.memory_category_profile
                                "project" -> R.string.memory_category_project
                                else -> R.string.memory_category_preference
                            }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(memory.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { editing = memory }) {
                                    Icon(Icons.Outlined.Edit, stringResource(R.string.edit_memory), Modifier.size(20.dp))
                                }
                                IconButton(onClick = { onDelete(memory.id) }) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.delete_memory), Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = FlowLayout.Gutter, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (memories.isNotEmpty()) TextButton(onClick = { confirmClear = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.clear_all))
                } else Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        }
    }
    editing?.let { memory ->
        var summary by remember(memory.id) { mutableStateOf(memory.summary) }
        val valid = MemoryPolicy.validSummary(summary.trim())
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.edit_memory)) },
            text = { OutlinedTextField(value = summary, onValueChange = { if (it.length <= 180) summary = it },
                modifier = Modifier.fillMaxWidth().testTag("memory-edit"), minLines = 3, maxLines = 6,
                shape = MaterialTheme.shapes.medium, colors = flowFieldColors(),
                label = { Text(stringResource(R.string.memory_summary_label)) },
                supportingText = { Text(stringResource(R.string.memory_edit_hint, summary.length)) }, isError = !valid) },
            confirmButton = { TextButton(enabled = valid, onClick = { onUpdate(memory.id, summary.trim()); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.clear_memory_title)) },
        text = { Text(stringResource(R.string.clear_memory_message)) },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.clear_all)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
    )
}
