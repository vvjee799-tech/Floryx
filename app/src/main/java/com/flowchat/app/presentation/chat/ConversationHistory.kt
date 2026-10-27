package com.flowchat.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.model.Conversation

@Composable
internal fun ConversationHistory(
    conversations: List<Conversation>, currentId: String?,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit, onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit, onExport: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit, modifier: Modifier = Modifier
) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    val visible = remember(conversations, archived) {
        conversations.filter { it.isArchived == archived }
            .sortedWith(compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.updatedAt }.thenBy { it.id })
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (archived) R.string.archived_conversations else R.string.conversation_history),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { archived = !archived }, modifier = Modifier.testTag("history-toggle-archive")) {
                Text(stringResource(if (archived) R.string.active_conversations else R.string.archived_conversations))
            }
        }
        if (visible.isEmpty()) Text(stringResource(R.string.no_conversations_here),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(28.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            items(visible, key = { it.id }) { conversation ->
                HistoryRow(conversation, conversation.id == currentId, { onSelect(conversation.id) },
                    onRename = { renaming = conversation }, onPin = { onPin(conversation.id, !conversation.isPinned) },
                    onArchive = { onArchive(conversation.id, !conversation.isArchived) },
                    onExport = { onExport(conversation) }, onDelete = { onDelete(conversation) })
            }
        }
    }
    renaming?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.rename_conversation)) },
            text = { OutlinedTextField(title, { title = it.take(160) }, singleLine = true,
                label = { Text(stringResource(R.string.conversation_title)) }, modifier = Modifier.testTag("history-rename-field")) },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                onRename(conversation.id, title.trim()); renaming = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun HistoryRow(conversation: Conversation, active: Boolean, onSelect: () -> Unit,
    onRename: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { selected = active }
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
        .drawBehind { if (active) drawLine(accent, Offset(1.dp.toPx(), 10.dp.toPx()),
            Offset(1.dp.toPx(), size.height - 10.dp.toPx()), 2.dp.toPx()) }
        .clickable(onClick = onSelect).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (conversation.isPinned) {
            Icon(Icons.Outlined.PushPin, stringResource(R.string.pinned_conversation), Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Text(conversation.displayTitle(), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("history-menu-${conversation.id}")) {
                Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.conversation_actions))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rename_conversation)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(if (conversation.isPinned) R.string.unpin_conversation else R.string.pin_conversation)) }, onClick = { menu = false; onPin() })
                DropdownMenuItem(text = { Text(stringResource(if (conversation.isArchived) R.string.restore_conversation else R.string.archive_conversation)) }, onClick = { menu = false; onArchive() })
                DropdownMenuItem(text = { Text(stringResource(R.string.export_conversation)) }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
