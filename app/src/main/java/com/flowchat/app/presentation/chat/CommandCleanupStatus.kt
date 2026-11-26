package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.terminal.*

@Composable
internal fun CommandCleanupStatus(cleanup: CommandCleanup, onRecheck: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("command-cleanup-${cleanup.commandId}").padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(when (cleanup.state) {
            CommandCleanupState.InUse -> R.string.command_cleanup_in_use
            CommandCleanupState.Releasing -> R.string.command_cleanup_releasing
            CommandCleanupState.Unverified -> R.string.command_cleanup_unverified
            CommandCleanupState.Released -> if (cleanup.processStarted) R.string.command_cleanup_released else R.string.command_cleanup_not_started
        }), style = MaterialTheme.typography.bodySmall,
            color = if (cleanup.blocksReuse) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (cleanup.state == CommandCleanupState.Unverified) {
            Text(stringResource(when (cleanup.reason) {
                CommandCleanupReason.ProcessActive -> R.string.command_cleanup_process_active
                CommandCleanupReason.IdentityUnknown -> R.string.command_cleanup_identity_unknown
                CommandCleanupReason.GroupPresent -> R.string.command_cleanup_group_present
                CommandCleanupReason.OriginalConnectionUnavailable -> R.string.command_cleanup_connection_unavailable
                CommandCleanupReason.EvidenceUnavailable -> R.string.command_cleanup_evidence_unavailable
                CommandCleanupReason.StartResponsePending -> R.string.command_cleanup_start_pending
                else -> R.string.command_cleanup_check_failed
            }), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRecheck) { Text(stringResource(R.string.command_cleanup_recheck)) }
        }
    }
}
