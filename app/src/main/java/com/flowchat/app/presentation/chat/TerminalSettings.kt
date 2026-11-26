package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Smartphone
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
internal fun TerminalSettingsScreen(
    state: TerminalUiState, aiEnabled: Boolean, androidAvailable: Boolean,
    onAiEnabledChange: (Boolean) -> Unit, onPrepare: () -> Unit,
    onInstallTools: () -> Unit, onStop: (String) -> Unit, onDismiss: () -> Unit,
    onRecheckCleanup: (String) -> Unit = {}
) {
    var environment by remember { mutableStateOf(CommandEnvironment.Linux) }
    val visibleCommands = state.commands.filter { it.environment == environment }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasRunningCommands = state.commands.any { it.isRunning }
    LaunchedEffect(hasRunningCommands) {
        while (hasRunningCommands) { nowMillis = System.currentTimeMillis(); delay(1_000) }
    }
    LaunchedEffect(Unit) { onPrepare() }
    FlowSettingsPage(title = stringResource(R.string.terminal_title), onBack = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("terminal-list"),
                contentPadding = PaddingValues(horizontal = FlowLayout.Gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    FlowGroup {
                        FlowToggleRow(stringResource(R.string.terminal_ai_enabled), null,
                            aiEnabled, onAiEnabledChange, Modifier.testTag("terminal-ai-toggle"))
                    }
                }
                item {
                    val linuxLabel = stringResource(R.string.terminal_linux)
                    val androidLabel = stringResource(R.string.terminal_android)
                    FlowBinaryChoice(
                        options = listOf(
                            FlowBinaryOption(label = linuxLabel, icon = Icons.Outlined.Terminal, sageTone = true, testTag = "terminal-env-linux"),
                            FlowBinaryOption(label = androidLabel, icon = Icons.Outlined.Smartphone, sageTone = true, testTag = "terminal-env-android")
                        ),
                        selected = CommandEnvironment.entries.indexOf(environment),
                        onSelect = { environment = CommandEnvironment.entries[it] }
                    )
                }

                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (environment == CommandEnvironment.Linux) {
                            if (state.linux.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            val statusText = when {
                                !state.linux.supported -> stringResource(R.string.terminal_unsupported)
                                state.linux.preparing -> stringResource(R.string.terminal_preparing)
                                state.linux.ready -> stringResource(if (state.linux.developerToolsReady) R.string.terminal_tools_ready else R.string.terminal_linux)
                                else -> state.linux.error ?: stringResource(R.string.terminal_not_ready)
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(statusText, style = MaterialTheme.typography.bodyLarge,
                                    color = if (state.linux.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f))
                                if (state.linux.ready && !state.linux.developerToolsReady) {
                                    TextButton(onClick = onInstallTools, enabled = state.commands.none {
                                        it.isRunning && (it.command.startsWith("apk add") || it.command == "flowchat-install-tools")
                                    }) { Text(stringResource(R.string.terminal_install_tools)) }
                                } else if (!state.linux.ready && !state.linux.preparing && state.linux.supported) {
                                    TextButton(onClick = onPrepare) { Text(stringResource(R.string.terminal_retry)) }
                                }
                            }
                        } else {
                            FlowStatusRow(stringResource(R.string.terminal_android),
                                stringResource(if (androidAvailable) R.string.accessibility_connected else R.string.device_readiness_off),
                                ready = androidAvailable)
                        }
                    }
                }
                item { FlowSectionLabel(stringResource(R.string.command_history)) }
                if (visibleCommands.isEmpty()) item {
                    Text(stringResource(R.string.command_history_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
                items(visibleCommands, key = { it.id }) { entry ->
                    CommandRecord(entry, nowMillis, onStop, onRecheckCleanup)
                }
            }
        }
    }
}

@Composable
private fun CommandRecord(entry: CommandSnapshot, nowMillis: Long, onStop: (String) -> Unit, onRecheckCleanup: (String) -> Unit) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val failed = entry.status in listOf(CommandStatus.Failed, CommandStatus.TimedOut) ||
        (entry.status == CommandStatus.Exited && entry.exitCode != 0)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$ ${entry.command}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(when (entry.status) {
                    CommandStatus.Running -> if (entry.startIntentId == entry.id) R.string.command_starting else R.string.terminal_running
                    CommandStatus.Exited -> if (entry.exitCode == 0) R.string.terminal_exited else R.string.terminal_failed
                    CommandStatus.Cancelled -> R.string.terminal_cancelled
                    CommandStatus.TimedOut -> R.string.terminal_timed_out
                    CommandStatus.Failed -> R.string.terminal_failed
                }), style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.terminal_elapsed_seconds, entry.elapsedMillis(nowMillis) / 1000L),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.isRunning) TextButton(onClick = { onStop(entry.id) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.stop)) }
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                stringResource(if (expanded) R.string.terminal_collapse_output else R.string.terminal_expand_output), Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entry.cleanup?.takeIf { it.state == CommandCleanupState.Unverified || it.state == CommandCleanupState.Releasing }?.let {
            CommandCleanupStatus(it) { onRecheckCleanup(entry.id) }
        }
        if (expanded) {
            entry.exitCode?.let { Text("exit=$it", style = MaterialTheme.typography.bodySmall) }
            if (entry.output.isNotEmpty() || entry.error != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small) {
                SelectionContainer {
                    Text(entry.output.takeLast(8000) + entry.error?.let { "\n$it" }.orEmpty(), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()).padding(12.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
