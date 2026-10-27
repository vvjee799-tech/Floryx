package com.flowchat.app.data.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import com.flowchat.app.domain.device.AutomationTimings
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
    private val taskControl: DeviceTaskControl = DeviceTaskControl(),
    private val floatingAssistantBridge: com.flowchat.app.presentation.overlay.FloatingAssistantBridge =
        com.flowchat.app.presentation.overlay.FloatingAssistantBridge(taskControl)
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
        uiActionMutex.withLock { snapshotGuard.remember(null); visualFrameGuard.clear() }
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

    override suspend fun observeScreen(): DeviceToolResult = AutomationTimings.measure("device.observe") { uiActionMutex.withLock {
        observeScreenLocked()
    } }

    override suspend fun waitForScreen(): DeviceToolResult = AutomationTimings.measure("device.wait") { uiActionMutex.withLock {
        val baseline = snapshotGuard.latest()
        observeScreenLocked(waitForChangeFrom = baseline)
    } }

    override suspend fun captureScreen(region: com.flowchat.app.domain.device.ScreenImageRegion?): DeviceToolResult = uiActionMutex.withLock {
        captureStableVisualFrameLocked(region)
    }

    private suspend fun captureScreenLocked(region: com.flowchat.app.domain.device.ScreenImageRegion? = null): DeviceToolResult {
        val owner = taskControl.currentTask
        val observed = observeScreenLocked(waitForSettledUi = false)
        val before = observed.screen
        if (!observed.success || before == null || before.sensitive) return observed.copy(
            imageErrorCode = observed.errorCode ?: "screen_unavailable")
        val accessibility = requireAccessibilityService() ?: return accessibilityUnavailable()
        return try {
            val image = accessibility.captureScreenImage(before, region) { capture ->
                floatingAssistantBridge.withDisplayCapture(capture)
            }
            val after = accessibility.observeScreen()
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
        waitForChangeFrom: DeviceScreenSnapshot? = null): DeviceToolResult {
        snapshotGuard.remember(null)
        visualFrameGuard.clear()
        val accessibility = requireAccessibilityService() ?: return accessibilityUnavailable()
        val screen = (if (waitForSettledUi) accessibility.observeSettledScreen(waitForChangeFrom) else accessibility.observeScreen())
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

    override suspend fun tapVisualTarget(target: VisualTapTarget): DeviceToolResult = uiActionMutex.withLock {
        val task = taskControl.currentTask
        currentCoroutineContext().ensureActive()
        taskControl.checkCanAct(task)
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
        taskControl.checkCanAct(task)
        snapshotGuard.remember(null); visualFrameGuard.clear()
        val dispatchError = accessibility.tapVisualPoint(point, freshScreen, target.longPress) {
            taskControl.checkCanAct(task)
        }
        // Always return a new frame, including after rejected/cancelled gestures, for the next model decision.
        var after = captureStableVisualFrameLocked(frame.image.region)
        val resultingScreen = after.screen
        if (resultingScreen != null && (resultingScreen.windowId != frame.screen.windowId || resultingScreen.geometry != frame.screen.geometry))
            after = captureStableVisualFrameLocked()
        if (dispatchError != null) return@withLock after.copy(success = false, errorCode = dispatchError,
            summary = "The visual gesture was rejected or cancelled. Inspect the refreshed image before choosing another action.")
        val difference = after.screenImage?.let { image ->
            try { AutomationTimings.measure("screen.compare_pixels") { withContext(Dispatchers.Default) { ScreenImageMatcher.compare(frame.image, image, target) } } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        }
        val unchanged = difference?.pixelsChanged == false && after.screen?.progressKey() == frame.screen.progressKey()
        after.copy(
            success = after.success && after.screenImage != null && !unchanged,
            summary = if (unchanged) "The visual gesture completed, but neither the pixels nor UI state changed. Do not report task completion or repeat blindly."
                else "The ${if (target.longPress) "long-press" else "tap"} gesture completed for ${target.targetDescription.replace(Regex("\\s+"), " ")}. " +
                    if (after.screenImage == null) "The resulting image is unavailable; the task result is unverified."
                    else "Inspect the attached resulting image to verify the user's intended outcome before reporting completion or choosing the next action.",
            errorCode = if (unchanged) "ui_no_change" else after.errorCode ?: after.imageErrorCode
                ?: "visual_verification_failed".takeIf { after.screenImage == null },
            before = frame.screen.packageName, after = after.screen?.packageName,
            visualProgressKey = if (difference?.targetOrSceneChanged == false) frame.image.visualKey else after.screenImage?.visualKey
        )
    }

    private suspend fun rejectVisualTarget(code: String, summary: String): DeviceToolResult =
        captureScreenLocked().copy(success = false, errorCode = code, summary = summary)

    private suspend fun captureStableVisualFrameLocked(region: com.flowchat.app.domain.device.ScreenImageRegion? = null): DeviceToolResult {
        val task = taskControl.currentTask
        var result = captureScreenLocked(region)
        repeat(2) { attempt ->
            if (!com.flowchat.app.domain.device.ScreenCaptureReadiness.shouldRetry(result)) return result
            currentCoroutineContext().ensureActive()
            taskControl.checkCanAct(task)
            delay(180L * (attempt + 1))
            result = captureScreenLocked(region)
        }
        return result
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
        visualFrameGuard.clear()
        performScreenActionLocked(toolName, summary, allowFlowChat, expectedScreenId, requiresSnapshot, targetIndex) { service, screen ->
            currentCoroutineContext().ensureActive()
            taskControl.checkCanAct(task)
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
                summary = "Floryx will not automate its own interface.",
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
        val isSwipe = toolName == com.flowchat.app.domain.tools.AgentToolDefinitions.SwipeScreenToolName
        // Ordinary text lists can prove progress through their controls. Canvas/video pagers need pixels.
        val verifySwipePixels = isSwipe && (before.elements.none { it.scrollable } ||
            before.elements.none { it.displayText.isNotBlank() })
        val visualBefore = if (verifySwipePixels && Build.VERSION.SDK_INT >= 30) captureStableVisualFrameLocked() else null
        visualBefore?.screen?.let { fresh ->
            if (fresh.sensitive || fresh.packageName != before.packageName || fresh.windowId != before.windowId || fresh.geometry != before.geometry)
                return visualBefore.copy(success = false, errorCode = if (fresh.sensitive) "sensitive_screen_blocked" else "stale_screen",
                    summary = "The app window changed while preparing the swipe. No gesture was dispatched; inspect the refreshed screen.")
        }
        snapshotGuard.remember(null)
        visualFrameGuard.clear()
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
        val after = accessibility.observeSettledScreen(waitForChangeFrom = before.takeIf { it.sensitive })
        snapshotGuard.remember(after)
        if (after?.sensitive == true) return DeviceToolResult(false,
            "The action completed on a sensitive screen. Further automation is blocked.", errorCode = "sensitive_screen_blocked", screen = after)
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
                summary = if (unchanged) "The swipe gesture completed, but neither pixels nor readable content changed. Do not repeat blindly."
                    else "The swipe gesture completed and the resulting screen changed. Inspect the returned image to verify the intended result.",
                before = before.packageName, after = observedAfter.screen?.packageName)
        }
        if (isSwipe && after != null && before.progressKey() == after.progressKey()) {
            return DeviceToolResult(
                success = false,
                summary = "The swipe gesture completed, but readable content is unchanged and visual progress was not verified. Observe an image before choosing another action; do not repeat blindly.",
                errorCode = "ui_change_unverified",
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
