package com.flowchat.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flowchat.app.R

@Composable
internal fun FlowStatusRow(title: String, status: String, modifier: Modifier = Modifier,
    ready: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodyMedium,
            color = if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(R.string.open), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FlowSegmentedChoice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp)) {
        labels.forEachIndexed { index, label ->
            Surface(color = if (index == selected) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) {
                Box(Modifier.heightIn(min = 48.dp).selectable(index == selected, role = Role.RadioButton,
                    onClick = { onSelect(index) }).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.bodyMedium,
                        color = if (index == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
