package com.flowchat.app.data.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flowchat.app.domain.device.DeviceScreenSnapshot
import com.flowchat.app.domain.device.DeviceScreenImage
import com.flowchat.app.domain.device.DeviceSwipeDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FlowChatAccessibilityService : AccessibilityService() {
    private val indexedNodes = linkedMapOf<Int, AccessibilityNodeInfo>()
    private var cachedSnapshot: DeviceScreenSnapshot? = null
    private var snapshotInvalidated = true
    private val screenshotMutex = Mutex()
    private var lastScreenshotAt = -1_000L

    override fun onServiceConnected() {
        instance = this
        mutableConnected.value = true
        snapshotInvalidated = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> snapshotInvalidated = true
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        clearNodeCache()
        if (instance === this) {
            instance = null
            mutableConnected.value = false
        }
        super.onDestroy()
    }

    suspend fun observeScreen(forceRefresh: Boolean = true): DeviceScreenSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            if (!forceRefresh && !snapshotInvalidated) return@withContext cachedSnapshot
            val root = rootInActiveWindow ?: run {
                clearNodeCache()
                cachedSnapshot = null
                return@withContext null
            }
            try {
                val activityName = windows.firstOrNull { it.isActive }?.title?.toString().orEmpty()
                val metrics = resources.displayMetrics
                val readResult = AccessibilityScreenReader.read(root, activityName,
                    android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels))
                clearNodeCache()
                indexedNodes.putAll(readResult.indexedNodes)
                cachedSnapshot = readResult.snapshot
                snapshotInvalidated = false
                readResult.snapshot
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }

    // Two matching observations avoid returning a transient animation frame. Always bounded.
    suspend fun observeSettledScreen(): DeviceScreenSnapshot? {
        var previous = observeScreen()
        repeat(6) {
            delay(200)
            val current = observeScreen()
            if (current != null && (current.sensitive ||
                    (current.elements.isNotEmpty() && previous?.hasSameUi(current) == true))) return current
            previous = current
        }
        return previous
    }

    suspend fun captureScreenImage(screen: DeviceScreenSnapshot, region: com.flowchat.app.domain.device.ScreenImageRegion? = null): DeviceScreenImage = screenshotMutex.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ScreenCaptureFailure("screen_capture_requires_android_11")
        val remaining = 500L - (SystemClock.elapsedRealtime() - lastScreenshotAt)
        if (remaining > 0) delay(remaining)
        var bitmap: Bitmap? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                lastScreenshotAt = SystemClock.elapsedRealtime()
                bitmap = withTimeout(3_000) { captureBitmap(screen.windowId) }
            }
            val capturedAt = System.currentTimeMillis()
            withContext(Dispatchers.Default) { ScreenImageEncoder.encode(requireNotNull(bitmap), screen.screenId, capturedAt, region) }
        } finally { bitmap?.recycle() }
    }

    @android.annotation.TargetApi(30)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun captureBitmap(windowId: Int?): Bitmap = suspendCancellableCoroutine { continuation ->
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
            if (Build.VERSION.SDK_INT >= 34) {
                if (windowId == null || windowId < 0) throw ScreenCaptureFailure("screen_window_unavailable")
                takeScreenshotOfWindow(windowId, mainExecutor, callback)
            } else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
    }

    suspend fun tapUiElement(index: Int, longPress: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val node = indexedNodes[index] ?: return@withContext false
            if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser) return@withContext false
            val action = if (longPress) {
                AccessibilityNodeInfo.ACTION_LONG_CLICK
            } else {
                AccessibilityNodeInfo.ACTION_CLICK
            }
            if (node.performAction(action)) {
                snapshotInvalidated = true
                return@withContext true
            }
            // Labels often delegate their click to a containing row.
            var parent = node.parent
            var depth = 0
            while (parent != null && depth++ < 5) {
                val candidate = parent
                parent = null
                try {
                    if (candidate.isEnabled && candidate.isVisibleToUser &&
                        (if (longPress) candidate.isLongClickable else candidate.isClickable) &&
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
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            val metrics = resources.displayMetrics
            if (!bounds.intersect(0, 0, metrics.widthPixels, metrics.heightPixels)) return@withContext false
            dispatchPointGesture(
                x = bounds.centerX().toFloat(),
                y = bounds.centerY().toFloat(),
                durationMillis = if (longPress) LongPressDurationMillis else TapDurationMillis
            ).also { accepted -> if (accepted) snapshotInvalidated = true }
        }

    suspend fun inputText(index: Int, text: String, clearExisting: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val node = indexedNodes[index] ?: return@withContext false
            if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser || !node.isEditable || node.isPassword) {
                return@withContext false
            }
            val value = if (clearExisting) text else node.text?.toString().orEmpty() + text
            if (value.length > MaxInputTextLength) return@withContext false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments).also { changed ->
                if (changed) snapshotInvalidated = true
            }
        }

    suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int): Boolean =
        withContext(Dispatchers.Main.immediate) {
            // Video pagers can return true for ACTION_SCROLL_DOWN without moving. The swipe tool
            // promises finger motion, so dispatch a real gesture and await its completion.
            val width = resources.displayMetrics.widthPixels.toFloat()
            val height = resources.displayMetrics.heightPixels.toFloat()
            val distance = distancePercent.coerceIn(MinSwipePercent, MaxSwipePercent) / 100f
            val centerX = width / 2f
            val centerY = height / 2f
            val horizontalDelta = width * distance / 2f
            val verticalDelta = height * distance / 2f
            val coordinates = when (direction) {
                DeviceSwipeDirection.Up -> floatArrayOf(centerX, centerY + verticalDelta, centerX, centerY - verticalDelta)
                DeviceSwipeDirection.Down -> floatArrayOf(centerX, centerY - verticalDelta, centerX, centerY + verticalDelta)
                DeviceSwipeDirection.Left -> floatArrayOf(centerX + horizontalDelta, centerY, centerX - horizontalDelta, centerY)
                DeviceSwipeDirection.Right -> floatArrayOf(centerX - horizontalDelta, centerY, centerX + horizontalDelta, centerY)
            }
            val path = Path().apply {
                moveTo(coordinates[0], coordinates[1])
                lineTo(coordinates[2], coordinates[3])
            }
            awaitGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, SwipeDurationMillis))
                    .build()
            ).also { accepted -> if (accepted) snapshotInvalidated = true }
        }

    suspend fun pressBack(): Boolean = withContext(Dispatchers.Main.immediate) {
        performGlobalAction(GLOBAL_ACTION_BACK).also { accepted -> if (accepted) snapshotInvalidated = true }
    }

    suspend fun pressHome(): Boolean = withContext(Dispatchers.Main.immediate) {
        performGlobalAction(GLOBAL_ACTION_HOME).also { accepted -> if (accepted) snapshotInvalidated = true }
    }

    private suspend fun dispatchPointGesture(x: Float, y: Float, durationMillis: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        return awaitGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
        )
    }

    private suspend fun awaitGesture(gesture: GestureDescription): Boolean =
        withTimeoutOrNull(2_000) {
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
        private const val MaxInputTextLength = 4_000
        private const val TapDurationMillis = 80L
        private const val LongPressDurationMillis = 650L
        private const val SwipeDurationMillis = 350L
        private const val MinSwipePercent = 20
        private const val MaxSwipePercent = 80
    }
}
