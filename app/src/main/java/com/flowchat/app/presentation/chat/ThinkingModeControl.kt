package com.flowchat.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.provider.ThinkingSupport

/** Split control: tap the brain to toggle, or the level to inspect model options. */
@Composable
internal fun ThinkingModeControl(
    support: ThinkingSupport,
    enabled: Boolean,
    effort: ThinkingEffort,
    interactive: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEffortChange: (ThinkingEffort) -> Unit
) {
    val effectiveEnabled = support.isEnabled(enabled)
    var menuExpanded by remember(support) { mutableStateOf(false) }
    LaunchedEffect(interactive) { if (!interactive) menuExpanded = false }
    val accent = if (effectiveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(14.dp)
    val toggleDescription = stringResource(R.string.thinking_mode)
    val optionsDescription = stringResource(R.string.thinking_options)
    Box {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min).heightIn(min = 48.dp).clip(shape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.fillMaxHeight().testTag("thinking-toggle")
                    .toggleable(value = effectiveEnabled, role = Role.Switch,
                        enabled = interactive && support.supported && support.canDisable,
                        onValueChange = onEnabledChange)
                    .semantics { contentDescription = toggleDescription }
                    .padding(start = 4.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                Text(stringResource(R.string.thinking_short), color = accent, style = MaterialTheme.typography.labelMedium)
            }
            Text("·", color = accent, style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxHeight().testTag("thinking-options")
                    .clickable(enabled = interactive) { menuExpanded = true }
                    .semantics { contentDescription = optionsDescription }
                    .padding(start = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        !support.supported -> stringResource(R.string.thinking_default)
                        !effectiveEnabled -> stringResource(R.string.thinking_off)
                        support.efforts.isEmpty() -> stringResource(R.string.thinking_on)
                        else -> thinkingEffortLabel(support.normalize(effort))
                    },
                    color = accent, style = MaterialTheme.typography.labelMedium
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
        }
        DropdownMenu(expanded = menuExpanded && interactive, onDismissRequest = { menuExpanded = false }) {
            Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.thinking_mode), style = MaterialTheme.typography.titleSmall)
                if (!support.supported || !support.canDisable || !effectiveEnabled) Text(stringResource(when {
                    !support.supported -> R.string.thinking_unsupported_hint
                    !support.canDisable && support.efforts.isEmpty() -> R.string.thinking_required_fixed_hint
                    !support.canDisable -> R.string.thinking_required_hint
                    !effectiveEnabled -> R.string.thinking_enable_first_hint
                    else -> R.string.thinking_mode_hint
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            }
            if (support.efforts.isNotEmpty()) {
                HorizontalDivider()
                (listOf(ThinkingEffort.Auto) + support.efforts).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(thinkingEffortLabel(option)) },
                        leadingIcon = { if (support.normalize(effort) == option) Icon(Icons.Default.Check, contentDescription = null) },
                        enabled = effectiveEnabled,
                        onClick = { onEffortChange(option); menuExpanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun thinkingEffortLabel(effort: ThinkingEffort): String = stringResource(when (effort) {
    ThinkingEffort.Auto -> R.string.thinking_effort_auto
    ThinkingEffort.Minimal -> R.string.thinking_effort_minimal
    ThinkingEffort.Low -> R.string.thinking_effort_low
    ThinkingEffort.Medium -> R.string.thinking_effort_medium
    ThinkingEffort.High -> R.string.thinking_effort_high
    ThinkingEffort.XHigh -> R.string.thinking_effort_xhigh
    ThinkingEffort.Max -> R.string.thinking_effort_max
})
