package com.flowchat.app.data.device

import android.os.Build
import android.os.SystemClock
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A task owns one display lease; failures never fall back to the physical screen. */
internal class BackgroundDeviceController(
    private val root: RootDeviceConnection,
    private val tasks: DeviceTaskControl,
    private val appPackage: String,
    private val enabled: () -> Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var owner: DeviceTaskControl.Task? = null
    private var attemptedOwner: String? = null
    private var display: RootDeviceConnection.ExecutionDisplayLease? = null
    private val snapshots = DeviceSnapshotGuard()
    private val frames = VisualFrameGuard()
    private var captureIntervalMillis = 280L

    fun release() {
        val previous = owner ?: return
        scope.launch { mutex.withLock { if (owner === previous) closeLocked() } }
    }

    private suspend fun closeLocked() {
        display?.close(); display = null; owner = null
        snapshots.remember(null); frames.clear()
    }

    private suspend fun lease(task: DeviceTaskControl.Task): RootDeviceConnection.ExecutionDisplayLease {
        tasks.checkCanAct(task)
        check(enabled() && Build.VERSION.SDK_INT >= 35) { "Background execution requires Root, Power mode and Android 15 or newer." }
        if (owner === task) return requireNotNull(display).also { check(it.alive) { "Execution display disconnected. Start a new task." } }
        check(attemptedOwner != task.id) { "This task's execution display was interrupted. Start a new task." }
        closeLocked()
        attemptedOwner = task.id
        val opened = root.openExecutionDisplay(task.id)
        try {
            tasks.checkCanAct(task)
            check(enabled()) { "Background execution was disabled." }
            owner = task; display = opened
            tasks.onFinish(task) {
                scope.launch { mutex.withLock { if (owner === task) closeLocked() } }
            }
            return opened
        } catch (error: Throwable) { opened.close(); throw error }
    }

    private suspend fun action(block: suspend (RootDeviceConnection.ExecutionDisplayLease, DeviceTaskControl.Task) -> DeviceToolResult): DeviceToolResult = mutex.withLock {
        val task = tasks.currentTask ?: return@withLock failure("background_task_required", "Start a phone task before using the execution display.")
        try {
            currentCoroutineContext().ensureActive()
            block(lease(task), task)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            snapshots.remember(null); frames.clear()
            failure("background_display_unavailable", error.message ?: "The execution display is unavailable. No physical-screen fallback was used.")
        }
    }

    suspend fun launch(packageName: String, activityName: String): DeviceToolResult = action { lease, task ->
        snapshots.remember(null); frames.clear()
        val result = withContext(Dispatchers.IO) { JSONObject(lease.remote.launchApp(packageName, activityName)) }
        if (!result.getBoolean("success")) return@action failure("background_launch_failed", result.optString("output"))
        tasks.checkCanAct(task)
        val actual = withContext(Dispatchers.IO) { JSONObject(lease.remote.info).optString("packageName") }
        if (actual != packageName) return@action failure("background_launch_unverified", "The requested app did not appear on the execution display.")
        observeLocked(lease, task).copy(summary = "Opened $packageName on the background execution display.")
    }

    suspend fun foreground(): DeviceToolResult = action { lease, _ ->
        val info = withContext(Dispatchers.IO) { JSONObject(lease.remote.info) }
        DeviceToolResult(true, "Background display ${info.getInt("displayId")}: ${info.optString("packageName")} / ${info.optString("activityName")}")
    }

    suspend fun observe(region: ScreenImageRegion? = null): DeviceToolResult = action { lease, task -> observeLocked(lease, task, region) }

    private suspend fun read(lease: RootDeviceConnection.ExecutionDisplayLease, settled: Boolean = false): DeviceScreenSnapshot? {
        val info = withContext(Dispatchers.IO) { JSONObject(lease.remote.info) }
        val id = info.getInt("displayId")
        check(id > 0)
        val service = FlowChatAccessibilityService.current() ?: error("Enable Floryx accessibility to read the execution display.")
        val screen = (if (settled) service.observeSettledScreenOnDisplay(id) else service.observeScreenOnDisplay(id)) ?: return null
        check(screen.geometry?.displayId == id && screen.geometry.displayWidth == info.getInt("width") &&
            screen.geometry.displayHeight == info.getInt("height") && screen.geometry.rotation == 0) { "Execution display geometry changed." }
        check(screen.packageName != appPackage) { "Floryx cannot automate its own interface." }
        return screen
    }

    private suspend fun observeLocked(lease: RootDeviceConnection.ExecutionDisplayLease, task: DeviceTaskControl.Task,
        region: ScreenImageRegion? = null, retry: Int = 0): DeviceToolResult {
        snapshots.remember(null); frames.clear()
        var before: DeviceScreenSnapshot? = null
        for (attempt in 0..5) {
            tasks.checkCanAct(task)
            before = read(lease, settled = true)
            if (before != null) break
            delay(100)
        }
        val screen = before ?: return failure("background_screen_unavailable", "No readable app window exists on the execution display.")
        if (screen.sensitive) return DeviceToolResult(false, "A sensitive screen was detected.", errorCode = "sensitive_screen_blocked", screen = screen)
        return try {
            // The platform window screenshot rejects FLAG_SECURE before any UI text is exposed.
            // It also uses this display's geometry and occlusion checks; there is no capture of display zero.
            val accessibility = requireNotNull(FlowChatAccessibilityService.current())
            val image = try {
                accessibility.captureScreenImage(screen, region, captureIntervalMillis)
            } catch (error: ScreenCaptureFailure) {
                if (error.code != "screen_capture_rate_limited" || captureIntervalMillis >= 500L) throw error
                // Some OEMs impose a longer interval than AOSP. Adapt without replaying an action.
                captureIntervalMillis = 500L
                delay(500L)
                accessibility.captureScreenImage(screen, region, captureIntervalMillis)
            }
            val after = read(lease)
            tasks.checkCanAct(task)
            val result = ScreenCapturePolicy.complete(screen, after, image)
            if (!result.success) {
                if (com.flowchat.app.BuildConfig.DEBUG) android.util.Log.d("BackgroundDisplay", "capture-rejected before=${screen.windowId}/${screen.geometry} after=${after?.windowId}/${after?.geometry} image=${image.screenId == screen.screenId}")
                if (result.errorCode == "screen_changed_during_capture" && retry < 2) {
                    delay(150)
                    return observeLocked(lease, task, region, retry + 1)
                }
                return failure(result.errorCode ?: "background_capture_failed", result.summary)
            }
            snapshots.remember(result.screen)
            frames.remember(result, SystemClock.elapsedRealtime(), task)
            result.copy(summary = "Observed background execution display ${screen.geometry?.displayId}.")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // A failed secure-window check must not leak accessibility text from that window.
            failure((error as? ScreenCaptureFailure)?.code ?: "background_capture_failed", "The background window could not be captured. No screen text or actions were exposed.")
        }
    }

    private suspend fun indexed(index: Int, screenId: String?, operation: suspend (RootDeviceConnection.ExecutionDisplayLease, DeviceScreenSnapshot) -> Boolean): DeviceToolResult = action { lease, task ->
        val current = read(lease, settled = true) ?: return@action failure("background_screen_unavailable", "Read the background app first.")
        if (!snapshots.matches(screenId, current, index)) return@action observeLocked(lease, task).copy(success = false,
            errorCode = "stale_screen", summary = "The background control changed. Select it again using this fresh screen_id.")
        // Recheck protected windows immediately before dispatch, preserving the prior index capability.
        val checked = observeLocked(lease, task)
        val screen = checked.screen
        if (!checked.success || screen == null || !current.hasSameUi(screen)) return@action checked.copy(success = false,
            errorCode = checked.errorCode ?: "stale_screen")
        tasks.checkCanAct(task)
        snapshots.remember(null); frames.clear()
        val accepted = operation(lease, screen)
        delay(100)
        observeLocked(lease, task).let { result ->
            val changed = result.screen?.hasSameUi(screen) == false
            result.copy(success = accepted && result.success && changed,
                errorCode = result.errorCode ?: if (!accepted) "background_action_rejected" else "ui_no_change".takeUnless { changed })
        }
    }

    suspend fun tap(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult = indexed(index, screenId) { lease, screen ->
        val target = screen.elements.firstOrNull { it.index == index && it.enabled && !it.password }
            ?: return@indexed false
        val bounds = requireNotNull(screen.geometry).windowBounds
        if (!bounds.contains(target.bounds.centerX, target.bounds.centerY)) return@indexed false
        withContext(Dispatchers.IO) { lease.remote.tap(target.bounds.centerX, target.bounds.centerY, longPress) }
    }

    suspend fun input(index: Int, text: String, clearExisting: Boolean, screenId: String?): DeviceToolResult = indexed(index, screenId) { _, screen ->
        if (text.length > 10_000 || screen.elements.none { it.index == index && it.editable && !it.password }) return@indexed false
        FlowChatAccessibilityService.current()?.inputTextOnDisplay(requireNotNull(screen.geometry).displayId, index, text, clearExisting) == true
    }

    suspend fun swipe(direction: DeviceSwipeDirection, distance: Int): DeviceToolResult = action { lease, task ->
        val before = observeLocked(lease, task)
        val geometry = before.screen?.geometry
        if (!before.success || geometry == null) return@action before
        val b = geometry.windowBounds
        val dx = b.width * distance.coerceIn(20, 80) / 200
        val dy = b.height * distance.coerceIn(20, 80) / 200
        val (x1, y1, x2, y2) = when (direction) {
            DeviceSwipeDirection.Up -> listOf(b.centerX, b.centerY + dy, b.centerX, b.centerY - dy)
            DeviceSwipeDirection.Down -> listOf(b.centerX, b.centerY - dy, b.centerX, b.centerY + dy)
            DeviceSwipeDirection.Left -> listOf(b.centerX + dx, b.centerY, b.centerX - dx, b.centerY)
            DeviceSwipeDirection.Right -> listOf(b.centerX - dx, b.centerY, b.centerX + dx, b.centerY)
        }
        tasks.checkCanAct(task)
        val accepted = withContext(Dispatchers.IO) { lease.remote.swipe(x1, y1, x2, y2, 350) }
        delay(150)
        val after = observeLocked(lease, task)
        val changed = after.screen?.progressKey() != before.screen?.progressKey() ||
            after.screenImage?.visualKey != before.screenImage?.visualKey
        after.copy(success = accepted && after.success && changed, summary = "Swiped on the background execution display. Inspect the result before continuing.",
            errorCode = after.errorCode ?: "ui_no_change".takeUnless { changed })
    }

    suspend fun back(): DeviceToolResult = action { lease, task ->
        val before = observeLocked(lease, task)
        if (!before.success) return@action before
        tasks.checkCanAct(task)
        val accepted = withContext(Dispatchers.IO) { lease.remote.pressBack() }
        delay(150)
        val after = observeLocked(lease, task)
        if (after.errorCode == "background_screen_unavailable" && accepted)
            DeviceToolResult(true, "The app closed on the execution display.")
        else after.copy(summary = "Pressed Back on the execution display.", success = accepted && after.success)
    }

    suspend fun home(): DeviceToolResult = action { lease, task ->
        tasks.checkCanAct(task)
        snapshots.remember(null); frames.clear()
        val cleared = withContext(Dispatchers.IO) { lease.remote.clearApps() }
        DeviceToolResult(cleared, "Cleared apps from the execution display. The phone's Home screen was not changed.")
    }

    suspend fun visual(target: VisualTapTarget): DeviceToolResult = action { lease, task ->
        val current = read(lease) ?: return@action failure("background_screen_unavailable", "Observe the background display again.")
        val frame = frames.current(target.screenId, current, SystemClock.elapsedRealtime(), task)
            ?: return@action observeLocked(lease, task).copy(success = false, errorCode = "stale_screen")
        if (frame.image.needsVisualRefinement) return@action observeLocked(lease, task, frame.image.visualRefinementRegion(target))
            .copy(visualRefinementRequired = true)
        val point = frame.image.visualTapPoint(target) ?: return@action failure("invalid_visual_target", "The target is outside the background window.")
        val fresh = observeLocked(lease, task, frame.image.region)
        val image = fresh.screenImage ?: return@action fresh.copy(success = false)
        val comparison = withContext(Dispatchers.Default) { ScreenImageMatcher.compare(frame.image, image, target) }
        if (!fresh.success || comparison.stableForTap != true || fresh.screen?.windowId != frame.screen.windowId)
            return@action fresh.copy(success = false, errorCode = "stale_visual_target")
        tasks.checkCanAct(task)
        val accepted = withContext(Dispatchers.IO) { lease.remote.tap(point.x, point.y, target.longPress) }
        delay(100)
        val after = observeLocked(lease, task, frame.image.region)
        val changed = after.screenImage?.let { withContext(Dispatchers.Default) { ScreenImageMatcher.compare(image, it, target).pixelsChanged } } == true ||
            after.screen?.progressKey() != fresh.screen?.progressKey()
        after.copy(success = accepted && after.success && changed, errorCode = after.errorCode ?: "ui_no_change".takeUnless { changed },
            summary = "Background gesture completed. Inspect the resulting image to verify the outcome.")
    }

    companion object {
        fun failure(code: String, summary: String) = DeviceToolResult(false, summary, errorCode = code)
    }
}
