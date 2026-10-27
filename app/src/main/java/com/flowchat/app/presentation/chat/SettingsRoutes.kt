package com.flowchat.app.presentation.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowchat.app.ui.theme.AppAppearance

@Composable
internal fun AppSettingsRoute(
    viewModel: ChatViewModel,
    appAppearance: AppAppearance,
    onAppAppearanceChange: (AppAppearance) -> Unit,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val workspace by viewModel.workspaceState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profile = rememberUserProfile()
    AppSettingsScreen(
        appSettings = state.appSettings,
        memories = state.memories,
        shizukuState = state.shizukuState,
        accessibilityState = state.accessibilityState,
        onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
        appAppearance = appAppearance,
        onAppAppearanceChange = onAppAppearanceChange,
        onOpenProviders = onOpenProviders,
        onOpenTerminal = onOpenTerminal,
        onOpenWorkspace = onOpenWorkspace,
        workspaceName = workspace.location?.name,
        onMemoryEnabledChange = viewModel::setMemoryEnabled,
        onUsageTrackingChange = viewModel::setUsageTrackingEnabled,
        onPowerModeEnabledChange = viewModel::setPowerModeEnabled,
        onPowerConnectionModeChange = viewModel::setPowerConnectionMode,
        onRequestShizukuPermission = viewModel::requestShizukuPermission,
        onRefreshShizuku = viewModel::refreshDeviceAssistantConnections,
        onDeleteMemory = viewModel::deleteMemory,
        onUpdateMemory = viewModel::updateMemory,
        onClearMemories = viewModel::clearMemories,
        onDismiss = onBack,
        userName = profile.name,
        userAvatarPath = profile.avatarPath,
        providerName = state.providers.firstOrNull { it.id == state.currentConversation?.providerId }?.displayName,
        onProfileNameChange = {
            context.getSharedPreferences("user_profile", android.content.Context.MODE_PRIVATE)
                .edit().putString("name", it.trim()).apply()
        },
        onProfileAvatarChange = {
            context.getSharedPreferences("user_profile", android.content.Context.MODE_PRIVATE)
                .edit().putString("avatar_path", it).apply()
        }
    )
}

@Composable
internal fun TerminalSettingsRoute(viewModel: ChatViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val terminal by viewModel.terminalState.collectAsStateWithLifecycle()
    TerminalSettingsScreen(
        state = terminal,
        aiEnabled = state.appSettings.commandExecutionEnabled,
        androidAvailable = state.appSettings.powerModeEnabled && state.shizukuState.isConnected,
        onAiEnabledChange = viewModel::setCommandExecutionEnabled,
        onPrepare = viewModel::prepareTerminal,
        onInstallTools = viewModel::installDeveloperTools,
        onStop = viewModel::stopTerminalCommand,
        onDismiss = onBack
    )
}

@Composable
internal fun WorkspaceSettingsRoute(viewModel: ChatViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val workspace by viewModel.workspaceState.collectAsStateWithLifecycle()
    val terminal by viewModel.terminalState.collectAsStateWithLifecycle()
    var pickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            data.data?.let { uri -> viewModel.selectWorkspace(uri.toString(), data.flags, pickerOwner) }
        }
    }
    WorkspaceSettingsScreen(
        state = workspace,
        isDefault = workspace.location != null && workspace.location == state.appSettings.defaultWorkspace,
        taskRunning = state.isStreaming || terminal.commands.any { it.isRunning },
        onChoose = {
            pickerOwner = state.currentConversation?.id
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            workspace.location?.let { intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it.treeUri)) }
            runCatching { picker.launch(intent) }.onFailure { viewModel.workspacePickerFailed(it.localizedMessage) }
        },
        onDefaultChange = viewModel::setWorkspaceDefault,
        onClear = viewModel::clearWorkspace,
        onRefresh = viewModel::refreshWorkspace,
        onRetrySave = viewModel::retryWorkspaceSave,
        onDismiss = onBack
    )
}
