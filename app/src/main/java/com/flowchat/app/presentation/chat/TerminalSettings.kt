package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.terminal.*
import com.flowchat.app.presentation.common.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TerminalSettingsSheet(
    state: TerminalUiState, aiEnabled: Boolean, androidAvailable: Boolean,
    onAiEnabledChange: (Boolean) -> Unit, onPrepare: () -> Unit,
    onInstallTools: () -> Unit, onStop: (String) -> Unit, onDismiss: () -> Unit
) {
    var environment by remember { mutableStateOf(CommandEnvironment.Linux) }
    val visibleCommands = state.commands.filter { it.environment == environment }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasRunningCommands = state.commands.any { it.isRunning }
    LaunchedEffect(hasRunningCommands) {
        while (hasRunningCommands) { nowMillis = System.currentTimeMillis(); delay(1_000) }
    }
    LaunchedEffect(Unit) { onPrepare() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.93f).navigationBarsPadding()) {
            FlowSheetHeader(stringResource(R.string.terminal_title), onDismiss, Modifier.padding(horizontal = FlowLayout.Gutter))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("terminal-list"),
                contentPadding = PaddingValues(horizontal = FlowLayout.Gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    FlowGroup {
                        FlowToggleRow(stringResource(R.string.terminal_ai_enabled), stringResource(R.string.terminal_ai_disclosure),
                            aiEnabled, onAiEnabledChange, Modifier.testTag("terminal-ai-toggle"))
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommandEnvironment.entries.forEach { option ->
                            FilterChip(selected = environment == option, onClick = { environment = option },
                                label = { Text(stringResource(if (option == CommandEnvironment.Linux) R.string.terminal_linux else R.string.terminal_android)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                leadingIcon = if (environment == option) {{ Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp)) }} else null)
                        }
                    }
                }
                item {
                    FlowGroup {
                        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (environment == CommandEnvironment.Linux) {
                                if (state.linux.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(when {
                                    !state.linux.supported -> stringResource(R.string.terminal_unsupported)
                                    state.linux.preparing -> stringResource(R.string.terminal_preparing)
                                    state.linux.ready -> "Alpine Linux ${state.linux.version} · ${state.linux.architecture}"
                                    else -> state.linux.error ?: stringResource(R.string.terminal_not_ready)
                                }, style = MaterialTheme.typography.titleSmall,
                                    color = if (state.linux.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                if (state.linux.ready && !state.linux.developerToolsReady) {
                                    TextButton(onClick = onInstallTools, enabled = state.commands.none {
                                        it.isRunning && (it.command.startsWith("apk add") || it.command == "flowchat-install-tools")
                                    }) { Text(stringResource(R.string.terminal_install_tools)) }
                                } else if (!state.linux.ready && !state.linux.preparing && state.linux.supported) {
                                    TextButton(onClick = onPrepare) { Text(stringResource(R.string.terminal_retry)) }
                                }
                                if (state.linux.developerToolsReady) Text(stringResource(R.string.terminal_tools_ready),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            } else Text(stringResource(if (androidAvailable) R.string.terminal_android_connected else R.string.terminal_android_unavailable),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { FlowSectionLabel(stringResource(R.string.command_history)) }
                if (visibleCommands.isEmpty()) item {
                    FlowEmptyState(Icons.Outlined.Terminal, stringResource(R.string.command_history_empty), stringResource(R.string.terminal_empty))
                }
                items(visibleCommands, key = { it.id }) { entry ->
                    CommandRecord(entry, nowMillis, onStop)
                }
            }
        }
    }
}

@Composable
private fun CommandRecord(entry: CommandSnapshot, nowMillis: Long, onStop: (String) -> Unit) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val failed = entry.status in listOf(CommandStatus.Failed, CommandStatus.TimedOut) ||
        (entry.status == CommandStatus.Exited && entry.exitCode != 0)
    FlowGroup {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (failed) Icons.Outlined.ErrorOutline else if (entry.isRunning) Icons.Outlined.Terminal else Icons.Outlined.CheckCircle,
                    null, Modifier.size(20.dp), tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(when (entry.status) {
                        CommandStatus.Running -> R.string.terminal_running
                        CommandStatus.Exited -> if (entry.exitCode == 0) R.string.terminal_exited else R.string.terminal_failed
                        CommandStatus.Cancelled -> R.string.terminal_cancelled
                        CommandStatus.TimedOut -> R.string.terminal_timed_out
                        CommandStatus.Failed -> R.string.terminal_failed
                    }), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.terminal_elapsed_seconds, entry.elapsedMillis(nowMillis) / 1000L),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.isRunning) TextButton(onClick = { onStop(entry.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.stop)) }
            }
            SelectionContainer { Text("$ ${entry.command}", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) }
            if (expanded) {
                entry.exitCode?.let { Text("exit=$it", style = MaterialTheme.typography.labelSmall) }
                if (entry.outputOffset > 0 || entry.output.length > 8000) Text(stringResource(R.string.terminal_output_truncated), style = MaterialTheme.typography.labelSmall)
                if (entry.output.isNotEmpty() || entry.error != null) Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small) {
                    SelectionContainer {
                        Text(entry.output.takeLast(8000) + entry.error?.let { "\n$it" }.orEmpty(), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp))
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.terminal_collapse_output else R.string.terminal_expand_output))
            }
        }
    }
}
