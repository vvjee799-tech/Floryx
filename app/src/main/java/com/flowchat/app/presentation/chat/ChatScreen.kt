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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.util.lerp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Link
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.toggleable
import com.flowchat.app.presentation.common.FlowSettingsPage
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.flowchat.app.domain.provider.conversationModelNames
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.domain.device.DeviceSafetyState
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
    onOpenAppSettings: () -> Unit,
    onOpenConversationSettings: () -> Unit,
    appAppearance: AppAppearance,
    onAppAppearanceChange: (AppAppearance) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    onOpenWorkspace: () -> Unit = {},
    onOpenTerminal: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val localizedError = state.errorMessage?.let { localizedChatError(it) }
    LaunchedEffect(localizedError, state.isStreaming, state.messages.lastOrNull()?.id) {
        val lastMessage = state.messages.lastOrNull()
        val alreadyShownInReply = lastMessage?.role == MessageRole.Assistant && lastMessage.status == MessageStatus.Failed
        if (!state.isStreaming && !localizedError.isNullOrBlank() && !alreadyShownInReply) {
            snackbarHostState.showSnackbar(localizedError)
        }
    }
    val providerModelOptions by viewModel.providerModelOptions.collectAsStateWithLifecycle()
    val usageRecords by viewModel.usageRecords.collectAsStateWithLifecycle()
    val balanceState by viewModel.balanceState.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val workspaceState by viewModel.workspaceState.collectAsStateWithLifecycle()
    val openDrawerDescription = stringResource(R.string.open_navigation_drawer)
    val newChatDescription = stringResource(R.string.new_chat)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val taskNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // A notification refusal does not refuse the user's task.
        viewModel.send()
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
    var exportOwner by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val owner = exportOwner
        exportOwner = null
        if (uri != null && owner != null) scope.launch {
            runCatching {
                val text = viewModel.exportConversation(owner)
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) }
                }
            }.onSuccess {
                android.widget.Toast.makeText(context, R.string.conversation_exported, android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                viewModel.reportError(context.getString(R.string.conversation_export_failed))
            }
        }
    }
    val userProfile = rememberUserProfile()
    val userName = userProfile.name
    val userAvatarPath = userProfile.avatarPath
    var conversationPendingDelete by remember { mutableStateOf<Conversation?>(null) }
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val imeInsets = WindowInsets.ime
    val drawerButtonInComposer by remember(density, screenHeightDp, imeInsets) {
        derivedStateOf {
            val keyboardHeight = with(density) { imeInsets.getBottom(this).toDp() }
            keyboardHeight > 0.dp && (screenHeightDp - keyboardHeight) < 300.dp
        }
    }
    val drawerActive by remember(drawerState) {
        derivedStateOf {
            drawerState.currentValue == DrawerValue.Open ||
                drawerState.targetValue == DrawerValue.Open ||
                drawerState.isAnimationRunning
        }
    }
    val loadOlderMessages = remember(viewModel) { viewModel::loadOlderMessages }
    val drawerButton: @Composable () -> Unit = {
        IconButton(
            onClick = { scope.launch { drawerState.open() } },
            modifier = Modifier.semantics { contentDescription = openDrawerDescription }
        ) {
            DrawerMenuIcon(color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(minOf(320.dp, LocalConfiguration.current.screenWidthDp.dp - 40.dp)),
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerContentColor = MaterialTheme.colorScheme.onSurface
            ) {
                var drawerSearchOpen by rememberSaveable { mutableStateOf(false) }
                var drawerSearchQuery by rememberSaveable { mutableStateOf("") }
                LaunchedEffect(drawerState.isClosed) {
                    if (drawerState.isClosed && (drawerSearchOpen || drawerSearchQuery.isNotEmpty())) {
                        drawerSearchOpen = false
                        drawerSearchQuery = ""
                    }
                }
                val drawerContentModifier = if (drawerActive) {
                    Modifier.fillMaxSize().navigationBarsPadding().imePadding()
                } else {
                    Modifier.fillMaxSize().navigationBarsPadding()
                }
                val activeTerminalLabel = when {
                    terminalState.linux.ready -> stringResource(R.string.terminal_linux)
                    state.appSettings.powerModeEnabled && state.shizukuState.isConnected -> stringResource(R.string.terminal_android)
                    else -> null
                }
                val activeModelLabel = state.currentConversation?.modelName?.takeIf { it.isNotBlank() }
                BoxWithConstraints(drawerContentModifier) {
                    val compactDrawerHeight = maxHeight < 480.dp
                    ConversationHistory(
                        conversations = state.conversations,
                        currentId = state.currentConversation?.id,
                        onSelect = { id -> viewModel.selectConversation(id); scope.launch { drawerState.close() } },
                        onRename = viewModel::renameConversation, onPin = viewModel::pinConversation,
                        onArchive = viewModel::archiveConversation,
                        onExport = { conversation ->
                            exportOwner = conversation.id
                            exportPicker.launch(ConversationExporter.fileName(conversation.title))
                        },
                        onDelete = { conversationPendingDelete = it },
                        modifier = Modifier.fillMaxSize(),
                        scrollHeaderAndFooter = compactDrawerHeight,
                        header = {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FlowWordmark(Modifier.weight(1f))
                                Surface(
                                    onClick = {
                                        if (drawerSearchOpen) {
                                            drawerSearchOpen = false
                                            drawerSearchQuery = ""
                                        } else {
                                            drawerSearchOpen = true
                                        }
                                    },
                                    shape = CircleShape,
                                    color = if (drawerSearchOpen) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.size(36.dp).testTag("history-search-toggle")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (drawerSearchOpen) Icons.Default.Close else Icons.Outlined.Search,
                                            contentDescription = stringResource(if (drawerSearchOpen) R.string.clear_search else R.string.search_conversations),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            if (drawerSearchOpen) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            if (drawerSearchQuery.isEmpty()) {
                                                Text(
                                                    stringResource(R.string.search_conversations_hint),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            BasicTextField(
                                                value = drawerSearchQuery,
                                                onValueChange = { drawerSearchQuery = it },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                modifier = Modifier.fillMaxWidth().testTag("history-search")
                                            )
                                        }
                                        if (drawerSearchQuery.isNotEmpty()) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.clear_search),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp).clickable { drawerSearchQuery = "" }
                                            )
                                        }
                                    }
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                                DrawerNavRow(
                                    icon = Icons.Outlined.Folder,
                                    label = stringResource(R.string.workspace_title),
                                    trailing = workspaceState.location?.name,
                                    testTag = "drawer-nav-workspace",
                                    onClick = { scope.launch { drawerState.close(); onOpenWorkspace() } }
                                )
                                DrawerNavRow(
                                    icon = Icons.Outlined.Terminal,
                                    label = stringResource(R.string.drawer_nav_terminal),
                                    trailing = activeTerminalLabel,
                                    testTag = "drawer-nav-terminal",
                                    onClick = { scope.launch { drawerState.close(); onOpenTerminal() } }
                                )
                                DrawerNavRow(
                                    icon = Icons.Outlined.Memory,
                                    label = stringResource(R.string.model),
                                    trailing = activeModelLabel,
                                    testTag = "drawer-nav-models",
                                    onClick = { scope.launch { drawerState.close(); onOpenProviders() } }
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        },
                        footer = {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UserProfileEntry(userName, userAvatarPath, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = { scope.launch { drawerState.close(); onOpenAppSettings() } },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Settings,
                                            stringResource(R.string.settings),
                                            Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Surface(
                                    onClick = {
                                        viewModel.newConversation()
                                        scope.launch { drawerState.close() }
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.inverseSurface,
                                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                    shadowElevation = 2.dp,
                                    modifier = Modifier.heightIn(min = 40.dp).testTag("drawer-new-chat")
                                        .semantics { contentDescription = newChatDescription }
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Text(
                                            stringResource(R.string.drawer_new_chat_short),
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        },
                        searchQuery = drawerSearchQuery,
                        overlayFooter = !compactDrawerHeight
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (!drawerButtonInComposer) {
                    TopAppBar(
                        expandedHeight = 56.dp,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        title = {
                            ConversationHeaderTitle(
                                assistantAvatarPath = state.currentConversation?.assistantAvatarPath,
                                title = state.currentConversation?.assistantName?.takeIf { it.isNotBlank() }.orEmpty()
                            )
                        },
                        navigationIcon = {
                            drawerButton()
                        },
                        actions = {
                            val confirmationOwner = state.awaitingConfirmationConversationId?.takeIf { it != state.currentConversation?.id }
                            if (confirmationOwner != null) {
                                TextButton(onClick = { viewModel.selectConversation(confirmationOwner) }, modifier = Modifier.testTag("open-task-confirmation")) {
                                    Text(stringResource(R.string.review_task_confirmation))
                                }
                            }
                            IconButton(
                                onClick = onOpenConversationSettings,
                                enabled = state.currentConversation != null,
                                modifier = Modifier.testTag("conversation-settings-button")
                            ) {
                                Icon(
                                    Icons.Outlined.MoreHoriz,
                                    contentDescription = stringResource(R.string.conversation_settings),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    )
                }
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
                    onSend = {
                        val preferences = context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("requested", false)) {
                            preferences.edit().putBoolean("requested", true).apply()
                            taskNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else viewModel.send()
                    },
                    onStop = viewModel::stop,
                    onThinkingEnabledChange = viewModel::setThinkingEnabled,
                    onThinkingEffortChange = viewModel::setThinkingEffort,
                    executionPermissionMode = state.appSettings.executionPermissionMode,
                    onExecutionPermissionModeChange = viewModel::setExecutionPermissionMode,
                    contextWindow = state.contextWindow,
                    onPickAttachment = {
                        attachmentPicker.launch(AttachmentDocumentParser.PickerMimeTypes)
                    },
                    onClearAttachment = viewModel::clearAttachment,
                    usageRecords = usageRecords.takeIf { state.appSettings.usageTrackingEnabled },
                    balanceState = balanceState, onRefreshBalance = viewModel::refreshBalance,
                    onSelectModel = viewModel::updateConversationModel,
                    providerModelOptions = providerModelOptions,
                    onOpenModelOptions = viewModel::loadModelOptions,
                    navigationButton = if (drawerButtonInComposer) drawerButton else null,
                    onCorrectCalendarTime = state.deviceTaskProgress?.takeIf { it.canCorrectCalendarTime }?.let { task ->
                        { viewModel.correctCalendarTime(task.runId) }
                    }
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
                if (state.messages.isEmpty() && !state.isStreaming) {
                    ChatWelcome(enabled = state.currentConversation != null,
                        modifier = Modifier.weight(1f),
                        onStart = {
                            if (state.providers.any { it.baseUrl.isNotBlank() && it.defaultModel.isNotBlank() }) viewModel.newConversation()
                            else onOpenProviders()
                        },
                        startLabel = stringResource(if (state.providers.any { it.baseUrl.isNotBlank() && it.defaultModel.isNotBlank() })
                            R.string.new_chat else R.string.provider_configuration),
                        appAppearance = appAppearance)
                } else MessageList(messages = state.messages, assistantAvatarPath = state.currentConversation?.assistantAvatarPath, userAvatarPath = userAvatarPath, showAvatars = state.currentConversation?.showAvatars == true, modifier = Modifier.weight(1f), hasOlderMessages = state.hasOlderMessages, onLoadOlder = loadOlderMessages,
                    statusKey = null, statusContent = null)
            }
        }
    }

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
internal fun localizedChatError(message: String): String =
    when (message) {
        "Create a provider first." -> stringResource(R.string.create_provider_first)
        "Conversation not found." -> stringResource(R.string.conversation_not_found)
        "Shared text destination changed. Remove the attachment and share again." -> stringResource(R.string.share_text_send_changed)
        "Provider not found." -> stringResource(R.string.provider_not_found)
        "Provider configuration is invalid." -> stringResource(R.string.provider_configuration_invalid)
        "Provider returned an empty response." -> stringResource(R.string.empty_response_message)
        "Image attachment unavailable. Please attach the image again." -> stringResource(R.string.image_reattach)
        "Tool call limit reached." -> stringResource(R.string.tool_call_limit_reached)
        "Device automation stopped: no progress." -> stringResource(R.string.device_automation_no_progress)
        "Phone execution settings changed. Start or resume the task to observe the selected target again." -> stringResource(R.string.device_target_changed)
        "Built-in web search is not configured." -> stringResource(R.string.web_search_not_configured)
        "Built-in web search is temporarily unavailable." -> stringResource(R.string.web_search_unavailable)
        else -> message
    }

@Composable
internal fun ConversationHeaderTitle(
    assistantAvatarPath: String?, title: String, modifier: Modifier = Modifier
) {
    if (assistantAvatarPath == null && title.isBlank()) return
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (assistantAvatarPath != null) AssistantAvatar(avatarPath = assistantAvatarPath, size = 30.dp)
        if (title.isNotBlank()) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    trailing: String?,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 38.dp)
            .testTag(testTag)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 112.dp)
            )
        }
    }
}

@Composable
internal fun UserProfileEntry(
    userName: String,
    avatarPath: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .testTag("drawer_profile")
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProfileAvatar(
            avatarPath = avatarPath,
            size = 32.dp
        )
        Text(
            text = userName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsScreen(
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
    onProfileNameChange: (String) -> Unit = {},
    onProfileAvatarChange: (String) -> Unit = {},
    onUsageTrackingChange: (Boolean) -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    workspaceName: String? = null,
    onBackgroundExecutionChange: (Boolean) -> Unit = {},
    onPhotoAnswerChange: (Boolean) -> Unit = {},
    onFloatingBallChange: (Boolean) -> Unit = {},
    deviceSafetyState: DeviceSafetyState = DeviceSafetyState(),
    modelSupportsImages: Boolean? = null
) {
    val context = LocalContext.current
    val currentLanguage = AppLocale.getLanguage(context)
    val settingsScroll = rememberScrollState()
    var showAppearanceDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showDeviceAssistantScreen by rememberSaveable { mutableStateOf(false) }
    var showMemoryDialog by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var avatarLoading by remember { mutableStateOf(false) }
    var avatarFailed by remember { mutableStateOf(false) }
    val profileScope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) profileScope.launch {
            avatarLoading = true
            avatarFailed = false
            val path = withContext(Dispatchers.IO) { copyUserAvatarToPrivateFile(context, uri) }
            if (path != null) onProfileAvatarChange(path) else avatarFailed = true
            avatarLoading = false
        }
    }
    if (showAppearanceDialog || showLanguageDialog) {
        FlowSettingsPage(title = stringResource(R.string.appearance_and_language),
            onBack = { showAppearanceDialog = false; showLanguageDialog = false }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = FlowLayout.Gutter, vertical = 16.dp)) {
                FlowSectionLabel(stringResource(R.string.appearance))
                AppAppearance.entries.forEach { option ->
                    SettingsSelectionOptionRow(stringResource(option.labelRes()), option == appAppearance) { onAppAppearanceChange(option) }
                }
                Spacer(Modifier.height(24.dp))
                FlowSectionLabel(stringResource(R.string.language))
                AppLanguage.entries.forEach { option ->
                    SettingsSelectionOptionRow(stringResource(option.labelRes()), option == currentLanguage) {
                        AppLocale.setLanguage(context, option)
                        context.findActivity()?.recreate()
                    }
                }
            }
        }
        return
    }
    if (showDeviceAssistantScreen) {
        DeviceAssistantScreen(appSettings, shizukuState, accessibilityState, onOpenAccessibilitySettings,
            onPowerModeEnabledChange, onPowerConnectionModeChange, onRequestShizukuPermission, onRefreshShizuku,
            onOpenShizuku = {
                runCatching { context.startActivity(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
            }, onDismiss = { showDeviceAssistantScreen = false }, onBackgroundExecutionChange = onBackgroundExecutionChange,
            deviceSafetyState = deviceSafetyState, modelSupportsImages = modelSupportsImages)
        return
    }
    if (showMemoryDialog) {
        MemorySettingsScreen(appSettings.memoryEnabled, memories, onMemoryEnabledChange,
            onUpdateMemory, onDeleteMemory, onClearMemories, onDismiss = { showMemoryDialog = false })
        return
    }
    FlowSettingsPage(title = stringResource(R.string.settings), onBack = onDismiss) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(settingsScroll)
                .padding(horizontal = 24.dp, vertical = 16.dp).testTag("app_settings_content"),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EditableAvatar(userAvatarPath, assistant = false, enabled = !avatarLoading,
                    onClick = { avatarPicker.launch("image/*") }, tag = "user")
                Column(Modifier.weight(1f)) {
                    Text(userName, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit_profile_name)) { editingName = true }
                            .padding(vertical = 10.dp).testTag("profile_name"))
                }
                IconButton(onClick = { editingName = true }) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.edit_profile_name), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (avatarLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (avatarFailed) Text(stringResource(R.string.avatar_update_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsGroup(title = stringResource(R.string.settings_connection)) {
                SettingsRow(title = stringResource(R.string.model_provider_configuration),
                    trailingText = providerName, onClick = onOpenProviders)
                SettingsRow(title = stringResource(R.string.memory),
                    trailingText = stringResource(if (appSettings.memoryEnabled) R.string.thinking_on else R.string.thinking_off),
                    onClick = { showMemoryDialog = true })
                SettingsRow(title = stringResource(R.string.device_assistant),
                    trailingText = deviceReadinessLabel(deviceReadiness(appSettings.powerModeEnabled, shizukuState, accessibilityState, deviceSafetyState)),
                    onClick = { onRefreshShizuku(); showDeviceAssistantScreen = true })
                SettingsRow(title = stringResource(R.string.terminal_title), onClick = onOpenTerminal)
                SettingsRow(title = stringResource(R.string.workspace_title), trailingText = workspaceName ?: stringResource(R.string.workspace_none),
                    onClick = onOpenWorkspace)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowToggleRow(stringResource(R.string.floating_ball_title), null, appSettings.floatingBallEnabled,
                onFloatingBallChange, Modifier.testTag("floating_ball"))
            FlowToggleRow(stringResource(R.string.photo_answer_title), null, appSettings.photoAnswerEnabled,
                onPhotoAnswerChange, Modifier.testTag("photo_answer_enabled"))
            FlowToggleRow(stringResource(R.string.usage_enable), null, appSettings.usageTrackingEnabled,
                onUsageTrackingChange, Modifier.testTag("usage_tracking"))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsGroup(title = stringResource(R.string.settings_preferences)) {
                SettingsRow(title = stringResource(R.string.appearance_and_language),
                    trailingText = "${stringResource(appAppearance.labelRes())} · ${stringResource(currentLanguage.labelRes())}",
                    onClick = { showAppearanceDialog = true })
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    if (editingName) NameEditorDialog(
        title = stringResource(R.string.profile_name), initialName = userName,
        onDismiss = { editingName = false }, onSave = { onProfileNameChange(it); editingName = false }
    )
}

@Composable
internal fun DeviceAssistantScreen(
    appSettings: AppSettings,
    shizukuState: ShizukuConnectionState,
    accessibilityState: AccessibilityConnectionState,
    onOpenAccessibilitySettings: () -> Unit,
    onPowerModeEnabledChange: (Boolean) -> Unit,
    onPowerConnectionModeChange: (PowerConnectionMode) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onDismiss: () -> Unit,
    onBackgroundExecutionChange: (Boolean) -> Unit = {},
    deviceSafetyState: DeviceSafetyState = DeviceSafetyState(),
    modelSupportsImages: Boolean? = null
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
    val rootMode = appSettings.powerConnectionMode == PowerConnectionMode.Root
    val connectionModes = listOf(PowerConnectionMode.Root, PowerConnectionMode.Shizuku)
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
    var systemAssistant by rememberSaveable { mutableStateOf(false) }
    if (systemAssistant) {
        FlowSettingsPage(title = stringResource(R.string.assistant_system_entry), onBack = { systemAssistant = false }) {
            Column(Modifier.padding(horizontal = FlowLayout.Gutter, vertical = 12.dp)) { SystemAssistantSettings() }
        }
        return
    }
    FlowSettingsPage(
        title = stringResource(R.string.device_assistant),
        onBack = onDismiss,
        actions = {
            IconButton(onClick = onRefreshShizuku, modifier = Modifier.testTag("device_refresh")) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.device_refresh_status), Modifier.size(20.dp))
            }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
            FlowToggleRow(stringResource(R.string.device_assistant), null, appSettings.powerModeEnabled, onPowerModeEnabledChange)
            Column(Modifier.graphicsLayer { alpha = if (appSettings.powerModeEnabled) 1f else 0.45f }) {
                Spacer(Modifier.height(12.dp))
                FlowBinaryChoice(
                    options = connectionModes.map { mode ->
                        if (mode == PowerConnectionMode.Root) {
                            FlowBinaryOption(
                                label = "Root",
                                icon = Icons.Outlined.Terminal,
                                sageTone = false,
                                testTag = "power-mode-root"
                            )
                        } else {
                            FlowBinaryOption(
                                label = "Shizuku",
                                icon = Icons.Outlined.Link,
                                sageTone = true,
                                testTag = "power-mode-shizuku"
                            )
                        }
                    },
                    selected = connectionModes.indexOf(appSettings.powerConnectionMode).coerceAtLeast(0),
                    onSelect = { onPowerConnectionModeChange(connectionModes[it]) }
                )
                Spacer(Modifier.height(8.dp))
                FlowStatusRow(
                    title = if (rootMode) "Root" else "Shizuku",
                    status = shizukuStatusLabel(shizukuState.status),
                    ready = shizukuState.serviceBound,
                    onClick = if (!shizukuState.isConnected && shizukuState.status != ShizukuConnectionStatus.Connecting) statusAction else null
                )
                if (!shizukuState.isConnected && shizukuState.status == ShizukuConnectionStatus.Error) {
                    Text(
                        if (rootMode) stringResource(R.string.root_error_description) else shizukuStatusDescription(shizukuState.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowStatusRow(stringResource(R.string.accessibility_screen_control), stringResource(when (accessibilityState.status) {
                    AccessibilityConnectionStatus.Connected -> R.string.accessibility_connected
                    AccessibilityConnectionStatus.Disabled -> R.string.accessibility_disabled
                    AccessibilityConnectionStatus.Unavailable -> R.string.accessibility_unavailable
                    AccessibilityConnectionStatus.Error -> R.string.accessibility_error
                }), ready = accessibilityState.isConnected, onClick = onOpenAccessibilitySettings)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowToggleRow(stringResource(R.string.background_execution_title), null, appSettings.backgroundExecutionEnabled,
                    onBackgroundExecutionChange, enabled = appSettings.powerModeEnabled && rootMode)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(stringResource(R.string.assistant_system_entry), onClick = { systemAssistant = true })
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
    DeviceReadiness.Paused -> R.string.device_readiness_paused
    DeviceReadiness.Unverified -> R.string.device_readiness_unverified
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
        ShizukuConnectionStatus.RootReady -> R.string.root_ready
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
        ShizukuConnectionStatus.RootReady -> R.string.root_ready_description
        ShizukuConnectionStatus.Unsupported -> R.string.shizuku_unsupported_description
        ShizukuConnectionStatus.Error -> R.string.shizuku_error_description
    }
)

@Composable
private fun SettingsToggleRow(title: String, supportingText: String, checked: Boolean,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    FlowToggleRow(title, null, checked, onCheckedChange, enabled = enabled)
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
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        RadioButton(selected = selected, onClick = null)
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
    "stale_screen", "stale_visual_target", "ui_draft_changed", "ui_editor_action_changed" -> stringResource(R.string.device_error_stale)
    "invalid_visual_target" -> stringResource(R.string.device_error_visual_target)
    "use_device_ui_tools" -> stringResource(R.string.device_error_use_ui_tools)
    "visual_target_obscured" -> stringResource(R.string.device_error_visual_obscured)
    "visual_geometry_unavailable", "visual_verification_failed" -> stringResource(R.string.screen_vision_unavailable)
    "ui_action_failed" -> stringResource(R.string.device_error_action)
    "ui_text_too_long" -> stringResource(R.string.device_error_text_limit)
    "ui_input_unsupported", "ui_input_text_unreadable", "ui_input_target_invalid", "ui_selection_unsupported", "ui_editor_action_unsupported", "ui_scroll_unsupported", "ui_range_unsupported", "ui_gesture_display_unsupported" -> stringResource(R.string.device_error_control_unsupported)
    "ui_input_focus_required" -> stringResource(R.string.device_error_input_focus)
    "ui_selection_invalid", "ui_selection_unknown", "ui_cursor_not_collapsed", "ui_selection_empty" -> stringResource(R.string.device_error_selection)
    "ui_input_unverified", "ui_selection_unverified", "ui_scroll_unverified", "ui_range_unverified" -> stringResource(R.string.device_error_result_unverified)
    "ui_gesture_path_blocked", "ui_gesture_context_changed" -> stringResource(R.string.device_error_gesture_path)
    "ui_touch_release_unverified" -> stringResource(R.string.device_error_touch_release)
    "ui_no_change" -> stringResource(R.string.device_error_no_change)
    "screen_unavailable" -> stringResource(R.string.device_error_screen)
    "accessibility_unavailable" -> stringResource(R.string.accessibility_disabled_description)
    "sensitive_screen_blocked" -> stringResource(R.string.device_error_sensitive)
    "self_automation_blocked" -> stringResource(R.string.device_error_self)
    "task_effect_unverified" -> stringResource(R.string.tool_error_task_effect_unverified)
    else -> detail
}

@Composable
private fun localizedToolName(toolName: String): String {
    val baseName = toolName.substringBefore(':')
    val detail = toolName.substringAfter(':', missingDelimiterValue = "").trim()
    return when (baseName) {
        TerminalToolDefinitions.Run -> stringResource(R.string.terminal_run)
        TerminalToolDefinitions.Status -> stringResource(R.string.tool_name_terminal_status)
        TerminalToolDefinitions.Read -> stringResource(R.string.tool_name_read_command_output)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Status -> stringResource(R.string.workspace_tool_status)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.ListFiles -> stringResource(R.string.workspace_tool_list)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Read -> stringResource(R.string.workspace_tool_read)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Stat -> stringResource(R.string.workspace_tool_stat)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Search -> stringResource(R.string.workspace_tool_search)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Write -> stringResource(R.string.workspace_tool_write)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Rename -> stringResource(R.string.workspace_tool_rename)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Import -> stringResource(R.string.workspace_tool_import)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Publish -> stringResource(R.string.workspace_tool_publish)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.InspectExecution -> stringResource(R.string.workspace_tool_inspect_execution)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.AnswerCompatibility -> stringResource(R.string.workspace_tool_answer_compatibility)
        com.flowchat.app.domain.chat.TaskContextTools.Read -> stringResource(R.string.tool_name_read_task_history)
        com.flowchat.app.domain.chat.AttachmentTools.Read -> stringResource(R.string.tool_name_read_attachment)
        com.flowchat.app.domain.device.CalendarEventSave.ToolName -> stringResource(R.string.tool_name_save_calendar_event)
        com.flowchat.app.domain.device.CalendarTimeEdit.ToolName -> stringResource(R.string.tool_name_edit_calendar_time)
        com.flowchat.app.domain.chat.TaskEffectRegistry.VerifyToolName -> stringResource(R.string.tool_name_verify_task_effect)
        "web_search" -> stringResource(R.string.web_search_short)
        "get_app_usage_summary" -> stringResource(R.string.app_usage_tool)
        "get_recent_app_activity" -> stringResource(R.string.recent_app_activity_tool)
        "open_phone_dialer" -> stringResource(R.string.tool_name_open_phone_dialer)
        "open_calendar_editor" -> stringResource(R.string.tool_name_open_calendar_editor)
        "open_app" -> if (detail.isBlank()) {
            stringResource(R.string.open_app_tool)
        } else {
            stringResource(R.string.open_app_named, detail)
        }
        "get_device_status" -> stringResource(R.string.capability_device_status)
        "get_device_time" -> stringResource(R.string.tool_name_device_time)
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
        com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiTargetToolName -> stringResource(R.string.tool_name_tap_ui_target)
        "tap_visual_target" -> stringResource(R.string.tool_name_tap_visual_target)
        "input_text" -> stringResource(R.string.tool_name_input_text)
        "paste_focused_text" -> stringResource(R.string.tool_name_paste_focused_text)
        "set_text_selection" -> stringResource(R.string.tool_name_set_text_selection)
        "perform_editor_action" -> stringResource(R.string.tool_name_perform_editor_action)
        "scroll_container" -> stringResource(R.string.tool_name_scroll_container)
        "drag_ui_element" -> stringResource(R.string.tool_name_drag_ui_element)
        "double_tap_ui_element" -> stringResource(R.string.tool_name_double_tap_ui_element)
        "adjust_range" -> stringResource(R.string.tool_name_adjust_range)
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
        TerminalToolDefinitions.Run -> stringResource(R.string.tool_action_run_command)
        TerminalToolDefinitions.Status -> stringResource(R.string.tool_action_terminal_status)
        TerminalToolDefinitions.Read -> stringResource(R.string.tool_action_read_command_output)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Status -> stringResource(R.string.tool_action_workspace_status)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.ListFiles -> stringResource(R.string.tool_action_workspace_list)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Read -> stringResource(R.string.tool_action_workspace_read)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Stat -> stringResource(R.string.tool_action_workspace_stat)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Search -> stringResource(R.string.tool_action_workspace_search)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Write -> stringResource(R.string.tool_action_workspace_write)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Rename -> stringResource(R.string.tool_action_workspace_rename)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Import -> stringResource(R.string.tool_action_workspace_import)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.Publish -> stringResource(R.string.tool_action_workspace_publish)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.InspectExecution -> stringResource(R.string.tool_action_workspace_inspect_execution)
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.AnswerCompatibility -> stringResource(R.string.tool_action_workspace_answer_compatibility)
        com.flowchat.app.domain.chat.TaskContextTools.Read -> stringResource(R.string.tool_action_read_task_history)
        com.flowchat.app.domain.chat.AttachmentTools.Read -> stringResource(R.string.tool_action_read_attachment)
        com.flowchat.app.domain.device.CalendarEventSave.ToolName -> stringResource(R.string.tool_action_save_calendar_event)
        com.flowchat.app.domain.device.CalendarTimeEdit.ToolName -> stringResource(R.string.tool_action_edit_calendar_time)
        com.flowchat.app.domain.chat.TaskEffectRegistry.VerifyToolName -> stringResource(R.string.tool_action_verify_task_effect)
        "web_search" -> stringResource(R.string.tool_action_web_search)
        "get_app_usage_summary" -> stringResource(R.string.tool_action_app_usage)
        "get_recent_app_activity" -> stringResource(R.string.tool_action_recent_activity)
        "open_phone_dialer" -> stringResource(R.string.tool_action_open_public_editor)
        "open_calendar_editor" -> stringResource(R.string.tool_action_open_calendar_editor)
        "open_app" -> if (detail.isBlank()) {
            stringResource(R.string.tool_action_open_app_generic)
        } else {
            stringResource(R.string.tool_action_open_app, detail)
        }
        "get_device_status" -> stringResource(R.string.tool_action_device_status)
        "get_device_time" -> stringResource(R.string.tool_action_device_time)
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
        com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiTargetToolName -> stringResource(R.string.tool_action_tap_ui_target)
        "tap_visual_target" -> stringResource(R.string.tool_action_tap_visual_target)
        "input_text" -> stringResource(R.string.tool_action_input_text)
        "paste_focused_text" -> stringResource(R.string.tool_action_paste_focused_text)
        "set_text_selection" -> stringResource(R.string.tool_action_set_text_selection)
        "perform_editor_action" -> stringResource(R.string.tool_action_perform_editor_action)
        "scroll_container" -> stringResource(R.string.tool_action_scroll_container)
        "drag_ui_element" -> stringResource(R.string.tool_action_drag_ui_element)
        "double_tap_ui_element" -> stringResource(R.string.tool_action_double_tap_ui_element)
        "adjust_range" -> stringResource(R.string.tool_action_adjust_range)
        "swipe_screen" -> stringResource(R.string.tool_action_swipe_screen)
        "press_back" -> stringResource(R.string.tool_action_press_back)
        "press_home" -> stringResource(R.string.tool_action_press_home)
        else -> stringResource(R.string.tool_action_generic, localizedToolName(toolName))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
                FlowGroup(content = content)
    }
}

@Composable
private fun SettingsRow(title: String, trailingText: String? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val stackValue = LocalDensity.current.fontScale > 1.3f
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (stackValue && trailingText != null) Text(trailingText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!stackValue && trailingText != null) Text(trailingText, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp))
        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun flowTextFieldColors() = flowFieldColors()

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MessageBubble(message: Message, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean, reasoning: Message?) {
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
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f) else Color.Transparent
    val bubbleContentColor = MaterialTheme.colorScheme.onBackground
    val bubbleShape = RoundedCornerShape(if (isUser) 20.dp else 0.dp)
    val messageRule = MaterialTheme.colorScheme.outlineVariant
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isCompactHeight = LocalConfiguration.current.screenHeightDp < 480
    val assistantMaxWidth = minOf(FlowLayout.ContentWidth - FlowLayout.Gutter * 2, screenWidth - FlowLayout.Gutter * 2)
    val bubbleMaxWidth = if (isUser) {
        minOf(480.dp, assistantMaxWidth * .9f - if (showAvatars) 46.dp else 0.dp)
    } else {
        assistantMaxWidth
    }
    val messageContent: @Composable () -> Unit = {
        Box(modifier = Modifier.widthIn(max = bubbleMaxWidth)) {
            val bubbleCard: @Composable () -> Unit = {
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
                        }.padding(horizontal = if (isUser) 14.dp else 0.dp, vertical = if (isCompactHeight) 4.dp else 10.dp)) {
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
                                if (!isUser && !isCommentary) MarkdownContent(displayContent)
                                else Text(
                                    text = displayContent,
                                    style = if (isCommentary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                                    color = if (isCommentary) MaterialTheme.colorScheme.onSurfaceVariant else bubbleContentColor
                                )
                            }
                        }
                    }
                }
            }
            val showAssistantActions = !isUser && !isCommentary && copyText.isNotBlank() && message.status != MessageStatus.Streaming
            val assistantActionButtons: @Composable () -> Unit = {
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
            Column(modifier = Modifier.widthIn(max = bubbleMaxWidth)) {
                if (!isUser && reasoning != null) {
                    ReasoningBubble(message = reasoning, maxWidth = bubbleMaxWidth)
                    Spacer(Modifier.size(6.dp))
                }
                if (showAssistantActions && isCompactHeight) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f, fill = false)) { bubbleCard() }
                        Spacer(Modifier.width(4.dp))
                        assistantActionButtons()
                    }
                } else {
                    bubbleCard()
                    if (showAssistantActions) assistantActionButtons()
                }
            }
            DropdownMenu(
                expanded = showMessageActions,
                onDismissRequest = { showMessageActions = false }
            ) {
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
internal fun ToolCallBubble(message: Message) {
    var commandExpanded by remember(message.id) { mutableStateOf(false) }
    val context = LocalContext.current
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
                        if (detail != null && commandExpanded) {
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

        }
    }
}

@Composable
internal fun ReasoningBubble(message: Message, maxWidth: Dp) {
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
    executionPermissionMode: com.flowchat.app.domain.device.ExecutionPermissionMode = com.flowchat.app.domain.device.ExecutionPermissionMode.RequestReview,
    onExecutionPermissionModeChange: (com.flowchat.app.domain.device.ExecutionPermissionMode) -> Unit = {},
    onPickAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onSelectModel: (ProviderConfig, String) -> Unit,
    usageRecords: List<com.flowchat.app.data.db.UsageRecord>? = null,
    balanceState: com.flowchat.app.data.usage.BalanceState = com.flowchat.app.data.usage.BalanceState(),
    onRefreshBalance: () -> Unit = {},
    contextWindow: com.flowchat.app.domain.model.ContextWindowUsage? = null,
    providerModelOptions: Map<String, ProviderModelOptions> = emptyMap(),
    onOpenModelOptions: () -> Unit = {},
    navigationButton: (@Composable () -> Unit)? = null,
    onCorrectCalendarTime: (() -> Unit)? = null,
    imeVisibleOverride: Boolean? = null
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val inputMethodManager = remember(view) {
        view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    var composerFocused by remember { mutableStateOf(false) }
    var modelSheetOpen by remember { mutableStateOf(false) }
    val hasInput = value.isNotBlank() || pendingAttachment != null
    val sendDescription = stringResource(if (isStreaming) R.string.stop else R.string.send)
    val modelOptions = remember(providers, providerModelOptions, currentConversation?.providerId, currentConversation?.modelName) {
        configuredModelOptions(providers, providerModelOptions, currentConversation)
    }
    val currentProvider = providers.firstOrNull { provider -> provider.id == currentConversation?.providerId }
    val thinkingSupport = remember(currentProvider, currentConversation?.modelName) {
        ThinkingSupport.resolve(currentProvider, currentConversation?.modelName.orEmpty())
    }
    val sendIconColor = when {
        isStreaming -> MaterialTheme.colorScheme.onPrimary
        hasInput -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val keyboardRequestPosted = remember { booleanArrayOf(false) }

    fun hasVisibleImeHeight(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.ime()) &&
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
    }

    fun showKeyboardForFocusedInput() {
        if (!enabled || keyboardRequestPosted[0]) return
        keyboardController?.show()
        if (hasVisibleImeHeight()) return
        keyboardRequestPosted[0] = true
        view.post {
            keyboardRequestPosted[0] = false
            if (composerFocused && !hasVisibleImeHeight()) {
                inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_FORCED)
            }
        }
    }

    fun requestFocusAndShowKeyboard() {
        if (!enabled) return
        val wasFocused = composerFocused
        composerFocused = true
        if (!wasFocused) {
            focusRequester.requestFocus()
        }
        showKeyboardForFocusedInput()
    }

    fun submitMessage() {
        if (!enabled || isAttachmentLoading || isStreaming) return
        if (value.isBlank() && pendingAttachment == null) return
        onSend()
        onValueChange("")
        focusManager.clearFocus()
        composerFocused = false
    }

    val isCompactHeight = LocalConfiguration.current.screenHeightDp < 480
    var showCompactOptions by remember { mutableStateOf(false) }
    LaunchedEffect(isCompactHeight) {
        if (!isCompactHeight) showCompactOptions = false
    }

    val attachmentPreview: @Composable () -> Unit = {
        if (isAttachmentLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
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
    }

    val composerInput: @Composable (Modifier) -> Unit = { fieldModifier ->
        key("composer-input") {
            BasicTextField(
                value = value,
                onValueChange = if (enabled) onValueChange else { _ -> },
                enabled = enabled,
                modifier = fieldModifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        composerFocused = focusState.isFocused
                        if (enabled && focusState.isFocused) showKeyboardForFocusedInput()
                    }
                    .pointerInput(enabled, keyboardController, view) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            requestFocusAndShowKeyboard()
                        }
                    },
                textStyle = (if (isCompactHeight) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
                    .copy(color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitMessage() }),
                maxLines = if (isCompactHeight) 2 else 5,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (isCompactHeight) 40.dp else 44.dp)
                            .padding(
                                horizontal = if (isCompactHeight) 6.dp else 6.dp,
                                vertical = if (isCompactHeight) 4.dp else 10.dp
                            ),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(when {
                                    !enabled -> R.string.create_conversation_to_chat
                                    onCorrectCalendarTime != null -> R.string.calendar_time_correction_hint
                                    else -> R.string.message_hint
                                }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }

    val attachmentButton: @Composable () -> Unit = {
        IconButton(onClick = onPickAttachment, enabled = enabled && !isStreaming && !isAttachmentLoading,
            modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Add, stringResource(R.string.upload_file), Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val calendarCorrectionButton: @Composable () -> Unit = {
        if (isStreaming && enabled && onCorrectCalendarTime != null) {
            IconButton(onClick = onCorrectCalendarTime,
                enabled = value.isNotBlank() && pendingAttachment == null && !isAttachmentLoading,
                modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Edit, stringResource(R.string.correct_calendar_time), Modifier.size(20.dp))
            }
        }
    }

    val sendStopButton: @Composable () -> Unit = {
        IconButton(onClick = if (isStreaming) onStop else ::submitMessage,
            enabled = (enabled && hasInput && !isAttachmentLoading) || isStreaming,
            modifier = Modifier.size(44.dp).semantics { contentDescription = sendDescription }) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(when {
                isStreaming -> MaterialTheme.colorScheme.primary
                enabled && hasInput -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }), contentAlignment = Alignment.Center) {
                Icon(if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward, null,
                    tint = sendIconColor, modifier = Modifier.size(19.dp))
            }
        }
    }

    val thinkingAndContextControls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThinkingModeControl(
                support = thinkingSupport,
                enabled = currentConversation?.enableThinking == true,
                effort = currentConversation?.thinkingEffort ?: ThinkingEffort.Auto,
                interactive = enabled && !isStreaming,
                onEnabledChange = onThinkingEnabledChange, onEffortChange = onThinkingEffortChange
            )
            key(currentConversation?.id) {
                ContextWindowIndicator(contextWindow, enabled = enabled)
            }
        }
    }

    val modelSwitch: @Composable (Boolean, Boolean) -> Unit = { compact, inline ->
        ModelSwitchButton(
            currentModelName = currentConversation?.modelName,
            enabled = enabled && !isStreaming && modelOptions.isNotEmpty(),
            compact = compact,
            inline = inline,
            thinkingSupport = thinkingSupport,
            thinkingEnabled = currentConversation?.enableThinking == true,
            thinkingEffort = currentConversation?.thinkingEffort ?: ThinkingEffort.Auto,
            sheetExpanded = modelSheetOpen,
            onClick = { modelSheetOpen = true; onOpenModelOptions() }
        )
    }

    val isImeVisible = imeVisibleOverride ?: WindowInsets.isImeVisible
    val showInlineControls = !isCompactHeight && (isImeVisible || modelSheetOpen)
    val expandProgressState = animateFloatAsState(
        targetValue = if (showInlineControls) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 700f,
            visibilityThreshold = 0.002f
        ),
        label = "composerExpand"
    )
    val showInBoxMeta by remember(showInlineControls) {
        derivedStateOf { showInlineControls || expandProgressState.value > 0.01f }
    }
    val metaSlideOffsetPx = with(LocalDensity.current) { 8.dp.toPx() }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
        .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = FlowLayout.ContentWidth)
        .padding(horizontal = 16.dp, vertical = if (isCompactHeight) 2.dp else 8.dp)) {
      Surface(
          modifier = Modifier.fillMaxWidth().testTag("message_composer_panel"),
          shape = RoundedCornerShape(if (isCompactHeight) 26.dp else 25.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(
              1.dp,
              if (composerFocused || modelSheetOpen) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
          ),
          shadowElevation = if (isCompactHeight) 0.dp else 2.dp,
          tonalElevation = 0.dp
      ) {
       if (isCompactHeight) {
           Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
               attachmentPreview()
               Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                   if (navigationButton != null) key("composer-nav") { navigationButton() }
                   attachmentButton()
                   IconButton(onClick = { showCompactOptions = true },
                       modifier = Modifier.size(44.dp).testTag("composer-options")) {
                       Icon(Icons.Outlined.Tune, stringResource(R.string.chat_options), Modifier.size(20.dp),
                           tint = MaterialTheme.colorScheme.onSurfaceVariant)
                   }
                   composerInput(Modifier.weight(1f))
                   calendarCorrectionButton()
                   sendStopButton()
               }
           }
       } else {
           Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
               attachmentPreview()
               Layout(
                   modifier = Modifier.fillMaxWidth(),
                   content = {
                       if (navigationButton != null) {
                           Box(Modifier.layoutId("nav")) { key("composer-nav") { navigationButton() } }
                       }
                       Box(Modifier.layoutId("attach")) { attachmentButton() }
                       Box(Modifier.layoutId("input")) { composerInput(Modifier.fillMaxWidth()) }
                       if (showInBoxMeta) {
                           Box(
                               Modifier
                                   .layoutId("context")
                                   .graphicsLayer {
                                       val progress = expandProgressState.value
                                       alpha = ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                                       translationY = metaSlideOffsetPx * (1f - progress)
                                   }
                           ) {
                               key(currentConversation?.id) {
                                   ContextWindowIndicator(contextWindow, enabled = enabled && showInlineControls)
                               }
                           }
                           Box(
                               Modifier
                                   .layoutId("model")
                                   .graphicsLayer {
                                       val progress = expandProgressState.value
                                       alpha = ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                                       translationY = metaSlideOffsetPx * (1f - progress)
                                   }
                           ) {
                               modelSwitch(false, true)
                           }
                       }
                       Box(Modifier.layoutId("calendar")) { calendarCorrectionButton() }
                       Box(Modifier.layoutId("send")) { sendStopButton() }
                   }
               ) { measurables, constraints ->
                   val expandProgress = expandProgressState.value
                   val maxWidth = constraints.maxWidth
                   val gapPx = 2.dp.roundToPx()
                   val expandedInputInsetPx = 10.dp.roundToPx().coerceAtMost(maxWidth / 4)
                   val expandedTopInsetPx = 2.dp.roundToPx()
                   val rowGapPx = 4.dp.roundToPx()
                   val minToolbarHeightPx = 44.dp.roundToPx()
                   val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

                   val navPlaceable = measurables.firstOrNull { it.layoutId == "nav" }?.measure(looseConstraints)
                   val attachPlaceable = measurables.firstOrNull { it.layoutId == "attach" }?.measure(looseConstraints)
                   val calendarPlaceable = measurables.firstOrNull { it.layoutId == "calendar" }
                       ?.measure(looseConstraints)?.takeIf { it.width > 0 }
                   val sendPlaceable = measurables.firstOrNull { it.layoutId == "send" }?.measure(looseConstraints)
                   val contextPlaceable = measurables.firstOrNull { it.layoutId == "context" }?.measure(looseConstraints)

                   val leftFixedWidth = (navPlaceable?.width ?: 0) +
                       (if (navPlaceable != null && attachPlaceable != null) gapPx else 0) +
                       (attachPlaceable?.width ?: 0)
                   val rightFixedWidth = (calendarPlaceable?.width ?: 0) +
                       (if (calendarPlaceable != null && sendPlaceable != null) gapPx else 0) +
                       (sendPlaceable?.width ?: 0)

                   val collapsedInputX = leftFixedWidth + (if (leftFixedWidth > 0) gapPx else 0)
                   val collapsedInputWidth = (maxWidth - collapsedInputX - rightFixedWidth -
                       (if (rightFixedWidth > 0) gapPx else 0)).coerceAtLeast(0)
                   val expandedInputWidth = (maxWidth - expandedInputInsetPx * 2).coerceAtLeast(0)
                   val currentInputWidth = lerp(collapsedInputWidth.toFloat(), expandedInputWidth.toFloat(), expandProgress)
                       .roundToInt().coerceAtLeast(0)
                   val inputPlaceable = measurables.first { it.layoutId == "input" }.measure(
                       constraints.copy(minWidth = currentInputWidth, maxWidth = currentInputWidth, minHeight = 0)
                   )

                   val contextWidth = contextPlaceable?.width ?: 0
                   val modelMaxWidth = (maxWidth - leftFixedWidth - contextWidth - rightFixedWidth - gapPx * 3)
                       .coerceAtLeast(0)
                   val modelPlaceable = measurables.firstOrNull { it.layoutId == "model" }?.measure(
                       constraints.copy(minWidth = 0, maxWidth = modelMaxWidth, minHeight = 0)
                   )

                   val baseToolbarHeight = listOfNotNull(
                       navPlaceable?.height,
                       attachPlaceable?.height,
                       calendarPlaceable?.height,
                       sendPlaceable?.height
                   ).maxOrNull()?.coerceAtLeast(minToolbarHeightPx) ?: minToolbarHeightPx
                   val expandedToolbarHeight = listOfNotNull(
                       navPlaceable?.height,
                       attachPlaceable?.height,
                       contextPlaceable?.height,
                       modelPlaceable?.height,
                       calendarPlaceable?.height,
                       sendPlaceable?.height
                   ).maxOrNull()?.coerceAtLeast(baseToolbarHeight) ?: baseToolbarHeight

                   val collapsedHeight = maxOf(inputPlaceable.height, baseToolbarHeight)
                   val expandedHeight = expandedTopInsetPx + inputPlaceable.height + rowGapPx + expandedToolbarHeight
                   val currentHeight = lerp(collapsedHeight.toFloat(), expandedHeight.toFloat(), expandProgress)
                       .roundToInt().coerceAtLeast(collapsedHeight)
                   val toolbarHeight = lerp(baseToolbarHeight.toFloat(), expandedToolbarHeight.toFloat(), expandProgress)
                       .roundToInt().coerceAtLeast(1)
                   val toolbarTop = currentHeight - toolbarHeight

                   layout(maxWidth, currentHeight) {
                       fun centerY(height: Int) = toolbarTop + ((toolbarHeight - height) / 2f).roundToInt()

                       val inputX = lerp(collapsedInputX.toFloat(), expandedInputInsetPx.toFloat(), expandProgress).roundToInt()
                       val collapsedInputY = ((collapsedHeight - inputPlaceable.height) / 2f).coerceAtLeast(0f)
                       val inputY = lerp(collapsedInputY, expandedTopInsetPx.toFloat(), expandProgress).roundToInt()
                       inputPlaceable.placeRelative(inputX, inputY)

                       var leftX = 0
                       navPlaceable?.let {
                           it.placeRelative(leftX, centerY(it.height))
                           leftX += it.width + gapPx
                       }
                       attachPlaceable?.let {
                           it.placeRelative(leftX, centerY(it.height))
                           leftX += it.width + gapPx
                       }
                       contextPlaceable?.let {
                           it.placeRelative(leftX, centerY(it.height))
                           leftX += it.width + gapPx
                       }

                       var rightX = maxWidth
                       sendPlaceable?.let {
                           rightX -= it.width
                           it.placeRelative(rightX, centerY(it.height))
                           rightX -= gapPx
                       }
                       calendarPlaceable?.let {
                           rightX -= it.width
                           it.placeRelative(rightX, centerY(it.height))
                           rightX -= gapPx
                       }
                       modelPlaceable?.let {
                           val modelX = (rightX - it.width).coerceAtLeast(leftX)
                           it.placeRelative(modelX, centerY(it.height))
                       }
                   }
               }
           }
       }
      }
    }
    if (isCompactHeight && showCompactOptions) {
        CompactComposerOptionsSheet(onDismissRequest = { showCompactOptions = false }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.chat_options), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { showCompactOptions = false },
                    modifier = Modifier.testTag("composer-options-close")
                ) {
                    Text(stringResource(R.string.close))
                }
            }
            thinkingAndContextControls()
            if (usageRecords != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UsageDisplay(usageRecords,
                    com.flowchat.app.data.usage.UsageTracker.supports(currentConversation?.modelName.orEmpty(), currentProvider),
                    Modifier.fillMaxWidth(), balanceState, onRefreshBalance)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            modelSwitch(false, false)
        }
    }
    if (modelSheetOpen) {
        ModelSwitchSheet(
            currentProvider = currentProvider,
            currentModelName = currentConversation?.modelName,
            modelOptions = modelOptions,
            enabled = enabled && !isStreaming && modelOptions.isNotEmpty(),
            onSelectModel = onSelectModel,
            providerModelOptions = providerModelOptions,
            onRetry = onOpenModelOptions,
            providers = providers,
            thinkingSupport = thinkingSupport,
            thinkingEnabled = currentConversation?.enableThinking == true,
            thinkingEffort = currentConversation?.thinkingEffort ?: ThinkingEffort.Auto,
            onThinkingEnabledChange = onThinkingEnabledChange,
            onThinkingEffortChange = onThinkingEffortChange,
            executionPermissionMode = executionPermissionMode,
            onExecutionPermissionModeChange = onExecutionPermissionModeChange,
            usageRecords = usageRecords,
            balanceState = balanceState,
            onRefreshBalance = onRefreshBalance,
            onDismissRequest = { modelSheetOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSwitchButton(
    currentModelName: String?,
    enabled: Boolean,
    compact: Boolean = false,
    inline: Boolean = false,
    thinkingSupport: ThinkingSupport = ThinkingSupport(com.flowchat.app.domain.provider.ThinkingStyle.Unsupported),
    thinkingEnabled: Boolean = false,
    thinkingEffort: ThinkingEffort = ThinkingEffort.Auto,
    sheetExpanded: Boolean = false,
    onClick: () -> Unit
) {
    val switchModelDescription = stringResource(R.string.switch_model)
    val effectiveThinking = thinkingSupport.isEnabled(thinkingEnabled)
    val thinkingSummary = when {
        !thinkingSupport.supported -> null
        !effectiveThinking -> stringResource(R.string.thinking_off)
        thinkingSupport.efforts.isEmpty() -> stringResource(R.string.thinking_on)
        else -> thinkingEffortLabel(thinkingSupport.normalize(thinkingEffort))
    }
    val labelStyle = if (inline) MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp) else MaterialTheme.typography.labelMedium
    Surface(
        shape = CircleShape,
        color = if (inline) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (inline) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (compact || inline) 0.dp else 2.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = if (inline) 40.dp else 34.dp)
                .clip(CircleShape)
                .testTag("model-switch")
                .semantics { contentDescription = switchModelDescription }
                .clickable(enabled = enabled, onClick = onClick)
                .padding(
                    start = if (inline) 4.dp else 10.dp,
                    end = if (inline) 4.dp else 8.dp,
                    top = if (inline) 8.dp else 6.dp,
                    bottom = if (inline) 8.dp else 6.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (inline) 4.dp else 6.dp)
        ) {
            Box(
                Modifier.size(6.dp).clip(CircleShape).background(
                    if (effectiveThinking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            )
            Text(
                currentModelName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.model),
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = if (compact) 140.dp else 180.dp)
            )
            if (thinkingSummary != null) {
                Text("·", style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    thinkingSummary,
                    style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(if (sheetExpanded) 180f else 0f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSwitchSheet(
    currentProvider: ProviderConfig?,
    currentModelName: String?,
    modelOptions: List<ComposerModelOption>,
    enabled: Boolean,
    onSelectModel: (ProviderConfig, String) -> Unit,
    providerModelOptions: Map<String, ProviderModelOptions>,
    onRetry: () -> Unit,
    providers: List<ProviderConfig>,
    thinkingSupport: ThinkingSupport,
    thinkingEnabled: Boolean,
    thinkingEffort: ThinkingEffort,
    onThinkingEnabledChange: (Boolean) -> Unit,
    onThinkingEffortChange: (ThinkingEffort) -> Unit,
    executionPermissionMode: com.flowchat.app.domain.device.ExecutionPermissionMode = com.flowchat.app.domain.device.ExecutionPermissionMode.RequestReview,
    onExecutionPermissionModeChange: (com.flowchat.app.domain.device.ExecutionPermissionMode) -> Unit = {},
    usageRecords: List<com.flowchat.app.data.db.UsageRecord>?,
    balanceState: com.flowchat.app.data.usage.BalanceState,
    onRefreshBalance: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val selectedDotColor = MaterialTheme.colorScheme.primary
    val effectiveThinking = thinkingSupport.isEnabled(thinkingEnabled)
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.switch_model), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            providerModelOptions.forEach { (id, options) ->
                if (providers.firstOrNull { it.id == id }?.conversationModels != null) return@forEach
                val name = providers.firstOrNull { it.id == id }?.displayName ?: "Gemini"
                if (options is ProviderModelOptions.Loading) {
                    DropdownMenuItem(enabled = false, onClick = {}, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.provider_models_loading, name))
                        }
                    })
                } else if (options is ProviderModelOptions.Failed) {
                    DropdownMenuItem(onClick = onRetry, modifier = Modifier.testTag("model-list-retry").semantics { liveRegion = LiveRegionMode.Polite }, text = {
                        Column {
                            Text(stringResource(R.string.provider_models_failed, name), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelMedium)
                        }
                    })
                }
            }
            modelOptions.forEach { option ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("conversation-option-${option.provider.id}-${option.modelName}"),
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
                        onDismissRequest()
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
            if (thinkingSupport.supported) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowToggleRow(
                        title = stringResource(R.string.thinking_short),
                        description = null,
                        checked = effectiveThinking,
                        onCheckedChange = onThinkingEnabledChange,
                        enabled = enabled && thinkingSupport.canDisable,
                        modifier = Modifier.testTag("thinking-toggle")
                    )
                    if (effectiveThinking && thinkingSupport.efforts.isNotEmpty()) {
                        val effortOptions = remember(thinkingSupport.efforts) { listOf(ThinkingEffort.Auto) + thinkingSupport.efforts }
                        val selectedIndex = effortOptions.indexOf(thinkingSupport.normalize(thinkingEffort)).coerceAtLeast(0)
                        FlowSegmentedChoice(
                            labels = effortOptions.map { thinkingEffortLabel(it) },
                            selected = selectedIndex,
                            onSelect = { onThinkingEffortChange(effortOptions[it]) }
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.execution_permission_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val permissionModes = remember { com.flowchat.app.domain.device.ExecutionPermissionMode.entries }
                FlowBinaryChoice(
                    options = listOf(
                        FlowBinaryOption(
                            label = stringResource(R.string.execution_permission_review),
                            description = stringResource(R.string.execution_permission_review_desc),
                            icon = Icons.Outlined.Visibility,
                            sageTone = true,
                            testTag = "permission-mode-review"
                        ),
                        FlowBinaryOption(
                            label = stringResource(R.string.execution_permission_full),
                            description = stringResource(R.string.execution_permission_full_desc),
                            icon = Icons.Outlined.GppMaybe,
                            sageTone = false,
                            testTag = "permission-mode-full"
                        )
                    ),
                    selected = permissionModes.indexOf(executionPermissionMode).coerceAtLeast(0),
                    onSelect = { onExecutionPermissionModeChange(permissionModes[it]) }
                )
            }
            if (usageRecords != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    UsageDisplay(
                        usageRecords,
                        com.flowchat.app.data.usage.UsageTracker.supports(currentModelName.orEmpty(), currentProvider),
                        Modifier.fillMaxWidth(),
                        balanceState,
                        onRefreshBalance
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactComposerOptionsSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

private fun configuredModelOptions(
    providers: List<ProviderConfig>,
    catalogs: Map<String, ProviderModelOptions> = emptyMap(),
    conversation: Conversation? = null
): List<ComposerModelOption> =
    providers.asSequence()
        .filter { provider -> provider.isEnabled }
        .filter { provider -> provider.baseUrl.isNotBlank() }
        .filter { provider -> provider.defaultModel.isNotBlank() }
        .flatMap { provider ->
            val fetched = (catalogs[provider.id] as? ProviderModelOptions.Ready)?.names
            val selected = conversation?.modelName?.takeIf { conversation.providerId == provider.id && it.isNotBlank() }
            provider.conversationModelNames(fetched ?: recognizedProviderModelNames(provider), selected).map { modelName ->
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
internal fun ConversationSettingsScreen(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onSave: (String, String?, Boolean, String, Double, Double, Int?) -> Unit
) {
    val context = LocalContext.current
    var assistantName by rememberSaveable(conversation.id) { mutableStateOf(conversation.assistantName) }
    var assistantAvatarPath by rememberSaveable(conversation.id) { mutableStateOf(conversation.assistantAvatarPath) }
    var showAvatars by rememberSaveable(conversation.id) { mutableStateOf(conversation.showAvatars) }
    var prompt by rememberSaveable(conversation.id) { mutableStateOf(conversation.systemPrompt) }
    var maxTokens by rememberSaveable(conversation.id) { mutableStateOf(conversation.maxTokens) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var editingPrompt by rememberSaveable { mutableStateOf(false) }
    var editingLimit by rememberSaveable { mutableStateOf(false) }
    var confirmBack by rememberSaveable { mutableStateOf(false) }
    var avatarLoading by remember { mutableStateOf(false) }
    var avatarFailed by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()
    val settingsScroll = rememberScrollState()
    val changed = assistantName != conversation.assistantName || assistantAvatarPath != conversation.assistantAvatarPath ||
        showAvatars != conversation.showAvatars || prompt != conversation.systemPrompt || maxTokens != conversation.maxTokens
    val save = {
        onSave(assistantName, assistantAvatarPath, showAvatars, prompt, conversation.temperature, conversation.topP, maxTokens)
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) settingsScope.launch {
            avatarLoading = true
            avatarFailed = false
            val path = withContext(Dispatchers.IO) { copyAssistantAvatarToPrivateFile(context, conversation.id, uri) }
            if (path != null) assistantAvatarPath = path else avatarFailed = true
            avatarLoading = false
        }
    }
    if (editingPrompt) {
        SystemPromptEditor(prompt, onChange = { prompt = it }, onBack = { editingPrompt = false })
        return
    }
    FlowSettingsPage(title = stringResource(R.string.conversation_settings),
        onBack = { if (changed) confirmBack = true else onDismiss() },
        actions = {
            TextButton(onClick = save, enabled = changed && !avatarLoading, modifier = Modifier.testTag("conversation_save")) {
                Text(stringResource(R.string.save))
            }
        }) {
        Column(Modifier.fillMaxSize().verticalScroll(settingsScroll)
            .padding(horizontal = 24.dp, vertical = 16.dp).testTag("conversation_settings_content"),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EditableAvatar(assistantAvatarPath, assistant = true, enabled = !avatarLoading,
                    onClick = { avatarPicker.launch("image/*") }, tag = "assistant")
                Column(Modifier.weight(1f)) {
                    Text(assistantName.ifBlank { stringResource(R.string.assistant_name) }, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.assistant_name)) { editingName = true }
                            .padding(vertical = 10.dp).testTag("assistant_name"))
                }
                IconButton(onClick = { editingName = true }) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.assistant_name), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (avatarLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (avatarFailed) Text(stringResource(R.string.avatar_update_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val showAvatarsInteraction = remember { MutableInteractionSource() }
            val showAvatarsPressed by showAvatarsInteraction.collectIsPressedAsState()
            val showAvatarsLabel = stringResource(R.string.show_avatars)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(showAvatarsLabel, style = MaterialTheme.typography.bodyLarge)
                }
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = FlowLayout.TouchTarget, minHeight = FlowLayout.TouchTarget)
                        .toggleable(
                            value = showAvatars,
                            role = Role.Switch,
                            interactionSource = showAvatarsInteraction,
                            indication = null,
                            onValueChange = { showAvatars = it }
                        )
                        .semantics { contentDescription = showAvatarsLabel }
                        .testTag("conversation_show_avatars"),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    FlowSwitch(checked = showAvatars, pressed = showAvatarsPressed)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(title = stringResource(R.string.system_prompt),
                trailingText = stringResource(if (prompt.isBlank()) R.string.not_configured else R.string.configured),
                onClick = { editingPrompt = true }, modifier = Modifier.testTag("conversation_prompt"))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(title = stringResource(R.string.max_tokens),
                trailingText = maxTokens?.toString() ?: stringResource(R.string.default_value), onClick = { editingLimit = true })
        }
    }
    if (editingName) NameEditorDialog(stringResource(R.string.assistant_name), assistantName,
        onDismiss = { editingName = false }, onSave = { assistantName = it; editingName = false })
    if (editingLimit) OutputLimitDialog(maxTokens, onDismiss = { editingLimit = false },
        onSave = { maxTokens = it; editingLimit = false })
    if (confirmBack) UnsavedSettingsDialog(onSave = save,
        onDiscard = { confirmBack = false; onDismiss() }, onKeepEditing = { confirmBack = false })
}

@Composable
private fun EditableAvatar(
    avatarPath: String?, assistant: Boolean, enabled: Boolean, onClick: () -> Unit, tag: String
) {
    val description = stringResource(if (assistant) R.string.assistant_avatar else R.string.edit_profile_avatar)
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button,
            onClickLabel = description, onClick = onClick).testTag("${tag}_avatar")) {
            if (assistant) AssistantAvatar(avatarPath = avatarPath, size = 44.dp) else ProfileAvatar(avatarPath = avatarPath, size = 44.dp)
        }
        if (assistant) AvatarEditButton(onClick, description,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).testTag("${tag}_avatar_edit"),
            enabled = enabled)
    }
}

@Composable
private fun AvatarEditButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary)
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
            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(size * .5f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

internal data class UserProfile(
    val name: String,
    val avatarPath: String?
)

@Composable
internal fun rememberUserProfile(): UserProfile {
    val context = LocalContext.current
    val defaultName = stringResource(R.string.user_display_name)
    val preferences = remember(context) { context.getSharedPreferences(UserProfilePrefsName, Context.MODE_PRIVATE) }
    var profile by remember(context, defaultName) { mutableStateOf(loadUserProfile(context, defaultName)) }
    DisposableEffect(preferences, defaultName) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            profile = loadUserProfile(context, defaultName)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        profile = loadUserProfile(context, defaultName)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return profile
}

internal fun loadUserProfile(context: Context, defaultName: String): UserProfile {
    val prefs = context.getSharedPreferences(UserProfilePrefsName, Context.MODE_PRIVATE)
    return UserProfile(
        name = prefs.getString(UserProfileNameKey, null)?.takeIf { it.isNotBlank() } ?: defaultName,
        avatarPath = prefs.getString(UserProfileAvatarKey, null)?.takeIf { it.isNotBlank() }
    )
}

internal fun saveUserProfile(context: Context, profileName: String, profileAvatarPath: String?) {
    context.getSharedPreferences(UserProfilePrefsName, Context.MODE_PRIVATE)
        .edit()
        .putString(UserProfileNameKey, profileName.trim())
        .putString(UserProfileAvatarKey, profileAvatarPath.orEmpty())
        .apply()
}

internal fun copyUserAvatarToPrivateFile(context: Context, uri: Uri): String? {
    return runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = bitmap.scaleToMax(512)
        val directory = File(context.filesDir, "user_profile").apply { mkdirs() }
        val output = File(directory, "avatar-${java.util.UUID.randomUUID()}.jpg")

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
        val output = File(directory, "$conversationId-${java.util.UUID.randomUUID()}.jpg")

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
internal fun Conversation.displayTitle(): String {
    return if (title == "New chat") stringResource(R.string.default_conversation_title) else title
}

@Composable
private fun Conversation.displayAssistantTitle(): String {
    return assistantName.ifBlank { displayTitle() }
}
