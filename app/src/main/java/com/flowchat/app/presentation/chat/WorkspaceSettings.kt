package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowchat.app.R
import com.flowchat.app.domain.workspace.*
import com.flowchat.app.presentation.common.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceSettingsScreen(
    state: WorkspaceUiState,
    isDefault: Boolean,
    taskRunning: Boolean,
    onChoose: () -> Unit,
    onDefaultChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onRetrySave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) onRefresh() }
        lifecycle.addObserver(observer)
        onRefresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    FlowSettingsPage(title = stringResource(R.string.workspace_title), onBack = onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = FlowLayout.Gutter, vertical = 12.dp).testTag("workspace_settings"),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowGroup {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.location?.name ?: stringResource(R.string.workspace_none), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).testTag("workspace_name"))
                    Text(stringResource(when (state.status.access) {
                        WorkspaceAccess.None -> R.string.workspace_status_none
                        WorkspaceAccess.ReadOnly -> R.string.workspace_status_readonly
                        WorkspaceAccess.ReadWrite -> R.string.workspace_status_ready
                        WorkspaceAccess.Unavailable -> R.string.workspace_status_unavailable
                    }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("workspace_access"),
                        color = if (state.status.access == WorkspaceAccess.ReadWrite) MaterialTheme.colorScheme.tertiary
                            else if (state.status.access == WorkspaceAccess.Unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.location?.let { location ->
                    Surface(shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Text(location.displayPath, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onChoose, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("workspace_choose")) {
                        Text(stringResource(if (state.location == null) R.string.workspace_choose else R.string.workspace_change))
                    }
                    if (state.location != null) TextButton(onClear, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("workspace_clear")) { Text(stringResource(R.string.workspace_unlink)) }
                }
            }
            if (state.location != null) FlowToggleRow(stringResource(R.string.workspace_default), null,
                isDefault, onDefaultChange, Modifier.testTag("workspace_default"), enabled = !state.busy)
            if (taskRunning) Text(stringResource(R.string.workspace_running_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { error ->
                Text(stringResource(R.string.workspace_error, error), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workspace_error").semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.lastSave?.let { saved ->
                Text(stringResource(if (saved.conflict) R.string.workspace_saved_copy else R.string.workspace_saved,
                    saved.workspaceName.orEmpty(), saved.path), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("workspace_save_receipt").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.pending.isNotEmpty()) {
                HorizontalDivider()
                FlowSectionLabel(stringResource(R.string.workspace_pending))
                state.pending.forEach { pending ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(pending.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(pending.location.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button({ onRetrySave(pending.localPath) }, enabled = !state.busy && !taskRunning,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("workspace_retry_${pending.localPath}"), contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
