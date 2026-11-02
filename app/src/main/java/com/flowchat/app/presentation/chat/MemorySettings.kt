package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
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
        null,
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
            LazyColumn(Modifier.weight(1f).testTag("memory-list"), contentPadding = PaddingValues(horizontal = FlowLayout.Gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item { FlowGroup { MemoryToggle(enabled, onEnabledChange) } }
                item { FlowSectionLabel(stringResource(R.string.saved_memories_count, memories.size)) }
                if (memories.isEmpty()) item {
                    FlowEmptyState(Icons.Outlined.Memory, stringResource(R.string.no_memories), "")
                }
                items(memories, key = { it.id }) { memory ->
                    var menu by remember(memory.id) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(memory.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.edit_memory), Modifier.size(20.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.edit_memory)) }, onClick = { menu = false; editing = memory })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete_memory), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; onDelete(memory.id) })
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (memories.isNotEmpty()) item {
                    TextButton(onClick = { confirmClear = true },
                        modifier = Modifier.padding(top = 16.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.clear_all))
                    }
                }
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
