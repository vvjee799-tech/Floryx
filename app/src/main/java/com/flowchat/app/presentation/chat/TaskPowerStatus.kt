package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.chat.*

@Composable
internal fun TaskPowerStatus(state: TaskPowerState?, conversationId: String?, onRecheck: () -> Unit) {
    if (state == null || (state.owners.none { it.conversationId == conversationId } &&
            state.recentOwners.none { it.conversationId == conversationId } && state.otherUnverifiedLeases == 0)) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("task_power_status")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.task_power_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(when (state.serviceState) {
                TaskServiceState.Running -> R.string.task_power_service_running
                TaskServiceState.Stopping -> R.string.task_power_service_stopping
                TaskServiceState.Destroyed -> R.string.task_power_service_destroyed
            }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(when (state.leaseHeld) {
                true -> R.string.task_power_held
                false -> R.string.task_power_released
                null -> R.string.task_power_unknown
            }), style = MaterialTheme.typography.bodySmall)
            if (state.owners.isNotEmpty()) {
                Text(stringResource(R.string.task_power_counts, state.owners.count { it.needsPower },
                    state.owners.count { !it.needsPower }), style = MaterialTheme.typography.bodySmall)
                val own = state.owners.filter { it.conversationId == conversationId }
                Text(stringResource(when {
                    own.any { it.work == TaskPowerWork.Finalizing } -> R.string.task_power_own_finalizing
                    own.any { it.needsPower } -> R.string.task_power_own_working
                    own.any { it.work == TaskPowerWork.WaitingForCleanup } -> R.string.task_power_own_cleanup_wait
                    own.isNotEmpty() -> R.string.task_power_own_waiting
                    else -> R.string.task_power_other_work
                }), style = MaterialTheme.typography.bodySmall)
            }
            if (state.failure != null) Text(stringResource(R.string.task_power_failed), style = MaterialTheme.typography.bodySmall)
            if (state.otherUnverifiedLeases > 0) Text(stringResource(R.string.task_power_old_pending), style = MaterialTheme.typography.bodySmall)
            if (state.failure != null || state.otherUnverifiedLeases > 0 || state.leaseHeld == null ||
                (state.serviceState == TaskServiceState.Destroyed && state.leaseHeld == true)) {
                TextButton(onClick = onRecheck) { Text(stringResource(R.string.task_power_recheck)) }
            }
            Text(stringResource(R.string.task_power_scope), style = MaterialTheme.typography.labelSmall)
        }
    }
}
