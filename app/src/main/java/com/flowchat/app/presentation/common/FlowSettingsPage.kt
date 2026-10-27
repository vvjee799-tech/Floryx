package com.flowchat.app.presentation.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R

/** A regular full-screen destination with a fixed app bar and system back support. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlowSettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("settings_page"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), Modifier.size(22.dp))
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().widthIn(max = FlowLayout.ContentWidth).fillMaxWidth(), content = content)
        }
    }
}
