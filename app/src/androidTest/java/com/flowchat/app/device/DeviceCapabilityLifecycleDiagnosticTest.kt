package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.AccessibilityConnectionStatus
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.device.DeviceSafetyReason
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt in with -e runDeviceCapabilityDiagnostics true. Uses the production singleton gateway
 * and safety monitor. Only an already-authorized Floryx Dev accessibility service is toggled.
 * No model, chat, screenshot, UI tree, physical lock or accessibility master setting is used.
 * The external runner must also restore/check service health after instrumentation exits.
 */
class DeviceCapabilityLifecycleDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val service get() = ComponentName(context, FlowChatAccessibilityService::class.java).also {
        check(it.flattenToString() == ServiceComponent) { "This diagnostic only targets the fixed Floryx Dev service" }
    }

    /** Null and an explicitly empty value are distinct, so exact settings ownership is retained. */
    private data class ServiceSelection(val value: String?) {
        fun components(): List<String> = value?.takeIf { it.isNotEmpty() }?.split(':').orEmpty()
    }

    private fun report(state: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\nPHONE_CAPABILITY_QA $state\n")
    })

    /** Closing the descriptor on cancellation bounds a blocked shell-output read as well. */
    private suspend fun shell(command: String): String = withTimeout(10_000) {
        val uiAutomation = automation
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<ParcelFileDescriptor?>()
            continuation.invokeOnCancellation { runCatching { active.getAndSet(null)?.close() } }
            thread(name = "capability-qa-shell", isDaemon = true) {
                val result = runCatching {
                    val descriptor = uiAutomation.executeShellCommand(command)
                    active.set(descriptor)
                    if (!continuation.isActive) {
                        active.getAndSet(null)?.close()
                        throw CancellationException("Diagnostic shell command cancelled")
                    }
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(4_096)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            check(output.length + count <= 1_048_576) { "Diagnostic shell output exceeded its bound" }
                            output.append(buffer, 0, count)
                        }
                        output.toString()
                    }
                }
                active.getAndSet(null)?.let { runCatching { it.close() } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }
    }

    private fun validateSelection(selection: ServiceSelection) {
        check(selection.components().all { ComponentPattern.matches(it) && ComponentName.unflattenFromString(it) != null }) {
            "Accessibility service list contains an unsupported component format; no settings were overwritten"
        }
        check(selection.value == null || selection.value.isEmpty() || selection.components().none { it.isEmpty() }) {
            "Accessibility service list has empty components; no settings were overwritten"
        }
    }

    private fun validatedComponentArgument(value: String): String {
        check(value.isNotEmpty() && value.split(':').all { ComponentPattern.matches(it) }) {
            "Refusing an unvalidated accessibility component list"
        }
        // UiAutomation's command transport accepts tokens, not a shell-quoted command line.
        // This grammar excludes whitespace, quotes, operators and substitutions.
        return value
    }

    private suspend fun readSelection(): ServiceSelection {
        val value = shell("settings get secure enabled_accessibility_services").trimEnd('\r', '\n')
        return ServiceSelection(value.takeUnless { it == "null" }).also(::validateSelection)
    }

    private suspend fun writeSelection(selection: ServiceSelection) {
        validateSelection(selection)
        // Preserve null/empty values exactly, without relying on command-line quote parsing.
        val uiAutomation = automation
        uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            check(withContext(Dispatchers.IO) {
                Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, selection.value)
            }) { "Android rejected the accessibility service-list update" }
        } finally { uiAutomation.dropShellPermissionIdentity() }
        check(readSelection() == selection) { "Accessibility service-list write was not confirmed by shell read-back" }
    }

    private fun isAuthorized(selection: ServiceSelection): Boolean = selection.components().any {
        ComponentName.unflattenFromString(it) == service
    }

    private suspend fun requireSelection(expected: ServiceSelection, stage: String) {
        check(readSelection() == expected) {
            "$stage: accessibility choices changed; preserved the current service list instead of replacing it"
        }
    }

    private suspend fun awaitState(label: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(20_000) { while (!condition()) delay(75) }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $label; accessibility recovery may need attention", error)
        }
    }

    private suspend fun refreshGateway() = withContext(Dispatchers.Main) {
        entry.deviceGateway().refreshAccessibilityConnection()
        entry.deviceGateway().refreshSafetyState()
    }

    private suspend fun awaitBound(original: ServiceSelection) {
        awaitState("production accessibility binding") {
            requireSelection(original, "While waiting for accessibility recovery")
            refreshGateway()
            val state = entry.deviceGateway().accessibilityState.value
            FlowChatAccessibilityService.current() != null && state.status == AccessibilityConnectionStatus.Connected &&
                state.enabled == true && state.isConnected
        }
        requireSelection(original, "After accessibility recovery")
    }

    private suspend fun verifySystemBindingAndCrashList() {
        val activity = shell("dumpsys activity services ${validatedComponentArgument(ServiceComponent)}")
        val componentPresent = activity.contains(ServiceComponent) || activity.contains(ShortServiceComponent)
        check(componentPresent && Regex("requested=true\\s+received=true\\s+hasBound=true").containsMatchIn(activity)) {
            "Production service recovered, but Android did not confirm hasBound=true; external recovery is required"
        }
        val accessibility = shell("dumpsys accessibility")
        val crashLines = Regex("(?m)^\\s*Crashed services:.*$").findAll(accessibility).map { it.value }.toList()
        check(crashLines.isNotEmpty()) { "Android crash-list format was not recognized; external crash-list verification is required" }
        val label = withContext(Dispatchers.Main) {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }
        check(crashLines.none { it.contains(context.packageName) || it.contains("FlowChatAccessibilityService") ||
            (label.isNotBlank() && it.contains(label)) }) {
            "Floryx accessibility is still in Android Crashed services; external recovery is required"
        }
        report("androidBound=true crashed=false")
    }

    /**
     * Settings has no atomic compare-and-set. Each write follows an exact fresh read;
     * an identical/ABA user write in the small read/write interval cannot be distinguished.
     */
    private suspend fun withTemporaryRemoval(original: ServiceSelection, exercise: suspend (ServiceSelection) -> Unit) {
        check(isAuthorized(original)) { "Floryx must already be authorized; this diagnostic will not grant new authorization" }
        val others = original.components().filterNot { ComponentName.unflattenFromString(it) == service }
        val temporary = ServiceSelection(others.joinToString(":").takeIf { it.isNotEmpty() })
        var attemptedRemoval = false
        var failure: Throwable? = null
        try {
            requireSelection(original, "Before temporary accessibility removal")
            check(entry.deviceTasks().currentTask == null) { "Stop the current phone task before this diagnostic" }
            // A shell failure can occur after its setting was applied, so finally must inspect ownership.
            attemptedRemoval = true
            writeSelection(temporary)
            exercise(temporary)
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (attemptedRemoval) withContext(NonCancellable) {
                try {
                    val current = readSelection()
                    check(current == temporary) {
                        "Accessibility choices changed during the diagnostic; preserved the current list without restoring a stale snapshot"
                    }
                    // Do not write accessibility_enabled: Android derives it from actual services.
                    writeSelection(original)
                    requireSelection(original, "Immediately after restoring accessibility authorization")
                    awaitBound(original)
                    verifySystemBindingAndCrashList()
                    requireSelection(original, "After verifying restored Android service health")
                    refreshGateway()
                    check(FlowChatAccessibilityService.current() != null &&
                        entry.deviceGateway().accessibilityState.value.status == AccessibilityConnectionStatus.Connected &&
                        entry.deviceGateway().accessibilityState.value.enabled == true) {
                        "Accessibility disconnected again before recovery verification completed"
                    }
                    report("restored=true enabled=true bound=true revision=${entry.safetyMonitor().state.value.revision}")
                } catch (restoreError: Throwable) {
                    report("restorationConfirmed=false currentChoicesPreserved=true enabled=${entry.deviceGateway().accessibilityState.value.enabled} " +
                        "bound=${FlowChatAccessibilityService.current() != null} revision=${entry.safetyMonitor().state.value.revision}")
                    if (failure == null) failure = AssertionError("Accessibility restoration was not confirmed", restoreError)
                    else failure?.addSuppressed(restoreError)
                }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun ensureAlreadyAuthorizedAccessibilityIsBound(original: ServiceSelection) {
        check(isAuthorized(original)) {
            "Precondition missing: Floryx accessibility is not already authorized; no new service will be enabled"
        }
        suspend fun bindingAppears() = withTimeoutOrNull(1_500) {
            while (FlowChatAccessibilityService.current() == null) delay(50)
            true
        } == true
        if (!bindingAppears()) {
            requireSelection(original, "Before launching Floryx for its already-authorized service")
            val launch = shell("am start -W --display 0 -f 0x10008000 -n ${validatedComponentArgument(MainComponent)}")
            check(!launch.contains("Error:") && !launch.contains("Exception")) { "Could not launch Floryx to recover its already-authorized service" }
            if (!bindingAppears()) withTemporaryRemoval(original) { temporary ->
                awaitState("initial stale accessibility service to disconnect") {
                    requireSelection(temporary, "During initial accessibility rebinding")
                    FlowChatAccessibilityService.current() == null
                }
                delay(250)
            }
        }
        awaitBound(original)
        verifySystemBindingAndCrashList()
    }

    private suspend fun assertCancelled(label: String, action: suspend () -> Unit) {
        var rejected = false
        try { action() } catch (_: CancellationException) { rejected = true }
        check(rejected) { "$label: an earlier execution target unexpectedly became valid" }
    }

    @Test
    fun revokedAccessibilityRestoresBindingButNeverRevivesAnEarlierTarget() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDeviceCapabilityDiagnostics") == "true")
        // Capture the original choices before initializing or refreshing the production objects.
        val original = readSelection()
        check(isAuthorized(original)) { "Floryx accessibility must already be authorized; service choices were left unchanged" }
        check(entry.deviceTasks().currentTask == null) { "Stop the current phone task before this opt-in diagnostic" }
        ensureAlreadyAuthorizedAccessibilityIsBound(original)
        val monitor = entry.safetyMonitor()
        val gateway = entry.deviceGateway()
        check(gateway.safetyState === monitor.state) { "Gateway and monitor do not expose the same production safety state" }
        val oldTarget = withContext(Dispatchers.Main) {
            refreshGateway()
            monitor.check()
            val settings = entry.settings().state.value
            DeviceExecutionTarget(settings.installId, "capability-lifecycle-qa-${UUID.randomUUID()}",
                settings.powerModeEnabled, settings.powerConnectionMode, settings.backgroundExecutionEnabled,
                settings.deviceConfigurationVersion, monitor.state.value.revision)
        }
        report("initial enabled=true bound=true revision=${oldTarget.safetyRevision}")
        withTemporaryRemoval(original) { temporary ->
            awaitState("real accessibility authorization revocation") {
                requireSelection(temporary, "While waiting for real accessibility revocation")
                refreshGateway()
                val state = gateway.accessibilityState.value
                // Removing the final setting can be unreadable from the target app on this ROM.
                // The shell read-back above proves removal; the app must be non-ready without
                // inventing a known permission value from an absent setting.
                val nonReady = (state.status == AccessibilityConnectionStatus.Disabled && state.enabled == false) ||
                    (state.status == AccessibilityConnectionStatus.Unavailable && state.enabled == null)
                nonReady && !state.isConnected &&
                    FlowChatAccessibilityService.current() == null && monitor.state.value.revision > oldTarget.safetyRevision
            }
            val state = gateway.accessibilityState.value
            check(monitor.state.value.reason == DeviceSafetyReason.AccessibilityLost) {
                "Revocation did not latch the accessibility-loss boundary"
            }
            check(gateway.safetyState.value.revision == monitor.state.value.revision) {
                "Production gateway and monitor safety revisions diverged"
            }
            report("revoked authorizationRemovedByShell=true status=${state.status} enabled=${state.enabled} bound=false revision=${monitor.state.value.revision}")
            assertCancelled("Revoked target check") { withContext(Dispatchers.Main) { monitor.check(oldTarget) } }
        }
        // Recovery restores prerequisites; it cannot authorize an operation planned before revocation.
        assertCancelled("Restored monitor target check") { withContext(Dispatchers.Main) { monitor.check(oldTarget) } }
        assertCancelled("Restored gateway target check") { withContext(oldTarget) { gateway.checkExecutionTarget() } }
        val freshTarget = oldTarget.copy(safetyRevision = monitor.state.value.revision)
        withContext(Dispatchers.Main) { monitor.check(freshTarget) }
        withContext(freshTarget) { gateway.checkExecutionTarget() }
        requireSelection(original, "After checking old and fresh safety targets")
        report("oldTargetRejected=true freshTargetAccepted=true enabled=true bound=true oldRevision=${oldTarget.safetyRevision} " +
            "currentRevision=${freshTarget.safetyRevision}")
    }

    companion object {
        private const val ServiceComponent = "com.floryx.app.debug/com.flowchat.app.data.device.FlowChatAccessibilityService"
        private const val ShortServiceComponent = "com.floryx.app.debug/.data.device.FlowChatAccessibilityService"
        private const val MainComponent = "com.floryx.app.debug/com.flowchat.app.MainActivity"
        private val ComponentPattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+/\\.?[A-Za-z_][A-Za-z0-9_\u0024]*(?:\\.[A-Za-z_][A-Za-z0-9_\u0024]*)*")
    }
}
