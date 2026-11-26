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
    private val adaptiveScroll = AdaptiveScrollController(SystemClock::elapsedRealtime)

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
        root.requireRequestCleanupVerified()
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
        catch (error: DeviceDispatchRejected) {
            snapshots.remember(null); frames.clear()
            val detail = if (error.code == "ui_input_text_unreadable") {
                "The background action was not dispatched (${error.code}). This editable control does not expose readable text to accessibility (selection exceeds text_length_utf16), so input_text cannot verify edits here. Tap the on-screen buttons or controls instead."
            } else {
                "The background action was not dispatched (${error.code}). Refresh the target, draft, focus and available actions before continuing."
            }
            failure(error.code, detail)
                .copy(actionOutcome = DeviceActionOutcome.NotDispatched)
        }
        catch (error: Exception) {
            snapshots.remember(null); frames.clear()
            failure("background_display_unavailable", error.message ?: "The execution display is unavailable. No physical-screen fallback was used.")
        }
    }

    suspend fun launch(packageName: String, activityName: String): DeviceToolResult = action { lease, task ->
        snapshots.remember(null); frames.clear()
        val result = JSONObject(lease.command { tasks.checkCanAct(task); it.launchApp(packageName, activityName) })
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

    suspend fun observe(region: ScreenImageRegion? = null, preserveSelector: Boolean = false,
        scope: DeviceObservationScope? = null): DeviceToolResult = action { lease, task ->
        observeLocked(lease, task, region, selector = snapshots.latest()?.elementQuery.takeIf { preserveSelector },
            scope = if (preserveSelector) snapshots.latest()?.observationScope else scope)
    }

    private suspend fun read(lease: RootDeviceConnection.ExecutionDisplayLease, settled: Boolean = false,
        selector: DeviceUiSelector? = null, scope: DeviceObservationScope? = null): DeviceScreenSnapshot? {
        val info = withContext(Dispatchers.IO) { JSONObject(lease.remote.info) }
        val id = info.getInt("displayId")
        check(id > 0)
        val service = FlowChatAccessibilityService.current() ?: error("Enable Floryx accessibility to read the execution display.")
        val screen = (if (settled && selector == null && scope == null) service.observeSettledScreenOnDisplay(id) else service.observeScreenOnDisplay(id, selector, scope)) ?: return null
        check(screen.geometry?.displayId == id && screen.geometry.displayWidth == info.getInt("width") &&
            screen.geometry.displayHeight == info.getInt("height") && screen.geometry.rotation == 0) { "Execution display geometry changed." }
        check(screen.packageName != appPackage) { "Floryx cannot automate its own interface." }
        return screen
    }

    private suspend fun observeLocked(lease: RootDeviceConnection.ExecutionDisplayLease, task: DeviceTaskControl.Task,
        region: ScreenImageRegion? = null, retry: Int = 0, selector: DeviceUiSelector? = null,
        scope: DeviceObservationScope? = null): DeviceToolResult {
        val priorGuardState = snapshots.exportState()
        snapshots.remember(null); frames.clear()
        var before: DeviceScreenSnapshot? = null
        for (attempt in 0..5) {
            tasks.checkCanAct(task)
            before = read(lease, settled = true, selector = selector, scope = scope)
            if (before != null) break
            delay(100)
        }
        val screen = before ?: return failure("background_screen_unavailable", "No readable app window exists on the execution display.")
        if (screen.sensitive) return DeviceToolResult(false, "A sensitive screen was detected.", errorCode = "sensitive_screen_blocked", screen = screen)
        screen.observationErrorCode?.let { return DeviceToolResult(false, "The observation root could not be uniquely resolved. No full-page fallback was used.", errorCode = it, screen = screen) }
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
            val after = read(lease, selector = selector, scope = scope)
            tasks.checkCanAct(task)
            val result = ScreenCapturePolicy.complete(screen, after, image)
            if (!result.success) {
                if (com.flowchat.app.BuildConfig.DEBUG) android.util.Log.d("BackgroundDisplay", "capture-rejected before=${screen.windowId}/${screen.geometry} after=${after?.windowId}/${after?.geometry} image=${image.screenId == screen.screenId}")
                if (result.errorCode == "screen_changed_during_capture" && retry < 2) {
                    delay(150)
                    return observeLocked(lease, task, region, retry + 1, selector, scope)
                }
                return failure(result.errorCode ?: "background_capture_failed", result.summary)
            }
            snapshots.rememberFrom(result.screen, priorGuardState)
            frames.remember(result, SystemClock.elapsedRealtime(), task)
            result.copy(summary = "Observed background execution display ${screen.geometry?.displayId}.")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if ((error as? ScreenCaptureFailure)?.code == "screen_capture_timeout" && retry < 1) {
                tasks.checkCanAct(task)
                delay(150)
                return observeLocked(lease, task, region, retry + 1, selector, scope)
            }
            // A failed secure-window check must not leak accessibility text from that window.
            failure((error as? ScreenCaptureFailure)?.code ?: "background_capture_failed", "The background window could not be captured. No screen text or actions were exposed.")
        }
    }

    suspend fun waitForCondition(request: DeviceScreenWaitRequest): DeviceToolResult = action { lease, task ->
        val baseline = snapshots.latest()
        request.condition?.regionChangedFrom?.let { id ->
            val frame = baseline?.let { frames.current(id, it, SystemClock.elapsedRealtime(), task) }
            return@action ScreenRegionWaiter({
                tasks.checkCanAct(task)
                observeLocked(lease, task, region = frame?.image?.region, selector = request.condition.element, scope = request.observationScope)
            }, SystemClock::elapsedRealtime).observe(request, frame)
        }
        snapshots.remember(null); frames.clear()
        val id = withContext(Dispatchers.IO) { JSONObject(lease.remote.info).getInt("displayId") }
        check(id > 0)
        val service = requireNotNull(FlowChatAccessibilityService.current())
        val waited = service.waitForCondition(request, baseline, id)
        tasks.checkCanAct(task)
        // Preserve the background secure-window capture gate before exposing controls.
        val verified = observeLocked(lease, task, selector = request.condition?.element, scope = request.observationScope)
        if (!verified.success) return@action verified.copy(conditionMatched = false, actionOutcome = DeviceActionOutcome.Unverified)
        val stillMatches = request.condition?.let { condition -> verified.screen?.let(condition::matches) == true } ?: true
        if (waited.conditionMatched == true && !stillMatches) return@action verified.copy(success = false,
            errorCode = "screen_condition_changed", summary = "The condition changed while capturing evidence. No action was replayed.",
            conditionMatched = false, actionOutcome = DeviceActionOutcome.Unverified)
        verified.copy(success = waited.success, summary = waited.summary, errorCode = waited.errorCode,
            conditionMatched = waited.conditionMatched, actionOutcome = waited.actionOutcome)
    }

    private suspend fun indexed(index: Int, screenId: String?, textInput: DeviceActionOutcomePolicy.TextInput? = null,
        selection: DeviceTextSelection? = null, scrollIndex: Int? = null,
        rangeAdjustment: DeviceRangeAdjustment? = null, fullSnapshot: Boolean = false, editorAction: Boolean = false,
        operation: suspend (RootDeviceConnection.ExecutionDisplayLease, DeviceScreenSnapshot, DeviceTaskControl.Task) -> Boolean): DeviceToolResult = action { lease, task ->
        val selector = snapshots.latest()?.elementQuery
        val scope = snapshots.latest()?.observationScope
        val current = read(lease, settled = true, selector = selector, scope = scope) ?: return@action failure("background_screen_unavailable", "Read the background app first.")
        val matchesSeen = if (editorAction) {
            snapshots.matchesEditorAction(screenId, current, index)
        } else {
            snapshots.matches(screenId, current, index.takeUnless { scrollIndex != null || fullSnapshot })
        }
        if (!matchesSeen) return@action observeLocked(lease, task).copy(success = false,
            errorCode = "stale_screen", summary = "The background control changed. Select it again using this fresh screen_id.",
            actionOutcome = DeviceActionOutcome.NotDispatched)
        val editorVolatileFilter = if (editorAction) snapshots.captureVolatileFilter(current, index) else null
        // Recheck protected windows immediately before dispatch, preserving the prior index capability.
        val checked = observeLocked(lease, task, selector = selector, scope = scope)
        val screen = checked.screen
        val dispatchValid = checked.success && screen != null && when {
            fullSnapshot || scrollIndex != null -> current.hasSameUi(screen)
            editorAction -> snapshots.matchesEditorSnapshot(current, screen, index, editorVolatileFilter)
            else -> DeviceSnapshotGuard.matchesSnapshot(current, screen, index)
        }
        if (!dispatchValid) return@action checked.copy(success = false,
            errorCode = checked.errorCode ?: "stale_screen", actionOutcome = DeviceActionOutcome.NotDispatched)
        val navigationBefore = if (scope != null && textInput == null && selection == null && scrollIndex == null && rangeAdjustment == null && !fullSnapshot)
            read(lease, selector = selector, scope = scope)?.takeIf { DevicePageTransitionPolicy.sourceMatches(screen, it) } else null
        tasks.checkCanAct(task)
        snapshots.remember(null); frames.clear()
        val accepted = operation(lease, screen, task)
        delay(100)
        val result = observeLocked(lease, task, selector = selector.takeIf { scope != null }, scope = scope)
        if (!accepted) return@action result.copy(success = false,
            errorCode = result.errorCode ?: "background_action_rejected",
            summary = "The background UI action was rejected or could not be confirmed. Inspect the resulting state before retrying; it may have taken effect.",
            actionOutcome = DeviceActionOutcome.Unverified)
        if (navigationBefore != null && (result.errorCode == "observation_root_not_found" ||
                DevicePageTransitionPolicy.needsWindowObservation(screen, result.screen))) {
            // observeLocked keeps the owned display and secure-window capture checks.
            return@action PageTransitionObservationVerifier.complete(screen, navigationBefore, result, true,
                readDeparture = { observeLocked(lease, task, selector = selector, scope = scope) },
                readDestination = { observeLocked(lease, task) }, check = { tasks.checkCanAct(task); check(lease.alive) })
        }
        val afterScreen = result.screen
        val suppressVolatileOnlyProgress = editorVolatileFilter != null && afterScreen != null &&
            editorVolatileFilter.matchesOnlyTelemetryOrVolatile(screen, afterScreen)
        val volatileReadableDriftOnly = suppressVolatileOnlyProgress && !screen.hasSameActionContent(afterScreen!!)
        val pixelsChanged = textInput == null && selection == null && scrollIndex == null && rangeAdjustment == null &&
            !fullSnapshot && !volatileReadableDriftOnly && changedPixels(checked, result)
        DeviceActionOutcomePolicy.complete(screen, result, "The background UI action was dispatched.", textInput, pixelsChanged,
            selection = selection, scrollIndex = scrollIndex, rangeAdjustment = rangeAdjustment,
            ignoreUiChange = suppressVolatileOnlyProgress)
    }

    suspend fun tap(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult = indexed(index, screenId) { lease, screen, task ->
        val target = screen.elements.firstOrNull { it.index == index && it.enabled && !it.password }
            ?: return@indexed false
        val fresh = requireNotNull(FlowChatAccessibilityService.current()).revalidateInteraction(screen, index)
        if (fresh.interactionContext?.allowsPoint(fresh, target.bounds.centerX, target.bounds.centerY) != true)
            throw DeviceDispatchRejected("ui_target_obscured")
        lease.command { tasks.checkCanAct(task); it.tap(target.bounds.centerX, target.bounds.centerY, longPress) }
    }

    suspend fun input(request: DeviceTextEditRequest, screenId: String?): DeviceToolResult =
        indexed(request.index, screenId, DeviceActionOutcomePolicy.TextInput(request.index, request.text,
            request.mode == DeviceTextEditMode.Replace, request.mode)) { _, screen, task ->
            requireNotNull(FlowChatAccessibilityService.current()).editText(request, screen) { tasks.checkCanAct(task) }
        }

    suspend fun tapLocatedTarget(index: Int, target: DeviceUiSelector, screenId: String): DeviceToolResult = indexed(index, screenId) { lease, screen, task ->
        val service = requireNotNull(FlowChatAccessibilityService.current())
        val fresh = service.revalidateInteraction(screen, index)
        val located = DeviceUiTargetPolicy.resolve(fresh, target)?.takeIf { it.index == index }
            ?: throw DeviceDispatchRejected("ui_target_changed")
        lease.command { tasks.checkCanAct(task); it.tap(located.bounds.centerX, located.bounds.centerY, false) }
    }

    suspend fun select(selection: DeviceTextSelection, screenId: String): DeviceToolResult =
        indexed(selection.index, screenId, selection = selection) { _, screen, task ->
            requireNotNull(FlowChatAccessibilityService.current()).setTextSelection(selection, screen) { tasks.checkCanAct(task) }
        }

    // Submitting protects the target input and static UI while allowing live clocks and known volatile widgets.
    suspend fun editorAction(index: Int, expectedLabel: String, screenId: String): DeviceToolResult =
        indexed(index, screenId, editorAction = true) { _, screen, task ->
            requireNotNull(FlowChatAccessibilityService.current()).performEditorAction(index, expectedLabel, screen) { tasks.checkCanAct(task) }
        }

    suspend fun scrollContainer(index: Int, direction: DeviceScrollDirection, screenId: String): DeviceToolResult =
        indexed(index, screenId, scrollIndex = index) { _, screen, task ->
            requireNotNull(FlowChatAccessibilityService.current()).scrollContainer(index, direction, screen) { tasks.checkCanAct(task) }
        }

    suspend fun anchoredGesture(request: DeviceAnchoredGesture, screenId: String): DeviceToolResult =
        indexed(request.index, screenId, fullSnapshot = true) { _, screen, task ->
            requireNotNull(FlowChatAccessibilityService.current()).anchoredGesture(request, screen) { tasks.checkCanAct(task) }
        }

    suspend fun adjustRange(request: DeviceRangeAdjustment, screenId: String): DeviceToolResult =
        indexed(request.index, screenId, rangeAdjustment = request) { _, screen, task ->
            requireNotNull(FlowChatAccessibilityService.current()).adjustRange(request, screen) { tasks.checkCanAct(task) }
        }

    suspend fun swipe(request: DeviceScrollRequest): DeviceToolResult = action { lease, task ->
        val selector = snapshots.latest()?.elementQuery
        val scope = snapshots.latest()?.observationScope
        val current = read(lease, selector = selector, scope = scope) ?: return@action failure("background_screen_unavailable", "Read the background app first.")
        val matches = request.screenId == null || snapshots.matchesScroll(request.screenId, current, request)
        var protectedResult = observeLocked(lease, task, selector = selector, scope = scope)
        val before = protectedResult.screen
        if (!protectedResult.success || before == null) return@action protectedResult.copy(actionOutcome = DeviceActionOutcome.NotDispatched)
        if (!matches || !DeviceAdaptiveScroll.sameScrollContext(current, before, request)) return@action protectedResult.copy(success = false,
            summary = "The background container changed. Choose it again from this fresh screen.", errorCode = "stale_screen",
            actionOutcome = DeviceActionOutcome.NotDispatched)
        val service = requireNotNull(FlowChatAccessibilityService.current())
        val originalContainer = DeviceAdaptiveScroll.area(before, request)?.container
        val result = adaptiveScroll.scroll(before, request, task.id, android.view.ViewConfiguration.get(service).scaledTouchSlop + 8,
            read = {
                protectedResult = observeLocked(lease, task, selector = selector, scope = scope)
                protectedResult.screen.takeIf { protectedResult.success }
            },
            capture = { observeLocked(lease, task, selector = selector, scope = scope) },
            dispatch = { expected, path, duration ->
                val fresh = service.revalidateInteraction(expected)
                if (!DeviceAdaptiveScroll.sameScrollContext(expected, fresh, request)) throw DeviceDispatchRejected("stale_screen")
                snapshots.remember(null); frames.clear()
                service.controlledScroll(expected, path, duration, originalContainer, request) { tasks.checkCanAct(task) }
            }, check = { tasks.checkCanAct(task) })
        val projected = if (protectedResult.success && protectedResult.screen?.screenId == result.screen?.screenId)
            result.copy(screenImage = protectedResult.screenImage) else result
        snapshots.remember(projected.screen); frames.remember(projected, SystemClock.elapsedRealtime(), task)
        projected
    }

    suspend fun back(): DeviceToolResult = action { lease, task ->
        val before = observeLocked(lease, task)
        if (!before.success) return@action before
        tasks.checkCanAct(task)
        val accepted = lease.command { tasks.checkCanAct(task); it.pressBack() }
        delay(150)
        val after = observeLocked(lease, task)
        if (after.errorCode == "background_screen_unavailable" && accepted) {
            val info = withContext(Dispatchers.IO) { JSONObject(lease.remote.info) }
            tasks.checkCanAct(task)
            if (info.getString("packageName").isBlank() && info.getString("activityName").isBlank())
                DeviceToolResult(true, "The app closed on the execution display.")
            else DeviceActionOutcomePolicy.complete(requireNotNull(before.screen), after, "Back was dispatched on the execution display.")
        } else if (!accepted) after.copy(success = false, errorCode = after.errorCode ?: "background_action_rejected",
            summary = "Back was rejected or could not be confirmed. Inspect the resulting state before retrying.")
        else DeviceActionOutcomePolicy.complete(requireNotNull(before.screen), after, "Back was dispatched on the execution display.")
    }

    suspend fun home(): DeviceToolResult = action { lease, task ->
        tasks.checkCanAct(task)
        snapshots.remember(null); frames.clear()
        val cleared = lease.command { tasks.checkCanAct(task); it.clearApps() }
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
        val checkedScreen = requireNotNull(fresh.screen)
        val dispatchScreen = requireNotNull(FlowChatAccessibilityService.current()).revalidateInteraction(checkedScreen)
        if (dispatchScreen.interactionContext?.allowsPoint(dispatchScreen, point.x, point.y) != true)
            throw DeviceDispatchRejected("ui_target_obscured")
        val accepted = lease.command { tasks.checkCanAct(task); it.tap(point.x, point.y, target.longPress) }
        delay(100)
        val after = observeLocked(lease, task, frame.image.region)
        if (!accepted) after.copy(success = false, errorCode = after.errorCode ?: "background_action_rejected",
            summary = "The background gesture was rejected or could not be confirmed. Inspect the resulting state before retrying.")
        else DeviceActionOutcomePolicy.complete(requireNotNull(fresh.screen), after,
            "The background gesture was dispatched.", pixelsChanged = changedPixels(fresh, after, target))
    }

    private suspend fun changedPixels(before: DeviceToolResult, after: DeviceToolResult, target: VisualTapTarget? = null): Boolean {
        val previous = before.screenImage ?: return false
        val current = after.screenImage ?: return false
        if (!after.success) return false
        return try {
            withContext(Dispatchers.Default) {
                ScreenImageMatcher.compare(previous, current,
                    target ?: VisualTapTarget(previous.screenId, 500, 500, "action result")).pixelsChanged
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
    }

    companion object {
        fun failure(code: String, summary: String) = DeviceToolResult(false, summary, errorCode = code)
    }
}
