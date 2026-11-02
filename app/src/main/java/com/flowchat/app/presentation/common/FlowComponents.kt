package com.flowchat.app.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.ui.theme.PaperWordmark

object FlowLayout {
    val Gutter = 24.dp
    val ContentWidth = 720.dp
    val TouchTarget = 48.dp
}

private val FlowWordmarkBrush = Brush.linearGradient(
    colors = listOf(Color(0xFF55DCE3), Color.White, Color(0xFFF3A5CF)),
    start = Offset.Zero,
    end = Offset.Infinite
)

@Composable
internal fun flowWordmarkStyle() = PaperWordmark.copy(
    brush = FlowWordmarkBrush,
    shadow = if (MaterialTheme.colorScheme.background.luminance() > .5f) {
        Shadow(Color(0x8838352F), Offset(0f, 1f), blurRadius = 3f)
    } else Shadow.None
)

@Composable
fun FlowWordmark(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.app_name), style = flowWordmarkStyle(), maxLines = 1, modifier = modifier)
}

/** Decorative vector mark accompanying the app name. */
@Composable
fun FlowMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null, modifier = modifier.size(32.dp),
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.tertiary))
}

@Composable
fun FlowSheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    FlowDialogSystemBars()
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close), Modifier.size(22.dp)) }
    }
}

/** Dialog windows otherwise inherit stale system-bar colors when the theme changes. */
@Composable
fun FlowDialogSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
        ?: (view as? DialogWindowProvider)?.window
    val light = MaterialTheme.colorScheme.surface.luminance() > .5f
    SideEffect {
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

@Composable
fun FlowSectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 10.dp))
}

@Composable
fun FlowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}

@Composable
fun FlowToggleRow(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowSwitch(checked, enabled = enabled)
    }
}

@Composable
fun FlowSwitch(checked: Boolean, enabled: Boolean = true) {
    Switch(checked, onCheckedChange = null, enabled = enabled,
        modifier = Modifier.scale(.82f),
        colors = SwitchDefaults.colors(
            uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
            uncheckedThumbColor = MaterialTheme.colorScheme.surfaceContainerLowest))
}

@Composable
fun FlowEmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun flowFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)
