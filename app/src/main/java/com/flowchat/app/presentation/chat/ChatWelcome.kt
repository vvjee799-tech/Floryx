package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Setup is offered only when a conversation cannot yet be used. */
@Composable
internal fun ChatWelcome(enabled: Boolean, modifier: Modifier = Modifier,
    onStart: (() -> Unit)? = null, startLabel: String = "") {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (!enabled && onStart != null) {
            TextButton(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) { Text(startLabel) }
        }
    }
}
