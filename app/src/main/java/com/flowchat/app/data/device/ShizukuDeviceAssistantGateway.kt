package com.flowchat.app.data.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.flowchat.app.BuildConfig
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceActionPolicy
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.terminal.CommandSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.domain.device.DeviceToolResult
import com.flowchat.app.domain.device.DeviceTaskControl
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku

@Singleton
class ShizukuDeviceAssistantGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsStore: AppSettingsStore,
    private val rootConnection: RootDeviceConnection,
    private val taskControl: DeviceTaskControl = DeviceTaskControl()
) : DeviceAssistantGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableConnectionState = MutableStateFlow(ShizukuConnectionState())
    override val connectionState: StateFlow<ShizukuConnectionState> = mutableConnectionState.asStateFlow()
    private val mutableAccessibilityState = MutableStateFlow(AccessibilityConnectionState())
    override val accessibilityState: StateFlow<AccessibilityConnectionState> =
        mutableAccessibilityState.asStateFlow()
    private val binding = AtomicBoolean(false)
    @Volatile private var service: IFlowChatShizukuService? = null
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
            service = null
            binding.set(false)
            refreshConnection()
        }
    }

    init {
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
            appSettingsStore.state.map { it.powerConnectionMode }.distinctUntilChanged().collect { mode ->
                if (mode == PowerConnectionMode.Root) {
                    publishRootState(rootConnection.state.value)
                    scope.launch {
                        if (isRootMode) {
                            rootConnection.refresh(requestPermission = appSettingsStore.state.value.powerModeEnabled)
                        }
                    }
                } else {
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
            capabilities = state.capabilities + if (FlowChatAccessibilityService.current() != null) {
                AccessibilityCapabilities
            } else {
                emptySet()
            }
        )
    }

    override fun refreshConnection() {
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
                        detail = "Open Shizuku and grant FlowChat permission."
                    )
                else -> Shizuku.requestPermission(PermissionRequestCode)
            }
        }.onFailure { error ->
            mutableConnectionState.value = errorState(error)
        }
    }

    override fun refreshAccessibilityConnection() {
        val availableService = FlowChatAccessibilityService.current()
        mutableAccessibilityState.value = when {
            availableService != null -> AccessibilityConnectionState(AccessibilityConnectionStatus.Connected)
            isAccessibilityServiceEnabled() -> AccessibilityConnectionState(
                AccessibilityConnectionStatus.Unavailable,
                "Accessibility is enabled but the service is not connected yet."
            )
            else -> AccessibilityConnectionState(AccessibilityConnectionStatus.Disabled)
        }
        val accessibilityCapabilities = if (availableService != null) {
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

    override suspend fun getDeviceStatus(): DeviceToolResult = callService { it.getDeviceStatus() }

    override suspend fun getForegroundApp(): DeviceToolResult = callService { it.getForegroundApp() }

    override suspend fun launchApp(packageName: String, activityName: String): DeviceToolResult {
        currentCoroutineContext().ensureActive()
        val task = taskControl.currentTask
        taskControl.checkCanAct(task)
        return callService {
            taskControl.checkCanAct(task)
            it.launchApp(packageName, activityName)
        }
    }

    override suspend fun setScreenBrightness(percent: Int): DeviceToolResult =
        callService { it.setScreenBrightness(percent.coerceIn(1, 100)) }

    override suspend fun setMediaVolume(percent: Int): DeviceToolResult =
        callService { it.setMediaVolume(percent.coerceIn(0, 100)) }

    override suspend fun forceStopApp(appName: String): DeviceToolResult {
        val app = withContext(Dispatchers.IO) { resolveLaunchableApp(appName) }
        if (app.packageName == context.packageName) {
            return DeviceToolResult(
                success = false,
                summary = "FlowChat cannot force stop itself.",
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

    override suspend fun observeScreen(): DeviceToolResult = uiActionMutex.withLock {
        observeScreenLocked()
    }

    private suspend fun observeScreenLocked(): DeviceToolResult {
        snapshotGuard.remember(null)
        val accessibility = requireAccessibilityService() ?: return accessibilityUnavailable()
        val screen = accessibility.observeSettledScreen()
            ?: return DeviceToolResult(
                success = false,
                summary = "The current screen could not be read.",
                errorCode = "screen_unavailable"
            )
        snapshotGuard.remember(screen)
        return DeviceToolResult(
            success = !screen.sensitive,
            summary = if (screen.sensitive) {
                "A sensitive screen was detected."
            } else {
                "Observed ${screen.elements.size} visible UI elements."
            },
            errorCode = "sensitive_screen_blocked".takeIf { screen.sensitive },
            screen = screen
        )
    }

    override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult =
        performScreenAction(
            toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.TapUiElementToolName,
            summary = if (longPress) "Long pressed UI element [$index]." else "Tapped UI element [$index].",
            expectedScreenId = screenId,
            requiresSnapshot = true,
            targetIndex = index
        ) { accessibility, screen ->
            if (screen.elements.none { it.index == index && it.enabled }) false
            else accessibility.tapUiElement(index, longPress)
        }

    override suspend fun inputText(
        index: Int,
        text: String,
        clearExisting: Boolean,
        screenId: String?
    ): DeviceToolResult = performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.InputTextToolName,
        summary = "Entered text into UI element [$index].",
        expectedScreenId = screenId,
        requiresSnapshot = true,
        targetIndex = index
    ) { accessibility, screen ->
        val target = screen.elements.firstOrNull { it.index == index }
        if (target?.editable != true || target.password) false
        else accessibility.inputText(index, text, clearExisting)
    }

    override suspend fun swipeScreen(
        direction: DeviceSwipeDirection,
        distancePercent: Int
    ): DeviceToolResult = performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.SwipeScreenToolName,
        summary = "Swiped ${direction.name.lowercase()} on the current screen."
    ) { accessibility, _ ->
        accessibility.swipeScreen(direction, distancePercent.coerceIn(20, 80))
    }

    override suspend fun pressBack(): DeviceToolResult = performScreenAction(
        toolName = com.flowchat.app.domain.tools.AgentToolDefinitions.PressBackToolName,
        summary = "Pressed Back.",
        allowFlowChat = true
    ) { accessibility, _ -> accessibility.pressBack() }

    override suspend fun pressHome(): DeviceToolResult = performScreenAction(
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
        action: suspend (FlowChatAccessibilityService, DeviceScreenSnapshot) -> Boolean
    ): DeviceToolResult = uiActionMutex.withLock {
        val task = taskControl.currentTask
        taskControl.checkCanAct(task)
        performScreenActionLocked(toolName, summary, allowFlowChat, expectedScreenId, requiresSnapshot, targetIndex) { service, screen ->
            currentCoroutineContext().ensureActive()
            taskControl.checkCanAct(task)
            action(service, screen)
        }
    }

    private suspend fun performScreenActionLocked(
        toolName: String,
        summary: String,
        allowFlowChat: Boolean,
        expectedScreenId: String?,
        requiresSnapshot: Boolean,
        targetIndex: Int?,
        action: suspend (FlowChatAccessibilityService, DeviceScreenSnapshot) -> Boolean
    ): DeviceToolResult {
        val accessibility = requireAccessibilityService() ?: return accessibilityUnavailable()
        val before = accessibility.observeScreen(forceRefresh = true)
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
                summary = "FlowChat will not automate its own interface.",
                errorCode = "self_automation_blocked"
            )
        }
        if (requiresSnapshot && !snapshotGuard.matches(expectedScreenId, before, targetIndex)) {
            snapshotGuard.remember(before)
            return DeviceToolResult(
                false,
                "The screen changed or the screen_id is missing/expired. No action was taken. Choose a target from this fresh screen and use its screen_id.",
                errorCode = "stale_screen",
                screen = before
            )
        }
        snapshotGuard.remember(null)
        val accepted = try {
            action(accessibility, before)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            val refreshed = accessibility.observeSettledScreen()
            snapshotGuard.remember(refreshed)
            return DeviceToolResult(
                success = false,
                summary = "The UI action failed or was cancelled. Inspect the refreshed screen before retrying; use another target or approach.",
                errorCode = "ui_action_failed",
                screen = refreshed
            )
        }
        val after = accessibility.observeSettledScreen()
        snapshotGuard.remember(after)
        if (toolName == com.flowchat.app.domain.tools.AgentToolDefinitions.SwipeScreenToolName &&
            after != null && before.progressKey() == after.progressKey()) {
            return DeviceToolResult(
                success = false,
                summary = "The swipe gesture completed, but no new page content was observed. Do not count this as a video change or repeat blindly.",
                errorCode = "ui_no_change",
                screen = after
            )
        }
        return DeviceToolResult(
            success = true,
            summary = summary + when {
                after == null -> " Resulting screen unavailable; observe again before continuing."
                before.hasSameUi(after) -> " Screen unchanged. Verify the intended result; do not repeat blindly."
                else -> " Inspect the resulting screen to verify progress."
            },
            before = before.packageName,
            after = after?.packageName,
            reversible = false,
            screen = after
        )
    }

    private fun requireAccessibilityService(): FlowChatAccessibilityService? {
        refreshAccessibilityConnection()
        return FlowChatAccessibilityService.current()
    }

    private fun accessibilityUnavailable() = DeviceToolResult(
        success = false,
        summary = "Enable FlowChat in Android Accessibility settings first.",
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
            if (isRootMode) return
            mutableConnectionState.value = ShizukuConnectionState(
                status = if (uid == 0) {
                    ShizukuConnectionStatus.ConnectedRoot
                } else {
                    ShizukuConnectionStatus.ConnectedAdb
                },
                capabilities = capabilities + if (FlowChatAccessibilityService.current() != null) {
                    AccessibilityCapabilities
                } else {
                    emptySet()
                }
            )
        }.onFailure { error ->
            service = null
            if (!isRootMode) mutableConnectionState.value = errorState(error)
        }
    }

    private fun hasLiveUserService(): Boolean =
        service?.asBinder()?.isBinderAlive == true

    override suspend fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): CommandSnapshot =
        callCommandService(null) { it.startShellCommand(command, workingDirectory, timeoutSeconds) }

    override suspend fun readShellCommand(commandId: String): CommandSnapshot =
        callCommandService(commandId) { it.readShellCommand(commandId) }

    override suspend fun cancelShellCommand(commandId: String): CommandSnapshot =
        callCommandService(commandId) { it.cancelShellCommand(commandId) }

    private suspend fun callCommandService(commandId: String?, operation: (IFlowChatShizukuService) -> String): CommandSnapshot = withContext(Dispatchers.IO) {
        val useRoot = commandId?.startsWith("root-") ?: isRootMode
        val connected = commandId?.let(commandServices::get) ?: if (useRoot) rootConnection.requireService() else awaitService()
        currentCoroutineContext().ensureActive()
        try {
            commandJson.decodeFromString<CommandSnapshot>(operation(connected)).also { snapshot ->
                if (snapshot.isRunning) commandServices[snapshot.id] = connected else commandServices.remove(snapshot.id)
            }
        } catch (error: Exception) {
            commandId?.let(commandServices::remove)
            throw error
        }
    }

    private suspend fun callService(
        operation: (IFlowChatShizukuService) -> String
    ): DeviceToolResult = withContext(Dispatchers.IO) {
        val root = isRootMode
        runCatching {
            val connectedService = if (root) rootConnection.requireService() else awaitService()
            currentCoroutineContext().ensureActive()
            check(isRootMode == root) { "Power connection changed. Retry the operation." }
            parseResult(operation(connectedService))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            refreshConnection()
            DeviceToolResult(
                success = false,
                summary = error.message ?: "Device operation failed.",
                errorCode = if (root) "root_operation_failed" else "shizuku_operation_failed"
            )
        }
    }

    private suspend fun awaitService(): IFlowChatShizukuService {
        service?.let { return it }
        refreshConnection()
        withTimeout(ServiceConnectionTimeoutMillis) {
            connectionState.filter { it.isConnected || it.status in TerminalConnectionStates }.first()
        }
        return service ?: error(connectionState.value.detail ?: "Shizuku is not connected.")
    }

    private fun parseResult(raw: String): DeviceToolResult {
        val json = JSONObject(raw)
        return DeviceToolResult(
            success = json.optBoolean("success", false),
            summary = json.optString("summary", "Shizuku operation finished."),
            before = json.optString("before").takeIf { it.isNotBlank() },
            after = json.optString("after").takeIf { it.isNotBlank() },
            reversible = json.optBoolean("reversible", false),
            errorCode = json.optString("errorCode").takeIf { it.isNotBlank() }
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
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
        val AccessibilityCapabilities = setOf(
            DeviceCapability.ScreenObservation,
            DeviceCapability.UiElementAction,
            DeviceCapability.TextInput,
            DeviceCapability.ScreenSwipe,
            DeviceCapability.Navigation
        )
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
