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
import androidx.compose.ui.geometry.Offset
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
    val Gutter = 28.dp
    val ContentWidth = 720.dp
    val TouchTarget = 48.dp
}

@Composable
fun FlowWordmark(modifier: Modifier = Modifier) {
    Text("FlowChat", style = PaperWordmark, maxLines = 1, modifier = modifier)
}

/** Decorative vector mark accompanying the app name. */
@Composable
fun FlowMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(32.dp)) {
        val stroke = size.width * .085f
        drawLine(color, Offset(size.width * .22f, size.height * .3f), Offset(size.width * .78f, size.height * .3f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .22f, size.height * .5f), Offset(size.width * .62f, size.height * .5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .22f, size.height * .7f), Offset(size.width * .46f, size.height * .7f), stroke, StrokeCap.Round)
    }
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
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun FlowEmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun flowFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.primary
)
