package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.device.*

@Composable
internal fun RootRequestCleanupStatus(records: List<RootRequestCleanup>, otherPending: Boolean, onRecheck: () -> Unit) {
    if (records.isEmpty() && !otherPending) return
    val pending = records.any { it.blocksReuse }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.root_request_cleanup_title), style = MaterialTheme.typography.titleSmall)
            if (records.isNotEmpty()) {
                Text(stringResource(when {
                    pending -> R.string.root_request_cleanup_pending
                    records.any { it.state == RootRequestState.InUse } -> R.string.root_request_cleanup_in_use
                    else -> R.string.root_request_cleanup_released
                }), style = MaterialTheme.typography.bodySmall)
                records.mapNotNull { it.reason }.distinct().forEach { reason ->
                    Text(stringResource(when (reason) {
                        RootRequestCleanupReason.RpcPending -> R.string.root_request_rpc_pending
                        RootRequestCleanupReason.FinishPending -> R.string.root_request_finish_pending
                        RootRequestCleanupReason.CheckFailed -> R.string.root_request_check_failed
                        RootRequestCleanupReason.RequestActive -> R.string.root_request_still_active
                        RootRequestCleanupReason.CommandsPresent -> R.string.root_request_commands_present
                        RootRequestCleanupReason.IdentityUnknown -> R.string.root_request_identity_unknown
                        RootRequestCleanupReason.OriginalConnectionUnavailable -> R.string.root_request_connection_unavailable
                    }), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (otherPending) Text(stringResource(R.string.root_request_other_pending), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.root_request_cleanup_scope), style = MaterialTheme.typography.labelSmall)
            if (pending || otherPending) TextButton(onClick = onRecheck) { Text(stringResource(R.string.root_request_recheck)) }
        }
    }
}
