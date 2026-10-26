package com.flowchat.app.device

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.terminal.CommandEnvironment
import com.flowchat.app.domain.terminal.CommandStatus
import com.flowchat.app.presentation.overlay.FloatingTaskPhase
import com.flowchat.app.ui.theme.AppAppearance
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** MuMu opt-in: real Activity, overlay, confirmation dialog and harmless Android commands. */
class BackgroundConfirmationDiagnosticTest {
    @Test fun returningFromTheOverlayPreservesApprovalAndOrdinaryReturnsStillPause() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runBackgroundConfirmationDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val approveInOverlay = InstrumentationRegistry.getArguments().getString("confirmInOverlay") == "true"
        val context = instrumentation.targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val bridge = EntryPointAccessors.fromApplication(context, DeviceDiagnosticsEntryPoint::class.java).bridge()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val original = entry.settings().state.value
        val originalAppearance = AppAppearance.load(context)
        val evidence = File(context.getExternalFilesDir(null), "background-confirmation").apply { mkdirs() }
        val resultFile = File(evidence, "results.txt").apply { writeText("") }
        fun report(message: String) {
            resultFile.appendText(message + "\n")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }.also {
                check(!it.contains("Error:") && !it.contains("Exception")) { it }
            }
        suspend fun waitFor(label: String, predicate: () -> Boolean) = withTimeout(20_000) {
            while (!predicate()) { delay(100) }
            report(label)
        }
        suspend fun capture(name: String) {
            instrumentation.waitForIdleSync()
            delay(250) // Allow the emulator compositor to present the newly drawn windows.
            val bitmap = requireNotNull(automation.takeScreenshot())
            try {
                File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        fun outputWindow(): View? {
            var result: View? = null
            instrumentation.runOnMainSync {
                result = WindowInspector.getGlobalWindowViews().firstOrNull {
                    (it.layoutParams as? WindowManager.LayoutParams)?.title == "FloryxOutput" && it.isShown
                }
            }
            return result
        }
        fun tap(bounds: Rect) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
        }
        @Suppress("DEPRECATION")
        fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isVisibleToUser && predicate(node)) return node
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    val found = search(child)
                    if (found != null) { node.recycle(); return found }
                }
                node.recycle()
                return null
            }
            if (approveInOverlay && !bridge.appInForeground.value) {
                for (window in automation.windows) {
                    val found = window.root?.let(::search)
                    if (found != null) return found
                }
                return null
            }
            return automation.rootInActiveWindow?.let(::search)
        }
        @Suppress("DEPRECATION")
        fun hasText(text: String): Boolean {
            val node = findNode { it.text?.toString() == text } ?: return false
            node.recycle()
            return true
        }
        @Suppress("DEPRECATION")
        fun tapText(text: String) {
            val node = requireNotNull(findNode { it.text?.toString() == text && it.isEnabled }) { "Visible text missing: $text" }
            val bounds = Rect()
            try { node.getBoundsInScreen(bounds) } finally { node.recycle() }
            tap(bounds)
        }
        fun expandOverlay() {
            val output = requireNotNull(outputWindow())
            if (output.width > (48 * context.resources.displayMetrics.density).toInt()) return
            val center = IntArray(2)
            instrumentation.runOnMainSync {
                output.getLocationOnScreen(center)
                center[0] += output.width / 2
                center[1] += output.height / 2
            }
            tap(Rect(center[0] - 1, center[1] - 1, center[0] + 1, center[1] + 1))
        }
        fun showMain() = shell("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
        suspend fun background() {
            shell("am start -W -f 0x10008000 -n ${instrumentation.context.packageName}/${DeviceAutomationFixtureActivity::class.java.name} --ez overlay_probe true")
            waitFor("Fixture foreground; Floryx onStop observed") { !bridge.appInForeground.value }
        }
        @Suppress("DEPRECATION")
        suspend fun send(text: String) {
            val editor = requireNotNull(findNode { it.isEditable }) { "Message editor missing" }
            try {
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }))
            } finally { editor.recycle() }
            waitFor("Send button enabled") {
                val send = findNode { it.contentDescription?.toString() == context.getString(R.string.send) && it.isEnabled }
                val found = send != null
                send?.recycle()
                found
            }
            val send = requireNotNull(findNode { it.contentDescription?.toString() == context.getString(R.string.send) && it.isEnabled })
            val bounds = Rect()
            try { send.getBoundsInScreen(bounds) } finally { send.recycle() }
            tap(bounds)
        }
        val server = TerminalAgentDiagnosticTest.LocalTerminalModel(holdRootRequests = true)
        val provider = ProviderConfig(id = "background-approval-qa-${UUID.randomUUID()}", displayName = "Background approval QA",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "terminal-qa")
        var conversationId: String? = null
        suspend fun awaitAnswer(marker: String) = withTimeout(30_000) {
            while (data.chats().getMessages(conversationId!!).none {
                it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.content.contains(marker)
            }) {
                server.failure?.let { error(it) }
                delay(100)
            }
        }
        suspend fun returnForApproval(name: String) {
            background()
            server.releaseBackgroundRequest()
            waitFor("$name: request became pending while Floryx was in the background") {
                bridge.state.value.progress?.phase == FloatingTaskPhase.WaitingForUser && outputWindow() != null
            }
            expandOverlay()
            if (approveInOverlay) {
                waitFor("$name: floating approval buttons visible without returning to Floryx") {
                    hasText(context.getString(R.string.floating_confirmation_allow)) &&
                        hasText(context.getString(R.string.floating_confirmation_reject))
                }
                instrumentation.waitForIdleSync()
                delay(300) // WindowManager and accessibility bounds settle after large-font repositioning.
                assertFalse(bridge.appInForeground.value)
                val output = requireNotNull(outputWindow())
                val params = output.layoutParams as WindowManager.LayoutParams
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                val bounds = Rect()
                instrumentation.runOnMainSync {
                    val location = IntArray(2)
                    output.getLocationOnScreen(location)
                    bounds.set(location[0], location[1], location[0] + output.width, location[1] + output.height)
                }
                assertTrue("Approval must fit on screen: $bounds", bounds.bottom <= context.resources.displayMetrics.heightPixels)
                if (name == "allow") {
                    val pending = bridge.state.value.pendingDeviceActionConfirmation
                    val collapse = requireNotNull(findNode {
                        it.contentDescription?.toString() == context.getString(R.string.collapse_floating_assistant)
                    })
                    val collapseBounds = Rect()
                    try { collapse.getBoundsInScreen(collapseBounds) } finally { collapse.recycle() }
                    tap(collapseBounds)
                    waitFor("Collapsing keeps the request pending") {
                        outputWindow()?.width == (48 * context.resources.displayMetrics.density).toInt()
                    }
                    assertEquals(pending, bridge.state.value.pendingDeviceActionConfirmation)
                    assertTrue(entry.terminal().state.value.commands.none { it.environment == CommandEnvironment.Android })
                    capture("allow-collapsed")
                    expandOverlay()
                    waitFor("Reopening the floating request restores approval buttons") {
                        hasText(context.getString(R.string.floating_confirmation_allow))
                    }
                }
                capture("$name-overlay")
                report("$name: floating approval is touchable and non-focusable; Floryx remains in background")
                return
            }
            capture("$name-overlay")
            showMain()
            waitFor("$name: actual Activity returned to the foreground") { bridge.appInForeground.value }
            waitFor("$name: approval dialog visible in Android's actual accessibility window") {
                hasText(context.getString(R.string.terminal_system_approval_title))
            }
            assertFalse(hasText(context.getString(R.string.device_task_resume)))
            assertFalse(hasText(context.getString(R.string.device_task_end)))
            capture("$name-returned")
            report("$name: original system-command approval visible; no takeover card")
        }
        fun allow() = tapText(context.getString(if (approveInOverlay) R.string.floating_confirmation_allow else R.string.continue_action))
        fun decline() = tapText(context.getString(if (approveInOverlay) R.string.floating_confirmation_reject else R.string.cancel))
        suspend fun returnToChat() {
            showMain()
            waitFor("Chat returned to foreground") { bridge.appInForeground.value }
        }
        try {
            check(Settings.canDrawOverlays(context)) { "Overlay permission required" }
            data.providers().upsertProvider(provider, null)
            val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
            conversationId = conversation.id
            entry.settings().setMemoryEnabled(false)
            entry.settings().setCommandExecutionEnabled(true)
            entry.settings().setPowerConnectionMode(PowerConnectionMode.Root)
            entry.settings().setPowerModeEnabled(true)
            if (InstrumentationRegistry.getArguments().getString("backgroundAppearance") == "dark") {
                AppAppearance.save(context, AppAppearance.Dark)
            }
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(1_500) // Let the real startup animation finish; no virtual Compose clock.
            entry.device().requestPermission()
            waitFor("Power connected: actual Root gateway") { entry.device().connectionState.value.isConnected }
            report("Connection: ${entry.device().connectionState.value.status}")

            send("ROOT_ALLOW_QA run two harmless system commands")
            returnForApproval("allow")
            assertTrue(entry.terminal().state.value.commands.none { it.environment == CommandEnvironment.Android })
            allow()
            awaitAnswer("ROOT_AGENT_LOCAL_OK")
            if (approveInOverlay) assertFalse(bridge.appInForeground.value)
            val approvedCommands = entry.terminal().state.value.commands.filter { it.environment == CommandEnvironment.Android }
            assertEquals(2, approvedCommands.size)
            assertTrue(approvedCommands.all { it.status == CommandStatus.Exited && it.exitCode == 0 })
            assertTrue(approvedCommands.any { it.output.lineSequence().any { line -> line.trim() == "0" } })
            capture("allow-completed")
            report("PASS allow: two actual Android commands completed after approval; UID 0 verified")

            if (approveInOverlay) returnToChat()
            send("ROOT_DECLINE_QA refuse system commands")
            returnForApproval("cancel")
            decline()
            awaitAnswer("ROOT_DECLINE_LOCAL_OK")
            if (approveInOverlay) assertFalse(bridge.appInForeground.value)
            assertEquals(2, entry.terminal().state.value.commands.count { it.environment == CommandEnvironment.Android })
            assertTrue(entry.terminal().state.value.commands.none { it.command.contains("MUST_NOT_RUN") })
            report("PASS cancel: no Android command executed after cancellation")

            if (approveInOverlay) {
                returnToChat()
                send("ROOT_STOP_QA stop while system approval is pending")
                returnForApproval("stop")
                val pending = requireNotNull(bridge.state.value.pendingDeviceActionConfirmation)
                tapText(context.getString(R.string.stop))
                waitFor("Stopping clears the floating approval") {
                    !bridge.state.value.isStreaming && bridge.state.value.pendingDeviceActionConfirmation == null
                }
                bridge.respondToConfirmation(requireNotNull(conversationId), pending.requestId, true)
                delay(250)
                assertEquals(2, entry.terminal().state.value.commands.count { it.environment == CommandEnvironment.Android })
                assertFalse(bridge.appInForeground.value)
                capture("stopped-approval")
                report("PASS stop: pending approval dismissed; late approval cannot run a command")
                returnToChat()
                if (InstrumentationRegistry.getArguments().getString("overlayApprovalOnly") == "true") {
                    report("PASS focused floating-confirmation checks; main-screen takeover is covered separately in portrait")
                    return@runBlocking
                }
            }

            send("ROOT_TAKEOVER_QA run a harmless wait for takeover testing")
            returnForApproval("takeover")
            allow()
            waitFor("Actual Android wait command is running") {
                entry.terminal().state.value.commands.any { it.command.contains("TAKEOVER_READY") && it.output.contains("TAKEOVER_READY") && it.isRunning }
            }
            background()
            showMain()
            waitFor("Takeover card is visible in Android's actual accessibility window") { hasText(context.getString(R.string.device_task_resume)) }
            assertTrue(hasText(context.getString(R.string.device_task_end)))
            assertFalse(hasText(context.getString(R.string.terminal_system_approval_title)))
            waitFor("Takeover cancelled the actual Android command") {
                entry.terminal().state.value.commands.any { it.command.contains("TAKEOVER_READY") && it.status == CommandStatus.Cancelled }
            }
            assertTrue(entry.terminal().state.value.commands.filter { it.command.contains("TAKEOVER_READY") }.none { it.output.contains("MUST_NOT_REACH") })
            capture("ordinary-return-paused")
            tapText(context.getString(R.string.device_task_end))
            waitFor("End task cleared the takeover card") { !hasText(context.getString(R.string.device_task_resume)) }
            report("PASS takeover: ordinary return paused the task; running command cancelled; End task cleared the card")
            report("PASS all background-confirmation device checks")
        } catch (error: Throwable) {
            report("FAIL: $error; connection=${entry.device().connectionState.value.status}; phase=${bridge.state.value.progress?.phase}")
            runCatching { capture("failure") }
            throw error
        } finally {
            entry.terminal().cancelAll()
            entry.settings().setCommandExecutionEnabled(original.commandExecutionEnabled)
            entry.settings().setPowerModeEnabled(original.powerModeEnabled)
            entry.settings().setPowerConnectionMode(original.powerConnectionMode)
            entry.settings().setMemoryEnabled(original.memoryEnabled)
            AppAppearance.save(context, originalAppearance)
            conversationId?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            server.close()
        }
    }
}
