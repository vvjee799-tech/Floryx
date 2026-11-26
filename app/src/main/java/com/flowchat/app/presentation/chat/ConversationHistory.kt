package com.flowchat.app.presentation.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.model.Conversation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationHistory(
    conversations: List<Conversation>, currentId: String?,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit, onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit, onExport: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit, modifier: Modifier = Modifier,
    scrollHeaderAndFooter: Boolean = false,
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    searchQuery: String = "",
    overlayFooter: Boolean = false
) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var sheetTarget by remember { mutableStateOf<Conversation?>(null) }
    val normalizedQuery = searchQuery.trim()
    val defaultTitle = stringResource(R.string.default_conversation_title)
    val visible = remember(conversations, archived, normalizedQuery, defaultTitle) {
        conversations.filter { it.isArchived == archived }
            .filter { normalizedQuery.isBlank() || it.title.ifBlank { defaultTitle }.contains(normalizedQuery, ignoreCase = true) }
            .sortedWith(compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.updatedAt }.thenBy { it.id })
    }
    val toggleArchiveDescription = stringResource(if (archived) R.string.active_conversations else R.string.archived_conversations)
    val historyHeader: @Composable () -> Unit = {
        header()
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(if (archived) R.string.archived_conversations else R.string.recent_conversations),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { archived = !archived },
                modifier = Modifier.size(36.dp).testTag("history-toggle-archive").semantics {
                    selected = archived
                    contentDescription = toggleArchiveDescription
                }
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (archived) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = null,
                        tint = if (archived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        if (visible.isEmpty()) Text(
            stringResource(if (normalizedQuery.isNotBlank()) R.string.no_matching_conversations else R.string.no_conversations_here),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
        )
    }
    Column(modifier.fillMaxWidth()) {
        if (!scrollHeaderAndFooter) historyHeader()
        val listContentPadding = if (overlayFooter && !scrollHeaderAndFooter) {
            PaddingValues(top = 4.dp, bottom = 88.dp)
        } else {
            PaddingValues(vertical = 4.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listContentPadding) {
                if (scrollHeaderAndFooter) item(key = "drawer-header") { Column { historyHeader() } }
                items(visible, key = { it.id }) { conversation ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 1.dp)) {
                        HistoryRow(
                            conversation = conversation,
                            active = conversation.id == currentId,
                            onSelect = { onSelect(conversation.id) },
                            onOpenActions = { sheetTarget = conversation }
                        )
                    }
                }
                if (scrollHeaderAndFooter) item(key = "drawer-footer") { Column { footer() } }
            }
            if (!scrollHeaderAndFooter && overlayFooter) {
                val bg = MaterialTheme.colorScheme.background
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.34f to bg, 1f to bg))
                        .padding(top = 24.dp)
                ) {
                    footer()
                }
            }
        }
        if (!scrollHeaderAndFooter && !overlayFooter) footer()
    }
    sheetTarget?.let { conversation ->
        ConversationActionsSheet(
            conversation = conversation,
            onDismiss = { sheetTarget = null },
            onRename = { sheetTarget = null; renaming = conversation },
            onPin = { sheetTarget = null; onPin(conversation.id, !conversation.isPinned) },
            onArchive = { sheetTarget = null; onArchive(conversation.id, !conversation.isArchived) },
            onExport = { sheetTarget = null; onExport(conversation) },
            onDelete = { sheetTarget = null; onDelete(conversation) }
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    conversation: Conversation,
    active: Boolean,
    onSelect: () -> Unit,
    onOpenActions: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .testTag("history-row-${conversation.id}")
            .semantics { selected = active }
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onOpenActions,
                onLongClickLabel = stringResource(R.string.conversation_actions)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            conversation.displayTitle(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (conversation.isPinned) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = stringResource(R.string.pinned_conversation),
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationActionsSheet(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                conversation.displayTitle(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            SheetActionRow(Icons.Outlined.Edit, stringResource(R.string.rename_conversation), onClick = onRename)
            SheetActionRow(
                Icons.Outlined.PushPin,
                stringResource(if (conversation.isPinned) R.string.unpin_conversation else R.string.pin_conversation),
                onClick = onPin
            )
            SheetActionRow(
                if (conversation.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                stringResource(if (conversation.isArchived) R.string.restore_conversation else R.string.archive_conversation),
                onClick = onArchive
            )
            SheetActionRow(Icons.Outlined.FileDownload, stringResource(R.string.export_conversation), onClick = onExport)
            SheetActionRow(
                Icons.Outlined.DeleteOutline,
                stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val iconTint = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
