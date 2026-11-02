package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.presentation.overlay.FloatingAssistantPermission

@Composable
internal fun PhotoAnswerSettings(settings: AppSettings, accessibility: AccessibilityConnectionState,
    onEnabledChange: (Boolean) -> Unit, onAccessibilitySettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var overlayReady by remember { mutableStateOf(FloatingAssistantPermission.isGranted(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME)
            overlayReady = FloatingAssistantPermission.isGranted(context) }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column {
        com.flowchat.app.presentation.common.FlowToggleRow(stringResource(R.string.photo_answer_title), null,
            settings.photoAnswerEnabled, onEnabledChange, Modifier.testTag("photo_answer_enabled"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        com.flowchat.app.presentation.common.FlowStatusRow(stringResource(R.string.overlay_permission_label),
            stringResource(if (overlayReady) R.string.permission_allowed else R.string.permission_needed), ready = overlayReady,
            onClick = { context.startActivity(FloatingAssistantPermission.requestIntent(context)) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        com.flowchat.app.presentation.common.FlowStatusRow(stringResource(R.string.accessibility_screen_control),
            stringResource(if (accessibility.isConnected) R.string.accessibility_connected else R.string.permission_needed),
            ready = accessibility.isConnected, onClick = onAccessibilitySettings)
        Text(stringResource(R.string.photo_answer_gesture), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
    }
}
