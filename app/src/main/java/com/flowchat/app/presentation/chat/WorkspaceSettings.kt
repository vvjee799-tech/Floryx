package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
internal fun WorkspaceSettingsSheet(
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = FlowLayout.Gutter, vertical = 12.dp).testTag("workspace_settings"),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FlowSheetHeader(stringResource(R.string.workspace_title), onDismiss)
            Text(stringResource(R.string.workspace_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowGroup {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.padding(bottom = 14.dp).size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.location?.name ?: stringResource(R.string.workspace_none), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("workspace_name"))
                state.location?.let { location ->
                    Text(location.displayPath, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                Text(stringResource(when (state.status.access) {
                    WorkspaceAccess.None -> R.string.workspace_status_none
                    WorkspaceAccess.ReadOnly -> R.string.workspace_status_readonly
                    WorkspaceAccess.ReadWrite -> R.string.workspace_status_ready
                    WorkspaceAccess.Unavailable -> R.string.workspace_status_unavailable
                }), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp).testTag("workspace_access"),
                    color = if (state.status.access == WorkspaceAccess.Unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onChoose, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("workspace_choose")) {
                        Text(stringResource(if (state.location == null) R.string.workspace_choose else R.string.workspace_change))
                    }
                    if (state.location != null) TextButton(onClear, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("workspace_clear")) { Text(stringResource(R.string.workspace_unlink)) }
                }
            }
            if (state.location != null) FlowToggleRow(stringResource(R.string.workspace_default), stringResource(R.string.workspace_default_description),
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
                Text(stringResource(R.string.workspace_pending_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.pending.forEach { pending ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(pending.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(pending.location.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ onRetrySave(pending.localPath) }, enabled = !state.busy && !taskRunning,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("workspace_retry_${pending.localPath}")) {
                            Text(stringResource(R.string.workspace_save_result))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
