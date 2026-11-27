package com.flowchat.app.data.device

import android.app.ActivityManager
import android.content.ComponentName
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.flowchat.app.domain.device.AutomationTimings
import com.flowchat.app.BuildConfig
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.DeviceAdaptiveScroll
import com.flowchat.app.domain.device.DeviceActionPolicy
import com.flowchat.app.domain.device.DeviceActionOutcomePolicy
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.terminal.CommandSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.domain.device.DeviceScrollRequest
import com.flowchat.app.domain.device.DeviceContainerScrollRequest
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.device.DeviceSafetyBoundary
import com.flowchat.app.domain.device.DeviceSafetyReason
import com.flowchat.app.domain.device.VisualTapTarget
import com.flowchat.app.domain.device.VisualFrameGuard
import com.flowchat.app.domain.device.visualTapPoint
import com.flowchat.app.domain.device.needsVisualRefinement
import com.flowchat.app.domain.device.visualRefinementRegion
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.shizuku.IFlowChatShizukuService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku

@Singleton
class ShizukuDeviceAssistantGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsStore: AppSettingsStore,
    private val rootConnection: RootDeviceConnection,
    private val taskControl: DeviceTaskControl = DeviceTaskControl(),
    private val floatingAssistantBridge: com.flowchat.app.presentation.overlay.FloatingAssistantBridge =
        com.flowchat.app.presentation.overlay.FloatingAssistantBridge(taskControl),
    private val safetyMonitor: DeviceSafetyMonitor = DeviceSafetyMonitor(context, DeviceSafetyBoundary()),
    private val reconnectStore: AccessibilityReconnectStore = AccessibilityReconnectStore(context)
) : DeviceAssistantGateway {
    override val executionDisplayCleanup get() = rootConnection.executionDisplayCleanup
    override val rootRequestCleanup get() = rootConnection.rootRequestCleanup
    override fun recheckRootRequestCleanup() { scope.launch { rootConnection.recheckRootRequestCleanup() } }
    override fun recheckExecutionDisplayCleanup() { scope.launch { rootConnection.recheckExecutionDisplayCleanup() } }
    override val safetyState get() = safetyMonitor.state
    override fun refreshSafetyState() { safetyMonitor.refresh() }
    override val backgroundExecutionRequested: Boolean get() = appSettingsStore.state.value.backgroundExecutionEnabled
    private fun checkPhoneAction(task: DeviceTaskControl.Task?) {
        rootConnection.requireRequestCleanupVerified()
        taskControl.checkCanAct(task)
    }

    private fun validateExecutionTarget(target: DeviceExecutionTarget?) {
        safetyMonitor.check(target)
        rootConnection.requireRequestCleanupVerified()
        if (target == null) return
        val settings = appSettingsStore.state.value
        target.requireCurrent(settings.installId, settings.powerModeEnabled, settings.powerConnectionMode,
            settings.backgroundExecutionEnabled, settings.deviceConfigurationVersion)
        checkPhoneAction(taskControl.currentTask)
    }

    override suspend fun checkExecutionTarget() {
        currentCoroutineContext().ensureActive()
        validateExecutionTarget(currentCoroutineContext()[DeviceExecutionTarget])
    }

    private suspend fun executionInBackground(): Boolean {
        checkExecutionTarget()
        return currentCoroutineContext()[DeviceExecutionTarget]?.backgroundExecution ?: backgroundExecutionRequested
    }
    private val background = BackgroundDeviceController(rootConnection, taskControl, context.packageName) {
        val settings = appSettingsStore.state.value
        settings.backgroundExecutionEnabled && settings.powerModeEnabled && settings.powerConnectionMode == PowerConnectionMode.Root
    }
    private fun backgroundUnsupported() = DeviceToolResult(false,
        "This operation affects the physical phone. Switch off background execution to use it.", errorCode = "background_action_unsupported")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableConnectionState = MutableStateFlow(ShizukuConnectionState())
    override val connectionState: StateFlow<ShizukuConnectionState> = mutableConnectionState.asStateFlow()
    private val mutableAccessibilityState = MutableStateFlow(AccessibilityConnectionState())
    override val accessibilityState: StateFlow<AccessibilityConnectionState> =
        mutableAccessibilityState.asStateFlow()
    private val binding = AtomicBoolean(false)
    private val accessibilityReconnectAttempted = AtomicBoolean(false)
    private val accessibilityReconnectMutex = Mutex()
    @Volatile private var service: IFlowChatShizukuService? = null
    private var accessibilityWasUsable = false
    private val accessibilitySettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshAccessibilityConnection() }
    }
    private val commandServices = ConcurrentHashMap<String, IFlowChatShizukuService>()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, FlowChatShizukuService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("device_assistant")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshConnection() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isRootMode) safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
        service = null
        binding.set(false)
        refreshConnection()
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PermissionRequestCode) return@OnRequestPermissionResultListener
        if (isRootMode) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            bindService()
        } else {
            safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
            mutableConnectionState.value = ShizukuConnectionState(
                status = ShizukuConnectionStatus.PermissionDenied,
                detail = "Shizuku permission was denied."
            )
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IFlowChatShizukuService.Stub.asInterface(binder)
            binding.set(false)
            scope.launch { probeConnectedService() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (!isRootMode) safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
            service = null
            binding.set(false)
            refreshConnection()
        }
    }

    init {
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilitySettingsObserver)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshAccessibilityConnection()
        refreshConnection()
        scope.launch {
            FlowChatAccessibilityService.connected.collect { refreshAccessibilityConnection() }
        }
        scope.launch {
            rootConnection.state.collect { state ->
                if (isRootMode) publishRootState(state)
            }
        }
        scope.launch {
            appSettingsStore.state.map { Triple(it.powerConnectionMode, it.powerModeEnabled, it.backgroundExecutionEnabled) }.distinctUntilChanged().collect { (mode, enabled, _) ->
                if (!enabled) reconnectStore.clearAuthorization()
                background.release()
                if (mode == PowerConnectionMode.Root && enabled) {
                    publishRootState(rootConnection.state.value)
                    scope.launch {
                        if (isRootMode) {
                            rootConnection.refresh(requestPermission = true)
                        }
                    }
                } else {
                    rootConnection.release()
                    if (mode == PowerConnectionMode.Root) {
                        publishRootState(rootConnection.state.value)
                        return@collect
                    }
                    mutableConnectionState.value = ShizukuConnectionState()
                    refreshConnection()
                }
            }
        }
    }

    private val isRootMode: Boolean
        get() = appSettingsStore.state.value.powerConnectionMode == PowerConnectionMode.Root

    private fun publishRootState(state: ShizukuConnectionState) {
        mutableConnectionState.value = state.copy(
            capabilities = state.capabilities + if (mutableAccessibilityState.value.isConnected) {
                AccessibilityCapabilities
            } else {
                emptySet()
            }
        )
    }

    override fun refreshConnection() {
        refreshSafetyState()
        scope.launch {
            if (isRootMode) {
                rootConnection.refresh()
                if (isRootMode) publishRootState(rootConnection.state.value)
                return@launch
            }
            if (hasLiveUserService()) {
                probeConnectedService()
                return@launch
            }
            service = null
            val inspectedState = inspectConnection()
            if (hasLiveUserService()) {
                probeConnectedService()
                return@launch
            }
            if (isRootMode) return@launch
            mutableConnectionState.value = inspectedState
            if (inspectedState.status == ShizukuConnectionStatus.Connecting) {
                bindService()
            }
        }
    }

    override fun requestPermission() {
        if (isRootMode) {
            scope.launch { if (isRootMode) rootConnection.refresh(requestPermission = true) }
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refreshConnection()
            return
        }
        runCatching {
            when {
                Shizuku.isPreV11() -> mutableConnectionState.value = ShizukuConnectionState(
                    status = ShizukuConnectionStatus.Unsupported,
                    detail = "This Shizuku version is not supported."
                )
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindService()
                Shizuku.shouldShowRequestPermissionRationale() -> mutableConnectionState.value =
                    ShizukuConnectionState(
                        status = ShizukuConnectionStatus.PermissionDenied,
                        detail = "Open Shizuku and grant Floryx permission."
                    )
                else -> Shizuku.requestPermission(PermissionRequestCode)
            }
        }.onFailure { error ->
            mutableConnectionState.value = errorState(error)
        }
    }

    override fun refreshAccessibilityConnection() {
        val availableService = FlowChatAccessibilityService.current()
        val configured = configuredAccessibilityService()
        if (availableService != null && configured == true) {
            reconnectStore.recordAuthorized(android.os.Process.myPid())
        } else if (availableService == null && configured != true) {
            reconnectStore.recordRevokedIfAuthorized(android.os.Process.myPid())
        }
        val enabled = isAccessibilityServiceEnabled()
        val usable = availableService != null && enabled != false
        synchronized(this) {
            if (accessibilityWasUsable && !usable) safetyMonitor.boundary.invalidate(DeviceSafetyReason.AccessibilityLost)
            accessibilityWasUsable = usable
        }
        val checkedAt = System.currentTimeMillis()
        mutableAccessibilityState.value = when {
            enabled == false -> AccessibilityConnectionState(AccessibilityConnectionStatus.Disabled,
                enabled = false, checkedAtMillis = checkedAt)
            usable -> AccessibilityConnectionState(AccessibilityConnectionStatus.Connected,
                enabled = enabled ?: true, checkedAtMillis = checkedAt)
            enabled == true -> AccessibilityConnectionState(
                AccessibilityConnectionStatus.Unavailable,
                "Accessibility is enabled but the service is not connected yet.", enabled = true, checkedAtMillis = checkedAt
            )
            else -> AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable,
                "The accessibility service status could not be read. Check Android Accessibility settings.",
                checkedAtMillis = checkedAt)
        }
        val accessibilityCapabilities = if (usable) {
            AccessibilityCapabilities
        } else {
            emptySet()
        }
        mutableConnectionState.update { state ->
            state.copy(capabilities = (state.capabilities - AccessibilityCapabilities) + accessibilityCapabilities)
        }
    }

    override fun openAccessibilitySettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override suspend fun reconnectAccessibilityAfterStop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (FlowChatAccessibilityService.current() != null) {
            refreshAccessibilityConnection()
            return
        }
        accessibilityReconnectMutex.withLock {
            if (FlowChatAccessibilityService.current() != null || accessibilityReconnectAttempted.get()) {
                refreshAccessibilityConnection()
                return
            }
            val settings = appSettingsStore.state.value
            val latestExit = withContext(Dispatchers.IO) {
                runCatching {
                    context.getSystemService(ActivityManager::class.java)
                        ?.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                        ?.firstOrNull { it.processName == context.packageName }
                        ?.let { info ->
                            ProcessExitSnapshot(
                                pid = info.pid,
                                reason = info.reason,
                                timestampMillis = info.timestamp,
                                description = info.description
                            )
                        }
                }.getOrNull()
            }
            fun decideRecovery(): AccessibilityRecoveryAction = appSettingsStore.state.value.let { current ->
                if (current.powerConnectionMode != settings.powerConnectionMode) {
                    AccessibilityRecoveryAction.None
                } else {
                    AccessibilityReconnectPolicy.decide(
                        packageName = context.packageName,
                        powerModeEnabled = current.powerModeEnabled,
                        serviceBound = FlowChatAccessibilityService.current() != null,
                        configuredSelected = configuredAccessibilityService(),
                        latestExit = latestExit,
                        receipt = reconnectStore.snapshot()
                    )
                }
            }
            if (decideRecovery() == AccessibilityRecoveryAction.None) return

            val useRoot = settings.powerConnectionMode == PowerConnectionMode.Root
            val connection = if (useRoot) rootConnection.state else connectionState
            if (!connection.value.isConnected) refreshConnection()
            if (withTimeoutOrNull(6_000) { connection.first { it.isConnected } } == null) return
            if (decideRecovery() == AccessibilityRecoveryAction.None) return

            try {
                val reconnected = withContext(Dispatchers.IO) {
                    val foreground = currentCoroutineContext()
                    val reconnect: (IFlowChatShizukuService) -> Boolean = { connected ->
                        foreground.ensureActive()
                        val action = decideRecovery()
                        if (action == AccessibilityRecoveryAction.None ||
                            !accessibilityReconnectAttempted.compareAndSet(false, true)
                        ) {
                            false
                        } else {
                            val userId = android.os.Process.myUid() / 100_000
                            when (action) {
                                AccessibilityRecoveryAction.None -> false
                                AccessibilityRecoveryAction.ReconnectSelected -> {
                                    // Unexpected deaths are handled once per exit so a crashing service cannot loop.
                                    latestExit?.takeIf { it.reason in AccessibilityReconnectPolicy.UnexpectedDeathReasons }
                                        ?.let { exit -> reconnectStore.markExitHandled(exit.pid, exit.timestampMillis) }
                                    connected.reconnectSelectedAccessibilityService(userId)
                                }
                                AccessibilityRecoveryAction.RestoreSingleCleaner -> {
                                    val exit = requireNotNull(latestExit)
                                    reconnectStore.markExitHandled(exit.pid, exit.timestampMillis)
                                    connected.restoreSystemClearedAccessibilityService(userId)
                                }
                            }
                        }
                    }
                    // Once the remote call starts, let it finish restoring the selection even if the window loses focus.
                    withContext(NonCancellable) {
                        if (useRoot) rootConnection.withService(operation = reconnect)
                        else if (hasLiveUserService()) service?.let(reconnect) ?: false
                        else false
                    }
                }
                if (reconnected && FlowChatAccessibilityService.current() == null) {
                    withTimeoutOrNull(AccessibilityRebindWaitMillis) {
                        FlowChatAccessibilityService.connected.first { it }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("FloryxAccessibility", "Could not reconnect the selected accessibility service", error)
            } finally {
                refreshAccessibilityConnection()
            }
        }
    }

    override suspend fun getDeviceStatus(): DeviceToolResult = callService { it.getDeviceStatus() }

    override suspend fun getForegroundApp(): DeviceToolResult = if (executionInBackground()) background.foreground() else callService { it.getForegroundApp() }

    override suspend fun launchApp(packageName: String, activityName: String): DeviceToolResult {
        if (executionInBackground()) return background.launch(packageName, activityName)
        currentCoroutineContext().ensureActive()
        val task = taskControl.currentTask
        checkPhoneAction(task)
        uiActionMutex.withLock { snapshotGuard.remember(null); visualFrameGuard.clear() }
        return callService {
            checkPhoneAction(task)
            it.launchApp(packageName, activityName)
        }
    }

    override suspend fun openPublicAppAction(tool: String, arguments: String): DeviceToolResult {
        val request = runCatching { com.flowchat.app.domain.device.PublicAppActions.parse(tool, arguments) }.getOrNull()
            ?: return publicAppRejected("invalid_public_app_action")
        checkExecutionTarget()
        if (backgroundExecutionRequested || currentCoroutineContext()[DeviceExecutionTarget]?.backgroundExecution == true || !isRootMode)
            return publicAppRejected("public_app_requires_foreground_root")
        if (requireAccessibilityService() == null) return publicAppRejected("accessibility_unavailable")
        val owner = taskControl.currentTask
        checkPhoneAction(owner)
        uiActionMutex.withLock { snapshotGuard.remember(null); visualFrameGuard.clear() }
        val notBefore = System.currentTimeMillis()
        val launched = callService { remote ->
            remote.openPublicAppAction(tool, arguments, android.os.Process.myUid() / 100_000)
        }
        if (!launched.success) return launched.copy(actionOutcome = launched.actionOutcome
            ?: com.flowchat.app.domain.device.DeviceActionOutcome.Unverified)
        var latest: DeviceScreenSnapshot? = null
        repeat(16) {
            delay(250)
            currentCoroutineContext().ensureActive()
            checkExecutionTarget()
            checkPhoneAction(owner)
            val observed = observeScreen()
            latest = observed.screen
            if (observed.success && latest?.let { screen -> com.flowchat.app.domain.device.PublicAppActions.prefillMatches(
                    request, screen, notBefore, java.time.ZoneId.systemDefault()) } == true) return DeviceToolResult(true,
                (if (request.completion == com.flowchat.app.domain.device.PublicAppActions.CalendarCompletion.Save)
                    "calendar_fields_verified_continue_task; business_result=not_saved_yet; " +
                        "The task requests a saved event. Prefer task_effect_save_calendar_event with the same title and times to save once and independently verify the record. " +
                        "For an uncertain registered save, use verify_task_effect, never repeat the save. Ordinary UI saves still need independent title and BOTH time checks. Do not ask again to save. Do not accept setup or grant new permissions. "
                else "prefill_verified_waiting_for_user; business_result=not_submitted_by_this_action; Do not call or save. Hand control to the user. ") +
                    "adapter=${request.registration.id}; app_version=${request.registration.versionCode}; only the displayed fields at this sample were checked.",
                screen = latest, actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.ProgressObserved)
        }
        return DeviceToolResult(false,
            "Editor launch returned, but exact prefilled fields were not verified. Setup, permission prompts, locale, changed fields or a different window may block verification. " +
                (if (request.completion == com.flowchat.app.domain.device.PublicAppActions.CalendarCompletion.Save)
                    "Observe the current page and verify fields before any save. Do not reopen blindly, accept setup or grant new permissions. No save was issued by this action."
                else "No automatic retry or UI fallback. Hand control to the user; no call or save was issued by this action."),
            errorCode = "public_app_prefill_unverified", screen = latest,
            actionOutcome = if (request.completion == com.flowchat.app.domain.device.PublicAppActions.CalendarCompletion.Save &&
                latest?.packageName == request.registration.packageName && latest?.sensitive == false && latest?.interactionContext?.complete == true)
                com.flowchat.app.domain.device.DeviceActionOutcome.StateObserved else com.flowchat.app.domain.device.DeviceActionOutcome.Unverified)
    }

    private fun publicAppRejected(code: String) = DeviceToolResult(false,
        "Public app editor was not dispatched. No fallback or new permission was requested.", errorCode = code,
        actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched)

    override suspend fun inspectCalendarEvents(arguments: String): com.flowchat.app.domain.device.CalendarEventReceipt? {
        checkExecutionTarget()
        if (!isRootMode || backgroundExecutionRequested || currentCoroutineContext()[DeviceExecutionTarget]?.backgroundExecution == true) return null
        if (runCatching { com.flowchat.app.domain.device.CalendarEventSave.request(arguments) }.isFailure) return null
        val result = callService { it.inspectCalendarEvents(arguments, android.os.Process.myUid() / 100_000) }
        if (!result.success) return null
        return runCatching { commandJson.decodeFromString<com.flowchat.app.domain.device.CalendarEventReceipt>(requireNotNull(result.after)) }.getOrNull()
    }

    override suspend fun openSavedCalendarEvent(edit: com.flowchat.app.domain.device.CalendarTimeEdit): Boolean = uiActionMutex.withLock {
        checkExecutionTarget()
        if (executionInBackground() || !isRootMode || requireAccessibilityService() == null) return@withLock false
        val owner = taskControl.currentTask ?: return@withLock false
        checkPhoneAction(owner)
        snapshotGuard.remember(null)
        visualFrameGuard.clear()
        callService { it.openSavedCalendarEvent(edit.original, edit.eventId, edit.calendarId, android.os.Process.myUid() / 100_000) }.success
    }

    override suspend fun moveCalendarTimeWheel(index: Int, steps: Int, screenId: String): DeviceToolResult = performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiElementToolName,
        summary = "Adjusted the visible calendar time wheel.", expectedScreenId = screenId, requiresSnapshot = true, targetIndex = index
    ) { service, screen ->
        val owner = taskControl.currentTask ?: return@performScreenAction false
        service.moveCalendarTimeWheel(screen, index, steps) { checkPhoneAction(owner) }
    }

    override suspend fun commitCalendarTimeEdit(index: Int, screenId: String): DeviceToolResult = performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiTargetToolName,
        summary = "Submitted the verified calendar edit once.", expectedScreenId = screenId, requiresSnapshot = true
    ) { service, screen ->
        val owner = taskControl.currentTask ?: throw DeviceDispatchRejected("calendar_edit_task_unavailable")
        if (screen.packageName != com.flowchat.app.domain.device.PublicAppActions.Event.packageName)
            throw DeviceDispatchRejected("calendar_edit_window_changed")
        service.tapUiElement(index, false, screen, targetAnchor = com.flowchat.app.domain.device.CalendarTimeEdit.SaveControl,
            requireSameUi = true) { checkPhoneAction(owner) }
    }

    override suspend fun setScreenBrightness(percent: Int): DeviceToolResult =
        if (executionInBackground()) backgroundUnsupported() else callService { it.setScreenBrightness(percent.coerceIn(1, 100)) }

    override suspend fun setMediaVolume(percent: Int): DeviceToolResult =
        if (executionInBackground()) backgroundUnsupported() else callService { it.setMediaVolume(percent.coerceIn(0, 100)) }

    override suspend fun forceStopApp(appName: String): DeviceToolResult {
        if (executionInBackground()) return backgroundUnsupported()
        val app = withContext(Dispatchers.IO) { resolveLaunchableApp(appName) }
        if (app.packageName == context.packageName) {
            return DeviceToolResult(
                success = false,
                summary = "Floryx cannot force stop itself.",
                errorCode = "self_stop_blocked"
            )
        }
        val result = callService { it.forceStopPackage(app.packageName) }
        return if (result.success) {
            result.copy(summary = "Force stopped ${app.label} (${app.packageName}).")
        } else {
            result
        }
    }

    override suspend fun enablePowerMode(): DeviceToolResult {
        val result = callService { it.enablePowerMode() }
        delay(PowerModeRefreshDelayMillis)
        refreshAccessibilityConnection()
        return result
    }

    private val uiActionMutex = Mutex()
    private val snapshotGuard = com.flowchat.app.domain.device.DeviceSnapshotGuard()
    private val visualFrameGuard = VisualFrameGuard()
    private var snapshotSafetyRevision = -1L
    private var snapshotOwner: DeviceTaskControl.Task? = null

    override suspend fun observeScreen(): DeviceToolResult = AutomationTimings.measure("device.observe") { uiActionMutex.withLock {
        if (executionInBackground()) return@withLock background.observe()
        observeScreenLocked()
    } }

    override suspend fun observeScreen(scope: com.flowchat.app.domain.device.DeviceObservationScope): DeviceToolResult = uiActionMutex.withLock {
        if (executionInBackground()) return@withLock background.observe(scope = scope)
        observeScreenLocked(scope = scope)
    }

    override suspend fun observeDocument(): DeviceToolResult = uiActionMutex.withLock {
        val owner = taskControl.currentTask
        checkPhoneAction(owner)
        if (executionInBackground()) return@withLock DeviceToolResult(false,
            "Extended document text currently requires foreground observation. Read this target's visible page instead.",
            errorCode = "document_read_unsupported")
        val result = observeScreenLocked(waitForSettledUi = false, includeDocumentText = true)
        checkPhoneAction(owner)
        val verified = DocumentObservationVerifier.complete(result,
            read = { observeScreenLocked(waitForSettledUi = false, includeDocumentText = true) },
            check = { checkPhoneAction(owner) })
        snapshotGuard.remember(verified.screen)
        verified
    }

    override suspend fun waitForScreen(): DeviceToolResult = AutomationTimings.measure("device.wait") { uiActionMutex.withLock {
        if (executionInBackground()) return@withLock background.observe()
        val baseline = snapshotGuard.latest()
        observeScreenLocked(waitForChangeFrom = baseline)
    } }

    override suspend fun waitForScreen(request: com.flowchat.app.domain.device.DeviceScreenWaitRequest): DeviceToolResult =
        AutomationTimings.measure("device.wait_condition") { uiActionMutex.withLock {
            if (executionInBackground()) return@withLock background.waitForCondition(request)
            val baseline = snapshotGuard.latest()
            request.condition?.regionChangedFrom?.let { id ->
                val owner = taskControl.currentTask
                val frame = baseline?.let { visualFrameGuard.current(id, it, SystemClock.elapsedRealtime(), owner) }
                return@withLock ScreenRegionWaiter({
                    checkPhoneAction(owner)
                    captureStableVisualFrameLocked(frame?.image?.region, request.condition.element, request.observationScope)
                        .also { checkPhoneAction(owner) }
                }, SystemClock::elapsedRealtime).observe(request, frame)
            }
            val accessibility = requireAccessibilityService() ?: return@withLock accessibilityUnavailable()
            snapshotGuard.remember(null); visualFrameGuard.clear()
            val result = accessibility.waitForCondition(request, baseline)
            snapshotGuard.remember(result.screen)
            result
        } }

    override suspend fun captureScreen(region: com.flowchat.app.domain.device.ScreenImageRegion?): DeviceToolResult = uiActionMutex.withLock {
        if (executionInBackground()) return@withLock background.observe(region, preserveSelector = true)
        captureStableVisualFrameLocked(region, snapshotGuard.latest()?.elementQuery, snapshotGuard.latest()?.observationScope)
    }

    override suspend fun captureForegroundScreen(): DeviceToolResult = uiActionMutex.withLock {
        captureScreenLocked(foregroundOnly = true)
    }

    override suspend fun captureScreen(region: com.flowchat.app.domain.device.ScreenImageRegion?,
        scope: com.flowchat.app.domain.device.DeviceObservationScope?): DeviceToolResult = uiActionMutex.withLock {
        if (executionInBackground()) return@withLock background.observe(region, scope = scope)
        captureStableVisualFrameLocked(region, scope = scope)
    }

    private suspend fun captureScreenLocked(region: com.flowchat.app.domain.device.ScreenImageRegion? = null,
        foregroundOnly: Boolean = false, selector: com.flowchat.app.domain.device.DeviceUiSelector? = null,
        scope: com.flowchat.app.domain.device.DeviceObservationScope? = null): DeviceToolResult {
        val owner = taskControl.currentTask
        val observed = observeScreenLocked(waitForSettledUi = false, foregroundOnly = foregroundOnly, selector = selector, scope = scope)
        val before = observed.screen
        if (!observed.success || before == null || before.sensitive) return observed.copy(
            imageErrorCode = observed.errorCode ?: "screen_unavailable")
        val accessibility = requireAccessibilityService() ?: return accessibilityUnavailable()
        return try {
            val image = accessibility.captureScreenImage(before, region) { capture ->
                floatingAssistantBridge.withDisplayCapture(capture)
            }
            val after = if (foregroundOnly) accessibility.observeScreenOnDisplay(android.view.Display.DEFAULT_DISPLAY)
                else accessibility.observeScreen(selector = selector, scope = scope)
            val result = com.flowchat.app.domain.device.ScreenCapturePolicy.complete(before, after, image)
            snapshotGuard.remember(result.screen)
            if (owner === taskControl.currentTask) visualFrameGuard.remember(result, SystemClock.elapsedRealtime(), owner)
            else visualFrameGuard.clear()
            result
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            observed.copy(imageErrorCode = "screen_capture_timeout")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            observed.copy(imageErrorCode = (error as? ScreenCaptureFailure)?.code ?: "screen_capture_failed")
        }
    }

    private suspend fun observeScreenLocked(waitForSettledUi: Boolean = true,
        waitForChangeFrom: DeviceScreenSnapshot? = null, foregroundOnly: Boolean = false,
        selector: com.flowchat.app.domain.device.DeviceUiSelector? = null,
        scope: com.flowchat.app.domain.device.DeviceObservationScope? = null,
        includeDocumentText: Boolean = false): DeviceToolResult {
        snapshotGuard.remember(null)
        visualFrameGuard.clear()
        val accessibility = requireAccessibilityService() ?: return accessibilityUnavailable()
        val screen = (if (includeDocumentText) accessibility.observeScreen(includeDocumentText = true)
            else if (foregroundOnly) accessibility.observeScreenOnDisplay(android.view.Display.DEFAULT_DISPLAY)
            else if (waitForSettledUi) accessibility.observeSettledScreen(waitForChangeFrom, selector, scope) else accessibility.observeScreen(selector = selector, scope = scope))
            ?: return DeviceToolResult(
                success = false,
                summary = "The current screen could not be read.",
                errorCode = "screen_unavailable"
            )
        snapshotGuard.remember(screen)
        return DeviceToolResult(
            success = !screen.sensitive && screen.observationErrorCode == null,
            summary = if (screen.sensitive) {
                "A sensitive screen was detected."
            } else if (screen.observationErrorCode != null) {
                "The requested observation root could not be uniquely resolved. No full-page fallback was used."
            } else {
                "Observed ${screen.elements.size} visible UI elements."
            },
            errorCode = "sensitive_screen_blocked".takeIf { screen.sensitive } ?: screen.observationErrorCode,
            screen = screen
        )
    }

    override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult =
        if (executionInBackground()) background.tap(index, longPress, screenId) else performScreenAction(
            toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiElementToolName,
            summary = if (longPress) "Long pressed UI element [$index]." else "Tapped UI element [$index].",
            expectedScreenId = screenId,
            requiresSnapshot = true,
            targetIndex = index
        ) { accessibility, screen ->
            if (screen.elements.none { it.index == index && it.enabled }) false
            else {
                val owner = taskControl.currentTask
                accessibility.tapUiElement(index, longPress, screen) { checkPhoneAction(owner) }
            }
        }

    override suspend fun tapLocatedUiTarget(index: Int, target: com.flowchat.app.domain.device.DeviceUiSelector, screenId: String): DeviceToolResult =
        if (executionInBackground()) background.tapLocatedTarget(index, target, screenId) else performScreenAction(
            toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiTargetToolName,
            summary = "Tapped the freshly located exact UI target.", expectedScreenId = screenId, requiresSnapshot = true, targetIndex = index
        ) { accessibility, screen ->
            val owner = taskControl.currentTask
            accessibility.tapUiElement(index, false, screen, targetAnchor = target) { checkPhoneAction(owner) }
        }

    override suspend fun tapVisualTarget(target: VisualTapTarget): DeviceToolResult = uiActionMutex.withLock {
        if (executionInBackground()) return@withLock background.visual(target)
        val task = taskControl.currentTask
        currentCoroutineContext().ensureActive()
        checkPhoneAction(task)
        val accessibility = requireAccessibilityService() ?: return@withLock accessibilityUnavailable()
        val current = accessibility.observeScreen() ?: return@withLock DeviceToolResult(
            false, "The current screen could not be read. No action was taken.", errorCode = "screen_unavailable")
        if (current.sensitive || current.packageName == context.packageName) {
            visualFrameGuard.clear(); snapshotGuard.remember(null)
            return@withLock DeviceToolResult(false, "Visual actions are blocked on this screen. No action was taken.",
                errorCode = if (current.sensitive) "sensitive_screen_blocked" else "self_automation_blocked", screen = current)
        }
        val frame = visualFrameGuard.current(target.screenId, current, SystemClock.elapsedRealtime(), task)
            ?: return@withLock rejectVisualTarget("stale_screen",
                "The image screen_id is missing, expired or no longer current. No action was taken. Locate the target again in the fresh image.")
        val point = frame.image.visualTapPoint(target)
            ?: return@withLock rejectVisualTarget("invalid_visual_target",
                "The image point does not map to the active app window. No action was taken. Use coordinates relative to the attached image.")

        if (frame.image.needsVisualRefinement) {
            val detail = captureStableVisualFrameLocked(frame.image.visualRefinementRegion(target))
            return@withLock detail.copy(success = detail.success && detail.screenImage != null,
                errorCode = detail.errorCode ?: detail.imageErrorCode,
                visualRefinementRequired = detail.screenImage != null,
                summary = if (detail.screenImage != null) "Magnified the proposed target region. NO gesture was dispatched. Locate the target center again in this new detail image before requesting a tap."
                    else "The target detail image is unavailable. No gesture was dispatched.")
        }

        // Re-sample pixels immediately before dispatch; unchanged accessibility nodes cannot validate a Canvas target.
        val refreshed = captureStableVisualFrameLocked(frame.image.region)
        val freshImage = refreshed.screenImage
        val freshScreen = refreshed.screen
        if (!refreshed.success || freshImage == null || freshScreen == null) return@withLock refreshed.copy(
            success = false, summary = "The visual target could not be revalidated. No action was taken.",
            errorCode = refreshed.errorCode ?: refreshed.imageErrorCode ?: "visual_verification_failed")
        val comparison = try {
            AutomationTimings.measure("screen.compare_pixels") { withContext(Dispatchers.Default) { ScreenImageMatcher.compare(frame.image, freshImage, target) } }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        if (freshScreen.packageName != frame.screen.packageName || freshScreen.windowId != frame.screen.windowId ||
            comparison?.stableForTap != true) return@withLock refreshed.copy(
            success = false, errorCode = "stale_visual_target",
            summary = "The target pixels or app window changed since the image you used. No action was taken. Locate the target again in this fresh image; do not reuse the old coordinates.")

        currentCoroutineContext().ensureActive()
        checkPhoneAction(task)
        snapshotGuard.remember(null); visualFrameGuard.clear()
        val dispatchError = accessibility.tapVisualPoint(point, freshScreen, target.longPress) {
            checkPhoneAction(task)
        }
        // Always return a new frame, including after rejected/cancelled gestures, for the next model decision.
        var after = captureStableVisualFrameLocked(frame.image.region)
        val resultingScreen = after.screen
        if (resultingScreen != null && (resultingScreen.windowId != frame.screen.windowId || resultingScreen.geometry != frame.screen.geometry))
            after = captureStableVisualFrameLocked()
        if (dispatchError != null) return@withLock after.copy(success = false, errorCode = dispatchError,
            summary = "The visual gesture was rejected or cancelled. Inspect the refreshed image before choosing another action.",
            actionOutcome = if (dispatchError == "ui_action_failed") com.flowchat.app.domain.device.DeviceActionOutcome.Unverified
                else com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched)
        val difference = after.screenImage?.let { image ->
            try { AutomationTimings.measure("screen.compare_pixels") { withContext(Dispatchers.Default) { ScreenImageMatcher.compare(frame.image, image, target) } } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        }
        val unchanged = difference?.pixelsChanged == false && after.screen?.progressKey() == frame.screen.progressKey()
        val comparisonUnverified = difference == null && after.screen?.let { frame.screen.hasSameActionContent(it) } != false
        after.copy(
            success = after.success && after.screenImage != null && !unchanged && !comparisonUnverified,
            summary = if (unchanged) "The visual gesture completed, but neither the pixels nor UI state changed. Do not report task completion or repeat blindly."
                else if (comparisonUnverified) "The visual gesture completed, but its effect could not be verified. It may have taken effect; inspect the resulting state before retrying and do not repeat blindly."
                else "The ${if (target.longPress) "long-press" else "tap"} gesture completed for ${target.targetDescription.replace(Regex("\\s+"), " ")}. " +
                    if (after.screenImage == null) "The resulting image is unavailable; the task result is unverified."
                    else "Inspect the attached resulting image to verify the user's intended outcome before reporting completion or choosing the next action.",
            errorCode = if (unchanged) "ui_no_change" else if (comparisonUnverified) after.errorCode ?: after.imageErrorCode ?: "visual_verification_failed"
                else after.errorCode ?: after.imageErrorCode
                ?: "visual_verification_failed".takeIf { after.screenImage == null },
            before = frame.screen.packageName, after = after.screen?.packageName,
            visualProgressKey = if (difference?.targetOrSceneChanged == false) frame.image.visualKey else after.screenImage?.visualKey,
            actionOutcome = if (unchanged || comparisonUnverified || !after.success || after.screenImage == null)
                com.flowchat.app.domain.device.DeviceActionOutcome.Unverified else com.flowchat.app.domain.device.DeviceActionOutcome.ProgressObserved
        )
    }

    private suspend fun rejectVisualTarget(code: String, summary: String): DeviceToolResult =
        captureScreenLocked().copy(success = false, errorCode = code, summary = summary)

    private suspend fun captureStableVisualFrameLocked(region: com.flowchat.app.domain.device.ScreenImageRegion? = null,
        selector: com.flowchat.app.domain.device.DeviceUiSelector? = null,
        scope: com.flowchat.app.domain.device.DeviceObservationScope? = null): DeviceToolResult {
        val task = taskControl.currentTask
        var result = captureScreenLocked(region, selector = selector, scope = scope)
        repeat(2) { attempt ->
            if (!com.flowchat.app.domain.device.ScreenCaptureReadiness.shouldRetry(result)) return result
            currentCoroutineContext().ensureActive()
            checkPhoneAction(task)
            delay(180L * (attempt + 1))
            result = captureScreenLocked(region, selector = selector, scope = scope)
        }
        return result
    }

    override suspend fun inputText(
        index: Int,
        text: String,
        clearExisting: Boolean,
        screenId: String?
    ): DeviceToolResult = editTextInternal(com.flowchat.app.domain.device.DeviceTextEditRequest(index, text,
        if (clearExisting) com.flowchat.app.domain.device.DeviceTextEditMode.Replace else com.flowchat.app.domain.device.DeviceTextEditMode.Append), screenId)

    override suspend fun editText(request: com.flowchat.app.domain.device.DeviceTextEditRequest, screenId: String): DeviceToolResult =
        editTextInternal(request, screenId)

    override suspend fun pasteFocusedText(text: String, screenId: String, replaceExisting: Boolean): DeviceToolResult {
        if (executionInBackground()) return backgroundUnsupported()
        if (text.isEmpty() || text.length > com.flowchat.app.domain.device.DeviceTextEditing.MaxLength) {
            return DeviceToolResult(
                success = false,
                summary = "Text must be 1..${com.flowchat.app.domain.device.DeviceTextEditing.MaxLength} UTF-16 units. No text was pasted.",
                errorCode = "invalid_text_argument",
                actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
            )
        }
        return uiActionMutex.withLock {
            val accessibility = requireAccessibilityService() ?: return@withLock accessibilityUnavailable()
            val task = taskControl.currentTask
            checkExecutionTarget()
            checkPhoneAction(task)
            val current = accessibility.observeScreen() ?: return@withLock DeviceToolResult(
                success = false,
                summary = "The current screen could not be read. No text was pasted.",
                errorCode = "screen_unavailable",
                actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
            )
            if (current.sensitive || current.packageName == context.packageName) {
                snapshotGuard.remember(null)
                visualFrameGuard.clear()
                return@withLock DeviceToolResult(
                    success = false,
                    summary = if (current.sensitive) "Device actions are blocked on sensitive screens." else "Floryx will not automate its own interface.",
                    errorCode = if (current.sensitive) "sensitive_screen_blocked" else "self_automation_blocked",
                    screen = current,
                    actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
                )
            }
            val freshVisual = visualFrameGuard.current(screenId, current, SystemClock.elapsedRealtime(), task) != null
            val freshSnapshot = snapshotGuard.matches(screenId, current, null)
            if (!freshVisual && !freshSnapshot) {
                val refreshed = if (Build.VERSION.SDK_INT >= 30) captureStableVisualFrameLocked() else observeScreenLocked()
                return@withLock refreshed.copy(
                    success = false,
                    errorCode = "stale_screen",
                    summary = "The screen changed or the screen_id is missing/expired. No text was pasted. Inspect the fresh screen/image and tap to focus the target input first.",
                    actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
                )
            }
            val visualBefore = if (Build.VERSION.SDK_INT >= 30) captureStableVisualFrameLocked() else null
            visualBefore?.screen?.let { fresh ->
                if (fresh.sensitive || visualBefore.errorCode == "sensitive_screen_blocked" || fresh.packageName == context.packageName) {
                    snapshotGuard.remember(null)
                    visualFrameGuard.clear()
                    return@withLock visualBefore.copy(
                        success = false,
                        errorCode = if (fresh.sensitive || visualBefore.errorCode == "sensitive_screen_blocked") "sensitive_screen_blocked" else "self_automation_blocked",
                        summary = "Clipboard paste is blocked on this screen. No text was pasted.",
                        actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
                    )
                }
            }
            val clipboard = accessibility.getSystemService(android.content.ClipboardManager::class.java)
                ?: context.getSystemService(android.content.ClipboardManager::class.java)
                ?: return@withLock DeviceToolResult(
                    success = false,
                    summary = "Clipboard service is unavailable. No text was pasted.",
                    errorCode = "clipboard_unavailable",
                    actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
                )
            val previousClip = runCatching { withContext(Dispatchers.Main.immediate) { clipboard.primaryClip } }.getOrNull()
            val copied = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Floryx", text))
                }
            }.isSuccess
            if (!copied) {
                return@withLock DeviceToolResult(
                    success = false,
                    summary = "Could not copy text to the system clipboard. No text was pasted.",
                    errorCode = "clipboard_write_failed",
                    actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
                )
            }
            suspend fun restoreClipboardBestEffort() {
                if (previousClip != null) {
                    runCatching { withContext(Dispatchers.Main.immediate) { clipboard.setPrimaryClip(previousClip) } }
                }
            }
            delay(80L)
            val accessibilityPasted = try {
                accessibility.pasteIntoFocusedInput(current, replaceExisting) { checkPhoneAction(task) }
            } catch (rejected: DeviceDispatchRejected) {
                restoreClipboardBestEffort()
                val refreshed = accessibility.observeScreen()
                snapshotGuard.remember(refreshed)
                return@withLock DeviceToolResult(
                    success = false,
                    summary = "Clipboard paste was not dispatched (${rejected.code}).",
                    errorCode = rejected.code,
                    screen = refreshed,
                    actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
                )
            }
            if (!accessibilityPasted) {
                if (!connectionState.value.isConnected) {
                    restoreClipboardBestEffort()
                    return@withLock DeviceToolResult(
                        success = false,
                        summary = "Focused input does not expose accessibility paste and Shizuku/Root is not connected.",
                        errorCode = "ui_paste_unsupported",
                        screen = current,
                        actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
                    )
                }
                val keyResult = callService { it.pasteFocusedText(replaceExisting) }
                if (!keyResult.success) {
                    restoreClipboardBestEffort()
                    val refreshed = if (Build.VERSION.SDK_INT >= 30) captureStableVisualFrameLocked() else observeScreenLocked()
                    return@withLock refreshed.copy(
                        success = false,
                        errorCode = keyResult.errorCode ?: "paste_keyevent_failed",
                        summary = keyResult.summary,
                        actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.Unverified
                    )
                }
            }
            snapshotGuard.remember(null)
            visualFrameGuard.clear()
            delay(220L)
            val after = if (Build.VERSION.SDK_INT >= 30) captureStableVisualFrameLocked() else observeScreenLocked()
            restoreClipboardBestEffort()
            if (after.screen?.sensitive == true) {
                return@withLock DeviceToolResult(
                    success = false,
                    summary = "The action completed on a sensitive screen. Further automation is blocked.",
                    errorCode = "sensitive_screen_blocked",
                    screen = after.screen
                )
            }
            val oldImage = visualBefore?.screenImage
            val newImage = after.screenImage
            val pixelsChanged = if (oldImage != null && newImage != null) {
                val sameWindow = oldImage.geometry == newImage.geometry &&
                    oldImage.sourceWidth == newImage.sourceWidth &&
                    oldImage.sourceHeight == newImage.sourceHeight
                if (!sameWindow) true else try {
                    AutomationTimings.measure("screen.compare_pixels") {
                        withContext(Dispatchers.Default) {
                            ScreenImageMatcher.compare(
                                oldImage,
                                newImage,
                                VisualTapTarget(oldImage.screenId, 500, 500, "paste result")
                            ).pixelsChanged
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            } else false
            val uiChanged = after.screen != null && after.screen.progressKey() != current.progressKey()
            val progressObserved = pixelsChanged || uiChanged
            after.copy(
                success = true,
                summary = "Dispatched clipboard paste to the focused input. Inspect the attached image/screen to verify the visible text before continuing or sending.",
                before = current.packageName,
                after = after.screen?.packageName,
                actionOutcome = if (progressObserved) com.flowchat.app.domain.device.DeviceActionOutcome.ProgressObserved
                else com.flowchat.app.domain.device.DeviceActionOutcome.Unverified
            )
        }
    }

    private suspend fun editTextInternal(request: com.flowchat.app.domain.device.DeviceTextEditRequest, screenId: String?): DeviceToolResult =
        if (executionInBackground()) background.input(request, screenId) else performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.InputTextToolName,
        summary = "Text editing was dispatched to UI element [${request.index}].",
        expectedScreenId = screenId,
        requiresSnapshot = true,
        targetIndex = request.index,
        textInput = DeviceActionOutcomePolicy.TextInput(request.index, request.text,
            request.mode == com.flowchat.app.domain.device.DeviceTextEditMode.Replace, request.mode)
    ) { accessibility, screen ->
        val owner = taskControl.currentTask
        accessibility.editText(request, screen) { checkPhoneAction(owner) }
    }

    override suspend fun setTextSelection(selection: com.flowchat.app.domain.device.DeviceTextSelection, screenId: String): DeviceToolResult =
        if (executionInBackground()) background.select(selection, screenId) else performScreenAction(
            toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.SetTextSelectionToolName,
            summary = "Text selection was dispatched to UI element [${selection.index}].",
            expectedScreenId = screenId, requiresSnapshot = true, targetIndex = selection.index, selection = selection
        ) { service, screen ->
            val owner = taskControl.currentTask
            service.setTextSelection(selection, screen) { checkPhoneAction(owner) }
        }

    // Submitting protects the target input and static UI while allowing live clocks and known volatile widgets.
    override suspend fun performEditorAction(index: Int, expectedLabel: String, screenId: String): DeviceToolResult =
        if (executionInBackground()) background.editorAction(index, expectedLabel, screenId) else performScreenAction(
            toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.PerformEditorActionToolName,
            summary = "The declared editor action was dispatched once. Its business result still needs verification.",
            expectedScreenId = screenId, requiresSnapshot = true, editorActionIndex = index
        ) { service, screen ->
            val owner = taskControl.currentTask
            service.performEditorAction(index, expectedLabel, screen) { checkPhoneAction(owner) }
        }

    override suspend fun scrollContainer(index: Int, direction: com.flowchat.app.domain.device.DeviceScrollDirection, screenId: String): DeviceToolResult =
        if (executionInBackground()) background.scrollContainer(index, direction, screenId) else performScreenAction(
            toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.ScrollContainerToolName,
            summary = "The selected container scroll was dispatched.", expectedScreenId = screenId,
            requiresSnapshot = true, scrollIndex = index
        ) { service, screen ->
            val owner = taskControl.currentTask
            service.scrollContainer(index, direction, screen) { checkPhoneAction(owner) }
        }

    override suspend fun anchoredGesture(request: com.flowchat.app.domain.device.DeviceAnchoredGesture, screenId: String): DeviceToolResult =
        if (executionInBackground()) background.anchoredGesture(request, screenId) else performScreenAction(
            toolName = if (request is com.flowchat.app.domain.device.DeviceAnchoredGesture.Drag)
                com.flowchat.app.domain.tools.AgentToolDefinitions.DragUiElementToolName else com.flowchat.app.domain.tools.AgentToolDefinitions.DoubleTapUiElementToolName,
            summary = "The anchored gesture was dispatched. Verify the intended result before continuing.",
            expectedScreenId = screenId, requiresSnapshot = true
        ) { service, screen ->
            val owner = taskControl.currentTask
            service.anchoredGesture(request, screen) { checkPhoneAction(owner) }
        }

    override suspend fun adjustRange(request: com.flowchat.app.domain.device.DeviceRangeAdjustment, screenId: String): DeviceToolResult =
        if (executionInBackground()) background.adjustRange(request, screenId) else performScreenAction(
            toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.AdjustRangeToolName,
            summary = "The range adjustment was dispatched.", expectedScreenId = screenId, requiresSnapshot = true,
            targetIndex = request.index, rangeAdjustment = request
        ) { service, screen ->
            val owner = taskControl.currentTask
            service.adjustRange(request, screen) { checkPhoneAction(owner) }
        }

    private val adaptiveScroll = AdaptiveScrollController(SystemClock::elapsedRealtime)

    override suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int): DeviceToolResult =
        swipeScreen(DeviceScrollRequest(direction, distancePercent))

    override suspend fun scrollContainer(request: DeviceContainerScrollRequest): DeviceToolResult =
        if (request.controlled) swipeScreen(request.gesture()) else scrollContainer(request.index, request.direction, request.screenId)

    override suspend fun swipeScreen(request: DeviceScrollRequest): DeviceToolResult {
        if (executionInBackground()) return background.swipe(request)
        return uiActionMutex.withLock {
            val owner = taskControl.currentTask
            checkPhoneAction(owner)
            val service = requireAccessibilityService() ?: return@withLock accessibilityUnavailable()
            val scope = snapshotGuard.latest()?.observationScope
            val selector = snapshotGuard.latest()?.elementQuery
            val before = service.observeScreen(forceRefresh = true, selector = selector, scope = scope)
                ?: return@withLock DeviceToolResult(false, "The current screen could not be read.", errorCode = "screen_unavailable")
            if (before.sensitive || before.packageName == context.packageName) return@withLock DeviceToolResult(false,
                "Scroll actions are blocked on this screen.", screen = before,
                errorCode = if (before.sensitive) "sensitive_screen_blocked" else "self_automation_blocked",
                actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched)
            if (request.screenId != null && !snapshotGuard.matchesScroll(request.screenId, before, request)) {
                snapshotGuard.remember(before); visualFrameGuard.clear()
                return@withLock DeviceToolResult(false, "The screen changed. Choose the container again from this fresh screen.",
                    screen = before, errorCode = "stale_screen", actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched)
            }
            visualFrameGuard.clear()
            val originalContainer = com.flowchat.app.domain.device.DeviceAdaptiveScroll.area(before, request)?.container
            var result = adaptiveScroll.scroll(before, request, owner?.id,
                android.view.ViewConfiguration.get(context).scaledTouchSlop + 8,
                read = { service.observeScreen(forceRefresh = true, selector = selector, scope = scope) },
                capture = { captureStableVisualFrameLocked(selector = selector, scope = scope) },
                dispatch = { expected, path, duration ->
                    snapshotGuard.remember(null); visualFrameGuard.clear()
                    service.controlledScroll(expected, path, duration, originalContainer, request) { checkPhoneAction(owner) }
                }, check = { checkPhoneAction(owner) })
            val feedback = result.scrollFeedback
            if (originalContainer != null && result.actionOutcome == DeviceActionOutcome.Unverified &&
                feedback?.targetState == "moving" && feedback.actions == 1) {
                val followupStarted = SystemClock.elapsedRealtime()
                val checked = ScrollObservationVerifier.complete(before, result.copy(success = result.screen != null),
                    originalContainer.index, "One controlled scroll was acknowledged.", true,
                    read = {
                        val fresh = service.observeScreen(forceRefresh = true, selector = selector, scope = scope)
                        DeviceToolResult(fresh != null, "Read the current controlled-scroll container.", screen = fresh)
                    }, check = { checkPhoneAction(owner) }, requireStableResult = true)
                val settled = checked.actionOutcome in setOf(DeviceActionOutcome.StateObserved, DeviceActionOutcome.ProgressObserved)
                val finalMotion = if (settled) checked.screen?.let { current ->
                    DeviceAdaptiveScroll.area(before, request)?.let { area -> DeviceAdaptiveScroll.measure(before, current, area, request.vertical) }
                } else feedback.motion
                result = checked.copy(scrollFeedback = feedback.copy(settled = settled,
                    motion = finalMotion,
                    targetState = if (settled) "state_observed" else feedback.targetState,
                    elapsedMillis = feedback.elapsedMillis + SystemClock.elapsedRealtime() - followupStarted))
            }
            snapshotGuard.remember(result.screen)
            if (result.screenImage != null) visualFrameGuard.remember(result, SystemClock.elapsedRealtime(), owner)
            result
        }
    }

    override suspend fun pressBack(): DeviceToolResult = if (executionInBackground()) background.back() else performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.PressBackToolName,
        summary = "Pressed Back.",
        allowFlowChat = true
    ) { accessibility, _ -> accessibility.pressBack() }

    override suspend fun pressHome(): DeviceToolResult = if (executionInBackground()) background.home() else performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.PressHomeToolName,
        summary = "Opened the Home screen.",
        allowFlowChat = true
    ) { accessibility, _ -> accessibility.pressHome() }

    private suspend fun performScreenAction(
        toolName: String,
        summary: String,
        allowFlowChat: Boolean = false,
        expectedScreenId: String? = null,
        requiresSnapshot: Boolean = false,
        targetIndex: Int? = null,
        textInput: DeviceActionOutcomePolicy.TextInput? = null,
        selection: com.flowchat.app.domain.device.DeviceTextSelection? = null,
        scrollIndex: Int? = null,
        rangeAdjustment: com.flowchat.app.domain.device.DeviceRangeAdjustment? = null,
        editorActionIndex: Int? = null,
        action: suspend (FlowChatAccessibilityService, DeviceScreenSnapshot) -> Boolean
    ): DeviceToolResult = uiActionMutex.withLock {
        val task = taskControl.currentTask
        checkPhoneAction(task)
        visualFrameGuard.clear()
        performScreenActionLocked(toolName, summary, allowFlowChat, expectedScreenId, requiresSnapshot, targetIndex, textInput, selection, scrollIndex, rangeAdjustment, editorActionIndex) { service, screen ->
            currentCoroutineContext().ensureActive()
            checkPhoneAction(task)
            AutomationTimings.measure("device.dispatch.$toolName") { action(service, screen) }
        }
    }

    private suspend fun performScreenActionLocked(
        toolName: String,
        summary: String,
        allowFlowChat: Boolean,
        expectedScreenId: String?,
        requiresSnapshot: Boolean,
        targetIndex: Int?,
        textInput: DeviceActionOutcomePolicy.TextInput?,
        selection: com.flowchat.app.domain.device.DeviceTextSelection?,
        scrollIndex: Int?,
        rangeAdjustment: com.flowchat.app.domain.device.DeviceRangeAdjustment?,
        editorActionIndex: Int?,
        action: suspend (FlowChatAccessibilityService, DeviceScreenSnapshot) -> Boolean
    ): DeviceToolResult {
        val scrollOwner = taskControl.currentTask
        val accessibility = requireAccessibilityService() ?: return accessibilityUnavailable()
        val before = accessibility.observeScreen(forceRefresh = true, selector = snapshotGuard.latest()?.elementQuery,
            scope = snapshotGuard.latest()?.observationScope)
            ?: return DeviceToolResult(false, "The current screen could not be read.", errorCode = "screen_unavailable")
        if (!DeviceActionPolicy.allowsOnScreen(toolName, before)) {
            snapshotGuard.remember(null)
            return DeviceToolResult(
                success = false,
                summary = "Device actions are blocked on sensitive screens.",
                errorCode = "sensitive_screen_blocked",
                screen = before
            )
        }
        if (!allowFlowChat && before.packageName == context.packageName) {
            snapshotGuard.remember(null)
            return DeviceToolResult(
                success = false,
                summary = "Floryx will not automate its own interface.",
                errorCode = "self_automation_blocked"
            )
        }
        val editorVolatileFilter = editorActionIndex?.let { snapshotGuard.captureVolatileFilter(before, it) }
        val snapshotMatches = if (editorActionIndex != null) {
            snapshotGuard.matchesEditorAction(expectedScreenId, before, editorActionIndex)
        } else {
            snapshotGuard.matches(expectedScreenId, before, targetIndex)
        }
        if (requiresSnapshot && !snapshotMatches) {
            snapshotGuard.remember(before)
            return DeviceToolResult(
                false,
                "The screen changed or the screen_id is missing/expired. No action was taken. Choose a target from this fresh screen and use its screen_id.",
                errorCode = "stale_screen",
                screen = before,
                actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched
            )
        }
        val isSwipe = toolName == com.flowchat.app.domain.tools.AgentToolDefinitions.SwipeScreenToolName
        // Ordinary text lists can prove progress through their controls. Canvas/video pagers need pixels.
        val verifySwipePixels = isSwipe && (before.elements.none { it.scrollable } ||
            before.elements.none { it.displayText.isNotBlank() })
        val visualBefore = if (verifySwipePixels && Build.VERSION.SDK_INT >= 30) captureStableVisualFrameLocked() else null
        visualBefore?.screen?.let { fresh ->
            if (fresh.sensitive || fresh.packageName != before.packageName || fresh.windowId != before.windowId || fresh.geometry != before.geometry)
                return visualBefore.copy(success = false, errorCode = if (fresh.sensitive) "sensitive_screen_blocked" else "stale_screen",
                    summary = "The app window changed while preparing the swipe. No gesture was dispatched; inspect the refreshed screen.",
                    actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched)
        }
        // Independent before/after window reads can prove that a dispatched tap left
        // its pane. They never widen target selection or authorize a second dispatch.
        val navigationBefore = if (before.observationScope != null && toolName in setOf(
                com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiTargetToolName,
                com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiElementToolName))
            accessibility.observeScreen(selector = before.elementQuery, scope = before.observationScope)
                ?.takeIf { com.flowchat.app.domain.device.DevicePageTransitionPolicy.sourceMatches(before, it) }
            else null
        snapshotGuard.remember(null)
        visualFrameGuard.clear()
        val accepted = try {
            action(accessibility, before)
        } catch (error: DeviceDispatchRejected) {
            val fresh = accessibility.observeScreen(selector = before.elementQuery, scope = before.observationScope)
            snapshotGuard.remember(fresh)
            val detail = if (error.code == "ui_input_text_unreadable") {
                "The action was not dispatched (${error.code}). This editable control does not expose readable text to accessibility (selection exceeds text_length_utf16), so input_text cannot verify edits here. Tap the on-screen buttons or controls instead."
            } else {
                "The action was not dispatched (${error.code}). Refresh the target, draft, focus and available actions before continuing."
            }
            return DeviceToolResult(false, detail,
                errorCode = error.code, screen = fresh, actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.NotDispatched)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("DeviceAction", "UI dispatch failed for $toolName", error)
            false
        }
        if (!accepted) {
            val refreshed = accessibility.observeSettledScreen()
            snapshotGuard.remember(refreshed)
            return DeviceToolResult(
                success = false,
                summary = "The UI action was rejected or could not be confirmed. It may have taken effect; inspect the refreshed state before retrying and do not repeat blindly.",
                errorCode = "ui_action_failed",
                screen = refreshed,
                actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.Unverified
            )
        }
        val after = accessibility.observeSettledScreen(waitForChangeFrom = before,
            selector = before.elementQuery.takeIf { before.observationScope != null }, scope = before.observationScope)
        snapshotGuard.remember(after)
        if (navigationBefore != null && com.flowchat.app.domain.device.DevicePageTransitionPolicy.needsWindowObservation(before, after)) {
            val navigation = PageTransitionObservationVerifier.complete(before, navigationBefore,
                DeviceToolResult(after != null, "Observed the source-root departure.", screen = after), true,
                readDeparture = {
                    val screen = accessibility.observeSettledScreen(selector = before.elementQuery, scope = before.observationScope)
                    snapshotGuard.remember(screen)
                    DeviceToolResult(screen != null, "Read the source-root departure again.", screen = screen)
                }, readDestination = {
                    val screen = accessibility.observeSettledScreen()
                    snapshotGuard.remember(screen)
                    DeviceToolResult(screen != null, "Observed the destination window.", screen = screen)
                }, check = { checkPhoneAction(scrollOwner) })
            snapshotGuard.remember(navigation.screen)
            return navigation
        }
        if (after?.sensitive == true) return DeviceToolResult(false,
            "The action completed on a sensitive screen. Further automation is blocked.", errorCode = "sensitive_screen_blocked", screen = after)
        if (scrollIndex != null) return ScrollObservationVerifier.complete(before,
            DeviceToolResult(after != null, summary, screen = after), scrollIndex, summary, true,
            read = {
                val fresh = accessibility.observeScreen(forceRefresh = true,
                    selector = before.elementQuery.takeIf { before.observationScope != null }, scope = before.observationScope)
                snapshotGuard.remember(fresh)
                DeviceToolResult(fresh != null, "Read the current semantic scroll container.", screen = fresh)
            }, check = { checkPhoneAction(scrollOwner) })
        if (isSwipe && visualBefore?.screenImage != null) {
            val observedAfter = captureStableVisualFrameLocked()
            val image = observedAfter.screenImage
            if (!observedAfter.success || image == null) return observedAfter.copy(success = false,
                errorCode = observedAfter.errorCode ?: observedAfter.imageErrorCode ?: "visual_verification_failed",
                summary = "The swipe gesture completed, but the resulting image is unavailable. Its effect is unverified; do not repeat blindly.")
            val oldImage = visualBefore.screenImage
            val sameWindow = oldImage.geometry == image.geometry && oldImage.sourceWidth == image.sourceWidth && oldImage.sourceHeight == image.sourceHeight
            val pixelsChanged = if (!sameWindow) true else try {
                AutomationTimings.measure("screen.compare_pixels") { withContext(Dispatchers.Default) {
                    ScreenImageMatcher.compare(oldImage, image, VisualTapTarget(oldImage.screenId, 500, 500, "swipe result")).pixelsChanged
                } }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { return observedAfter.copy(success = false, errorCode = "visual_verification_failed",
                summary = "The swipe gesture completed, but pixel comparison failed. Inspect the returned image before continuing.") }
            val unchanged = !pixelsChanged && before.progressKey() == observedAfter.screen?.progressKey()
            return observedAfter.copy(success = !unchanged, errorCode = if (unchanged) "ui_no_change" else null,
                actionOutcome = if (unchanged) com.flowchat.app.domain.device.DeviceActionOutcome.Unverified else com.flowchat.app.domain.device.DeviceActionOutcome.ProgressObserved,
                summary = if (unchanged) "The swipe gesture completed, but neither pixels nor readable content changed. Do not repeat blindly."
                    else "The swipe gesture completed and the resulting screen changed. Inspect the returned image to verify the intended result.",
                before = before.packageName, after = observedAfter.screen?.packageName)
        }
        if (isSwipe && after != null && before.progressKey() == after.progressKey()) {
            return DeviceToolResult(
                success = false,
                summary = "The swipe gesture completed, but readable content is unchanged and visual progress was not verified. Observe an image before choosing another action; do not repeat blindly.",
                errorCode = "ui_change_unverified",
                screen = after,
                actionOutcome = com.flowchat.app.domain.device.DeviceActionOutcome.Unverified
            )
        }
        val suppressVolatileOnlyProgress = editorVolatileFilter != null && after != null &&
            editorVolatileFilter.matchesOnlyTelemetryOrVolatile(before, after)
        return DeviceActionOutcomePolicy.complete(before,
            DeviceToolResult(success = after != null, summary = summary, screen = after), summary, textInput,
            selection = selection, scrollIndex = scrollIndex, rangeAdjustment = rangeAdjustment,
            ignoreUiChange = suppressVolatileOnlyProgress)
    }

    private fun requireAccessibilityService(): FlowChatAccessibilityService? {
        rootConnection.requireRequestCleanupVerified()
        safetyMonitor.check()
        val revision = safetyState.value.revision
        val owner = taskControl.currentTask
        if (snapshotSafetyRevision != revision || snapshotOwner !== owner) {
            snapshotGuard.remember(null)
            visualFrameGuard.clear()
            snapshotSafetyRevision = revision
            snapshotOwner = owner
        }
        refreshAccessibilityConnection()
        return FlowChatAccessibilityService.current().takeIf { mutableAccessibilityState.value.isConnected }
    }

    private fun accessibilityUnavailable() = DeviceToolResult(
        success = false,
        summary = "Enable Floryx in Android Accessibility settings first.",
        errorCode = "accessibility_unavailable"
    )

    private suspend fun inspectConnection(): ShizukuConnectionState {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return ShizukuConnectionState(
                status = if (isShizukuInstalled()) {
                    ShizukuConnectionStatus.NotRunning
                } else {
                    ShizukuConnectionStatus.NotInstalled
                }
            )
        }
        return runCatching {
            when {
                Shizuku.isPreV11() -> ShizukuConnectionState(ShizukuConnectionStatus.Unsupported)
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    ShizukuConnectionState(ShizukuConnectionStatus.Connecting)
                Shizuku.shouldShowRequestPermissionRationale() ->
                    ShizukuConnectionState(ShizukuConnectionStatus.PermissionDenied)
                else -> ShizukuConnectionState(ShizukuConnectionStatus.PermissionRequired)
            }
        }.getOrElse(::errorState)
    }

    private fun bindService() {
        if (isRootMode) return
        if (hasLiveUserService()) {
            scope.launch { probeConnectedService() }
            return
        }
        service = null
        if (!binding.compareAndSet(false, true)) return
        mutableConnectionState.value = ShizukuConnectionState(ShizukuConnectionStatus.Connecting)
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }.onFailure { error ->
            binding.set(false)
            mutableConnectionState.value = errorState(error)
        }
    }

    private suspend fun probeConnectedService() {
        if (isRootMode) return
        val connectedService = service ?: return
        runCatching {
            val capabilities = connectedService.probeCapabilities()
                .split(',')
                .mapNotNull { name -> DeviceCapability.entries.firstOrNull { it.name == name } }
                .toSet()
            val uid = Shizuku.getUid()
            if (isRootMode || service !== connectedService || !hasLiveUserService()) return
            mutableConnectionState.value = ShizukuConnectionState(
                status = if (uid == 0) {
                    ShizukuConnectionStatus.ConnectedRoot
                } else {
                    ShizukuConnectionStatus.ConnectedAdb
                },
                lastVerifiedAtMillis = System.currentTimeMillis(),
                capabilities = capabilities + if (mutableAccessibilityState.value.isConnected) {
                    AccessibilityCapabilities
                } else {
                    emptySet()
                }
            )
        }.onFailure { error ->
            if (!isRootMode && service === connectedService) {
                safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
                service = null
                mutableConnectionState.value = errorState(error)
            }
        }
    }

    private fun hasLiveUserService(): Boolean {
        val connected = service ?: return false
        if (!connected.asBinder().isBinderAlive) {
            if (service === connected) {
                service = null
                safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
            }
            return false
        }
        val authorized = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        if (!authorized) {
            service = null
            safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
        }
        return authorized
    }

    override suspend fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): CommandSnapshot =
        if (executionInBackground()) error("Android shell commands are disabled during background execution. Use the display-specific device tools.")
        else callCommandService(null) { it.startShellCommand(command, workingDirectory, timeoutSeconds) }

    override suspend fun readShellCommand(commandId: String): CommandSnapshot =
        callCommandService(commandId) { it.readShellCommand(commandId) }

    override suspend fun cancelShellCommand(commandId: String): CommandSnapshot =
        callCommandService(commandId) { it.cancelShellCommand(commandId) }

    private suspend fun callCommandService(commandId: String?, operation: (IFlowChatShizukuService) -> String): CommandSnapshot {
        // Reads/cancellation keep following the command ID so cleanup survives a target change.
        val capturedTarget = currentCoroutineContext()[DeviceExecutionTarget]
        val startGuard = if (commandId == null)
            DeviceCommandStartGuard(currentCoroutineContext(), appSettingsStore, taskControl) {
                validateExecutionTarget(capturedTarget)
            } else null
        return withContext(Dispatchers.IO) {
            val useRoot = commandId?.startsWith("root-") ?: requireNotNull(startGuard).useRoot
            currentCoroutineContext().ensureActive()
            startGuard?.checkCanDispatch()
            if (useRoot) return@withContext rootConnection.withShellCommand(commandId) { connected ->
                // Root callbacks run in the connection's independent operation scope.
                startGuard?.checkCanDispatch()
                operation(connected)
            }
            val connected = commandId?.let(commandServices::get) ?: awaitService()
            try {
                startGuard?.checkCanDispatch()
                commandJson.decodeFromString<CommandSnapshot>(operation(connected)).also { snapshot ->
                    if (snapshot.isRunning) commandServices[snapshot.id] = connected else commandServices.remove(snapshot.id)
                }
            } catch (error: Exception) {
                commandId?.let(commandServices::remove)
                if (error is android.os.RemoteException || error is SecurityException || !connected.asBinder().isBinderAlive) {
                    safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
                }
                throw error
            }
        }
    }

    private suspend fun callService(
        operation: (IFlowChatShizukuService) -> String
    ): DeviceToolResult = withContext(Dispatchers.IO) {
        val target = currentCoroutineContext()[DeviceExecutionTarget]
        val task = taskControl.currentTask
        val dispatchContext = currentCoroutineContext()
        validateExecutionTarget(target)
        val root = target?.let { it.connectionMode == PowerConnectionMode.Root } ?: isRootMode
        runCatching {
            currentCoroutineContext().ensureActive()
            check(isRootMode == root) { "Power connection changed. Retry the operation." }
            val raw = if (root) rootConnection.withService(ownerId = task?.id) { connected ->
                validateExecutionTarget(target)
                checkPhoneAction(task)
                dispatchContext.ensureActive()
                check(isRootMode) { "Power connection changed. Retry the operation." }
                operation(connected)
            } else {
                val connected = awaitService()
                currentCoroutineContext().ensureActive()
                validateExecutionTarget(target)
                checkPhoneAction(task)
                check(!isRootMode) { "Power connection changed. Retry the operation." }
                operation(connected)
            }
            parseResult(raw)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (root) rootConnection.invalidate()
            else if (error is android.os.RemoteException || error is SecurityException) {
                safetyMonitor.boundary.invalidate(DeviceSafetyReason.PowerConnectionLost)
            }
            refreshConnection()
            DeviceToolResult(
                success = false,
                summary = error.message ?: "Device operation failed.",
                errorCode = if (root) "root_operation_failed" else "shizuku_operation_failed"
            )
        }
    }

    private suspend fun awaitService(): IFlowChatShizukuService {
        if (hasLiveUserService()) service?.let { return it }
        refreshConnection()
        withTimeout(ServiceConnectionTimeoutMillis) {
            connectionState.filter { it.isConnected || it.status in TerminalConnectionStates }.first()
        }
        check(hasLiveUserService()) { connectionState.value.detail ?: "Shizuku is not connected." }
        return requireNotNull(service)
    }

    private fun parseResult(raw: String): DeviceToolResult {
        val json = JSONObject(raw)
        return DeviceToolResult(
            success = json.optBoolean("success", false),
            summary = json.optString("summary", "Shizuku operation finished."),
            before = json.optString("before").takeIf { it.isNotBlank() },
            after = json.optString("after").takeIf { it.isNotBlank() },
            reversible = json.optBoolean("reversible", false),
            errorCode = json.optString("errorCode").takeIf { it.isNotBlank() },
            actionOutcome = json.optString("actionOutcome").takeIf { it.isNotBlank() }?.let { value ->
                com.flowchat.app.domain.device.DeviceActionOutcome.entries.firstOrNull { it.name == value }
            }
        )
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                ShizukuPackageName,
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(ShizukuPackageName, 0)
        }
        true
    }.getOrDefault(false)

    private fun isAccessibilityServiceEnabled(): Boolean? {
        val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
        // A readable user choice takes precedence over a manager list that may lag revocation.
        configuredAccessibilityService()?.let { return it }
        val listed = runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any { enabled ->
                    val serviceInfo = enabled.resolveInfo?.serviceInfo
                    val component = serviceInfo?.let { ComponentName(it.packageName, it.name) }
                    component == expected || ComponentName.unflattenFromString(enabled.id.orEmpty()) == expected
                }
        }.getOrNull()
        if (listed == true) return true
        // An enabled service can temporarily be absent from the manager list while rebinding.
        // A missing/unreadable setting is unknown, rather than proof that permission was revoked.
        return configuredAccessibilityService()
    }

    private fun configuredAccessibilityService(): Boolean? {
        val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.any { it == expected }
        }.getOrNull()
    }

    private fun resolveLaunchableApp(query: String): ResolvedDeviceApp {
        val normalizedQuery = query.normalizedAppKey()
        require(normalizedQuery.isNotBlank()) { "App name is required." }
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            ResolvedDeviceApp(
                label = info.loadLabel(context.packageManager).toString().ifBlank { activity.packageName },
                packageName = activity.packageName
            )
        }.distinctBy { it.packageName }
        val exact = resolved.filter { app ->
            app.label.normalizedAppKey() == normalizedQuery ||
                app.packageName.normalizedAppKey() == normalizedQuery
        }
        if (exact.size == 1) return exact.single()
        val partial = resolved.filter { app ->
            app.label.normalizedAppKey().contains(normalizedQuery) ||
                app.packageName.normalizedAppKey().contains(normalizedQuery)
        }
        return when (partial.size) {
            1 -> partial.single()
            0 -> throw IllegalArgumentException("No installed app matches \"$query\".")
            else -> throw IllegalArgumentException(
                "Multiple apps match \"$query\": ${partial.take(5).joinToString { it.label }}."
            )
        }
    }

    private fun String.normalizedAppKey(): String =
        lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun errorState(error: Throwable) = ShizukuConnectionState(
        status = ShizukuConnectionStatus.Error,
        detail = error.message ?: "Shizuku connection failed."
    )

    private data class ResolvedDeviceApp(val label: String, val packageName: String)

    private companion object {
        val commandJson = Json { ignoreUnknownKeys = true }
        const val ShizukuPackageName = "moe.shizuku.privileged.api"
        const val PermissionRequestCode = 6201
        const val ServiceConnectionTimeoutMillis = 5_000L
        const val PowerModeRefreshDelayMillis = 350L
        const val AccessibilityRebindWaitMillis = 4_000L
        val AccessibilityCapabilities = setOf(
            DeviceCapability.ScreenObservation,
            DeviceCapability.UiElementAction,
            DeviceCapability.TextInput,
            DeviceCapability.ScreenSwipe,
            DeviceCapability.Navigation
        ) + if (Build.VERSION.SDK_INT >= 30) setOf(DeviceCapability.VisualTargetAction) else emptySet()
        val TerminalConnectionStates = setOf(
            ShizukuConnectionStatus.NotInstalled,
            ShizukuConnectionStatus.NotRunning,
            ShizukuConnectionStatus.PermissionRequired,
            ShizukuConnectionStatus.PermissionDenied,
            ShizukuConnectionStatus.Unsupported,
            ShizukuConnectionStatus.Error
        )
    }
}
