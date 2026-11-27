package com.flowchat.app.data.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.graphics.Path
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flowchat.app.domain.device.DeviceBounds
import com.flowchat.app.domain.device.DeviceScreenGeometry
import com.flowchat.app.domain.device.AutomationTimings
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceScreenImage
import com.flowchat.app.domain.device.DeviceSwipeDirection
import com.flowchat.app.domain.device.VisualTapPoint
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.device.DeviceSafetyReason
import com.flowchat.app.domain.device.DeviceInteractionContext
import com.flowchat.app.domain.device.DeviceWindowRegion
import com.flowchat.app.domain.device.DeviceWindowKind
import com.flowchat.app.domain.device.DeviceUiSelector
import com.flowchat.app.domain.device.DeviceScreenWaitRequest
import com.flowchat.app.domain.device.DeviceTextEditing
import com.flowchat.app.domain.device.DeviceTextEditRequest
import com.flowchat.app.domain.device.DeviceTextEditMode
import com.flowchat.app.domain.device.DeviceTextEditRejected
import com.flowchat.app.domain.device.DeviceTextSelection
import com.flowchat.app.domain.device.DeviceScrollDirection
import com.flowchat.app.domain.device.DeviceUiElement
import com.flowchat.app.domain.device.DeviceAnchoredGesture
import com.flowchat.app.domain.device.DeviceAnchoredGesturePolicy
import com.flowchat.app.domain.device.DeviceRangeAdjustment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@AndroidEntryPoint
class FlowChatAccessibilityService : AccessibilityService() {
    @Inject lateinit var safetyMonitor: DeviceSafetyMonitor
    @Inject lateinit var reconnectStore: AccessibilityReconnectStore
    private val indexedNodes = linkedMapOf<Int, AccessibilityNodeInfo>()
    private var cachedSnapshot: DeviceScreenSnapshot? = null
    private var snapshotInvalidated = true
    private val screenChanges = MutableStateFlow(0L)
    private val screenshotMutex = Mutex()
    private val gestureMutex = Mutex()
    private var gestureReleaseBlocked = false
    private var lastScreenshotAt = -1_000L

    override fun onServiceConnected() {
        if (instance != null && instance !== this) safetyMonitor.boundary.invalidate(DeviceSafetyReason.AccessibilityLost)
        instance = this
        mutableConnected.value = true
        snapshotInvalidated = true
        if (::reconnectStore.isInitialized && isSelectedInSecureSettings()) {
            reconnectStore.recordAuthorized(android.os.Process.myPid())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                snapshotInvalidated = true
                screenChanges.value += 1
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        // System feedback interruptions can fire during active gestures without unbinding or revoking accessibility.
        snapshotInvalidated = true
    }

    private fun disconnected() {
        clearNodeCache()
        cachedSnapshot = null
        snapshotInvalidated = true
        if (instance === this) {
            if (::reconnectStore.isInitialized && !isSelectedInSecureSettings()) {
                reconnectStore.recordRevokedIfAuthorized(android.os.Process.myPid())
            }
            safetyMonitor.boundary.invalidate(DeviceSafetyReason.AccessibilityLost)
            instance = null
            mutableConnected.value = false
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    suspend fun observeScreen(forceRefresh: Boolean = true, selector: DeviceUiSelector? = null,
        scope: com.flowchat.app.domain.device.DeviceObservationScope? = null,
        includeDocumentText: Boolean = false): DeviceScreenSnapshot? =
        AutomationTimings.measure("screen.read_controls") { withContext(Dispatchers.Main.immediate) {
            checkSafety()
            if (!forceRefresh && !snapshotInvalidated && cachedSnapshot?.elementQuery == selector && cachedSnapshot?.observationScope == scope &&
                (cachedSnapshot?.documentText != null) == includeDocumentText) return@withContext cachedSnapshot
            val visible = if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays[Display.DEFAULT_DISPLAY].orEmpty() else windows
            var root = rootInActiveWindow
            val activeWindow = visible.firstOrNull { it.id == root?.windowId }
            // The IME can be active while input focus still belongs to an application window.
            if (activeWindow == null || activeWindow.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                @Suppress("DEPRECATION")
                root?.recycle()
                root = visible.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.isActive }.thenByDescending { it.layer })
                    .firstOrNull()?.root
            }
            val targetRoot = root ?: run {
                clearNodeCache()
                cachedSnapshot = null
                return@withContext null
            }
            try {
                val window = visible.firstOrNull { it.id == targetRoot.windowId }
                val activityName = window?.title?.toString().orEmpty()
                val geometry = window?.let(::windowGeometry)
                val metrics = resources.displayMetrics
                val viewport = geometry?.windowBounds?.let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
                    ?: android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                val readResult = AccessibilityScreenReader.read(targetRoot, activityName, viewport, selector, scope, includeDocumentText)
                clearNodeCache()
                indexedNodes.putAll(readResult.indexedNodes)
                cachedSnapshot = withInteractionContext(readResult.snapshot, geometry, visible, readResult.inputFocusWindowId)
                snapshotInvalidated = false
                cachedSnapshot
            } finally {
                @Suppress("DEPRECATION")
                targetRoot.recycle()
            }
        } }

    suspend fun observeSettledScreen(waitForChangeFrom: DeviceScreenSnapshot? = null, selector: DeviceUiSelector? = null,
        scope: com.flowchat.app.domain.device.DeviceObservationScope? = null): DeviceScreenSnapshot? =
        AutomationTimings.measure("screen.await_ready") {
            ScreenReadinessWaiter({ observeScreen(selector = selector, scope = scope) }, screenChanges, SystemClock::elapsedRealtime)
                .observe(waitForChangeFrom)
        }

    /** Never consult rootInActiveWindow: it can belong to the user's physical screen. */
    suspend fun observeScreenOnDisplay(displayId: Int, selector: DeviceUiSelector? = null,
        scope: com.flowchat.app.domain.device.DeviceObservationScope? = null): DeviceScreenSnapshot? = withContext(Dispatchers.Main.immediate) {
        checkSafety()
        if (Build.VERSION.SDK_INT < 30) return@withContext null
        require(displayId >= 0)
        clearNodeCache()
        cachedSnapshot = null
        snapshotInvalidated = true
        val visible = windowsOnAllDisplays[displayId].orEmpty()
        val window = visible.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.layer })
            .firstOrNull() ?: return@withContext null
        if (window.displayId != displayId) return@withContext null
        val geometry = windowGeometry(window) ?: return@withContext null
        val bounds = geometry.windowBounds
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > geometry.displayWidth || bounds.bottom > geometry.displayHeight)
            return@withContext null
        val root = window.root ?: return@withContext null
        try {
            val result = AccessibilityScreenReader.read(root, window.title?.toString().orEmpty(),
                android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), selector, scope)
            indexedNodes.putAll(result.indexedNodes)
            withInteractionContext(result.snapshot, geometry, visible, result.inputFocusWindowId).also { cachedSnapshot = it }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    suspend fun inputTextOnDisplay(displayId: Int, index: Int, text: String, clearExisting: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (displayId <= 0 || cachedSnapshot?.geometry?.displayId != displayId) return@withContext false
            inputText(index, text, clearExisting)
        }

    suspend fun observeSettledScreenOnDisplay(displayId: Int): DeviceScreenSnapshot? =
        ScreenReadinessWaiter({ observeScreenOnDisplay(displayId) }, screenChanges, SystemClock::elapsedRealtime).observe()

    suspend fun waitForCondition(request: DeviceScreenWaitRequest, baseline: DeviceScreenSnapshot?, displayId: Int = 0) =
        ScreenReadinessWaiter({
            if (displayId > 0) observeScreenOnDisplay(displayId, request.condition?.element, request.observationScope)
            else observeScreen(selector = request.condition?.element, scope = request.observationScope)
        }, screenChanges, SystemClock::elapsedRealtime).observeCondition(request, baseline)

    private fun withInteractionContext(screen: DeviceScreenSnapshot, geometry: DeviceScreenGeometry?, visible: List<AccessibilityWindowInfo>,
        inputFocusWindowId: Int? = null): DeviceScreenSnapshot {
        var complete = geometry != null && visible.size <= 64
        if (geometry != null && (geometry.windowBounds.left < 0 || geometry.windowBounds.top < 0 ||
                geometry.windowBounds.right > geometry.displayWidth || geometry.windowBounds.bottom > geometry.displayHeight)) complete = false
        val regions = visible.take(64).map { window ->
            val bounds = android.graphics.Rect().also(window::getBoundsInScreen)
            val parts = mutableListOf<DeviceBounds>()
            if (Build.VERSION.SDK_INT >= 30) {
                val region = android.graphics.Region().also(window::getRegionInScreen)
                val iterator = android.graphics.RegionIterator(region)
                val part = android.graphics.Rect()
                while (iterator.next(part)) {
                    if (parts.size >= 128) { complete = false; break }
                    parts += DeviceBounds(part.left, part.top, part.right, part.bottom)
                }
            } else if (!bounds.isEmpty) parts += DeviceBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            DeviceWindowRegion(window.id, when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> DeviceWindowKind.Application
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> DeviceWindowKind.InputMethod
                AccessibilityWindowInfo.TYPE_SYSTEM -> DeviceWindowKind.System
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> DeviceWindowKind.Overlay
                else -> DeviceWindowKind.Other
            }, window.layer, DeviceBounds(bounds.left, bounds.top, bounds.right, bounds.bottom), parts, window.isFocused, window.isActive)
        }.sortedBy { it.id }
        val display = geometry?.let { getSystemService(DisplayManager::class.java).getDisplay(it.displayId) }
        val cutouts = if (Build.VERSION.SDK_INT >= 29) display?.cutout?.boundingRects.orEmpty().map {
            DeviceBounds(it.left, it.top, it.right, it.bottom)
        } else emptyList()
        return screen.copy(geometry = geometry, interactionContext = DeviceInteractionContext(regions, cutouts,
            screen.elements.singleOrNull { it.focused && it.editable }?.index, complete, inputFocusWindowId))
    }

    /** Re-sample the exact display and selector before any indexed or coordinate input. */
    suspend fun revalidateInteraction(expected: DeviceScreenSnapshot, index: Int? = null): DeviceScreenSnapshot {
        val current = if ((expected.geometry?.displayId ?: 0) > 0)
            observeScreenOnDisplay(requireNotNull(expected.geometry).displayId, expected.elementQuery, expected.observationScope)
            else observeScreen(selector = expected.elementQuery, scope = expected.observationScope)
        if (current == null) throw DeviceDispatchRejected("screen_unavailable")
        if (current.sensitive) throw DeviceDispatchRejected("sensitive_screen_blocked")
        current.observationErrorCode?.let { throw DeviceDispatchRejected(it) }
        if (current.packageName != expected.packageName || current.windowId != expected.windowId ||
            current.geometry != expected.geometry || current.interactionContext != expected.interactionContext ||
            !current.hasSameObservationScope(expected) ||
            (index != null && (expected.elements.singleOrNull { it.index == index } == null ||
                expected.elements.singleOrNull { it.index == index } != current.elements.singleOrNull { it.index == index })))
            throw DeviceDispatchRejected("stale_screen")
        if (current.interactionContext?.complete != true) throw DeviceDispatchRejected("ui_context_unavailable")
        return current
    }

    private fun requirePoint(screen: DeviceScreenSnapshot, x: Int, y: Int) {
        if (screen.interactionContext?.allowsPoint(screen, x, y) != true) throw DeviceDispatchRejected("ui_target_obscured")
    }

    @Suppress("DEPRECATION")
    private fun windowGeometry(window: AccessibilityWindowInfo): DeviceScreenGeometry? {
        val displayId = if (Build.VERSION.SDK_INT >= 30) window.displayId else Display.DEFAULT_DISPLAY
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val bounds = android.graphics.Rect().also(window::getBoundsInScreen)
        if (bounds.isEmpty || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        return DeviceScreenGeometry(DeviceBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            metrics.widthPixels, metrics.heightPixels, display.rotation, displayId)
    }

    suspend fun captureScreenImage(
        screen: DeviceScreenSnapshot,
        region: com.flowchat.app.domain.device.ScreenImageRegion? = null,
        minimumIntervalMillis: Long = 500L,
        withDisplayCapture: suspend (suspend () -> Bitmap) -> Bitmap = { it() }
    ): DeviceScreenImage = screenshotMutex.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ScreenCaptureFailure("screen_capture_requires_android_11")
        suspend fun sample(windowCapture: Boolean): Bitmap {
            suspend fun capture(): Bitmap {
                val remaining = minimumIntervalMillis.coerceIn(260L, 1000L) - (SystemClock.elapsedRealtime() - lastScreenshotAt)
                if (remaining > 0) AutomationTimings.measure("screen.capture_throttle") { delay(remaining) }
                return withContext(Dispatchers.Main.immediate) {
                    checkSafety()
                    if (!windowCapture && Build.VERSION.SDK_INT >= 34) {
                        val targetBounds = screen.geometry?.windowBounds ?: throw ScreenCaptureFailure("screen_window_unavailable")
                        val visible = windowsOnAllDisplays[screen.geometry.displayId].orEmpty()
                        val target = visible.firstOrNull { it.id == screen.windowId } ?: throw ScreenCaptureFailure("screen_window_unavailable")
                        // A display crop must not accidentally send pixels from an overlay belonging to another window.
                        if (visible.any { other -> other.id != target.id && other.layer > target.layer &&
                                other.displayId == screen.geometry.displayId &&
                                android.graphics.Rect().also(other::getBoundsInScreen).intersect(
                                    targetBounds.left, targetBounds.top, targetBounds.right, targetBounds.bottom) })
                            throw ScreenCaptureFailure("visual_target_obscured")
                    }
                    lastScreenshotAt = SystemClock.elapsedRealtime()
                    AutomationTimings.measure("screen.capture_pixels") {
                        captureWithinBoundary { captureBitmap(screen.windowId,
                            screen.geometry?.displayId ?: Display.DEFAULT_DISPLAY, windowCapture) }
                    }
                }
            }
            return if (windowCapture) capture() else withDisplayCapture { capture() }
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = sample(windowCapture = Build.VERSION.SDK_INT >= 34)
            val geometry = screen.geometry
            val window = geometry?.windowBounds
            if (Build.VERSION.SDK_INT >= 34 && geometry != null && window != null &&
                (bitmap!!.width != window.width || bitmap!!.height != window.height) &&
                window.left >= 0 && window.top >= 0 && window.right <= geometry.displayWidth && window.bottom <= geometry.displayHeight) {
                // Window screenshots can include asymmetric/unknown surface shadow insets. Never guess their offset.
                // After the secure-window check above succeeded, sample visible display pixels and crop exact window bounds.
                val display = sample(windowCapture = false)
                try {
                    if (display.width != geometry.displayWidth || display.height != geometry.displayHeight)
                        throw ScreenCaptureFailure("screen_changed_during_capture")
                    val cropped = withContext(Dispatchers.Default) {
                        val crop = Bitmap.createBitmap(display, window.left, window.top, window.width, window.height)
                        if (crop === display) requireNotNull(crop.copy(Bitmap.Config.ARGB_8888, false)) else crop
                    }
                    bitmap?.recycle()
                    bitmap = cropped
                } finally { display.recycle() }
            }
            val capturedAt = System.currentTimeMillis()
            withContext(Dispatchers.Default) {
                val pixels = requireNotNull(bitmap)
                val bounds = geometry?.let {
                    if (Build.VERSION.SDK_INT >= 34) it.windowBounds
                    else DeviceBounds(0, 0, it.displayWidth, it.displayHeight)
                }?.takeIf { it.width == pixels.width && it.height == pixels.height }
                AutomationTimings.measure("screen.encode_image") {
                    ScreenImageEncoder.encode(pixels, screen.screenId, capturedAt, region).copy(
                        geometry = geometry.takeIf { bounds != null }, captureBounds = bounds)
                }
            }
        } finally { bitmap?.recycle() }
    }

    @android.annotation.TargetApi(30)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun captureBitmap(windowId: Int?, displayId: Int, windowCapture: Boolean): Bitmap = suspendCancellableCoroutine { continuation ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var wrapped: Bitmap? = null
                try {
                    result.hardwareBuffer.use { buffer ->
                        if (!continuation.isActive) return
                        wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false) ?: throw ScreenCaptureFailure("screen_capture_empty")
                        continuation.resume(copy) { _, value, _ -> value.recycle() }
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { wrapped?.recycle() }
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(ScreenCaptureFailure(when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "screen_capture_permission_required"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screen_capture_rate_limited"
                    ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "screen_display_unavailable"
                    ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "screen_window_unavailable"
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure_window_blocked"
                    else -> "screen_capture_failed"
                }))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34 && windowCapture) {
                if (windowId == null || windowId < 0) throw ScreenCaptureFailure("screen_window_unavailable")
                takeScreenshotOfWindow(windowId, mainExecutor, callback)
            } else takeScreenshot(displayId, mainExecutor, callback)
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
    }

    suspend fun tapUiElement(index: Int, longPress: Boolean, expected: DeviceScreenSnapshot? = cachedSnapshot,
        targetAnchor: com.flowchat.app.domain.device.DeviceUiSelector? = null, requireSameUi: Boolean = false,
        checkCanAct: () -> Unit = {}): Boolean =
        withContext(Dispatchers.Main.immediate) {
            requireReleasedTouch()
            checkSafety()
            val screen = revalidateInteraction(expected ?: throw DeviceDispatchRejected("stale_screen"), index)
            if (requireSameUi && !screen.hasSameUi(expected)) throw DeviceDispatchRejected("stale_screen")
            if (targetAnchor != null && com.flowchat.app.domain.device.DeviceUiTargetPolicy.resolve(screen, targetAnchor)?.index != index)
                throw DeviceDispatchRejected("ui_target_changed")
            val node = indexedNodes[index] ?: return@withContext false
            if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser) return@withContext false
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            requirePoint(screen, bounds.centerX(), bounds.centerY())
            checkCanAct()
            val action = if (longPress) {
                AccessibilityNodeInfo.ACTION_LONG_CLICK
            } else {
                AccessibilityNodeInfo.ACTION_CLICK
            }
            if (node.performAction(action)) {
                snapshotInvalidated = true
                return@withContext true
            }
            // A semantic target already fixed its exact clickable row. Never climb to a
            // different action when its callback is false or its result is unknown.
            if (targetAnchor != null) return@withContext false
            // Labels often delegate their click to a containing row.
            var parent = node.parent
            var depth = 0
            while (parent != null && depth++ < 5) {
                val candidate = parent
                parent = null
                try {
                    if (candidate.isEnabled && candidate.isVisibleToUser &&
                        (if (longPress) candidate.isLongClickable else candidate.isClickable) &&
                        candidate.windowId == screen.windowId &&
                        candidate.performAction(action)) {
                        snapshotInvalidated = true
                        return@withContext true
                    }
                    parent = candidate.parent
                } finally {
                    @Suppress("DEPRECATION")
                    candidate.recycle()
                }
            }
            @Suppress("DEPRECATION")
            parent?.recycle()
            checkCanAct()
            checkSafety()
            val fresh = revalidateInteraction(screen, index)
            requirePoint(fresh, bounds.centerX(), bounds.centerY())
            dispatchPointGesture(
                x = bounds.centerX().toFloat(),
                y = bounds.centerY().toFloat(),
                durationMillis = if (longPress) LongPressDurationMillis else TapDurationMillis,
                displayId = requireNotNull(screen.geometry).displayId
            ).also { accepted -> if (accepted) snapshotInvalidated = true }
        }

    /** Last main-thread window/occlusion and task check, immediately before gesture dispatch. */
    suspend fun tapVisualPoint(
        point: VisualTapPoint,
        expected: DeviceScreenSnapshot,
        longPress: Boolean,
        checkCanAct: () -> Unit
    ): String? = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        val current = try { revalidateInteraction(expected) }
            catch (error: DeviceDispatchRejected) { return@withContext error.code }
        val geometry = requireNotNull(current.geometry)
        if (current.interactionContext?.allowsPoint(current, point.x, point.y) != true) return@withContext "visual_target_obscured"
        checkCanAct()
        if (dispatchPointGesture(point.x.toFloat(), point.y.toFloat(),
                if (longPress) LongPressDurationMillis else TapDurationMillis, geometry.displayId)) {
            snapshotInvalidated = true
            null
        } else "ui_action_failed"
    }

    suspend fun inputText(index: Int, text: String, clearExisting: Boolean): Boolean =
        editText(DeviceTextEditRequest(index, text, if (clearExisting) DeviceTextEditMode.Replace else DeviceTextEditMode.Append))

    suspend fun editText(request: DeviceTextEditRequest, expected: DeviceScreenSnapshot? = cachedSnapshot,
        checkCanAct: () -> Unit = {}): Boolean =
        withContext(Dispatchers.Main.immediate) {
            requireReleasedTouch()
            checkSafety()
            val screen = revalidateInteraction(expected ?: throw DeviceDispatchRejected("stale_screen"), request.index)
            val target = screen.elements.single { it.index == request.index }
            val plan = try { DeviceTextEditing.plan(target, request) }
                catch (error: DeviceTextEditRejected) { throw DeviceDispatchRejected(error.code) }
            val node = checkedInputNode(screen, target)
            if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) throw DeviceDispatchRejected("ui_input_unsupported")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.value)
            }
            // SET_TEXT targets the node directly. Do not move focus and then write an old draft after a focus callback.
            checkCanAct()
            currentCoroutineContext().ensureActive()
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return@withContext false
            snapshotInvalidated = true
            if (plan.cursor != null) {
                // Text has already been dispatched. A failed cursor restoration is an unverified result, never a no-dispatch rejection.
                checkSafety()
                val displayId = requireNotNull(screen.geometry).displayId
                val updated = ScreenReadinessWaiter({
                    if (displayId > 0) observeScreenOnDisplay(displayId, screen.elementQuery, screen.observationScope)
                    else observeScreen(selector = screen.elementQuery, scope = screen.observationScope)
                }, screenChanges, SystemClock::elapsedRealtime).observe(waitForChangeFrom = screen)
                if (updated == null || updated.sensitive || updated.observationErrorCode != null ||
                    updated.windowId != screen.windowId || updated.packageName != screen.packageName ||
                    updated.geometry != screen.geometry || updated.interactionContext?.complete != true ||
                    updated.interactionContext.inputFocusWindowId != updated.windowId || !updated.hasSameObservationScope(screen))
                    return@withContext true
                val field = updated.elements.singleOrNull { it.nodePath == target.nodePath && it.resourceId == target.resourceId &&
                    it.className == target.className && it.contentDescription == target.contentDescription && it.editable && it.focused &&
                    it.text.orEmpty() == plan.value && it.textSelectionStart == plan.value.length && it.textSelectionEnd == plan.value.length }
                    ?: return@withContext true
                val freshNode = try { checkedInputNode(updated, field) } catch (_: DeviceDispatchRejected) { return@withContext true }
                if (freshNode.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) return@withContext true
                checkCanAct()
                currentCoroutineContext().ensureActive()
                freshNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArguments(plan.cursor, plan.cursor))
            }
            true
        }

    suspend fun pasteIntoFocusedInput(
        expected: DeviceScreenSnapshot,
        replaceExisting: Boolean,
        checkCanAct: () -> Unit
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        checkSafety()
        val current = revalidateInteraction(expected)
        if (current.sensitive) throw DeviceDispatchRejected("sensitive_screen_blocked")
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@withContext false
        try {
            if (focused.isPassword) throw DeviceDispatchRejected("sensitive_screen_blocked")
            if (focused.actionList.none { it.id == AccessibilityNodeInfo.ACTION_PASTE }) return@withContext false
            if (replaceExisting && focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) {
                val existingLength = focused.text?.length ?: 0
                if (existingLength > 0) {
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArguments(0, existingLength))
                }
            }
            checkCanAct()
            currentCoroutineContext().ensureActive()
            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE).also { pasted ->
                if (pasted) snapshotInvalidated = true
            }
        } finally {
            @Suppress("DEPRECATION")
            focused.recycle()
        }
    }

    suspend fun setTextSelection(selection: DeviceTextSelection, expected: DeviceScreenSnapshot,
        checkCanAct: () -> Unit): Boolean = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        checkSafety()
        val screen = revalidateInteraction(expected, selection.index)
        val target = screen.elements.single { it.index == selection.index }
        try { DeviceTextEditing.validateSelection(target, selection.start, selection.end) }
        catch (error: DeviceTextEditRejected) { throw DeviceDispatchRejected(error.code) }
        val node = checkedInputNode(screen, target)
        if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) throw DeviceDispatchRejected("ui_selection_unsupported")
        checkCanAct()
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArguments(selection.start, selection.end))
            .also { if (it) snapshotInvalidated = true }
    }

    suspend fun performEditorAction(index: Int, expectedLabel: String, expected: DeviceScreenSnapshot,
        checkCanAct: () -> Unit): Boolean = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        checkSafety()
        val screen = revalidateInteraction(expected, index)
        val target = screen.elements.single { it.index == index }
        if (!target.focused) throw DeviceDispatchRejected("ui_input_focus_required")
        if (Build.VERSION.SDK_INT < 30 || "ime_enter" !in target.supportedActions || target.editorActionLabel.isNullOrBlank())
            throw DeviceDispatchRejected("ui_editor_action_unsupported")
        if (expectedLabel.isBlank() || target.editorActionLabel != expectedLabel) throw DeviceDispatchRejected("ui_editor_action_changed")
        val node = checkedInputNode(screen, target)
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
        if (node.actionList.singleOrNull { it.id == action.id }?.label?.toString() != expectedLabel)
            throw DeviceDispatchRejected("ui_editor_action_changed")
        checkCanAct()
        node.performAction(action.id).also { if (it) snapshotInvalidated = true }
    }

    suspend fun scrollContainer(index: Int, direction: DeviceScrollDirection, expected: DeviceScreenSnapshot,
        checkCanAct: () -> Unit): Boolean = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        checkSafety()
        val screen = revalidateInteraction(expected, index)
        if (!screen.hasSameUi(expected)) throw DeviceDispatchRejected("stale_screen")
        val target = screen.elements.single { it.index == index }
        if (!target.scrollable || !target.enabled || direction.actionName !in target.supportedActions)
            throw DeviceDispatchRejected("ui_scroll_unsupported")
        requirePoint(screen, target.bounds.centerX, target.bounds.centerY)
        val node = indexedNodes[index] ?: throw DeviceDispatchRejected("stale_screen")
        val action = when (direction) {
            DeviceScrollDirection.Forward -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            DeviceScrollDirection.Backward -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            DeviceScrollDirection.Up -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            DeviceScrollDirection.Down -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            DeviceScrollDirection.Left -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            DeviceScrollDirection.Right -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        if (!node.refresh() || !node.isScrollable || !node.isEnabled || !node.isVisibleToUser || node.actionList.none { it.id == action })
            throw DeviceDispatchRejected("ui_scroll_unsupported")
        checkCanAct()
        // Only the selected container receives the advertised semantic action. No parent, global swipe or main-display fallback.
        node.performAction(action).also { if (it) snapshotInvalidated = true }
    }

    suspend fun adjustRange(request: DeviceRangeAdjustment, expected: DeviceScreenSnapshot, checkCanAct: () -> Unit): Boolean =
        withContext(Dispatchers.Main.immediate) {
            requireReleasedTouch()
            checkSafety()
            val screen = revalidateInteraction(expected, request.index)
            val target = screen.elements.single { it.index == request.index }
            if (!request.validate(target)) throw DeviceDispatchRejected("ui_range_unsupported")
            requirePoint(screen, target.bounds.centerX, target.bounds.centerY)
            val node = indexedNodes[request.index] ?: throw DeviceDispatchRejected("stale_screen")
            if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser || node.isPassword ||
                node.actionList.none { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id } ||
                node.rangeInfo?.let { it.type == target.range?.type && it.min == target.range.min && it.max == target.range.max && it.current == target.range.current } != true)
                throw DeviceDispatchRejected("stale_screen")
            checkCanAct()
            if (target.range?.current == request.value) return@withContext true
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, request.value)
            }).also { if (it) snapshotInvalidated = true }
        }

    suspend fun anchoredGesture(request: DeviceAnchoredGesture, expected: DeviceScreenSnapshot, checkCanAct: () -> Unit): Boolean =
        withContext(Dispatchers.Main.immediate) { gestureMutex.withLock {
            checkSafety(); checkCanAct()
            if (gestureReleaseBlocked) throw DeviceDispatchRejected("ui_touch_release_unverified")
            val initial = revalidateInteraction(expected)
            if (!initial.hasSameUi(expected)) throw DeviceDispatchRejected("stale_screen")
            val (from, to) = DeviceAnchoredGesturePolicy.points(initial, request) ?: throw DeviceDispatchRejected("ui_gesture_path_blocked")
            val displayId = requireNotNull(initial.geometry).displayId
            if (displayId != 0 && Build.VERSION.SDK_INT < 30) throw DeviceDispatchRejected("ui_gesture_display_unsupported")
            val anchorIndex = if (request is DeviceAnchoredGesture.Drag) request.targetIndex else request.index
            val anchor = initial.elements.single { it.index == anchorIndex }
            var dispatched = false
            val runner = OwnedGestureRunner<GestureDescription.StrokeDescription>(dispatch = { leg, previous ->
                val path = Path().apply { moveTo(leg.from.x, leg.from.y); lineTo(leg.to.x, leg.to.y) }
                val stroke = previous?.continueStroke(path, 0, leg.durationMillis, leg.keepDown)
                    ?: GestureDescription.StrokeDescription(path, 0, leg.durationMillis, leg.keepDown)
                val completion = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) { completion.complete(true) }
                    override fun onCancelled(description: GestureDescription?) { completion.complete(false) }
                }
                val accepted = dispatchGesture(GestureDescription.Builder().apply { if (Build.VERSION.SDK_INT >= 30) setDisplayId(displayId) }
                    .addStroke(stroke).build(), callback, null)
                if (accepted) { dispatched = true; snapshotInvalidated = true }
                OwnedGestureRunner.Pending(stroke, completion, accepted)
            }, check = {
                checkSafety(); checkCanAct()
                val screen = if (displayId > 0) observeScreenOnDisplay(displayId, initial.elementQuery, initial.observationScope)
                    else observeScreen(selector = initial.elementQuery, scope = initial.observationScope)
                val currentAnchor = screen?.elements?.singleOrNull { it.index == anchorIndex }
                // Focus may legitimately change after the first tap. The window, anchor content and path must stay valid.
                if (screen == null || screen.sensitive || screen.observationErrorCode != null || screen.windowId != initial.windowId ||
                    screen.packageName != initial.packageName || screen.geometry != initial.geometry || !screen.hasSameObservationScope(initial) ||
                    currentAnchor?.copy(focused = anchor.focused) != anchor || screen.interactionContext?.complete != true ||
                    !DeviceAnchoredGesturePolicy.coversLine(screen.interactionContext.availableRegions(screen), from, to))
                    throw DeviceDispatchRejected("ui_gesture_context_changed")
            })
            try { runner.run(DeviceAnchoredGesturePolicy.legs(request, from, to)) }
            catch (error: DeviceDispatchRejected) {
                if (dispatched) throw IllegalStateException("${error.code}: gesture was partially dispatched; inspect the result", error) else throw error
            } finally { if (runner.holdingTouch) gestureReleaseBlocked = true }
        } }

    private fun checkedInputNode(screen: DeviceScreenSnapshot, target: DeviceUiElement): AccessibilityNodeInfo {
        val node = indexedNodes[target.index] ?: throw DeviceDispatchRejected("stale_screen")
        try { DeviceTextEditing.requireEditable(target) }
        catch (error: DeviceTextEditRejected) { throw DeviceDispatchRejected(error.code) }
        requirePoint(screen, target.bounds.centerX, target.bounds.centerY)
        if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser || !node.isEditable ||
            !(node.isFocusable || node.isFocused) || node.isPassword ||
            node.windowId != screen.windowId || node.text?.toString().orEmpty() != target.text.orEmpty() ||
            node.isFocused != target.focused || node.textSelectionStart.takeIf { it >= 0 } != target.textSelectionStart ||
            node.textSelectionEnd.takeIf { it >= 0 } != target.textSelectionEnd)
            throw DeviceDispatchRejected("ui_draft_changed")
        return node
    }

    private fun selectionArguments(start: Int, end: Int) = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
    }

    private fun requireReleasedTouch() {
        if (gestureReleaseBlocked) throw DeviceDispatchRejected("ui_touch_release_unverified")
    }

    suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int, expected: DeviceScreenSnapshot? = cachedSnapshot, checkCanAct: () -> Unit = {}): Boolean =
        withContext(Dispatchers.Main.immediate) {
            checkSafety()
            val screen = revalidateInteraction(expected ?: throw DeviceDispatchRejected("stale_screen"))
            val coordinates = screen.interactionContext?.swipe(screen, direction, distancePercent)
                ?: throw DeviceDispatchRejected("ui_action_region_unavailable")
            swipePath(coordinates, 350, screen, checkCanAct).accepted
        }

    internal suspend fun moveCalendarTimeWheel(expected: DeviceScreenSnapshot, index: Int, steps: Int,
        checkCanAct: () -> Unit): Boolean = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        checkSafety()
        val screen = revalidateInteraction(expected)
        if (!screen.hasSameUi(expected) || screen.packageName != "com.bbk.calendar" || steps !in setOf(-2, -1, 1, 2))
            throw DeviceDispatchRejected("calendar_wheel_context_changed")
        val wheel = screen.elements.singleOrNull { it.index == index && it.enabled && !it.password &&
            it.className == "android.widget.SeekBar" && it.resourceId in setOf(
                "com.bbk.calendar:id/not_allday_col3", "com.bbk.calendar:id/not_allday_col4") }
            ?: throw DeviceDispatchRejected("calendar_wheel_unavailable")
        val bounds = wheel.bounds
        val shift = bounds.height * 23 / 100 * steps
        val x = bounds.centerX
        val y = bounds.centerY
        if (!bounds.contains(x, y + shift) || screen.geometry?.displayId != 0)
            throw DeviceDispatchRejected("calendar_wheel_bounds_changed")
        if (kotlin.math.abs(steps) == 1) {
            val point = com.flowchat.app.domain.device.DeviceGesturePoint(x.toFloat(), (y + shift).toFloat())
            if (!DeviceAnchoredGesturePolicy.coversLine(requireNotNull(screen.interactionContext).availableRegions(screen), point, point))
                throw DeviceDispatchRejected("calendar_wheel_point_blocked")
            checkCanAct()
            dispatchPointGesture(point.x, point.y, TapDurationMillis).also { if (it) snapshotInvalidated = true }
        } else {
            val path = com.flowchat.app.domain.device.DeviceSwipePath(x, y + shift / 2, x, y - shift / 2)
            swipePath(path, 500, screen, checkCanAct).accepted
        }
    }

    private suspend fun swipePath(coordinates: com.flowchat.app.domain.device.DeviceSwipePath, durationMillis: Int,
        expected: DeviceScreenSnapshot, checkCanAct: () -> Unit,
        request: com.flowchat.app.domain.device.DeviceScrollRequest? = null): AdaptiveScrollDispatch = withContext(Dispatchers.Main.immediate) {
        gestureMutex.withLock {
            requireReleasedTouch(); checkSafety()
            require(durationMillis in 150..1200)
            val screen = revalidateInteraction(expected)
            val contextMatches = if (request != null) com.flowchat.app.domain.device.DeviceAdaptiveScroll.sameScrollContext(expected, screen, request)
                else screen.hasSameUi(expected)
            if (!contextMatches) throw DeviceDispatchRejected("stale_screen")
            val from = com.flowchat.app.domain.device.DeviceGesturePoint(coordinates.startX.toFloat(), coordinates.startY.toFloat())
            val to = com.flowchat.app.domain.device.DeviceGesturePoint(coordinates.endX.toFloat(), coordinates.endY.toFloat())
            if (!DeviceAnchoredGesturePolicy.coversLine(requireNotNull(screen.interactionContext).availableRegions(screen), from, to))
                throw DeviceDispatchRejected("ui_scroll_path_blocked")
            val displayId = requireNotNull(screen.geometry).displayId
            if (displayId != 0 && Build.VERSION.SDK_INT < 30) throw DeviceDispatchRejected("ui_gesture_display_unsupported")
            var dispatched = false
            var checkedScreen = screen
            val runner = OwnedGestureRunner<GestureDescription.StrokeDescription>(dispatch = { leg, previous ->
                val path = Path().apply { moveTo(leg.from.x, leg.from.y); lineTo(leg.to.x, leg.to.y) }
                val stroke = previous?.continueStroke(path, 0, leg.durationMillis, leg.keepDown)
                    ?: GestureDescription.StrokeDescription(path, 0, leg.durationMillis, leg.keepDown)
                val completion = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) { completion.complete(true) }
                    override fun onCancelled(description: GestureDescription?) { completion.complete(false) }
                }
                val accepted = dispatchGesture(GestureDescription.Builder().apply { if (Build.VERSION.SDK_INT >= 30) setDisplayId(displayId) }
                    .addStroke(stroke).build(), callback, null)
                if (accepted) { dispatched = true; snapshotInvalidated = true }
                OwnedGestureRunner.Pending(stroke, completion, accepted)
            }, check = {
                checkSafety(); checkCanAct()
                val current = if (displayId == 0) observeScreen(selector = screen.elementQuery, scope = screen.observationScope)
                    else observeScreenOnDisplay(displayId, screen.elementQuery, screen.observationScope)
                if (current == null || current.sensitive || current.packageName != screen.packageName || current.windowId != screen.windowId ||
                    current.geometry != screen.geometry || current.interactionContext != screen.interactionContext)
                    throw DeviceDispatchRejected("ui_scroll_context_changed")
                checkedScreen = current
            })
            try {
                // Send a slow 1px out-and-back tail on the same pointer. A wall-clock-only hold is
                // insufficient on ROMs that reuse continuation event timestamps for velocity tracking.
                val rest = if (from.x == to.x) com.flowchat.app.domain.device.DeviceGesturePoint(to.x, to.y + 1)
                    else com.flowchat.app.domain.device.DeviceGesturePoint(to.x + 1, to.y)
                val accepted = runner.run(listOf(com.flowchat.app.domain.device.DeviceGestureLeg(from, to, durationMillis.toLong(), true),
                    com.flowchat.app.domain.device.DeviceGestureLeg(to, rest, 90, true),
                    com.flowchat.app.domain.device.DeviceGestureLeg(rest, to, 90, true),
                    com.flowchat.app.domain.device.DeviceGestureLeg(to, to, 1, false)))
                // The runner checks the screen before the final release leg as part of guarding the gesture.
                AdaptiveScrollDispatch(accepted, beforeRelease = checkedScreen.takeIf { accepted })
            } catch (error: DeviceDispatchRejected) {
                if (dispatched) throw IllegalStateException("${error.code}: scroll partially dispatched; inspect the result", error) else throw error
            } finally { if (runner.holdingTouch) gestureReleaseBlocked = true }
        }
        }

    /** Returns the actual input mode; a semantic page is never reported as a short finger movement. */
    internal suspend fun controlledScroll(expected: DeviceScreenSnapshot, coordinates: com.flowchat.app.domain.device.DeviceSwipePath,
        durationMillis: Int, originalContainer: DeviceUiElement?, request: com.flowchat.app.domain.device.DeviceScrollRequest,
        checkCanAct: () -> Unit): AdaptiveScrollDispatch = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch(); checkSafety()
        val screen = revalidateInteraction(expected)
        if (!com.flowchat.app.domain.device.DeviceAdaptiveScroll.sameScrollContext(expected, screen, request)) throw DeviceDispatchRejected("stale_screen")
        val container = originalContainer?.let { com.flowchat.app.domain.device.DeviceAdaptiveScroll.resolveContainer(screen, it)
            ?: throw DeviceDispatchRejected("stale_screen") }
        val node = container?.let { indexedNodes[it.index] ?: throw DeviceDispatchRejected("stale_screen") }
        var nested = false
        var ancestor = node?.parent
        try {
            var count = 0
            while (ancestor != null && count++ < 128) {
                if (isScrollableAncestorForAxis(ancestor, request.vertical)) { nested = true; break }
                val next = ancestor.parent
                @Suppress("DEPRECATION") ancestor.recycle()
                ancestor = next
            }
        } finally { @Suppress("DEPRECATION") ancestor?.recycle() }
        if (container != null && node != null && (nested || container.granularScrolling)) {
            val forward = if (request.vertical) coordinates.startY > coordinates.endY else coordinates.startX > coordinates.endX
            val action = if (request.vertical) if (forward) "scroll_down" else "scroll_up" else if (forward) "scroll_right" else "scroll_left"
            val fallback = if (forward) "scroll_forward" else "scroll_backward"
            val name = if (action in container.supportedActions) action else fallback.takeIf { it in container.supportedActions }
            val target = request.target?.let { selector -> com.flowchat.app.domain.device.DeviceAdaptiveScroll.descendants(screen, container).singleOrNull(selector::matches) }
            if (nested && target != null && "show_on_screen" in target.supportedActions) {
                checkCanAct()
                val accepted = indexedNodes[target.index]?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true
                if (accepted) snapshotInvalidated = true
                return@withContext AdaptiveScrollDispatch(accepted, "show_target")
            }
            if (name != null && (container.granularScrolling || request.target != null && request.distancePercent == null && request.durationMillis == null)) {
                val actionId = when (name) {
                    "scroll_down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                    "scroll_up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                    "scroll_left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    "scroll_right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                    "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val args = Bundle()
                if (Build.VERSION.SDK_INT >= 35 && container.granularScrolling) {
                    val extent = if (request.vertical) container.bounds.height else container.bounds.width
                    val delta = if (request.vertical) kotlin.math.abs(coordinates.startY - coordinates.endY) else kotlin.math.abs(coordinates.startX - coordinates.endX)
                    args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, delta.toFloat() / extent)
                }
                checkCanAct()
                val accepted = node.performAction(actionId, args)
                if (accepted) snapshotInvalidated = true
                return@withContext AdaptiveScrollDispatch(accepted, if (container.granularScrolling) "granular_action" else "semantic_page")
            }
            if (nested) throw DeviceDispatchRejected("ui_scroll_precision_unsupported")
        }
        swipePath(coordinates, durationMillis, screen, checkCanAct, request)
    }

    private fun isScrollableAncestorForAxis(ancestor: AccessibilityNodeInfo, vertical: Boolean): Boolean {
        if (!ancestor.isScrollable) return false
        val actions = ancestor.actionList.mapNotNull {
            when (it.id) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "scroll_up"
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "scroll_down"
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> "scroll_left"
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "scroll_right"
                else -> null
            }
        }
        return com.flowchat.app.domain.device.DeviceAdaptiveScroll.isSameAxisScrollableAncestor(
            ancestor.className?.toString(), actions, vertical
        )
    }

    suspend fun pressBack(): Boolean = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        checkSafety()
        performGlobalAction(GLOBAL_ACTION_BACK).also { accepted -> if (accepted) snapshotInvalidated = true }
    }

    suspend fun pressHome(): Boolean = withContext(Dispatchers.Main.immediate) {
        requireReleasedTouch()
        checkSafety()
        performGlobalAction(GLOBAL_ACTION_HOME).also { accepted -> if (accepted) snapshotInvalidated = true }
    }

    private suspend fun dispatchPointGesture(x: Float, y: Float, durationMillis: Long, displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        return awaitGesture(
            GestureDescription.Builder().apply { if (Build.VERSION.SDK_INT >= 30) setDisplayId(displayId) }
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
        )
    }

    private suspend fun awaitGesture(gesture: GestureDescription): Boolean = gestureMutex.withLock {
        if (gestureReleaseBlocked) throw DeviceDispatchRejected("ui_touch_release_unverified")
        withTimeoutOrNull(2_000) {
            checkSafety()
            suspendCancellableCoroutine { continuation ->
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                if (!dispatchGesture(gesture, callback, null) && continuation.isActive) continuation.resume(false)
            }
        } ?: false
    }

    private fun readSelectedInSecureSettings(): Boolean? = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)
            ?.contains(ComponentName(this, FlowChatAccessibilityService::class.java))
    }.getOrNull()

    private fun isSelectedInSecureSettings(): Boolean = readSelectedInSecureSettings() == true

    private suspend fun checkSafety() {
        currentCoroutineContext().ensureActive()
        safetyMonitor.check(currentCoroutineContext()[DeviceExecutionTarget])
        val authorized = readSelectedInSecureSettings()
        if (instance !== this || authorized == false) {
            safetyMonitor.boundary.invalidate(DeviceSafetyReason.AccessibilityLost)
            throw CancellationException("Accessibility authorization or binding changed; start or resume the phone task after reconnecting.")
        }
    }

    private fun clearNodeCache() {
        indexedNodes.values.forEach { node ->
            @Suppress("DEPRECATION")
            node.recycle()
        }
        indexedNodes.clear()
    }

    companion object {
        private val mutableConnected = MutableStateFlow(false)
        val connected = mutableConnected.asStateFlow()
        @Volatile
        private var instance: FlowChatAccessibilityService? = null

        fun current(): FlowChatAccessibilityService? = instance

        const val MaxObservedElements = AccessibilityScreenReader.MaxObservedElements
        private const val TapDurationMillis = 80L
        private const val LongPressDurationMillis = 650L
    }
}
