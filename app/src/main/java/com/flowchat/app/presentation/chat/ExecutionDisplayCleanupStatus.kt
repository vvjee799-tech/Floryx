package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.device.*

@Composable
internal fun ExecutionDisplayCleanupStatus(
    cleanup: ExecutionDisplayCleanup?, otherPending: Boolean, taskEnded: Boolean, onRecheck: () -> Unit
) {
    if (cleanup == null && !otherPending) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("execution_display_cleanup")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.execution_display_cleanup_title), style = MaterialTheme.typography.titleSmall)
            cleanup?.let { record ->
                Text(stringResource(when (record.state) {
                    ExecutionDisplayState.Creating -> R.string.execution_display_creating
                    ExecutionDisplayState.InUse -> if (taskEnded) R.string.execution_display_stopped_pending else R.string.execution_display_in_use
                    ExecutionDisplayState.Releasing -> R.string.execution_display_releasing
                    ExecutionDisplayState.Released -> if (record.displayId == null) R.string.execution_display_not_created else R.string.execution_display_released
                    ExecutionDisplayState.Unverified -> R.string.execution_display_unverified
                }), style = MaterialTheme.typography.bodySmall)
                record.reason?.let { reason ->
                    Text(stringResource(when (reason) {
                        ExecutionDisplayCleanupReason.DisplayPresent -> R.string.execution_display_still_present
                        ExecutionDisplayCleanupReason.IdentityUnknown -> R.string.execution_display_identity_unknown
                        ExecutionDisplayCleanupReason.CreationPending -> R.string.execution_display_creation_pending
                        ExecutionDisplayCleanupReason.CheckFailed -> R.string.execution_display_check_failed
                        ExecutionDisplayCleanupReason.ClosePending -> R.string.execution_display_close_pending
                    }), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (otherPending) Text(stringResource(R.string.execution_display_other_pending), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.execution_display_cleanup_scope), style = MaterialTheme.typography.labelSmall)
            if (otherPending || cleanup?.state == ExecutionDisplayState.Unverified) {
                TextButton(onClick = onRecheck) { Text(stringResource(R.string.execution_display_recheck)) }
            }
        }
    }
}
