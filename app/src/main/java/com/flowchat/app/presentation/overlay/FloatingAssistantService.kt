package com.flowchat.app.presentation.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FloatingAssistantService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    @Inject lateinit var bridge: FloatingAssistantBridge

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val serviceViewModelStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var controlsView: ComposeView
    private lateinit var controlsLayoutParams: WindowManager.LayoutParams
    private var overlayAttached = false
    private var controlsAttached = false
    private var visibilityJob: Job? = null
    private var expanded by mutableStateOf(false)
    private var watching by mutableStateOf(false)
    private var automationBusy = false
    private var anchorY = 0
    private var appearance by mutableStateOf(AppAppearance.System)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = serviceViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WindowManager::class.java)
        appearance = AppAppearance.load(this)
        startForegroundServiceNotification()
        createOverlayView()
        observeVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FloatingAssistantPermission.isGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        appearance = AppAppearance.load(this)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        visibilityJob?.cancel()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceViewModelStore.clear()
        super.onDestroy()
    }

    private fun createOverlayView() {
        val edgeInset = dp(8)
        val savedY = getSharedPreferences(PositionPreferences, MODE_PRIVATE)
            .getInt(PositionYKey, dp(214))
        anchorY = savedY.coerceIn(dp(48), (resources.displayMetrics.heightPixels - dp(360)).coerceAtLeast(dp(48)))
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = edgeInset
            y = anchorY
            setTitle(OutputWindowTitle)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        controlsLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = edgeInset
            y = anchorY
            setTitle(ControlsWindowTitle)
        }
        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                FlowChatTheme(appAppearance = appearance) {
                    val state by bridge.state.collectAsState()
                    FloatingAssistantOverlay(
                        state = state,
                        expanded = expanded,
                        watching = watching,
                        onExpand = { updateExpanded(true) },
                        onCollapse = { updateExpanded(false) },
                        onDrag = ::moveVertically,
                        onDragFinished = ::savePosition,
                        onSend = { text ->
                            bridge.send(text)
                        },
                        onStop = bridge::stop
                    )
                }
            }
        }
        controlsView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                FlowChatTheme(appAppearance = appearance) {
                    val state by bridge.state.collectAsState()
                    WatchingAssistantControls(
                        isStreaming = state.isStreaming,
                        onCollapse = { updateExpanded(false) },
                        onReply = {
                            if (!bridge.state.value.isStreaming && !automationBusy) {
                                watching = false
                                updateOverlayWindows()
                            }
                        },
                        onStop = bridge::stop,
                        onDrag = ::moveVertically,
                        onDragFinished = ::savePosition
                    )
                }
            }
        }
    }

    private fun observeVisibility() {
        visibilityJob = serviceScope.launch {
            combine(bridge.state, bridge.appInForeground, bridge.uiInteractionCount) { state, appInForeground, interactions ->
                Triple(!appInForeground && state.canChat, state.isStreaming, interactions > 0)
            }.distinctUntilChanged().collect { (shouldShow, streaming, busy) ->
                automationBusy = busy
                // Viewing output never needs keyboard focus, including after the task finishes.
                if (expanded && (streaming || busy)) watching = true
                if (shouldShow && FloatingAssistantPermission.isGranted(this@FloatingAssistantService)) {
                    showOverlay()
                    updateOverlayWindows()
                } else {
                    removeOverlay()
                }
            }
        }
    }

    private fun showOverlay() {
        if (overlayAttached) return
        appearance = AppAppearance.load(this)
        runCatching {
            windowManager.addView(overlayView, layoutParams)
            overlayAttached = true
        }.onFailure { stopSelf() }
    }

    private fun removeOverlay() {
        removeControls()
        if (!overlayAttached) return
        clearOverlayFocus()
        runCatching { windowManager.removeViewImmediate(overlayView) }
        overlayAttached = false
        expanded = false
        watching = false
    }

    private fun updateExpanded(value: Boolean) {
        expanded = value
        watching = value && (bridge.state.value.isStreaming || automationBusy)
        updateOverlayWindows()
    }

    private fun clearOverlayFocus() {
        if (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        overlayView.clearFocus()
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(overlayView.windowToken, 0)
    }

    private fun updateOverlayWindows() {
        if (!overlayAttached) return
        val passive = expanded && watching
        val passThrough = passive || automationBusy
        val focusable = expanded && !watching && !automationBusy
        if (!focusable) clearOverlayFocus()
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        // Android 12+ can block pass-through touches when an untrusted overlay is too opaque.
        // The controls occupy a separate, non-overlapping window above the text panel.
        // A visible bubble must also stay below Android's obscuring-opacity limit while yielding.
        // Hiding it for every observation/tap caused it to disappear throughout repeated actions.
        layoutParams.alpha = if (passThrough) passThroughAlpha() else 1f
        layoutParams.width = if (expanded) WindowManager.LayoutParams.WRAP_CONTENT else dp(BubbleSizeDp)
        layoutParams.height = if (expanded) WindowManager.LayoutParams.WRAP_CONTENT else dp(BubbleSizeDp)
        layoutParams.y = anchorY + if (passive) dp(48) else 0
        runCatching { windowManager.updateViewLayout(overlayView, layoutParams) }
        if (passive) {
            controlsLayoutParams.y = anchorY
            controlsView.visibility = if (automationBusy) View.INVISIBLE else View.VISIBLE
            runCatching {
                if (controlsAttached) windowManager.updateViewLayout(controlsView, controlsLayoutParams)
                else {
                    windowManager.addView(controlsView, controlsLayoutParams)
                    controlsAttached = true
                }
            }.onFailure { stopSelf() }
        } else {
            removeControls()
        }
    }

    private fun passThroughAlpha(): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        minOf(0.78f, getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch)
    } else {
        0.78f
    }

    private fun removeControls() {
        if (!controlsAttached) return
        runCatching { windowManager.removeViewImmediate(controlsView) }
        controlsAttached = false
    }

    private fun moveVertically(deltaY: Float) {
        if (!overlayAttached || (expanded && !watching) || automationBusy) return
        val displayHeight = resources.displayMetrics.heightPixels
        anchorY = (anchorY + deltaY.roundToInt())
            .coerceIn(dp(48), (displayHeight - dp(360)).coerceAtLeast(dp(48)))
        updateOverlayWindows()
    }

    private fun savePosition() {
        getSharedPreferences(PositionPreferences, MODE_PRIVATE)
            .edit()
            .putInt(PositionYKey, anchorY)
            .apply()
    }

    private fun startForegroundServiceNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationChannelId,
                    getString(R.string.floating_assistant),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.floating_assistant_notification_description)
                    setShowBadge(false)
                }
            )
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NotificationChannelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.floating_assistant_running))
            .setContentText(getString(R.string.floating_assistant_notification_description))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationId, notification, foregroundType)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val NotificationChannelId = "floating_assistant"
        const val NotificationId = 1207
        const val PositionPreferences = "floating_assistant_position"
        const val PositionYKey = "position_y"
        const val OutputWindowTitle = "FloryxOutput"
        const val ControlsWindowTitle = "FloryxControls"
    }
}

@Composable
private fun FloatingAssistantOverlay(
    state: FloatingAssistantState,
    expanded: Boolean,
    watching: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    Crossfade(
        targetState = expanded to watching,
        // Do not retain the outgoing panel's rectangular bounds around a collapsed bubble.
        modifier = if (expanded) Modifier else Modifier.size(BubbleSizeDp.dp).clip(CircleShape),
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "floating-assistant"
    ) { (isExpanded, isWatching) ->
        if (isExpanded) {
            ExpandedAssistantPanel(
                state = state,
                watching = isWatching,
                onCollapse = onCollapse,
                onSend = onSend,
                onStop = onStop
            )
        } else {
            FloatingAssistantBubble(
                isStreaming = state.isStreaming,
                onClick = onExpand,
                onDrag = onDrag,
                onDragFinished = onDragFinished
            )
        }
    }
}

@Composable
private fun FloatingAssistantBubble(
    isStreaming: Boolean,
    onClick: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit
) {
    val dragState = rememberDraggableState { delta -> onDrag(delta) }
    val pulse = if (isStreaming) {
        val transition = rememberInfiniteTransition(label = "assistant-activity")
        val animatedPulse by transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "assistant-ring"
        )
        animatedPulse
    } else {
        0.58f
    }
    Box(
        modifier = Modifier
            .size(BubbleSizeDp.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { onDragFinished() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = if (isStreaming) 2.dp else 1.dp,
                    color = if (isStreaming) {
                        MaterialTheme.colorScheme.primary.copy(alpha = pulse)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.58f)
                    },
                    shape = CircleShape
                ),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FlowChatMark(modifier = Modifier.size(28.dp))
                if (isStreaming) CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize().padding(3.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ExpandedAssistantPanel(
    state: FloatingAssistantState,
    watching: Boolean,
    onCollapse: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    val messageScrollState = rememberScrollState()
    LaunchedEffect(state.assistantText, messageScrollState.maxValue) {
        messageScrollState.scrollTo(messageScrollState.maxValue)
    }
    Surface(
        modifier = Modifier
            .width(272.dp)
            .shadow(4.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FloatingProgressStatus(state, Modifier.weight(1f))
                if (!watching) IconButton(onClick = onCollapse, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.collapse_floating_assistant),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp, max = 208.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.30f))
            ) {
                Text(
                    text = state.assistantText.ifBlank {
                        if (state.isStreaming) {
                            stringResource(R.string.floating_assistant_thinking)
                        } else {
                            stringResource(R.string.floating_assistant_ready)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                        .verticalScroll(messageScrollState)
                )
            }
            if (!watching) {
                Spacer(Modifier.height(8.dp))
                OverlayComposer(
                    isStreaming = state.isStreaming,
                    onSend = onSend,
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun FloatingProgressStatus(state: FloatingAssistantState, modifier: Modifier = Modifier) {
    val progress = state.progress ?: FloatingTaskProgress(
        if (state.isStreaming) FloatingTaskPhase.Thinking else FloatingTaskPhase.Ready)
    val phase = progress.phase
    // Tick locally even when a command prints nothing. Anchoring to monotonic time avoids
    // clock changes while visible; reopening uses the actual persisted command start time.
    var elapsedSeconds by remember(phase, progress.startedAt) {
        mutableStateOf(((System.currentTimeMillis() - (progress.startedAt ?: System.currentTimeMillis())) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(phase, progress.startedAt) {
        if (phase.isActive && progress.startedAt != null) {
            val initial = (System.currentTimeMillis() - progress.startedAt).coerceAtLeast(0)
            val anchor = android.os.SystemClock.elapsedRealtime()
            while (true) {
                elapsedSeconds = (initial + android.os.SystemClock.elapsedRealtime() - anchor) / 1000
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    val label = when (phase) {
        FloatingTaskPhase.Ready -> R.string.floating_status_ready
        FloatingTaskPhase.Thinking -> R.string.floating_status_thinking
        FloatingTaskPhase.Responding -> R.string.floating_status_responding
        FloatingTaskPhase.PreparingCommand -> R.string.floating_status_preparing
        FloatingTaskPhase.RunningCommand -> R.string.floating_status_command
        FloatingTaskPhase.UsingTool -> R.string.floating_status_tool
        FloatingTaskPhase.WaitingForUser -> R.string.floating_status_confirmation
        FloatingTaskPhase.Paused -> R.string.floating_status_paused
        FloatingTaskPhase.Finished -> R.string.floating_status_finished
        FloatingTaskPhase.Stopped -> R.string.floating_status_stopped
        FloatingTaskPhase.Failed -> R.string.floating_status_failed
    }
    Column(modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (phase.isActive) {
                CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSurface, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                color = if (phase == FloatingTaskPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (phase.isActive && progress.startedAt != null) {
            Text(stringResource(R.string.floating_status_elapsed, elapsedSeconds),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val hint = when (phase) {
            FloatingTaskPhase.RunningCommand -> R.string.floating_status_command_hint
            FloatingTaskPhase.WaitingForUser -> R.string.floating_status_confirmation_hint
            else -> null
        }
        if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WatchingAssistantControls(
    isStreaming: Boolean,
    onCollapse: () -> Unit,
    onReply: () -> Unit,
    onStop: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 48.dp).draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Vertical,
                onDragStopped = { onDragFinished() }
            )
        ) {
            if (isStreaming) {
                TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.stop), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onReply) {
                    Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.floating_assistant_reply))
                }
            }
            IconButton(onClick = onCollapse, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.collapse_floating_assistant))
            }
        }
    }
}

@Composable
private fun OverlayComposer(
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.42f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.floating_assistant_input_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (isStreaming) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(1.5.dp, MaterialTheme.colorScheme.error, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.stop),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = {
                        text.trim().takeIf { it.isNotEmpty() }?.let { value ->
                            onSend(value)
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        modifier = Modifier
                            .size(19.dp)
                            .rotate(-18f)
                    )
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { text = "" }
    }
}

@Composable
private fun FlowChatMark(modifier: Modifier = Modifier) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val invert = remember {
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
    Image(
        painter = painterResource(R.mipmap.ic_launcher),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = if (isDark) ColorFilter.colorMatrix(invert) else null,
        modifier = modifier.clip(CircleShape)
    )
}

private const val BubbleSizeDp = 48
