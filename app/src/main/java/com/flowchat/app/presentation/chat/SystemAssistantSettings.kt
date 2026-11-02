package com.flowchat.app.presentation.chat

import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowchat.app.R
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.AssistantTileService
import com.flowchat.app.presentation.assistant.FloryxVoiceInteractionService

@Composable
internal fun SystemAssistantSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun selected(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        else ComponentName.unflattenFromString(Settings.Secure.getString(context.contentResolver, "assistant").orEmpty())?.packageName == context.packageName
    }.getOrDefault(false)
    var isSelected by remember { mutableStateOf(selected()) }
    var activeVoice by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        isSelected = selected()
        activeVoice = VoiceInteractionService.isActiveService(context, ComponentName(context, FloryxVoiceInteractionService::class.java))
    }
    val assistantSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column {
        com.flowchat.app.presentation.common.FlowStatusRow(stringResource(R.string.assistant_system_entry),
            stringResource(if (isSelected) R.string.assistant_selected else R.string.assistant_not_selected), ready = isSelected)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column {
            AssistantActionRow(R.string.assistant_choose_default, onClick = {
                message = null
                // Some OEMs immediately dismiss assistant role requests without showing a chooser.
                val opened = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).any { action ->
                    try {
                        assistantSettings.launch(Intent(action))
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                }
                if (!opened) message = context.getString(R.string.assistant_settings_unavailable)
            })
            AssistantActionRow(R.string.assistant_try_panel, onClick = { context.startActivity(Intent(context, AssistantPanelActivity::class.java)) })
        }
        if (Build.VERSION.SDK_INT >= 33) AssistantActionRow(R.string.assistant_add_tile, onClick = {
            runCatching {
                context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(context, AssistantTileService::class.java), context.getString(R.string.assistant_tile),
                    Icon.createWithResource(context, R.drawable.ic_launcher_foreground), context.mainExecutor
                ) { result -> message = context.getString(if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) R.string.assistant_tile_added else R.string.assistant_tile_manual) }
            }.onFailure { message = context.getString(R.string.assistant_tile_manual) }
        })
        else Text(stringResource(R.string.assistant_tile_manual), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AssistantActionRow(label: Int, onClick: () -> Unit) {
    com.flowchat.app.presentation.common.FlowStatusRow(stringResource(label), "", onClick = onClick)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
