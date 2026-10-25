package com.flowchat.app.presentation.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.input.KeyboardType
import com.flowchat.app.presentation.common.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import com.flowchat.app.ui.theme.PaperWordmark
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.BuildConfig
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.terminal.TerminalToolDefinitions
import com.flowchat.app.domain.provider.ThinkingSupport
import com.flowchat.app.data.attachment.AttachmentDocumentParser
import com.flowchat.app.data.attachment.AttachmentFileReader
import com.flowchat.app.data.attachment.AttachmentReadException
import com.flowchat.app.data.attachment.AttachmentReadFailure
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.locale.AppLanguage
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.common.findActivity
import com.flowchat.app.ui.theme.AppAppearance
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ComposerToolHeight = 48.dp
private const val BottomMessageAnchorKey = "bottom-message-anchor"

@Composable
private fun DrawerMenuIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Icon(Icons.Outlined.Menu, contentDescription = null, tint = color, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onOpenProviders: () -> Unit,
    appAppearance: AppAppearance,
    onAppAppearanceChange: (AppAppearance) -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val usageRecords by viewModel.usageRecords.collectAsStateWithLifecycle()
    val balanceState by viewModel.balanceState.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val workspaceState by viewModel.workspaceState.collectAsStateWithLifecycle()
    val openDrawerDescription = stringResource(R.string.open_navigation_drawer)
    val newChatDescription = stringResource(R.string.new_chat)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var workspacePickerOwner by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val workspacePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.let { data ->
            data.data?.let { uri -> viewModel.selectWorkspace(uri.toString(), data.flags, workspacePickerOwner) }
        }
    }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val appContext = context.applicationContext
            viewModel.loadAttachment(
                reader = { AttachmentFileReader.read(appContext, uri) },
                errorText = { attachmentErrorMessage(appContext, it) }
            )
        }
    }
    val defaultUserName = stringResource(R.string.user_display_name)
    val initialUserProfile = remember(context, defaultUserName) {
        loadUserProfile(context, defaultUserName)
    }
    var userName by remember(context, defaultUserName) { mutableStateOf(initialUserProfile.name) }
    var userAvatarPath by remember(context, defaultUserName) { mutableStateOf(initialUserProfile.avatarPath) }
    var showSettings by remember { mutableStateOf(false) }
    var showUserSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showWorkspace by remember { mutableStateOf(false) }
    var conversationPendingDelete by remember { mutableStateOf<Conversation?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(minOf(320.dp, LocalConfiguration.current.screenWidthDp.dp - 40.dp)),
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerContentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                    FlowWordmark(Modifier.padding(horizontal = 28.dp, vertical = 24.dp))
                    TextButton(
                        onClick = {
                            viewModel.newConversation()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(newChatDescription, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.conversation_history),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 10.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(state.conversations, key = { it.id }) { conversation ->
                            val isActiveConversation = conversation.id == state.currentConversation?.id
                            DrawerConversationRow(
                                conversation = conversation,
                                isActive = isActiveConversation,
                                                                modifier = Modifier.semantics { selected = isActiveConversation }.clip(RoundedCornerShape(6.dp)).combinedClickable(
                                    onClick = {
                                        viewModel.selectConversation(conversation.id)
                                        scope.launch { drawerState.close() }
                                    },
                                    onLongClick = { conversationPendingDelete = conversation }
                                )
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        UserProfileEntry(userName, userAvatarPath, onClick = {
                            scope.launch { drawerState.close(); showUserSettings = true }
                        }, modifier = Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { drawerState.close(); showAppSettings = true } }) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.settings), Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    title = {
                        ConversationHeaderTitle(
                            assistantAvatarPath = state.currentConversation?.assistantAvatarPath,
                            title = state.currentConversation?.assistantName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.app_name),
                            modifier = Modifier.semantics { contentDescription = context.getString(R.string.conversation_settings) }.clickable(
                                enabled = state.currentConversation != null,
                                onClick = { showSettings = true }
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.semantics { contentDescription = openDrawerDescription }
                        ) {
                            DrawerMenuIcon(
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::newConversation) {
                            Icon(Icons.Default.Add, newChatDescription, Modifier.size(24.dp))
                        }
                    }
                )
            },
            bottomBar = {
                MessageComposer(
                    value = state.input,
                    pendingAttachment = state.pendingAttachment,
                    isAttachmentLoading = state.isAttachmentLoading,
                    enabled = state.currentConversation != null,
                    isStreaming = state.isStreaming,
                    providers = state.providers,
                    currentConversation = state.currentConversation,
                    onValueChange = viewModel::updateInput,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onThinkingEnabledChange = viewModel::setThinkingEnabled,
                    onThinkingEffortChange = viewModel::setThinkingEffort,
                    onPickAttachment = {
                        attachmentPicker.launch(AttachmentDocumentParser.PickerMimeTypes)
                    },
                    onClearAttachment = viewModel::clearAttachment,
                    usageRecords = usageRecords.takeIf { state.appSettings.usageTrackingEnabled },
                    balanceState = balanceState, onRefreshBalance = viewModel::refreshBalance,
                    onSelectModel = viewModel::updateConversationModel
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = FlowLayout.ContentWidth)
                    .padding(horizontal = FlowLayout.Gutter)
            ) {
                if (state.deviceTaskPaused) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.device_task_paused_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.device_task_paused_description), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = viewModel::resumeDeviceTask, enabled = !state.isStreaming) {
                                    Text(stringResource(R.string.device_task_resume))
                                }
                                TextButton(onClick = viewModel::stop) {
                                    Text(stringResource(R.string.device_task_end))
                                }
                            }
                        }
                    }
                }
                if (state.errorMessage != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Text(localizedChatError(state.errorMessage.orEmpty()), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (state.messages.isEmpty()) {
                    ChatWelcome(enabled = state.currentConversation != null,
                        modifier = Modifier.weight(1f),
                        onStart = {
                            if (state.providers.any { it.baseUrl.isNotBlank() && it.defaultModel.isNotBlank() }) viewModel.newConversation()
                            else onOpenProviders()
                        },
                        startLabel = stringResource(if (state.providers.any { it.baseUrl.isNotBlank() && it.defaultModel.isNotBlank() })
                            R.string.new_chat else R.string.provider_configuration))
                } else MessageList(messages = state.messages, assistantAvatarPath = state.currentConversation?.assistantAvatarPath, userAvatarPath = userAvatarPath, showAvatars = state.currentConversation?.showAvatars == true, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showSettings && state.currentConversation != null) {
        ConversationSettingsSheet(
            conversation = state.currentConversation!!,
            onDismiss = { showSettings = false },
            onSave = { assistantName, assistantAvatarPath, showAvatars, prompt, temperature, topP, maxTokens ->
                viewModel.updateConversationSettings(
                    assistantName,
                    assistantAvatarPath,
                    showAvatars,
                    prompt,
                    temperature,
                    topP,
                    maxTokens
                )
                showSettings = false
            }
        )
    }

    if (showUserSettings) {
        UserProfileSheet(
            userName = userName,
            avatarPath = userAvatarPath,
            onDismiss = { showUserSettings = false },
            onSave = { profileName, profileAvatarPath ->
                val normalizedName = profileName.trim().ifBlank { defaultUserName }
                saveUserProfile(context, profileName, profileAvatarPath)
                userName = normalizedName
                userAvatarPath = profileAvatarPath
                showUserSettings = false
            }
        )
    }

    if (showAppSettings) {
        AppSettingsSheet(
            appSettings = state.appSettings,
            memories = state.memories,
            shizukuState = state.shizukuState,
            accessibilityState = state.accessibilityState,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
            appAppearance = appAppearance,
            onAppAppearanceChange = onAppAppearanceChange,
            onOpenProviders = {
                showAppSettings = false
                onOpenProviders()
            },
            onOpenTerminal = { showAppSettings = false; showTerminal = true },
            onOpenWorkspace = { showAppSettings = false; showWorkspace = true },
            workspaceName = workspaceState.location?.name,
            onMemoryEnabledChange = viewModel::setMemoryEnabled,
            onUsageTrackingChange = viewModel::setUsageTrackingEnabled,
            onPowerModeEnabledChange = viewModel::setPowerModeEnabled,
            onPowerConnectionModeChange = viewModel::setPowerConnectionMode,
            onRequestShizukuPermission = viewModel::requestShizukuPermission,
            onRefreshShizuku = viewModel::refreshDeviceAssistantConnections,
            onDeleteMemory = viewModel::deleteMemory,
            onUpdateMemory = viewModel::updateMemory,
            onClearMemories = viewModel::clearMemories,
            onDismiss = { showAppSettings = false },
            userName = userName, userAvatarPath = userAvatarPath,
            providerName = state.providers.firstOrNull { it.id == state.currentConversation?.providerId }?.displayName,
            onOpenProfile = { showAppSettings = false; showUserSettings = true }
        )
    }

    if (showTerminal) TerminalSettingsSheet(
        state = terminalState,
        aiEnabled = state.appSettings.commandExecutionEnabled,
        androidAvailable = state.appSettings.powerModeEnabled && state.shizukuState.isConnected,
        onAiEnabledChange = viewModel::setCommandExecutionEnabled,
        onPrepare = viewModel::prepareTerminal,
        onInstallTools = viewModel::installDeveloperTools,
        onStop = viewModel::stopTerminalCommand,
        onDismiss = { showTerminal = false }
    )

    if (showWorkspace) WorkspaceSettingsSheet(
        state = workspaceState,
        isDefault = workspaceState.location != null && workspaceState.location == state.appSettings.defaultWorkspace,
        taskRunning = state.isStreaming || terminalState.commands.any { it.isRunning },
        onChoose = {
            workspacePickerOwner = state.currentConversation?.id
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            workspaceState.location?.let { intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it.treeUri)) }
            runCatching { workspacePicker.launch(intent) }.onFailure { viewModel.workspacePickerFailed(it.localizedMessage) }
        },
        onDefaultChange = viewModel::setWorkspaceDefault,
        onClear = viewModel::clearWorkspace,
        onRefresh = viewModel::refreshWorkspace,
        onRetrySave = viewModel::retryWorkspaceSave,
        onDismiss = { showWorkspace = false }
    )

    state.usageAccessPermissionRequest?.let { request ->
        UsageAccessPermissionDialog(
            toolName = request.toolName,
            onDismiss = viewModel::dismissUsageAccessPermissionRequest,
            onOpenSettings = {
                viewModel.dismissUsageAccessPermissionRequest()
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
        )
    }

    state.pendingDeviceActionConfirmation?.let { confirmation ->
        DeviceActionConfirmationDialog(
            confirmation = confirmation,
            onConfirm = viewModel::confirmDeviceAction,
            onDismiss = viewModel::cancelDeviceAction
        )
    }

    conversationPendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { conversationPendingDelete = null },
            title = { Text(stringResource(R.string.delete_conversation_title)) },
            text = { Text(stringResource(R.string.delete_conversation_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(conversation.id)
                        conversationPendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationPendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun localizedChatError(message: String): String =
    when (message) {
        "Create a provider first." -> stringResource(R.string.create_provider_first)
        "Conversation not found." -> stringResource(R.string.conversation_not_found)
        "Provider not found." -> stringResource(R.string.provider_not_found)
        "Provider configuration is invalid." -> stringResource(R.string.provider_configuration_invalid)
        "Provider returned an empty response." -> stringResource(R.string.empty_response_message)
        "Image attachment unavailable. Please attach the image again." -> stringResource(R.string.image_reattach)
        "Tool call limit reached." -> stringResource(R.string.tool_call_limit_reached)
        "Device automation stopped: no progress." -> stringResource(R.string.device_automation_no_progress)
        "Built-in web search is not configured." -> stringResource(R.string.web_search_not_configured)
        "Built-in web search is temporarily unavailable." -> stringResource(R.string.web_search_unavailable)
        else -> message
    }

@Composable
internal fun MessageList(
    messages: List<Message>,
    assistantAvatarPath: String?,
    userAvatarPath: String?,
    showAvatars: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val displayMessages = remember(messages) { groupChatMessages(messages) }
    val lastMessage = displayMessages.lastOrNull()?.message
    val activeReasoning = displayMessages.firstOrNull { it.reasoning?.status == MessageStatus.Streaming }?.reasoning
    var lastAnimatedMessageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(displayMessages.size, lastMessage?.id) {
        if (displayMessages.isNotEmpty()) {
            lastAnimatedMessageId = lastMessage?.id
            listState.animateScrollToItem(displayMessages.size)
        }
    }

    LaunchedEffect(lastMessage?.content, activeReasoning?.reasoningContent) {
        if (displayMessages.isNotEmpty() && lastAnimatedMessageId == lastMessage?.id) {
            listState.scrollToItem(displayMessages.size)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        items(displayMessages, key = { it.message.id }) { entry ->
            if (entry.activity.isNotEmpty()) {
                ReplyActivity(entry)
            } else MessageBubble(entry.message, assistantAvatarPath, userAvatarPath, showAvatars, entry.reasoning)
        }
        item(key = BottomMessageAnchorKey) {
            Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun ReplyActivity(entry: DisplayChatMessage) {
    var expanded by remember(entry.message.id) { mutableStateOf(false) }
    Column {
        entry.reasoning?.let { ReasoningBubble(it, FlowLayout.ContentWidth - FlowLayout.Gutter * 2) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reply_activity), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                stringResource(if (expanded) R.string.thinking_collapse else R.string.thinking_expand),
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) entry.activity.forEach { message ->
            if (message.role == MessageRole.Tool) ToolCallBubble(message)
            else if (message.content.isNotBlank()) Text(message.content,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
internal fun ConversationHeaderTitle(
    assistantAvatarPath: String?, title: String, modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (assistantAvatarPath != null) AssistantAvatar(avatarPath = assistantAvatarPath, size = 30.dp)
        Text(title, style = if (title == stringResource(R.string.app_name)) PaperWordmark else MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DrawerConversationRow(conversation: Conversation, isActive: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(6.dp))
        .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .drawBehind { if (isActive) drawLine(accent, Offset(1.dp.toPx(), 10.dp.toPx()),
            Offset(1.dp.toPx(), size.height - 10.dp.toPx()), strokeWidth = 2.dp.toPx()) }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(conversation.displayTitle(), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserProfileEntry(
    userName: String,
    avatarPath: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ProfileAvatar(
            avatarPath = avatarPath,
            size = 32.dp
        )
        Text(
            text = userName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserProfileSheet(
    userName: String,
    avatarPath: String?,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    val context = LocalContext.current
    var profileName by remember(userName) { mutableStateOf(userName) }
    var profileAvatarPath by remember(avatarPath) { mutableStateOf(avatarPath) }
    val profileScope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            profileScope.launch {
                val path = withContext(Dispatchers.IO) {
                    copyUserAvatarToPrivateFile(context, uri)
                }
                if (path != null) {
                    profileAvatarPath = path
                }
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            FlowSheetHeader(stringResource(R.string.profile_title), onDismiss)
            Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                ProfileAvatar(
                    avatarPath = profileAvatarPath,
                    size = 96.dp
                )
                AvatarEditButton(
                    onClick = { avatarPicker.launch("image/*") },
                    contentDescription = stringResource(R.string.assistant_avatar),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-4).dp, y = (-4).dp)
                )
            }
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text(stringResource(R.string.profile_name)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = flowTextFieldColors(),
                singleLine = true
            )
            Button(
                onClick = { onSave(profileName, profileAvatarPath) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.save))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(
    appSettings: AppSettings,
    memories: List<MemoryRecord>,
    shizukuState: ShizukuConnectionState,
    accessibilityState: AccessibilityConnectionState,
    onOpenAccessibilitySettings: () -> Unit,
    appAppearance: AppAppearance,
    onAppAppearanceChange: (AppAppearance) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenTerminal: () -> Unit,
    onMemoryEnabledChange: (Boolean) -> Unit,
    onPowerModeEnabledChange: (Boolean) -> Unit,
    onPowerConnectionModeChange: (PowerConnectionMode) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onUpdateMemory: (String, String) -> Unit,
    onClearMemories: () -> Unit,
    onDismiss: () -> Unit,
    userName: String = "Floryx", userAvatarPath: String? = null, providerName: String? = null,
    onOpenProfile: (() -> Unit)? = null,
    onUsageTrackingChange: (Boolean) -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    workspaceName: String? = null
) {
    val context = LocalContext.current
    val currentLanguage = AppLocale.getLanguage(context)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDeviceAssistantDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(.94f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = FlowLayout.Gutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            FlowSheetHeader(stringResource(R.string.settings), onDismiss)
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .clickable(enabled = onOpenProfile != null) { onOpenProfile?.invoke() }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileAvatar(userAvatarPath, size = 42.dp)
                Text(userName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onOpenProfile != null) Icon(Icons.Default.ChevronRight, stringResource(R.string.profile_title),
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsGroup(title = stringResource(R.string.settings_connection)) {
                SettingsRow(title = stringResource(R.string.model_provider_configuration),
                    trailingText = providerName, onClick = onOpenProviders)
                SettingsRow(title = stringResource(R.string.memory),
                    trailingText = stringResource(if (appSettings.memoryEnabled) R.string.thinking_on else R.string.thinking_off),
                    onClick = { showMemoryDialog = true })
                SettingsRow(title = stringResource(R.string.device_assistant),
                    trailingText = deviceReadinessLabel(deviceReadiness(appSettings.powerModeEnabled, shizukuState, accessibilityState)),
                    onClick = { onRefreshShizuku(); showDeviceAssistantDialog = true })
                SettingsRow(title = stringResource(R.string.terminal_title), onClick = onOpenTerminal)
                SettingsRow(title = stringResource(R.string.workspace_title), trailingText = workspaceName ?: stringResource(R.string.workspace_none),
                    onClick = onOpenWorkspace)
            }
            SettingsGroup(title = stringResource(R.string.usage_title)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.usage_enable), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.usage_setting_description), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = appSettings.usageTrackingEnabled, onCheckedChange = onUsageTrackingChange,
                        modifier = Modifier.testTag("usage_tracking").semantics { contentDescription = context.getString(R.string.usage_enable) })
                }
            }
            SettingsGroup(title = stringResource(R.string.settings_preferences)) {
                SettingsRow(title = stringResource(R.string.appearance),
                    trailingText = stringResource(appAppearance.labelRes()), onClick = { showAppearanceDialog = true })
                SettingsRow(title = stringResource(R.string.language),
                    trailingText = stringResource(currentLanguage.labelRes()), onClick = { showLanguageDialog = true })
            }
            Text(
                text = "${stringResource(R.string.app_name)} · ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
        }
    }
    if (showAppearanceDialog) {
        AppearanceDialog(
            selected = appAppearance,
            onSelect = onAppAppearanceChange,
            onDismiss = { showAppearanceDialog = false }
        )
    }
    if (showLanguageDialog) {
        LanguageDialog(
            selected = currentLanguage,
            onSelect = { language ->
                AppLocale.setLanguage(context, language)
                showLanguageDialog = false
                context.findActivity()?.recreate()
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
    if (showDeviceAssistantDialog) {
        DeviceAssistantDialog(
            appSettings = appSettings,
            shizukuState = shizukuState,
            accessibilityState = accessibilityState,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onPowerModeEnabledChange = onPowerModeEnabledChange,
            onPowerConnectionModeChange = onPowerConnectionModeChange,
            onRequestShizukuPermission = onRequestShizukuPermission,
            onRefreshShizuku = onRefreshShizuku,
            onOpenShizuku = {
                runCatching {
                    context.startActivity(
                        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    )
                }
            },
            onDismiss = { showDeviceAssistantDialog = false }
        )
    }
    if (showMemoryDialog) {
        MemorySettingsDialog(
            enabled = appSettings.memoryEnabled,
            memories = memories,
            onEnabledChange = onMemoryEnabledChange,
            onDelete = onDeleteMemory,
            onUpdate = onUpdateMemory,
            onClear = onClearMemories,
            onDismiss = { showMemoryDialog = false }
        )
    }
}

@Composable
internal fun DeviceAssistantDialog(
    appSettings: AppSettings,
    shizukuState: ShizukuConnectionState,
    accessibilityState: AccessibilityConnectionState,
    onOpenAccessibilitySettings: () -> Unit,
    onPowerModeEnabledChange: (Boolean) -> Unit,
    onPowerConnectionModeChange: (PowerConnectionMode) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onDismiss: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshShizuku()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onRefreshShizuku()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val readiness = deviceReadiness(appSettings.powerModeEnabled, shizukuState, accessibilityState)
    val rootMode = appSettings.powerConnectionMode == PowerConnectionMode.Root
    val statusAction = when (shizukuState.status) {
        ShizukuConnectionStatus.RootUnavailable,
        ShizukuConnectionStatus.RootPermissionRequired,
        ShizukuConnectionStatus.RootPermissionDenied,
        ShizukuConnectionStatus.PermissionRequired,
        ShizukuConnectionStatus.PermissionDenied -> onRequestShizukuPermission
        ShizukuConnectionStatus.NotInstalled,
        ShizukuConnectionStatus.NotRunning -> onOpenShizuku
        else -> onRefreshShizuku
    }
    val actionLabel = when (shizukuState.status) {
        ShizukuConnectionStatus.RootPermissionRequired,
        ShizukuConnectionStatus.PermissionRequired -> R.string.device_connect_authorize
        ShizukuConnectionStatus.RootUnavailable,
        ShizukuConnectionStatus.RootPermissionDenied,
        ShizukuConnectionStatus.PermissionDenied -> R.string.device_connect_retry
        ShizukuConnectionStatus.NotInstalled,
        ShizukuConnectionStatus.NotRunning -> R.string.device_open_shizuku
        else -> R.string.device_refresh_status
    }
    Dialog(onDismissRequest = onDismiss) {
        FlowDialogSystemBars()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(18.dp)) {
                FlowSheetHeader(stringResource(R.string.device_assistant), onDismiss)
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SettingsToggleRow(
                        title = stringResource(R.string.power_mode),
                        supportingText = stringResource(R.string.power_mode_description),
                        checked = appSettings.powerModeEnabled,
                        onCheckedChange = onPowerModeEnabledChange
                    )
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(deviceReadinessLabel(readiness), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(when (readiness) {
                            DeviceReadiness.Off -> R.string.device_off_description
                            DeviceReadiness.NeedsSetup -> R.string.device_setup_description
                            DeviceReadiness.ScreenOnly -> R.string.device_screen_only_description
                            DeviceReadiness.Ready -> R.string.device_ready_description
                        }), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.device_connection_method), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PowerConnectionMode.entries.forEach { mode ->
                            androidx.compose.material3.FilterChip(
                                selected = appSettings.powerConnectionMode == mode,
                                onClick = { onPowerConnectionModeChange(mode) },
                                label = { Text(if (mode == PowerConnectionMode.Root) "Root" else "Shizuku") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(stringResource(if (rootMode) R.string.root_mode_description else R.string.shizuku_mode_description),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(if (rootMode) R.string.root_status else R.string.shizuku_status),
                            style = MaterialTheme.typography.titleSmall)
                        Text(shizukuStatusLabel(shizukuState.status), color = MaterialTheme.colorScheme.primary)
                        Text(if (rootMode && shizukuState.status == ShizukuConnectionStatus.Error)
                            stringResource(R.string.root_error_description) else shizukuStatusDescription(shizukuState.status),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = statusAction, enabled = shizukuState.status != ShizukuConnectionStatus.Connecting) {
                            Text(stringResource(actionLabel))
                        }
                    }
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.accessibility_screen_control), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(when (accessibilityState.status) {
                            AccessibilityConnectionStatus.Connected -> R.string.accessibility_connected
                            AccessibilityConnectionStatus.Disabled -> R.string.accessibility_disabled
                            AccessibilityConnectionStatus.Unavailable -> R.string.accessibility_unavailable
                            AccessibilityConnectionStatus.Error -> R.string.accessibility_error
                        }), color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(when (accessibilityState.status) {
                            AccessibilityConnectionStatus.Connected -> R.string.accessibility_connected_description
                            AccessibilityConnectionStatus.Disabled -> R.string.accessibility_disabled_description
                            AccessibilityConnectionStatus.Unavailable -> R.string.accessibility_unavailable_description
                            AccessibilityConnectionStatus.Error -> R.string.accessibility_error_description
                        }), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!accessibilityState.isConnected) {
                            TextButton(onClick = onOpenAccessibilitySettings) {
                                Text(stringResource(R.string.device_accessibility_settings))
                            }
                        }
                        Text(stringResource(R.string.screen_vision_description), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.device_usage_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun deviceReadinessLabel(readiness: DeviceReadiness): String = stringResource(when (readiness) {
    DeviceReadiness.Off -> R.string.device_readiness_off
    DeviceReadiness.NeedsSetup -> R.string.device_readiness_setup
    DeviceReadiness.ScreenOnly -> R.string.device_readiness_screen_only
    DeviceReadiness.Ready -> R.string.device_readiness_ready
})

@Composable
private fun DeviceActionConfirmationDialog(
    confirmation: DeviceActionConfirmationUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val systemCommand = confirmation.toolName == TerminalToolDefinitions.Run
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (systemCommand) stringResource(R.string.terminal_system_approval_title) else stringResource(R.string.force_stop_confirmation_title, confirmation.title)) },
        text = { Text(if (systemCommand) stringResource(R.string.terminal_system_approval_message, confirmation.title, confirmation.message)
            else stringResource(R.string.force_stop_confirmation_message, confirmation.title),
            modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.continue_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun shizukuStatusLabel(status: ShizukuConnectionStatus): String = stringResource(
    when (status) {
        ShizukuConnectionStatus.NotInstalled -> R.string.shizuku_not_installed
        ShizukuConnectionStatus.NotRunning -> R.string.shizuku_not_running
        ShizukuConnectionStatus.PermissionRequired -> R.string.shizuku_permission_required
        ShizukuConnectionStatus.PermissionDenied -> R.string.shizuku_permission_denied
        ShizukuConnectionStatus.Connecting -> R.string.shizuku_connecting
        ShizukuConnectionStatus.ConnectedAdb -> R.string.shizuku_connected_adb
        ShizukuConnectionStatus.ConnectedRoot -> R.string.shizuku_connected_root
        ShizukuConnectionStatus.RootUnavailable -> R.string.root_unavailable
        ShizukuConnectionStatus.RootPermissionRequired -> R.string.root_permission_required
        ShizukuConnectionStatus.RootPermissionDenied -> R.string.root_permission_denied
        ShizukuConnectionStatus.ConnectedDirectRoot -> R.string.root_connected
        ShizukuConnectionStatus.Unsupported -> R.string.shizuku_unsupported
        ShizukuConnectionStatus.Error -> R.string.shizuku_error
    }
)

@Composable
private fun shizukuStatusDescription(status: ShizukuConnectionStatus): String = stringResource(
    when (status) {
        ShizukuConnectionStatus.NotInstalled -> R.string.shizuku_not_installed_description
        ShizukuConnectionStatus.NotRunning -> R.string.shizuku_not_running_description
        ShizukuConnectionStatus.PermissionRequired -> R.string.shizuku_permission_required_description
        ShizukuConnectionStatus.PermissionDenied -> R.string.shizuku_permission_denied_description
        ShizukuConnectionStatus.Connecting -> R.string.shizuku_connecting_description
        ShizukuConnectionStatus.ConnectedAdb -> R.string.shizuku_connected_adb_description
        ShizukuConnectionStatus.ConnectedRoot -> R.string.shizuku_connected_root_description
        ShizukuConnectionStatus.RootUnavailable -> R.string.root_unavailable_description
        ShizukuConnectionStatus.RootPermissionRequired -> R.string.root_permission_required_description
        ShizukuConnectionStatus.RootPermissionDenied -> R.string.root_permission_denied_description
        ShizukuConnectionStatus.ConnectedDirectRoot -> R.string.root_connected_description
        ShizukuConnectionStatus.Unsupported -> R.string.shizuku_unsupported_description
        ShizukuConnectionStatus.Error -> R.string.shizuku_error_description
    }
)

@Composable
private fun SettingsToggleRow(title: String, supportingText: String, checked: Boolean,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    FlowToggleRow(title, supportingText, checked, onCheckedChange, enabled = enabled)
}

@Composable
private fun AppearanceDialog(
    selected: AppAppearance,
    onSelect: (AppAppearance) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        FlowDialogSystemBars()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.appearance),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                SettingsSelectionOptionRow(
                    label = stringResource(R.string.appearance_system),
                    selected = selected == AppAppearance.System,
                    onClick = {
                        onSelect(AppAppearance.System)
                        onDismiss()
                    }
                )
                SettingsSelectionOptionRow(
                    label = stringResource(R.string.appearance_light),
                    selected = selected == AppAppearance.Light,
                    onClick = {
                        onSelect(AppAppearance.Light)
                        onDismiss()
                    }
                )
                SettingsSelectionOptionRow(
                    label = stringResource(R.string.appearance_dark),
                    selected = selected == AppAppearance.Dark,
                    onClick = {
                        onSelect(AppAppearance.Dark)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun LanguageDialog(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        FlowDialogSystemBars()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                SettingsSelectionOptionRow(
                    label = stringResource(R.string.language_system),
                    selected = selected == AppLanguage.System,
                    onClick = { onSelect(AppLanguage.System) }
                )
                SettingsSelectionOptionRow(
                    label = stringResource(R.string.language_zh_cn),
                    selected = selected == AppLanguage.ChineseSimplified,
                    onClick = { onSelect(AppLanguage.ChineseSimplified) }
                )
                SettingsSelectionOptionRow(
                    label = stringResource(R.string.language_en),
                    selected = selected == AppLanguage.English,
                    onClick = { onSelect(AppLanguage.English) }
                )
            }
        }
    }
}

@Composable
private fun SettingsSelectionOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AppearanceRadioDot(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val ringColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(20.dp)) {
        drawCircle(
            color = ringColor,
            radius = size.minDimension * 0.38f,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = 2.5.dp.toPx())
        )
        if (selected) {
            drawCircle(
                color = fillColor,
                radius = size.minDimension * 0.18f,
                center = Offset(size.width / 2f, size.height / 2f)
            )
        }
    }
}

private fun AppAppearance.labelRes(): Int =
    when (this) {
        AppAppearance.System -> R.string.appearance_system
        AppAppearance.Light -> R.string.appearance_light
        AppAppearance.Dark -> R.string.appearance_dark
    }

private fun AppLanguage.labelRes(): Int =
    when (this) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.ChineseSimplified -> R.string.language_zh_cn
        AppLanguage.English -> R.string.language_en
    }

@Composable
private fun UsageAccessPermissionDialog(
    toolName: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val displayToolName = localizedToolName(toolName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.usage_access_permission_title))
        },
        text = {
            Text(text = stringResource(R.string.usage_access_permission_message, displayToolName))
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.open_system_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun localizedDeviceToolDetail(detail: String): String = when (detail) {
    "model_vision_unsupported" -> stringResource(R.string.screen_vision_model_unsupported)
    "screen_capture_requires_android_11" -> stringResource(R.string.screen_vision_android_required)
    "screen_capture_permission_required" -> stringResource(R.string.screen_vision_permission)
    "secure_window_blocked" -> stringResource(R.string.screen_vision_secure)
    "screen_changed_during_capture" -> stringResource(R.string.screen_vision_changed)
    "screen_capture_rate_limited" -> stringResource(R.string.screen_vision_rate_limited)
    "screen_capture_failed", "screen_capture_empty", "screen_window_unavailable", "screen_display_unavailable",
    "screen_image_too_large", "screen_capture_timeout", "screen_capture_unavailable" -> stringResource(R.string.screen_vision_unavailable)
    "stale_screen" -> stringResource(R.string.device_error_stale)
    "ui_action_failed" -> stringResource(R.string.device_error_action)
    "ui_no_change" -> stringResource(R.string.device_error_no_change)
    "screen_unavailable" -> stringResource(R.string.device_error_screen)
    "accessibility_unavailable" -> stringResource(R.string.accessibility_disabled_description)
    "sensitive_screen_blocked" -> stringResource(R.string.device_error_sensitive)
    "self_automation_blocked" -> stringResource(R.string.device_error_self)
    else -> detail
}

@Composable
private fun localizedToolName(toolName: String): String {
    val baseName = toolName.substringBefore(':')
    val detail = toolName.substringAfter(':', missingDelimiterValue = "").trim()
    return when (baseName) {
        TerminalToolDefinitions.Run -> stringResource(R.string.terminal_run)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Status -> stringResource(R.string.workspace_tool_status)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.ListFiles -> stringResource(R.string.workspace_tool_list)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Read -> stringResource(R.string.workspace_tool_read)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Write -> stringResource(R.string.workspace_tool_write)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Rename -> stringResource(R.string.workspace_tool_rename)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Import -> stringResource(R.string.workspace_tool_import)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Publish -> stringResource(R.string.workspace_tool_publish)
        "web_search" -> stringResource(R.string.web_search_short)
        "get_app_usage_summary" -> stringResource(R.string.app_usage_tool)
        "get_recent_app_activity" -> stringResource(R.string.recent_app_activity_tool)
        "open_app" -> if (detail.isBlank()) {
            stringResource(R.string.open_app_tool)
        } else {
            stringResource(R.string.open_app_named, detail)
        }
        "get_device_status" -> stringResource(R.string.capability_device_status)
        "get_foreground_app" -> stringResource(R.string.capability_foreground_app)
        "set_screen_brightness" -> stringResource(R.string.capability_screen_brightness)
        "set_media_volume" -> stringResource(R.string.capability_media_volume)
        "force_stop_app" -> if (detail.isBlank()) {
            stringResource(R.string.capability_force_stop_app)
        } else {
            stringResource(R.string.force_stop_named, detail)
        }
        "observe_screen" -> stringResource(R.string.tool_name_observe_screen)
        "wait_for_screen" -> stringResource(R.string.tool_name_wait_for_screen)
        "tap_ui_element" -> stringResource(R.string.tool_name_tap_ui_element)
        "input_text" -> stringResource(R.string.tool_name_input_text)
        "swipe_screen" -> stringResource(R.string.tool_name_swipe_screen)
        "press_back" -> stringResource(R.string.tool_name_press_back)
        "press_home" -> stringResource(R.string.tool_name_press_home)
        else -> toolName.ifBlank { "tool" }
    }
}

@Composable
private fun localizedToolAction(toolName: String): String {
    val baseName = toolName.substringBefore(':')
    val detail = toolName.substringAfter(':', missingDelimiterValue = "").trim()
    return when (baseName) {
        "run_command" -> stringResource(R.string.tool_action_run_command)
        "web_search" -> stringResource(R.string.tool_action_web_search)
        "get_app_usage_summary" -> stringResource(R.string.tool_action_app_usage)
        "get_recent_app_activity" -> stringResource(R.string.tool_action_recent_activity)
        "open_app" -> if (detail.isBlank()) {
            stringResource(R.string.tool_action_open_app_generic)
        } else {
            stringResource(R.string.tool_action_open_app, detail)
        }
        "get_device_status" -> stringResource(R.string.tool_action_device_status)
        "get_foreground_app" -> stringResource(R.string.tool_action_foreground_app)
        "set_screen_brightness" -> stringResource(R.string.tool_action_brightness)
        "set_media_volume" -> stringResource(R.string.tool_action_volume)
        "force_stop_app" -> if (detail.isBlank()) {
            stringResource(R.string.tool_action_force_stop_generic)
        } else {
            stringResource(R.string.tool_action_force_stop, detail)
        }
        "observe_screen" -> stringResource(R.string.tool_action_observe_screen)
        "wait_for_screen" -> stringResource(R.string.tool_action_wait_for_screen)
        "tap_ui_element" -> stringResource(R.string.tool_action_tap_ui_element)
        "input_text" -> stringResource(R.string.tool_action_input_text)
        "swipe_screen" -> stringResource(R.string.tool_action_swipe_screen)
        "press_back" -> stringResource(R.string.tool_action_press_back)
        "press_home" -> stringResource(R.string.tool_action_press_home)
        else -> stringResource(R.string.tool_action_generic, localizedToolName(toolName))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        FlowSectionLabel(title)
        FlowGroup(content = content)
    }
}

@Composable
private fun SettingsRow(title: String, trailingText: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (trailingText != null) Text(trailingText, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp))
        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun flowTextFieldColors() = flowFieldColors()

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageBubble(message: Message, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean, reasoning: Message?) {
    if (message.role == MessageRole.Assistant && message.status == MessageStatus.Complete &&
        message.content.isBlank() && message.reasoningContent.isBlank()) return
    if (message.role == MessageRole.Tool) {
        ToolCallBubble(message)
        return
    }

    val isUser = message.role == MessageRole.User
    val isCommentary = message.replyPhase == com.flowchat.app.domain.model.ReplyPhase.Commentary
    val hasReasoning = !isUser && reasoning != null
    val isWaitingForAssistant =
        !isUser && message.status == MessageStatus.Streaming && message.content.isBlank() && !hasReasoning
    val shouldShowContentBubble =
        isUser || message.content.isNotBlank() || isWaitingForAssistant || (!hasReasoning && message.role == MessageRole.Assistant)
    val context = LocalContext.current
    val messageTime = remember(context, message.createdAt) {
        formatMessageTime(context, message.createdAt)
    }
    val clipboardManager = LocalClipboardManager.current
    var showMessageActions by remember { mutableStateOf(false) }
    var showTextSelection by remember { mutableStateOf(false) }
    val displayContent = when {
        message.content.isNotBlank() -> message.content
        message.attachmentName != null -> ""
        message.role == MessageRole.Assistant -> stringResource(R.string.empty_response_message)
        else -> "..."
    }
    val copyText = if (!shouldShowContentBubble || isWaitingForAssistant) {
        ""
    } else {
        buildString {
            if (displayContent.isNotBlank()) append(displayContent)
            if (message.attachmentName != null) {
                if (isNotEmpty()) append("\n\n")
                append("[").append(message.attachmentName).append("]")
                message.attachmentText?.takeIf { it.isNotBlank() }?.let { text ->
                    append("\n").append(text)
                }
            }
        }
    }
    val bubbleColor = Color.Transparent
    val bubbleContentColor = MaterialTheme.colorScheme.onBackground
    val bubbleShape = RoundedCornerShape(0.dp)
    val messageRule = MaterialTheme.colorScheme.outlineVariant
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val assistantMaxWidth = minOf(FlowLayout.ContentWidth - FlowLayout.Gutter * 2, screenWidth - FlowLayout.Gutter * 2)
    val bubbleMaxWidth = if (isUser) {
        minOf(480.dp, assistantMaxWidth * .9f - if (showAvatars) 46.dp else 0.dp)
    } else {
        assistantMaxWidth
    }
    val messageContent: @Composable () -> Unit = {
        Box(modifier = Modifier.widthIn(max = bubbleMaxWidth)) {
            Column(modifier = Modifier.widthIn(max = bubbleMaxWidth)) {
                if (!isUser && reasoning != null) {
                    ReasoningBubble(message = reasoning, maxWidth = bubbleMaxWidth)
                    Spacer(Modifier.size(6.dp))
                }
                if (shouldShowContentBubble) {
                    Card(
                        modifier = Modifier
                            .widthIn(min = 56.dp, max = bubbleMaxWidth)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (copyText.isNotBlank()) {
                                        showMessageActions = true
                                    }
                                }
                            ),
                        shape = bubbleShape,
                        colors = CardDefaults.cardColors(
                            containerColor = bubbleColor,
                            contentColor = bubbleContentColor
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.drawBehind {
                            if (isUser) drawLine(messageRule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                        }.padding(vertical = 12.dp)) {
                            message.attachmentName?.let { name ->
                                if (message.attachmentImagePath != null) {
                                    ImageAttachmentPreview(name, message.attachmentImagePath)
                                } else AttachmentMessagePreview(name = name)
                                if (displayContent.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            if (isWaitingForAssistant) {
                                StreamingJumpingDots()
                            } else if (displayContent.isNotBlank()) {
                                Text(
                                    text = displayContent,
                                    style = if (isCommentary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                    color = if (isCommentary) MaterialTheme.colorScheme.onSurfaceVariant else bubbleContentColor
                                )
                            }
                        }
                    }
                }
                if (!isUser && !isCommentary && copyText.isNotBlank() && message.status != MessageStatus.Streaming) {
                    Row {
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(copyText)) }) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_message), Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showMessageActions = true }) {
                            Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.message_actions), Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            DropdownMenu(
                expanded = showMessageActions,
                onDismissRequest = { showMessageActions = false }
            ) {
                Text(messageTime, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_message)) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(copyText))
                        showMessageActions = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select_text)) },
                    onClick = {
                        showMessageActions = false
                        showTextSelection = true
                    }
                )
            }
        }
    }

    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            messageContent()
            if (showAvatars) {
                Spacer(Modifier.size(8.dp))
                ProfileAvatar(
                    avatarPath = userAvatarPath,
                    size = 38.dp
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            if (showAvatars) {
                AssistantAvatar(
                    avatarPath = assistantAvatarPath,
                    size = 38.dp
                )
                Spacer(Modifier.height(6.dp))
            }
            messageContent()
        }
    }

    if (showTextSelection) {
        AlertDialog(
            onDismissRequest = { showTextSelection = false },
            text = {
                SelectionContainer {
                    Text(copyText)
                }
            },
            confirmButton = {
                TextButton(onClick = { showTextSelection = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AttachmentMessagePreview(name: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToolCallBubble(message: Message) {
    var commandExpanded by remember(message.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val messageTime = remember(context, message.createdAt) {
        formatMessageTime(context, message.createdAt)
    }
    val lines = remember(message.content) { message.content.lines() }
    val toolName = lines.firstOrNull()?.takeIf { it.isNotBlank() }.orEmpty()
    val action = localizedToolAction(toolName)
    val detail = lines.drop(1).joinToString(separator = "\n").takeIf { it.isNotBlank() }
    val titleRes = when (message.status) {
        MessageStatus.Streaming -> R.string.tool_action_running
        MessageStatus.Complete -> R.string.tool_action_complete
        MessageStatus.Cancelled -> R.string.tool_action_cancelled
        MessageStatus.Failed -> R.string.tool_action_failed
        else -> R.string.tool_action_complete
    }
    val accentColor = when (message.status) {
        MessageStatus.Streaming -> MaterialTheme.colorScheme.primary
        MessageStatus.Complete -> MaterialTheme.colorScheme.tertiary
        MessageStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageStatus.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(modifier = Modifier.widthIn(max = 560.dp)) {
            Card(
                modifier = Modifier.clickable { commandExpanded = !commandExpanded },
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor, CircleShape)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(titleRes, action),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(if (commandExpanded) R.string.thinking_collapse else R.string.thinking_expand),
                            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (message.status == MessageStatus.Streaming && toolName == "run_command") {
                            Regex("elapsed=(\\d+)s").find(detail.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { seconds ->
                                Text(stringResource(R.string.tool_running_elapsed, seconds),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (detail != null && (commandExpanded || message.status == MessageStatus.Failed)) {
                            Text(
                                text = localizedDeviceToolDetail(detail),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                        }
                    }
                }
            }
            if (commandExpanded) Text(
                text = messageTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun ReasoningBubble(message: Message, maxWidth: Dp) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val isStillThinking = message.status == MessageStatus.Streaming && message.content.isBlank()
    val elapsedSeconds = (message.reasoningDurationMillis / 1000L).coerceAtLeast(1L)
    val title = when {
        message.status == MessageStatus.Stopped -> stringResource(R.string.thinking_stopped)
        isStillThinking -> stringResource(R.string.thinking_in_progress)
        else -> stringResource(R.string.thinking_done, elapsedSeconds)
    }

    Card(
        modifier = Modifier
            .widthIn(min = 160.dp, max = maxWidth)
            .heightIn(min = 48.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(Icons.Default.KeyboardArrowDown,
                    stringResource(if (expanded) R.string.thinking_collapse else R.string.thinking_expand),
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = message.reasoningContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatMessageTime(context: Context, timestampMillis: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(timestampMillis))

private fun attachmentErrorMessage(context: Context, error: Throwable): String =
    when ((error as? AttachmentReadException)?.failure) {
        AttachmentReadFailure.UnsupportedType -> context.getString(R.string.attachment_unsupported)
        AttachmentReadFailure.TooLarge -> context.getString(R.string.attachment_too_large)
        AttachmentReadFailure.EmptyContent -> context.getString(R.string.attachment_empty)
        AttachmentReadFailure.InvalidImage -> context.getString(R.string.attachment_invalid_image)
        AttachmentReadFailure.ImageDimensionsTooLarge -> context.getString(R.string.attachment_image_dimensions)
        AttachmentReadFailure.ReadFailed, null -> context.getString(R.string.attachment_read_failed)
    }

@Composable
private fun StreamingJumpingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "streamingJumpingDots")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart
        ),
        label = "streamingJumpingProgress"
    )
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val phaseOffsets = listOf(0f, 0.18f, 0.36f)

    Canvas(modifier = modifier.size(width = 42.dp, height = 22.dp)) {
        val dotRadius = 3.dp.toPx()
        val jumpHeight = dotRadius * 2.2f
        val spacing = dotRadius * 3.2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val centers = listOf(
            center.copy(x = center.x - spacing),
            center,
            center.copy(x = center.x + spacing)
        )

        centers.forEachIndexed { index, dotCenter ->
            val phase = (progress - phaseOffsets[index] + 1f) % 1f
            val jump = if (phase < 0.5f) {
                1f - kotlin.math.abs(phase - 0.25f) / 0.25f
            } else {
                0f
            }
            val radius = dotRadius * (1f + (jump * 0.18f))
            val alpha = 0.45f + (jump * 0.55f)

            drawCircle(
                color = dotColor.copy(alpha = alpha),
                radius = radius,
                center = dotCenter.copy(y = dotCenter.y - (jumpHeight * jump))
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun MessageComposer(
    value: String,
    pendingAttachment: PendingAttachment?,
    isAttachmentLoading: Boolean,
    enabled: Boolean,
    isStreaming: Boolean,
    providers: List<ProviderConfig>,
    currentConversation: Conversation?,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onThinkingEnabledChange: (Boolean) -> Unit,
    onThinkingEffortChange: (ThinkingEffort) -> Unit,
    onPickAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onSelectModel: (ProviderConfig, String) -> Unit,
    usageRecords: List<com.flowchat.app.data.db.UsageRecord>? = null,
    balanceState: com.flowchat.app.data.usage.BalanceState = com.flowchat.app.data.usage.BalanceState(),
    onRefreshBalance: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val inputMethodManager = remember(view) {
        view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    val hasInput = value.isNotBlank() || pendingAttachment != null
    val sendDescription = stringResource(if (isStreaming) R.string.stop else R.string.send)
    val modelOptions = remember(providers) { configuredModelOptions(providers) }
    val currentProvider = providers.firstOrNull { provider -> provider.id == currentConversation?.providerId }
    val sendIconColor = when {
        isStreaming -> MaterialTheme.colorScheme.onErrorContainer
        hasInput -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun showKeyboardForFocusedInput() {
        if (!enabled) return
        keyboardController?.show()
        view.post {
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_FORCED)
        }
    }

    fun requestFocusAndShowKeyboard() {
        if (!enabled) return
        focusRequester.requestFocus()
        showKeyboardForFocusedInput()
    }

    fun submitMessage() {
        if (!enabled || isAttachmentLoading || isStreaming) return
        if (value.isBlank() && pendingAttachment == null) return
        onSend()
        onValueChange("")
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
        .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = FlowLayout.ContentWidth)
        .padding(horizontal = 20.dp, vertical = 4.dp)) {
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(17.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest).testTag("message_composer_panel")) {
       Column(Modifier.padding(6.dp)) {
        if (isAttachmentLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            Text(stringResource(R.string.attachment_loading), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (pendingAttachment?.imagePath != null) {
            ImageAttachmentPreview(pendingAttachment.name, pendingAttachment.imagePath, compact = true, onRemove = onClearAttachment)
        } else if (pendingAttachment != null) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = pendingAttachment.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onClearAttachment, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close,
                            contentDescription = stringResource(R.string.remove_attachment),
                            modifier = Modifier.size(18.dp))
                    }
                },
                modifier = Modifier.widthIn(max = 280.dp)
            )
        }
            OutlinedTextField(
                value = value,
                onValueChange = if (enabled) onValueChange else { _ -> },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (enabled && focusState.isFocused) showKeyboardForFocusedInput()
                    }
                    .pointerInput(enabled, keyboardController, view) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            requestFocusAndShowKeyboard()
                        }
                    },
                placeholder = {
                    Text(
                        stringResource(if (enabled) R.string.message_hint else R.string.create_conversation_to_chat),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitMessage() }),
                maxLines = if (LocalConfiguration.current.screenHeightDp < 480) 3 else 5
            )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onPickAttachment, enabled = enabled && !isStreaming && !isAttachmentLoading,
                    modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Add, stringResource(R.string.upload_file), Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ThinkingModeControl(
                    support = ThinkingSupport.resolve(currentProvider, currentConversation?.modelName.orEmpty()),
                    enabled = currentConversation?.enableThinking == true,
                    effort = currentConversation?.thinkingEffort ?: ThinkingEffort.Auto,
                    interactive = enabled && !isStreaming,
                    onEnabledChange = onThinkingEnabledChange, onEffortChange = onThinkingEffortChange
                )
            }
            IconButton(onClick = if (isStreaming) onStop else ::submitMessage,
                enabled = (enabled && hasInput && !isAttachmentLoading) || isStreaming,
                modifier = Modifier.size(48.dp).semantics { contentDescription = sendDescription }) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(when {
                    isStreaming -> MaterialTheme.colorScheme.errorContainer
                    enabled && hasInput -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }), contentAlignment = Alignment.Center) {
                    Icon(if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward, null,
                        tint = sendIconColor, modifier = Modifier.size(20.dp))
                }
            }
        }
       }
       if (usageRecords != null) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
              .padding(horizontal = 12.dp, vertical = 6.dp).testTag("composer_usage_footer"),
              verticalAlignment = Alignment.CenterVertically) {
              UsageDisplay(usageRecords,
                  com.flowchat.app.data.usage.UsageTracker.supports(currentConversation?.modelName.orEmpty(), currentProvider),
                  Modifier.weight(1f), balanceState, onRefreshBalance)
              VerticalDivider(Modifier.height(28.dp).padding(horizontal = 10.dp),
                  color = MaterialTheme.colorScheme.outlineVariant)
              Box(Modifier.weight(1.1f)) {
                  ModelSwitchButton(currentProvider, currentConversation?.modelName, modelOptions,
                      enabled = enabled && !isStreaming && modelOptions.isNotEmpty(), onSelectModel = onSelectModel, compact = true)
              }
          }
       }
      }
      if (usageRecords == null) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
              ModelSwitchButton(currentProvider, currentConversation?.modelName, modelOptions,
                  enabled = enabled && !isStreaming && modelOptions.isNotEmpty(), onSelectModel = onSelectModel)
          }
      }
    }
}

@Composable
private fun ModelSwitchButton(
    currentProvider: ProviderConfig?,
    currentModelName: String?,
    modelOptions: List<ComposerModelOption>,
    enabled: Boolean,
    onSelectModel: (ProviderConfig, String) -> Unit,
    compact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDotColor = MaterialTheme.colorScheme.primary
    val switchModelDescription = stringResource(R.string.switch_model)
    Box {
        Row(
            modifier = Modifier
                .heightIn(min = ComposerToolHeight)
                .clip(RoundedCornerShape(16.dp))
                .semantics { contentDescription = switchModelDescription }
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = if (compact) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            Text(currentModelName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.model),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 240.dp))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            modelOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ProviderLogoImage(
                                providerName = option.provider.displayName,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = option.modelName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = option.provider.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectModel(option.provider, option.modelName)
                    },
                    trailingIcon = if (option.modelName == currentModelName && option.provider.id == currentProvider?.id) {
                        {
                            Icon(Icons.Default.Check, null, tint = selectedDotColor, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

private fun configuredModelOptions(providers: List<ProviderConfig>): List<ComposerModelOption> =
    providers.asSequence()
        .filter { provider -> provider.isEnabled }
        .filter { provider -> provider.baseUrl.isNotBlank() }
        .filter { provider -> provider.defaultModel.isNotBlank() }
        .flatMap { provider ->
            recognizedProviderModelNames(provider).map { modelName ->
                ComposerModelOption(provider = provider, modelName = modelName)
            }
        }
        .toList()

private fun recognizedProviderModelNames(provider: ProviderConfig): List<String> {
    val providerKey = "${provider.displayName} ${provider.baseUrl}".lowercase()
    val names = when {
        providerKey.contains("deepseek") ->
            listOf(provider.defaultModel.trim(), "deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash")
        else ->
            listOf(provider.defaultModel.trim())
    }
    return names
        .filter { modelName -> modelName.isNotBlank() }
        .distinctBy { modelName -> modelName.lowercase() }
}

private data class ComposerModelOption(
    val provider: ProviderConfig,
    val modelName: String
)

@Composable
private fun ProviderLogoImage(
    providerName: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (providerLogoRes(providerName) == R.drawable.provider_kimi) Color(0xFF111827) else Color.Transparent)
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        val logoRes = providerLogoRes(providerName)
        if (logoRes != null) {
            Image(
                painter = painterResource(logoRes),
                colorFilter = if (logoRes == R.drawable.provider_grok) androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
                contentDescription = stringResource(R.string.switch_model),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = stringResource(R.string.switch_model),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
            )
        }
    }
}

private fun providerLogoRes(providerName: String?): Int? =
    when {
        providerName?.contains("qwen", ignoreCase = true) == true -> R.drawable.provider_qwen
        providerName?.contains("通义", ignoreCase = true) == true -> R.drawable.provider_qwen
        providerName?.contains("glm", ignoreCase = true) == true -> R.drawable.provider_glm
        providerName?.contains("zhipu", ignoreCase = true) == true -> R.drawable.provider_glm
        providerName?.contains("智谱", ignoreCase = true) == true -> R.drawable.provider_glm
        providerName?.contains("kimi", ignoreCase = true) == true -> R.drawable.provider_kimi
        providerName?.contains("moonshot", ignoreCase = true) == true -> R.drawable.provider_kimi
        providerName?.contains("grok", ignoreCase = true) == true -> R.drawable.provider_grok
        providerName?.contains("x.ai", ignoreCase = true) == true -> R.drawable.provider_grok
        providerName?.contains("minimax", ignoreCase = true) == true -> R.drawable.provider_minimax
        providerName?.contains("doubao", ignoreCase = true) == true -> R.drawable.provider_doubao
        providerName?.contains("豆包", ignoreCase = true) == true -> R.drawable.provider_doubao
        providerName?.contains("claude", ignoreCase = true) == true -> R.drawable.provider_claude
        providerName?.contains("deep", ignoreCase = true) == true -> R.drawable.provider_deepseek
        providerName?.contains("gemini", ignoreCase = true) == true -> R.drawable.provider_gemini
        providerName?.contains("chat", ignoreCase = true) == true ||
            providerName?.contains("open", ignoreCase = true) == true -> R.drawable.provider_openai
        else -> null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSettingsSheet(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onSave: (String, String?, Boolean, String, Double, Double, Int?) -> Unit
) {
    val context = LocalContext.current
    var assistantName by remember(conversation.id) { mutableStateOf(conversation.assistantName) }
    var assistantAvatarPath by remember(conversation.id) { mutableStateOf(conversation.assistantAvatarPath) }
    var showAvatars by remember(conversation.id) { mutableStateOf(conversation.showAvatars) }
    var prompt by remember(conversation.id) { mutableStateOf(conversation.systemPrompt) }
    var temperature by remember(conversation.id) { mutableStateOf(conversation.temperature.toFloat()) }
    var topP by remember(conversation.id) { mutableStateOf(conversation.topP.toFloat()) }
    var maxTokens by remember(conversation.id) { mutableStateOf(conversation.maxTokens?.toString().orEmpty()) }
    var showAdvancedParameters by remember(conversation.id) { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            settingsScope.launch {
                val path = withContext(Dispatchers.IO) {
                    copyAssistantAvatarToPrivateFile(context, conversation.id, uri)
                }
                if (path != null) {
                    assistantAvatarPath = path
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                FlowSheetHeader(stringResource(R.string.conversation_settings), onDismiss)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_avatar),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AssistantAvatarStrip(
                        assistantAvatarPath = assistantAvatarPath,
                        onAvatarClick = { avatarPicker.launch("image/*") }
                    )
                }
                OutlinedTextField(
                    value = assistantName,
                    onValueChange = { assistantName = it },
                    label = { Text(stringResource(R.string.assistant_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = flowTextFieldColors(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(R.string.show_avatars),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(R.string.personalization_switch_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showAvatars,
                        onCheckedChange = { showAvatars = it }
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.system_prompt)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 132.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = flowTextFieldColors(),
                    minLines = 4,
                    maxLines = 8
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showAdvancedParameters = !showAdvancedParameters }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.advanced_parameters),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(
                            if (showAdvancedParameters) R.string.thinking_collapse else R.string.thinking_expand
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showAdvancedParameters) {
                    SettingsLabeledSlider(
                        title = stringResource(R.string.reply_style),
                        startLabel = stringResource(R.string.concise),
                        endLabel = stringResource(R.string.detailed),
                        value = topP,
                        onValueChange = { topP = it },
                        valueRange = 0f..1f
                    )
                    SettingsLabeledSlider(
                        title = stringResource(R.string.creativity),
                        startLabel = stringResource(R.string.strict),
                        endLabel = stringResource(R.string.playful),
                        value = temperature / 2f,
                        onValueChange = { temperature = it * 2f },
                        valueRange = 0f..1f
                    )
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { value -> maxTokens = value.filter { it.isDigit() } },
                        label = { Text(stringResource(R.string.max_tokens)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = flowTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Button(
                onClick = {
                    onSave(assistantName, assistantAvatarPath, showAvatars, prompt, temperature.toDouble(), topP.toDouble(), maxTokens.toIntOrNull())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun AssistantAvatarStrip(
    assistantAvatarPath: String?,
    onAvatarClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(66.dp), contentAlignment = Alignment.Center) {
            AssistantAvatar(
                avatarPath = assistantAvatarPath,
                size = 62.dp
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), CircleShape)
            )
            AvatarEditButton(
                onClick = onAvatarClick,
                contentDescription = stringResource(R.string.assistant_avatar),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp)
            )
        }
    }
}

@Composable
private fun SettingsLabeledSlider(
    title: String,
    startLabel: String,
    endLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = startLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = endLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AssistantProfileEditor(
    assistantName: String,
    assistantAvatarPath: String?,
    onAssistantNameChange: (String) -> Unit,
    onAvatarClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            AssistantAvatar(
                avatarPath = assistantAvatarPath,
                size = 96.dp
            )
            AvatarEditButton(
                onClick = onAvatarClick,
                contentDescription = stringResource(R.string.assistant_avatar),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-4).dp, y = (-4).dp)
            )
        }
        OutlinedTextField(
            value = assistantName,
            onValueChange = onAssistantNameChange,
            label = { Text(stringResource(R.string.assistant_name)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = flowTextFieldColors(),
            singleLine = true
        )
    }
}

@Composable
private fun AvatarEditButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(0xFF3A3A3A), CircleShape)
            .border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.35f)), CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val pencilColor = Color.White
        Canvas(modifier = Modifier.size(17.dp)) {
            val strokeWidth = 2.1.dp.toPx()
            val upperBodyStart = Offset(size.width * 0.29f, size.height * 0.68f)
            val upperBodyEnd = Offset(size.width * 0.64f, size.height * 0.33f)
            val lowerBodyStart = Offset(size.width * 0.40f, size.height * 0.79f)
            val lowerBodyEnd = Offset(size.width * 0.75f, size.height * 0.44f)
            val pencilTip = Offset(size.width * 0.23f, size.height * 0.85f)
            val tailStart = upperBodyEnd
            val tailEnd = lowerBodyEnd

            drawLine(
                color = pencilColor,
                start = upperBodyStart,
                end = upperBodyEnd,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = pencilColor,
                start = lowerBodyStart,
                end = lowerBodyEnd,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = pencilColor,
                start = tailStart,
                end = tailEnd,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = pencilColor,
                start = upperBodyStart,
                end = pencilTip,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = pencilColor,
                start = lowerBodyStart,
                end = pencilTip,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ProfileAvatar(
    avatarPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val image = remember(avatarPath) {
        avatarPath?.let { path ->
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun AssistantAvatar(
    avatarPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp
) {
    val image = remember(avatarPath) {
        avatarPath?.let { path ->
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "AI",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class UserProfile(
    val name: String,
    val avatarPath: String?
)

private fun loadUserProfile(context: Context, defaultName: String): UserProfile {
    val prefs = context.getSharedPreferences(UserProfilePrefsName, Context.MODE_PRIVATE)
    return UserProfile(
        name = prefs.getString(UserProfileNameKey, null)?.takeIf { it.isNotBlank() } ?: defaultName,
        avatarPath = prefs.getString(UserProfileAvatarKey, null)?.takeIf { it.isNotBlank() }
    )
}

private fun saveUserProfile(context: Context, profileName: String, profileAvatarPath: String?) {
    context.getSharedPreferences(UserProfilePrefsName, Context.MODE_PRIVATE)
        .edit()
        .putString(UserProfileNameKey, profileName.trim())
        .putString(UserProfileAvatarKey, profileAvatarPath.orEmpty())
        .apply()
}

private fun copyUserAvatarToPrivateFile(context: Context, uri: Uri): String? {
    return runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = bitmap.scaleToMax(512)
        val directory = File(context.filesDir, "user_profile").apply { mkdirs() }
        val output = File(directory, "avatar.jpg")

        try {
            FileOutputStream(output).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
        } finally {
            if (scaled != bitmap) {
                scaled.recycle()
            }
            bitmap.recycle()
        }
        output.absolutePath
    }.getOrNull()
}

private fun copyAssistantAvatarToPrivateFile(context: Context, conversationId: String, uri: Uri): String? {
    return runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = bitmap.scaleToMax(512)
        val directory = File(context.filesDir, "assistant_avatars").apply { mkdirs() }
        val output = File(directory, "$conversationId.jpg")

        try {
            FileOutputStream(output).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
        } finally {
            if (scaled != bitmap) {
                scaled.recycle()
            }
            bitmap.recycle()
        }
        output.absolutePath
    }.getOrNull()
}

private fun Bitmap.scaleToMax(maxSize: Int): Bitmap {
    val largestSide = maxOf(width, height)
    if (largestSide <= maxSize) return this
    val ratio = maxSize.toFloat() / largestSide.toFloat()
    val scaledWidth = (width * ratio).toInt().coerceAtLeast(1)
    val scaledHeight = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

private const val UserProfilePrefsName = "user_profile"
private const val UserProfileNameKey = "name"
private const val UserProfileAvatarKey = "avatar_path"

@Composable
private fun Conversation.displayTitle(): String {
    return if (title == "New chat") stringResource(R.string.default_conversation_title) else title
}

@Composable
private fun Conversation.displayAssistantTitle(): String {
    return assistantName.ifBlank { displayTitle() }
}
