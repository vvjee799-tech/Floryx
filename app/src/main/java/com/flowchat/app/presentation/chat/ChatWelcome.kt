package com.flowchat.app.presentation.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowchat.app.presentation.BloomEmptyChatImprint
import com.flowchat.app.ui.theme.AppAppearance

/** Shows the settled bloom imprint on ready empty chats, or setup CTA when no provider is configured. */
@Composable
internal fun ChatWelcome(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStart: (() -> Unit)? = null,
    startLabel: String = "",
    appAppearance: AppAppearance = AppAppearance.System
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (enabled) {
            BloomEmptyChatImprint(
                appAppearance = appAppearance,
                modifier = Modifier.fillMaxSize()
            )
        } else if (onStart != null) {
            TextButton(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) { Text(startLabel) }
        }
    }
}
