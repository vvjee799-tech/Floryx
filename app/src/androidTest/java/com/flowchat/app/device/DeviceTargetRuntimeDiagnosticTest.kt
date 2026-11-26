package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.AutomationTimings
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.DeviceSafetyReason
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.chat.TaskPowerWork
import com.flowchat.app.domain.chat.TaskServiceState
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import com.flowchat.app.presentation.chat.DeviceTaskPhase
import dagger.hilt.android.EntryPointAccessors
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt in with runDeviceTargetDiagnostics=true. Uses the actual singleton runtime, settings,
 * Room and HTTP client. No desktop input, real model, API key or paid provider is used.
 * An already-authorized accessibility service may be rebound after the instrumentation restart.
 * A connected ScreenObservation capability is required: unavailable tools must not count as a pass.
 */
class DeviceTargetRuntimeDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
    private val data get() = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
    private val marker get() = AtomicFile(context.getFileStreamPath("phone-target-qa-fixture.json"))
    private val probeMarker get() = AtomicFile(context.getFileStreamPath("phone-target-qa-keystore.json"))

    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runDeviceTargetDiagnostics") == "true")
    private fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\nPHONE_TARGET_QA $text\n")
    })
    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!condition()) delay(40) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        report(label)
    }
    private fun persist(file: AtomicFile, value: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }
    private fun read(file: AtomicFile): JSONObject? = if (file.baseFile.exists()) {
        file.openRead().bufferedReader().use { JSONObject(it.readText()) }
    } else null

    private fun snapshot(settings: AppSettings) = JSONObject().apply {
        put("memory", settings.memoryEnabled)
        put("commands", settings.commandExecutionEnabled)
        put("power", settings.powerModeEnabled)
        put("background", settings.backgroundExecutionEnabled)
        put("connection", settings.powerConnectionMode.name)
        put("version", settings.deviceConfigurationVersion)
    }

    private fun assertExpectedSettings(expected: JSONObject) {
        val current = entry.settings().state.value
        check(current.memoryEnabled == expected.getBoolean("memory") &&
            current.commandExecutionEnabled == expected.getBoolean("commands") &&
            targetMatches(current, expected)) {
            "Settings changed outside this fixture; aborting without replacing the user's new choices"
        }
    }

    private fun targetMatches(current: AppSettings, expected: JSONObject) =
        current.powerModeEnabled == expected.getBoolean("power") &&
            current.backgroundExecutionEnabled == expected.getBoolean("background") &&
            current.powerConnectionMode.name == expected.getString("connection") &&
            current.deviceConfigurationVersion == expected.getLong("version")

    /** Write the intended state before each setting change so a failed run leaves a recovery record. */
    private suspend fun setBackground(runtime: ChatTaskRuntime, saved: JSONObject, value: Boolean) = withContext(Dispatchers.Main) {
        val expected = saved.getJSONObject("expected")
        assertExpectedSettings(expected)
        val previous = entry.settings().state.value
        saved.put("expected", snapshot(previous.copy(backgroundExecutionEnabled = value,
            deviceConfigurationVersion = previous.deviceConfigurationVersion + if (previous.backgroundExecutionEnabled != value) 1 else 0)))
        persist(marker, saved)
        runtime.setBackgroundExecutionEnabled(value)
        assertExpectedSettings(saved.getJSONObject("expected"))
    }

    /** Only records owned by this UUID provider can be removed. No broad title-based deletion. */
    private suspend fun restoreFixture() {
        val saved = read(marker) ?: return
        val namespace = saved.getString("namespace")
        check(namespace.startsWith(Prefix) && UUID.fromString(namespace.removePrefix(Prefix)).toString() == namespace.removePrefix(Prefix))
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val recorded = saved.optJSONArray("owners") ?: JSONArray()
        val ownerIds = (0 until recorded.length()).map { recorded.getString(it) }.toSet()
        val rows = data.chats().observeConversations().first().filter { it.providerId == namespace }
        val owned = rows.filter { it.id in ownerIds || it.title.startsWith(namespace) ||
            (it.title == "New chat" && data.chats().getMessages(it.id).isEmpty()) }
        withContext(Dispatchers.Main) { owned.forEach { runtime.stopFromAssistant(it.id) } }
        await("fixture-owned-tasks-stopped") { owned.none { it.id in runtime.activeConversations.value } }
        // sendFromAssistant never selects a QA chat. Delete only the owned IDs; the runtime
        // checks its actual selected ID, so a newly selected personal chat remains selected.
        withContext(Dispatchers.Main) { owned.forEach { runtime.deleteConversation(it.id) } }
        await("fixture-owned-conversations-removed") { owned.all { data.chats().getConversation(it.id) == null } }
        val provider = data.providers().getProvider(namespace)
        val remaining = data.chats().observeConversations().first().any { it.providerId == namespace }
        if (!remaining && provider != null && provider.apiKeyAlias == null &&
            provider.baseUrl == saved.getString("baseUrl") && provider.defaultModel == Model &&
            provider.customHeadersJson == saved.getString("headers") && provider.isEnabled &&
            provider.displayName == ProviderName && provider.conversationModels == listOf(Model)) {
            data.providers().deleteProvider(namespace)
        } else if (provider != null) report("preserved-edited-QA-provider-or-conversation")

        val original = saved.getJSONObject("original")
        val expected = saved.getJSONObject("expected")
        withContext(Dispatchers.Main) {
            val store = entry.settings()
            val current = store.state.value
            if (current.memoryEnabled == expected.getBoolean("memory")) store.setMemoryEnabled(original.getBoolean("memory"))
            else report("preserved-user-memory-setting")
            if (current.commandExecutionEnabled == expected.getBoolean("commands")) store.setCommandExecutionEnabled(original.getBoolean("commands"))
            else report("preserved-user-command-setting")
            // Configuration versions reset on process restart. Do not treat version=0 as proof
            // that an old marker still owns the current phone settings after a cold restart.
            if (saved.getString("process") == ProcessToken && targetMatches(current, expected)) {
                store.setPowerModeEnabled(original.getBoolean("power"))
                runtime.setBackgroundExecutionEnabled(original.getBoolean("background"))
            } else report("preserved-current-phone-settings-version-or-process-changed")
        }
        withContext(Dispatchers.IO) {
            check(context.getSharedPreferences("app_controls", Context.MODE_PRIVATE).edit().commit()) {
                "Could not flush restored settings"
            }
        }
        marker.delete()
    }

    private suspend fun preflight(saved: JSONObject, model: GatedLocalModel, owner: String, instruction: String) {
        assertExpectedSettings(saved.getJSONObject("expected"))
        val namespace = saved.getString("namespace")
        val provider = requireNotNull(data.providers().getProvider(namespace))
        val uri = URI(provider.baseUrl)
        check(namespace.startsWith(Prefix) && provider.id == namespace && provider.isEnabled &&
            provider.defaultModel == Model && provider.baseUrl == model.baseUrl &&
            provider.displayName == ProviderName && provider.conversationModels == listOf(Model) &&
            uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == model.port &&
            uri.path == "/v1" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            provider.customHeadersJson == saved.getString("headers") && provider.apiKeyAlias == null) {
            "Synthetic request refused: provider or current loopback endpoint ownership failed"
        }
        // A fresh UUID provider has no alias: this call cannot read a real provider's stored key.
        check(data.providers().getApiKey(provider) == null) { "Synthetic provider unexpectedly has a credential" }
        val owners = saved.getJSONArray("owners")
        check((0 until owners.length()).any { owners.getString(it) == owner }) { "Unowned QA conversation" }
        val chat = requireNotNull(data.chats().getConversation(owner))
        check(chat.providerId == namespace && chat.modelName == Model && chat.title.startsWith(namespace) &&
            chat.workspace == null && !chat.enableThinking && chat.systemPrompt.isEmpty() &&
            chat.assistantName.isEmpty() && chat.assistantAvatarPath == null) { "QA conversation settings changed" }
        check(data.chats().getMessages(owner).isEmpty() && data.chats().getContextCheckpoint(owner) == null &&
            instruction.startsWith(namespace)) { "Synthetic request refused: conversation is not empty and synthetic" }
    }

    private suspend fun awaitObservationReady(label: String) {
        val gateway = entry.deviceGateway()
        withContext(Dispatchers.Main) {
            gateway.refreshAccessibilityConnection()
            gateway.refreshConnection()
        }
        try {
            await("$label-screen-observation-ready") {
                val connection = gateway.connectionState.value
                (connection.isConnected || gateway.accessibilityState.value.isConnected) &&
                    DeviceCapability.ScreenObservation in connection.capabilities
            }
        } catch (error: AssertionError) {
            report("$label readiness connection=${gateway.connectionState.value.status} " +
                "accessibility=${gateway.accessibilityState.value.status} " +
                "screenObservation=${DeviceCapability.ScreenObservation in gateway.connectionState.value.capabilities}")
            throw error
        }
    }

    private data class AccessibilitySelection(val services: String, val master: String)

    private fun accessibilitySelection(automation: UiAutomation): AccessibilitySelection {
        fun setting(name: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("settings get secure $name")
        ).bufferedReader().use { it.readText() }.trim().takeUnless { it == "null" }.orEmpty()
        // Android 16 may filter the target app's settings read. The shell view is authoritative
        // for whether this service was already authorized before the diagnostic began.
        return AccessibilitySelection(setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            setting(Settings.Secure.ACCESSIBILITY_ENABLED))
    }

    private suspend fun ensureAlreadyAuthorizedAccessibilityIsBound() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val expected = ComponentName(context, FlowChatAccessibilityService::class.java)
        // ACCESSIBILITY_ENABLED is derived by Android from UiAutomation/bound services. It may
        // change while reconnecting and is neither authorization nor a value this fixture owns.
        fun authorized(selection: AccessibilitySelection) =
            selection.services.split(':').any { ComponentName.unflattenFromString(it) == expected }
        check(authorized(accessibilitySelection(automation))) {
            "Precondition missing: Floryx accessibility must already be in the enabled-service list; this fixture will not enable a revoked service"
        }
        suspend fun bindingAppears() = withTimeoutOrNull(1_500) {
            while (FlowChatAccessibilityService.current() == null) delay(50)
            true
        } == true

        if (!bindingAppears()) {
            // OriginOS can reject a background start from targetContext. Use the same shell
            // launcher path as the existing device diagnostics; no desktop input is involved.
            val component = ComponentName(context, com.flowchat.app.MainActivity::class.java).flattenToString()
            val launch = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "am start -W --display 0 -f 0x10008000 -n $component"
            )).bufferedReader().use { it.readText() }
            check(!launch.contains("Error:") && !launch.contains("Exception")) {
                "Could not start Floryx to reconnect its already-authorized accessibility service"
            }
            if (!bindingAppears()) {
                val original = accessibilitySelection(automation)
                check(authorized(original)) { "Accessibility authorization changed before rebinding; preserving the user's current choices" }
                val temporary = original.copy(services = original.services.split(':')
                    .filterNot { ComponentName.unflattenFromString(it) == expected }.joinToString(":"))
                automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
                try {
                    check(accessibilitySelection(automation).services == original.services) {
                        "Accessibility choices changed before temporary removal; refusing to overwrite them"
                    }
                    try {
                        check(Settings.Secure.putString(context.contentResolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, temporary.services)) {
                            "Could not temporarily unbind the already-authorized Floryx service"
                        }
                        delay(250)
                    } finally {
                        withContext(NonCancellable) {
                            val current = accessibilitySelection(automation)
                            when (current.services) {
                                original.services -> Unit // Already restored; avoid an unnecessary settings write.
                                temporary.services -> check(Settings.Secure.putString(context.contentResolver,
                                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original.services)) {
                                    "Could not restore the already-authorized Floryx accessibility service"
                                }
                                else -> {
                                    report("accessibility-rebind-preserved-new-selection " +
                                        "servicesChanged=true systemDerivedMaster=${current.master}")
                                    error("Accessibility service list changed during rebinding; preserved the user's new choices instead of restoring a stale snapshot")
                                }
                            }
                        }
                    }
                } finally { automation.dropShellPermissionIdentity() }
                report("accessibility-already-authorized-service-rebind-requested")
            }
        }
        await("accessibility-current-service-and-gateway-bound") {
            withContext(Dispatchers.Main) { entry.deviceGateway().refreshAccessibilityConnection() }
            FlowChatAccessibilityService.current() != null && entry.deviceGateway().accessibilityState.value.isConnected
        }
        val finalSelection = accessibilitySelection(automation)
        check(authorized(finalSelection)) {
            "Accessibility authorization changed while waiting for binding; preserving the user's current choices"
        }
        report("accessibility-authorization-retained bound=true gatewayConnected=true systemDerivedMaster=${finalSelection.master}")
    }

    @Test fun delayedPhonePlansAreRejectedAfterModeChangesWhilePlainChatStillCompletes() = runBlocking<Unit> {
        enabled()
        ensureAlreadyAuthorizedAccessibilityIsBound()
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        check(runtime.activeConversations.value.isEmpty()) { "Stop existing tasks before this opt-in diagnostic" }
        // Keep the actual UI projection current without selecting a conversation or consuming its draft.
        val collector = launch(Dispatchers.Main) { runtime.uiState.collect() }
        try { exerciseFixture(runtime) } finally { collector.cancel() }
    }

    private suspend fun withFixture(runtime: ChatTaskRuntime,
        exercise: suspend (JSONObject, GatedLocalModel, String) -> Unit) {
        val existing = data.chats().observeConversations().first().map { it.id }.toSet()
        await("runtime-projection-ready") { runtime.uiState.value.conversations.map { it.id }.toSet() == existing }
        val initial = entry.settings().state.value
        val namespace = Prefix + UUID.randomUUID()
        val model = GatedLocalModel(namespace)
        val headers = JSONObject().put("X-Phone-Target-QA", namespace).toString()
        val saved = JSONObject().apply {
            put("namespace", namespace); put("baseUrl", model.baseUrl); put("headers", headers)
            put("process", ProcessToken)
            put("original", snapshot(initial)); put("expected", snapshot(initial)); put("owners", JSONArray())
        }
        try {
            persist(marker, saved)
            withContext(Dispatchers.Main) {
                assertExpectedSettings(saved.getJSONObject("expected"))
                val expected = initial.copy(memoryEnabled = false, commandExecutionEnabled = false, powerModeEnabled = true,
                    deviceConfigurationVersion = initial.deviceConfigurationVersion + if (initial.powerModeEnabled) 0 else 1)
                saved.put("expected", snapshot(expected)); persist(marker, saved)
                entry.settings().setMemoryEnabled(false)
                entry.settings().setCommandExecutionEnabled(false)
                entry.settings().setPowerModeEnabled(true)
                assertExpectedSettings(saved.getJSONObject("expected"))
            }
            data.providers().upsertProvider(ProviderConfig(id = namespace, displayName = ProviderName,
                baseUrl = model.baseUrl, defaultModel = Model, customHeadersJson = headers,
                conversationModels = listOf(Model)), null)
            exercise(saved, model, namespace)
            model.checkFailure()
        } finally {
            try { model.close() } finally { restoreFixture() }
        }
    }

    private suspend fun exerciseFixture(runtime: ChatTaskRuntime) = withFixture(runtime) { saved, model, namespace ->
            val changes = listOf(listOf(false, true), listOf(true, false), listOf(false, true, false))
            for (phoneTool in listOf(true, false)) for ((index, backgrounds) in changes.withIndex()) {
                val label = "${if (phoneTool) "stale-tool" else "plain-text"}-$index"
                setBackground(runtime, saved, backgrounds.first())
                // Root/accessibility reconnection after a setting change is asynchronous.
                // Wait before freezing this request's available-tool set, then also verify
                // the exact HTTP request actually advertised observe_screen below.
                if (phoneTool) awaitObservationReady(label)
                val chat = data.chats().createConversation(namespace, Model)
                saved.getJSONArray("owners").put(chat.id); persist(marker, saved)
                data.chats().updateConversationTitle(chat.id, "$namespace-$label")
                data.chats().updateConversationWorkspace(chat.id, null)
                data.chats().updateThinkingSettings(chat.id, false, ThinkingEffort.Auto)
                val instruction = "$namespace $label synthetic request"
                val round = model.prepare(instruction, phoneTool)
                preflight(saved, model, chat.id, instruction)
                val sentSettings = entry.settings().state.value
                withContext(Dispatchers.Main) {
                    assertExpectedSettings(saved.getJSONObject("expected"))
                    check(runtime.sendFromAssistant(chat.id, instruction)) { "Runtime rejected owned QA request" }
                }
                await("$label-waiting-for-local-model") {
                    model.checkFailure()
                    round.received.count == 0L && runtime.taskProgress.value[chat.id]?.phase == DeviceTaskPhase.WaitingForModel
                }
                // The app generates a short title for a first message; restore the full namespace.
                data.chats().updateConversationTitle(chat.id, "$namespace-$label")
                val captured = requireNotNull(runtime.taskProgress.value[chat.id])
                assertEquals(chat.id, captured.ownerConversationId)
                assertEquals(chat.id, captured.target.ownerConversationId)
                assertEquals(sentSettings.installId, captured.target.deviceId)
                assertTrue(captured.target.powerEnabled)
                assertEquals(sentSettings.powerConnectionMode, captured.target.connectionMode)
                assertEquals(backgrounds.first(), captured.target.backgroundExecution)
                assertEquals(sentSettings.deviceConfigurationVersion, captured.target.configurationVersion)
                // Verify it is an available phone tool, not an unavailable-tool rejection.
                if (phoneTool) check(round.observationAdvertised) {
                    "Precondition missing: connected Root/accessibility with ScreenObservation capability is required"
                }
                for (value in backgrounds.drop(1)) setBackground(runtime, saved, value)
                assertEquals(DeviceTaskPhase.WaitingForModel, runtime.taskProgress.value[chat.id]?.phase)
                assertEquals(captured.target, runtime.taskProgress.value[chat.id]?.target)
                check(entry.settings().state.value.deviceConfigurationVersion > captured.target.configurationVersion)
                round.release.countDown()
                await("$label-ended") {
                    model.checkFailure()
                    chat.id !in runtime.activeConversations.value && runtime.taskProgress.value[chat.id]?.phase == DeviceTaskPhase.Ended
                }
                val messages = data.chats().getMessages(chat.id)
                val assistants = messages.filter { it.role == MessageRole.Assistant }
                assertEquals("Exactly one model request is allowed per fresh synthetic conversation", 1, round.requests.get())
                assertEquals(captured.target, runtime.taskProgress.value[chat.id]?.target)
                assertFalse("A stale plan must never have a successful phone-tool result",
                    messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Complete })
                if (phoneTool) {
                    assertTrue("Late tool response must be stopped by the target guard", assistants.any { it.status == MessageStatus.Stopped })
                    assertEquals("Stopped", entry.journal().get(captured.runId)?.state)
                    assertTrue("Expected cancelled observe_screen evidence, rather than an unrelated failure",
                        messages.any { it.role == MessageRole.Tool && it.content.startsWith("observe_screen") && it.status == MessageStatus.Cancelled })
                    assertTrue(assistants.any { it.executionContext.orEmpty().contains("interrupted_unconfirmed") })
                } else {
                    assertTrue("Ordinary chat must finish despite phone setting changes",
                        assistants.any { it.content == PrefixText + FinalText && it.status == MessageStatus.Complete })
                    assertEquals("Completed", entry.journal().get(captured.runId)?.state)
                    assertFalse(messages.any { it.role == MessageRole.Tool })
                }
                report("$label verified requests=1 originalTargetRetained=true phoneToolSucceeded=false")
            }
            model.checkFailure()
    }

    /** Uses the production monitor's singleton; it changes no Android lock or permission setting. */
    private suspend fun simulateSafetyBoundary(reason: DeviceSafetyReason) = withContext(Dispatchers.Main) {
        val monitor = entry.safetyMonitor()
        val gateway = entry.deviceGateway()
        assertSame(monitor.state, gateway.safetyState)
        gateway.refreshSafetyState()
        val before = monitor.boundary.state.value.revision
        try {
            when (reason) {
                DeviceSafetyReason.Locked -> monitor.boundary.updateScreen(true, false, System.currentTimeMillis())
                DeviceSafetyReason.ScreenOff -> monitor.boundary.updateScreen(false, false, System.currentTimeMillis())
                else -> monitor.boundary.invalidate(reason)
            }
        } finally {
            // Recover from real system readings, never by pretending the physical phone is unlocked.
            gateway.refreshSafetyState()
        }
        val recovered = monitor.boundary.state.value
        assertTrue("A recovered capability must retain the invalidating revision", recovered.revision > before)
        assertEquals(false, recovered.screenLocked)
        assertEquals(true, recovered.screenInteractive)
        assertNotNull(recovered.checkedAtMillis)
    }

    /** Only timing metadata is compared; no screen, hierarchy or personal text is read here. */
    private fun observationAttempts() = AutomationTimings.snapshot().filter { it.stage == "device.observe" }

    @Test fun delayedPhonePlansAreRejectedAfterSafetyRecoveryWhilePlainChatStillCompletes() = runBlocking<Unit> {
        enabled()
        ensureAlreadyAuthorizedAccessibilityIsBound()
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        check(runtime.activeConversations.value.isEmpty()) { "Stop existing tasks before this opt-in diagnostic" }
        val collector = launch(Dispatchers.Main) { runtime.uiState.collect() }
        try {
            withFixture(runtime) { saved, model, namespace ->
                setBackground(runtime, saved, false)
                for (phoneTool in listOf(true, false)) for (reason in listOf(
                    DeviceSafetyReason.AccessibilityLost, DeviceSafetyReason.PowerConnectionLost, DeviceSafetyReason.Locked
                )) {
                    val label = "safety-${reason.name.lowercase(Locale.ROOT)}-${if (phoneTool) "stale-tool" else "plain-text"}"
                    if (phoneTool) awaitObservationReady(label)
                    withContext(Dispatchers.Main) { entry.deviceGateway().refreshSafetyState() }
                    entry.safetyMonitor().boundary.requireScreenAvailable()
                    val chat = data.chats().createConversation(namespace, Model)
                    saved.getJSONArray("owners").put(chat.id); persist(marker, saved)
                    data.chats().updateConversationTitle(chat.id, "$namespace-$label")
                    data.chats().updateConversationWorkspace(chat.id, null)
                    data.chats().updateThinkingSettings(chat.id, false, ThinkingEffort.Auto)
                    val instruction = "$namespace $label synthetic request"
                    val round = model.prepare(instruction, phoneTool)
                    preflight(saved, model, chat.id, instruction)
                    withContext(Dispatchers.Main) {
                        assertExpectedSettings(saved.getJSONObject("expected"))
                        check(runtime.sendFromAssistant(chat.id, instruction)) { "Runtime rejected owned QA request" }
                    }
                    await("$label-waiting-for-local-model") {
                        model.checkFailure()
                        round.received.count == 0L && runtime.taskProgress.value[chat.id]?.phase == DeviceTaskPhase.WaitingForModel
                    }
                    data.chats().updateConversationTitle(chat.id, "$namespace-$label")
                    val captured = requireNotNull(runtime.taskProgress.value[chat.id])
                    assertEquals(entry.safetyMonitor().state.value.revision, captured.target.safetyRevision)
                    if (phoneTool) check(round.observationAdvertised) {
                        "Precondition missing: observe_screen must actually be advertised before the safety boundary changes"
                    }
                    val attemptsBefore = observationAttempts()
                    simulateSafetyBoundary(reason)
                    assertTrue(entry.deviceGateway().safetyState.value.revision > captured.target.safetyRevision)
                    assertEquals("A model-only wait must remain available for ordinary chat",
                        DeviceTaskPhase.WaitingForModel, runtime.taskProgress.value[chat.id]?.phase)
                    val expectedPhase = if (phoneTool) DeviceTaskPhase.Paused else DeviceTaskPhase.Ended
                    val expectedJournalState = if (phoneTool) "Paused" else "Completed"
                    round.release.countDown()
                    await("$label-ended") {
                        model.checkFailure()
                        chat.id !in runtime.activeConversations.value && runtime.taskProgress.value[chat.id]?.phase == expectedPhase &&
                            entry.journal().get(captured.runId)?.state == expectedJournalState
                    }
                    val messages = data.chats().getMessages(chat.id)
                    val assistants = messages.filter { it.role == MessageRole.Assistant }
                    assertEquals(1, round.requests.get())
                    assertEquals(captured.target, runtime.taskProgress.value[chat.id]?.target)
                    assertEquals("The late plan must not enter the real observe_screen gateway", attemptsBefore, observationAttempts())
                    assertFalse(messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Complete })
                    if (phoneTool) {
                        assertTrue(assistants.any { it.status == MessageStatus.Stopped &&
                            it.executionContext.orEmpty().contains("interrupted_unconfirmed") })
                        assertTrue("Expected the late tool to be cancelled by its captured safety revision", messages.any {
                            it.role == MessageRole.Tool && it.content.startsWith("observe_screen") && it.status == MessageStatus.Cancelled
                        })
                        assertEquals("Paused", entry.journal().get(captured.runId)?.state)
                        assertTrue(runtime.observeFromAssistant(chat.id).first().deviceTaskPaused)
                    } else {
                        assertTrue("Ordinary chat must finish after phone safety changes", assistants.any {
                            it.content == PrefixText + FinalText && it.status == MessageStatus.Complete
                        })
                        assertFalse(messages.any { it.role == MessageRole.Tool })
                        assertEquals("Completed", entry.journal().get(captured.runId)?.state)
                    }
                    report("$label verified requests=1 capturedSafetyRevisionRetained=true screenRecovered=true actualObservationCalls=0")
                }
            }
        } finally { collector.cancel() }
    }

    @Test fun actualGatewaySafetySnapshotUsesTheProductionMonitorAndKnownScreenReadings() = runBlocking<Unit> {
        enabled()
        val monitor = entry.safetyMonitor()
        val gateway = entry.deviceGateway()
        withContext(Dispatchers.Main) { gateway.refreshSafetyState() }
        assertSame("The gateway must expose the singleton monitor state", monitor.state, gateway.safetyState)
        assertSame(monitor.boundary.state, gateway.safetyState)
        val actual = gateway.safetyState.value
        assertEquals("Run this opt-in snapshot check while the physical phone is unlocked", false, actual.screenLocked)
        assertEquals("Run this opt-in snapshot check while the physical display is awake", true, actual.screenInteractive)
        assertTrue("The screen check must have a known timestamp", (actual.checkedAtMillis ?: 0L) > 0L)
        monitor.boundary.requireScreenAvailable()
        report("safety-snapshot verified singletonShared=true locked=false interactive=true checkedTimeKnown=true")
    }

    /** Fixed power keys only. The pipe reader cannot block Main or keep the caller waiting indefinitely. */
    private suspend fun screenPowerKey(code: Int) {
        check(code == 223 || code == 224) { "This diagnostic only supports screen sleep and wake keys" }
        val descriptor = withContext(Dispatchers.IO) {
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("input keyevent $code")
        }
        val completed = CompletableDeferred<Unit>()
        thread(isDaemon = true, name = "phone-target-qa-power-key") {
            try {
                val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                check(output.isEmpty()) { "The screen power key returned unexpected shell output" }
                completed.complete(Unit)
            } catch (error: Throwable) { completed.completeExceptionally(error) }
        }
        try { withTimeout(5_000) { completed.await() } }
        finally {
            // Close our descriptor on completion or timeout; the dedicated reader never holds Main.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { descriptor.close() } }
        }
    }

    @Test fun actualScreenLockPausesTheLatePhonePlanAndManualUnlockDoesNotResumeIt() = runBlocking<Unit> {
        enabled()
        check(Build.MODEL == "V2302A" && Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
            "This physical-screen diagnostic requires the separately verified V2302A phone"
        }
        ensureAlreadyAuthorizedAccessibilityIsBound()
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        check(runtime.activeConversations.value.isEmpty()) { "Stop existing tasks before this opt-in diagnostic" }
        val collector = launch(Dispatchers.Main) { runtime.uiState.collect() }
        try {
            withFixture(runtime) { saved, model, namespace ->
                setBackground(runtime, saved, false)
                awaitObservationReady("actual-screen-lock")
                val monitor = entry.safetyMonitor()
                withContext(Dispatchers.Main) { monitor.check() }
                assertEquals(false, monitor.state.value.screenLocked)
                assertEquals(true, monitor.state.value.screenInteractive)
                val chat = data.chats().createConversation(namespace, Model)
                saved.getJSONArray("owners").put(chat.id); persist(marker, saved)
                data.chats().updateConversationTitle(chat.id, "$namespace-actual-screen-lock")
                data.chats().updateConversationWorkspace(chat.id, null)
                data.chats().updateThinkingSettings(chat.id, false, ThinkingEffort.Auto)
                val instruction = "$namespace actual-screen-lock synthetic request"
                val round = model.prepare(instruction, true)
                preflight(saved, model, chat.id, instruction)
                withContext(Dispatchers.Main) {
                    assertExpectedSettings(saved.getJSONObject("expected"))
                    check(runtime.sendFromAssistant(chat.id, instruction)) { "Runtime rejected owned QA request" }
                }
                await("actual-screen-lock-waiting-for-local-model") {
                    model.checkFailure()
                    round.received.count == 0L && runtime.taskProgress.value[chat.id]?.phase == DeviceTaskPhase.WaitingForModel
                }
                data.chats().updateConversationTitle(chat.id, "$namespace-actual-screen-lock")
                check(round.observationAdvertised) { "observe_screen must be available before the physical screen is locked" }
                val captured = requireNotNull(runtime.taskProgress.value[chat.id])
                assertEquals(monitor.state.value.revision, captured.target.safetyRevision)
                val attemptsBefore = observationAttempts()
                var sleepRequested = false
                try {
                    // Bound the entire dark-screen interval. User unlock is requested only after waking.
                    withTimeout(15_000) {
                        sleepRequested = true
                        screenPowerKey(223)
                        while (true) {
                            withContext(Dispatchers.Main) { monitor.refresh() }
                            val actual = monitor.state.value
                            if (actual.screenInteractive == false && actual.screenLocked == true &&
                                actual.revision > captured.target.safetyRevision) break
                            delay(40)
                        }
                        report("actual-screen-lock screenLocked=true screenInteractive=false revisionAdvanced=true")
                        round.release.countDown()
                        while (true) {
                            model.checkFailure()
                            if (chat.id !in runtime.activeConversations.value &&
                                runtime.taskProgress.value[chat.id]?.phase == DeviceTaskPhase.Paused &&
                                entry.journal().get(captured.runId)?.state == "Paused" &&
                                data.chats().getMessages(chat.id).none { it.status == MessageStatus.Streaming }) break
                            delay(40)
                        }
                        assertEquals(attemptsBefore, observationAttempts())
                        val messages = data.chats().getMessages(chat.id)
                        assertTrue(messages.any { it.role == MessageRole.Tool && it.content.startsWith("observe_screen") &&
                            it.status == MessageStatus.Cancelled })
                        assertFalse(messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Complete })
                        assertTrue(runtime.observeFromAssistant(chat.id).first().deviceTaskPaused)
                    }
                } finally {
                    if (sleepRequested) withContext(NonCancellable) {
                        // Always wake first, including assertion/cancellation failures. Never dismiss the keyguard.
                        val wakeFailure = runCatching { screenPowerKey(224) }.exceptionOrNull()
                        report("NEED_USER_UNLOCK physical screen wake requested; unlock manually within 180 seconds")
                        val unlocked = withTimeoutOrNull(180_000) {
                            while (true) {
                                withContext(Dispatchers.Main) { monitor.refresh() }
                                val actual = monitor.state.value
                                if (actual.screenLocked == false && actual.screenInteractive == true) break
                                delay(100)
                            }
                            true
                        } == true
                        check(unlocked) { "Manual unlock did not complete within 180 seconds; no lock settings were changed" }
                        if (wakeFailure != null) throw AssertionError("Screen wake command failed before manual recovery", wakeFailure)
                        report("actual-screen-lock unlockObserved=true interactive=true")
                    }
                }
                repeat(10) {
                    delay(50)
                    model.checkFailure()
                    assertTrue("Unlock must not restart the old phone task", runtime.activeConversations.value.isEmpty())
                    assertEquals(DeviceTaskPhase.Paused, runtime.taskProgress.value[chat.id]?.phase)
                    assertEquals("Paused", entry.journal().get(captured.runId)?.state)
                    assertNull(entry.deviceTasks().currentTask)
                    assertFalse(runtime.backgroundWorkActive.value)
                }
                assertEquals(captured.target, runtime.taskProgress.value[chat.id]?.target)
                assertEquals(attemptsBefore, observationAttempts())
                assertEquals(1, round.requests.get())
                withContext(Dispatchers.Main) {
                    assertThrows(CancellationException::class.java) { monitor.check(captured.target) }
                    monitor.check(captured.target.copy(safetyRevision = monitor.state.value.revision))
                }
                report("actual-screen-lock verified latePhonePlanPaused=true oldTargetRejected=true freshTargetCheckPassed=true " +
                    "manualUnlockDidNotResume=true requests=1 actualObservationCalls=0")
            }
        } finally { collector.cancel() }
    }

    @Test fun queuedPhoneWorkStopsAfterModeChangeAndAnOldConfirmationCannotAuthorizeIt() = exerciseQueueBoundaryChange(false)

    @Test fun queuedPhoneWorkStopsAfterSafetyLossAndAnOldConfirmationCannotAuthorizeIt() = exerciseQueueBoundaryChange(true)

    private fun exerciseQueueBoundaryChange(safetyLoss: Boolean) = runBlocking<Unit> {
        enabled()
        ensureAlreadyAuthorizedAccessibilityIsBound()
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        check(runtime.activeConversations.value.isEmpty()) { "Stop existing tasks before this opt-in diagnostic" }
        val collector = launch(Dispatchers.Main) { runtime.uiState.collect() }
        try {
            withFixture(runtime) { saved, model, namespace ->
                setBackground(runtime, saved, false)
                awaitObservationReady("queue")
                try {
                    await("queue-force-stop-capability-ready") {
                        val connection = entry.deviceGateway().connectionState.value
                        connection.isConnected && DeviceCapability.ForceStopApp in connection.capabilities
                    }
                } catch (error: AssertionError) {
                    report("queue precondition=force-stop-capability-unavailable " +
                        "connection=${entry.deviceGateway().connectionState.value.status}")
                    throw error
                }
                // The guard is exercised with a nonexistent UUID app name, never a personal app.
                // Even if confirmation handling regresses, the resolver has no real app to stop.
                val fakeAppName = "Phone target QA nonexistent $namespace"
                assertNoInstalledAppMatches(fakeAppName)

                suspend fun sendTool(label: String, toolName: String, arguments: String): Pair<String, Round> {
                    val chat = data.chats().createConversation(namespace, Model)
                    saved.getJSONArray("owners").put(chat.id); persist(marker, saved)
                    data.chats().updateConversationTitle(chat.id, "$namespace-$label")
                    data.chats().updateConversationWorkspace(chat.id, null)
                    data.chats().updateThinkingSettings(chat.id, false, ThinkingEffort.Auto)
                    val instruction = "$namespace $label synthetic request"
                    val round = model.prepare(instruction, true, toolName, arguments)
                    preflight(saved, model, chat.id, instruction)
                    withContext(Dispatchers.Main) {
                        assertExpectedSettings(saved.getJSONObject("expected"))
                        check(runtime.sendFromAssistant(chat.id, instruction)) { "Runtime rejected owned QA request" }
                    }
                    await("$label-waiting-for-local-model") {
                        model.checkFailure()
                        round.received.count == 0L && runtime.taskProgress.value[chat.id]?.phase == DeviceTaskPhase.WaitingForModel
                    }
                    check(toolName in round.advertisedTools) {
                        "Precondition missing: $toolName must be available on this device; " +
                            "connection=${entry.deviceGateway().connectionState.value.status}"
                    }
                    data.chats().updateConversationTitle(chat.id, "$namespace-$label")
                    round.release.countDown()
                    return chat.id to round
                }

                val (ownerA, roundA) = sendTool("queue-A", "force_stop_app",
                    JSONObject().put("app_name", fakeAppName).toString())
                await("queue-A-waiting-for-confirmation") {
                    model.checkFailure()
                    runtime.taskProgress.value[ownerA]?.let { it.phase == DeviceTaskPhase.WaitingForUser && it.ownsDevice } == true &&
                        !runtime.backgroundWorkActive.value
                }
                val confirmation = withTimeout(25_000) {
                    runtime.observeFromAssistant(ownerA).first { it.pendingDeviceActionConfirmation != null }
                        .pendingDeviceActionConfirmation!!
                }
                assertEquals("force_stop_app", confirmation.toolName)
                assertEquals(fakeAppName, confirmation.title)
                val deviceTaskA = requireNotNull(entry.deviceTasks().currentTask)
                val progressA = requireNotNull(runtime.taskProgress.value[ownerA])

                val (ownerB, roundB) = sendTool("queue-B", "observe_screen", "{}")
                await("queue-B-waits-with-A-retaining-device-and-background-inactive") {
                    model.checkFailure()
                    val progress = runtime.taskProgress.value
                    progress[ownerA]?.let { it.phase == DeviceTaskPhase.WaitingForUser && it.ownsDevice } == true &&
                        progress[ownerB]?.let { it.phase == DeviceTaskPhase.WaitingForDevice && !it.ownsDevice && it.queueOrder != null } == true &&
                        runtime.activeConversations.value == setOf(ownerA, ownerB) && !runtime.backgroundWorkActive.value &&
                        runtime.uiState.value.deviceOwner?.ownerConversationId == ownerA &&
                        runtime.uiState.value.deviceQueue.map { it.ownerConversationId } == listOf(ownerB)
                }
                assertSame("A must keep the actual device-control task while B waits", deviceTaskA, entry.deviceTasks().currentTask)
                val progressB = requireNotNull(runtime.taskProgress.value[ownerB])
                assertEquals(progressA.target.configurationVersion, progressB.target.configurationVersion)
                if (safetyLoss) assertEquals(progressA.target.safetyRevision, progressB.target.safetyRevision)
                val attemptsBefore = observationAttempts()

                // This cancels both the confirmation holder and the queued waiter before A's
                // lock is released. No positive response is ever sent to a live confirmation.
                if (safetyLoss) simulateSafetyBoundary(DeviceSafetyReason.AccessibilityLost)
                else setBackground(runtime, saved, true)
                val expectedPhase = if (safetyLoss) DeviceTaskPhase.Paused else DeviceTaskPhase.Ended
                val expectedJournalState = if (safetyLoss) "Paused" else "Stopped"
                await("queue-${if (safetyLoss) "safety-loss" else "mode-change"}-stops-both-owners") {
                    model.checkFailure()
                    listOf(ownerA, ownerB).all { owner ->
                        owner !in runtime.activeConversations.value && runtime.taskProgress.value[owner]?.phase == expectedPhase &&
                            entry.journal().latest(owner)?.state == expectedJournalState &&
                            data.chats().getMessages(owner).none { it.status == MessageStatus.Streaming }
                    } && entry.deviceTasks().currentTask == null && !runtime.backgroundWorkActive.value &&
                        runtime.uiState.value.deviceOwner == null && runtime.uiState.value.deviceQueue.isEmpty()
                }
                assertNull(runtime.observeFromAssistant(ownerA).first().pendingDeviceActionConfirmation)
                // stopTurn's final persistence may repeat the same Stopped update after its
                // cancellation handler finishes. Compare all message evidence except write time.
                val stoppedMessages = listOf(ownerA, ownerB).associateWith { owner ->
                    data.chats().getMessages(owner).map { it.copy(updatedAt = 0L) }
                }
                for ((owner, messages) in stoppedMessages) {
                    val expectedTool = if (owner == ownerA) "force_stop_app" else "observe_screen"
                    assertTrue(messages.any { it.role == MessageRole.Tool && it.content.startsWith(expectedTool) && it.status == MessageStatus.Cancelled })
                    assertFalse("Neither phone tool may have a successful result", messages.any { it.role == MessageRole.Tool && it.status == MessageStatus.Complete })
                    assertTrue(messages.any { it.role == MessageRole.Assistant && it.status == MessageStatus.Stopped &&
                        it.executionContext.orEmpty().contains("interrupted_unconfirmed") })
                }
                // Recheck the nonexistent target immediately before delivering a stale approval.
                assertNoInstalledAppMatches(fakeAppName)
                withContext(Dispatchers.Main) {
                    runtime.respondFromAssistant(ownerA, confirmation.requestId, true)
                }
                repeat(10) {
                    delay(50)
                    model.checkFailure()
                    assertTrue("A stale confirmation must not revive either owner", runtime.activeConversations.value.isEmpty())
                    assertNull(entry.deviceTasks().currentTask)
                    assertFalse(runtime.backgroundWorkActive.value)
                }
                for (owner in listOf(ownerA, ownerB)) {
                    assertEquals(stoppedMessages.getValue(owner), data.chats().getMessages(owner).map { it.copy(updatedAt = 0L) })
                    assertEquals(expectedJournalState, entry.journal().latest(owner)?.state)
                    assertEquals(expectedPhase, runtime.taskProgress.value[owner]?.phase)
                    if (safetyLoss) assertTrue(runtime.observeFromAssistant(owner).first().deviceTaskPaused)
                }
                assertEquals(progressA.target, runtime.taskProgress.value[ownerA]?.target)
                assertEquals(progressB.target, runtime.taskProgress.value[ownerB]?.target)
                assertEquals(1, roundA.requests.get())
                assertEquals(1, roundB.requests.get())
                assertEquals("Queued observe_screen must never enter the real gateway", attemptsBefore, observationAttempts())
                report("queue verified A=WaitingForUser B=WaitingForDevice ownerRetained=true backgroundWorkActive=false " +
                    "${if (safetyLoss) "safetyLossStoppedBoth" else "modeChangeStoppedBoth"}=true staleApprovalIgnored=true " +
                    "requests=2 phoneToolSucceeded=false actualObservationCalls=0")
            }
        } finally { collector.cancel() }
    }

    @Suppress("DEPRECATION")
    private fun assertNoInstalledAppMatches(query: String) {
        fun String.normalized() = lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        val needle = query.normalized()
        check(needle.length > 32 && query.startsWith("Phone target QA nonexistent $Prefix"))
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else context.packageManager.queryIntentActivities(intent, 0)
        check(launchers.none { info ->
            info.activityInfo?.packageName.orEmpty().normalized().contains(needle) ||
                info.loadLabel(context.packageManager).toString().normalized().contains(needle)
        }) { "Synthetic force-stop target unexpectedly matches an installed app; refusing this diagnostic" }
    }

    @Test fun restoreAnAbortedDeviceTargetFixture() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restoreDeviceTargetFixture") == "true")
        restoreFixture()
        assertFalse(marker.baseFile.exists())
    }

    /** Metadata only: never reads key bytes or creates/deletes the historical application alias. */
    @Test fun reportActualKeystoreSecurityMetadataWithoutReadingCredentials() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runDeviceTargetDiagnostics") == "true" || args.getString("runKeystoreMetadataDiagnostics") == "true")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        read(probeMarker)?.let { saved ->
            val alias = saved.getString("alias")
            check(alias.startsWith(ProbePrefix) && UUID.fromString(alias.removePrefix(ProbePrefix)).toString() == alias.removePrefix(ProbePrefix))
            store.deleteEntry(alias)
            probeMarker.delete()
        }
        val realEntry = store.getEntry("flowchat_api_keys", null)
        check(realEntry == null || realEntry is KeyStore.SecretKeyEntry) {
            "Existing flowchat_api_keys alias is not a secret key; its security metadata remains unverified"
        }
        val real = realEntry as? KeyStore.SecretKeyEntry
        if (real != null) {
            reportKeyInfo("real-existing-key", real)
        } else {
            report("keystore real-existing-key=absent historical-key-security=UNVERIFIED")
            val alias = ProbePrefix + UUID.randomUUID()
            persist(probeMarker, JSONObject().put("alias", alias))
            try {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build())
                }.generateKey()
                reportKeyInfo("independent-QA-probe-only-not-historical-key", store.getEntry(alias, null) as KeyStore.SecretKeyEntry)
            } finally {
                store.deleteEntry(alias)
                check(!store.containsAlias(alias)) { "QA keystore probe cleanup failed" }
                probeMarker.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reportKeyInfo(source: String, entry: KeyStore.SecretKeyEntry) {
        val info = SecretKeyFactory.getInstance(entry.secretKey.algorithm, "AndroidKeyStore")
            .getKeySpec(entry.secretKey, KeyInfo::class.java) as KeyInfo
        val securityLevel = if (Build.VERSION.SDK_INT >= 31) {
            val value = info.securityLevel
            val label = when (value) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TrustedEnvironment"
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                else -> "Other"
            }
            "$value($label)"
        } else "API<31-unavailable"
        report("keystore source=$source securityLevel=$securityLevel isInsideSecureHardware=${info.isInsideSecureHardware} sdk=${Build.VERSION.SDK_INT}")
    }

    private fun withPowerFixture(exercise: suspend (ChatTaskRuntime, JSONObject, GatedLocalModel, String) -> Unit) = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runTaskPowerDiagnostics") == "true")
        ensureAlreadyAuthorizedAccessibilityIsBound()
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        check(runtime.activeConversations.value.isEmpty() && runtime.taskPowerOwners.value.isEmpty()) { "Do not interrupt user work" }
        val collector = launch(Dispatchers.Main) { runtime.uiState.collect() }
        var visibleActivity: com.flowchat.app.MainActivity? = null
        var alreadyKeptScreenOn = false
        try {
            // One real renewal takes a minute. Keep only this test window awake; do not change system timeout or unlock policy.
            powerShell("am start -W -f 0x10008000 -n ${context.packageName}/com.flowchat.app.MainActivity")
            await("power-test-window-visible") { withContext(Dispatchers.Main) {
                visibleActivity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<com.flowchat.app.MainActivity>().firstOrNull()
                visibleActivity != null
            } }
            withContext(Dispatchers.Main) {
                val window = requireNotNull(visibleActivity).window
                alreadyKeptScreenOn = window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            withFixture(runtime) { saved, model, namespace -> exercise(runtime, saved, model, namespace) }
        } finally {
            withContext(Dispatchers.Main) {
                if (!alreadyKeptScreenOn) visibleActivity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            collector.cancel()
        }
    }

    private suspend fun preparePowerRequest(saved: JSONObject, model: GatedLocalModel,
        namespace: String, label: String, tool: String? = null): Pair<String, Round> {
        val chat = data.chats().createConversation(namespace, Model)
        saved.getJSONArray("owners").put(chat.id); persist(marker, saved)
        data.chats().updateConversationTitle(chat.id, "$namespace-$label")
        data.chats().updateConversationWorkspace(chat.id, null)
        data.chats().updateThinkingSettings(chat.id, false, ThinkingEffort.Auto)
        val instruction = "$namespace $label synthetic request"
        val args = if (tool == "force_stop_app") JSONObject().put("app_name", "Phone target QA nonexistent $namespace").toString() else "{}"
        val round = model.prepare(instruction, tool != null, tool ?: "observe_screen", args)
        preflight(saved, model, chat.id, instruction)
        return chat.id to round
    }

    private suspend fun powerSend(runtime: ChatTaskRuntime, saved: JSONObject, model: GatedLocalModel,
        namespace: String, label: String, tool: String? = null): Pair<String, Round> {
        val (owner, round) = preparePowerRequest(saved, model, namespace, label, tool)
        withContext(Dispatchers.Main) { check(runtime.sendFromAssistant(owner, round.instruction)) }
        await("power-$label-model-received") { model.checkFailure(); round.received.count == 0L }
        if (tool != null) check(tool in round.advertisedTools) { "Required tool unavailable: $tool" }
        return owner to round
    }

    private fun powerShell(command: String): String {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    }

    private suspend fun assertSystemPower(label: String, held: Boolean, service: Boolean) {
        var systemLocks = emptyList<String>()
        var systemService = ""
        await("power-$label-system") {
            val lockLines = powerShell("dumpsys power").lineSequence()
                .filter { it.contains("PARTIAL_WAKE_LOCK") && it.contains("Floryx:UserTask") }.toList()
            // A different installed channel may use the same tag; require this exact UID.
            val ownLocks = lockLines.filter { Regex("uid=${context.applicationInfo.uid}(?:\\D|$)").containsMatchIn(it) }
            val serviceDump = powerShell("dumpsys activity services ${context.packageName}/com.flowchat.app.data.task.TaskExecutionService")
            val running = serviceDump.contains("isForeground=true") && serviceDump.contains("TaskExecutionService")
            val absent = !serviceDump.contains("ServiceRecord{")
            systemLocks = ownLocks
            systemService = serviceDump
            (ownLocks.size == if (held) 1 else 0) && (if (service) running else absent)
        }
        val state = requireNotNull(entry.taskPower().state.value)
        assertEquals(held, state.leaseHeld)
        assertNull(state.failure)
        assertEquals(0, state.otherUnverifiedLeases)
        if (!service) assertEquals(TaskServiceState.Destroyed, state.serviceState)
        report("power-$label uid=${context.applicationInfo.uid} held=$held foreground=$service evidence=$state")
        report("power-$label actualLockLines=$systemLocks actualForeground=$service")
        context.cacheDir.resolve("r7e-power-$label-service.txt").writeText(systemService)
    }

    @Test fun taskPowerWaitingOwnerAndQueueReleaseWhileAConcurrentModelRenewsThenNotificationStopsAll() = withPowerFixture { runtime, saved, model, namespace ->
        setBackground(runtime, saved, false)
        awaitObservationReady("power-queue")
        await("power-force-stop-capability") { DeviceCapability.ForceStopApp in entry.deviceGateway().connectionState.value.capabilities }
        assertNoInstalledAppMatches("Phone target QA nonexistent $namespace")
        val (a, roundA) = powerSend(runtime, saved, model, namespace, "wait-person", "force_stop_app")
        roundA.release.countDown()
        await("power-A-confirmation") { runtime.taskProgress.value[a]?.phase == DeviceTaskPhase.WaitingForUser }
        val (b, roundB) = powerSend(runtime, saved, model, namespace, "wait-device", "observe_screen")
        roundB.release.countDown()
        await("power-B-queued") { runtime.taskProgress.value[b]?.phase == DeviceTaskPhase.WaitingForDevice &&
            entry.taskPower().state.value?.owners?.size == 2 && !runtime.backgroundWorkActive.value }
        assertEquals(setOf(a, b), entry.taskPower().state.value!!.owners.map { it.conversationId }.toSet())
        assertSystemPower("all-waiting", held = false, service = true)
        val (c, roundC) = powerSend(runtime, saved, model, namespace, "concurrent-model")
        await("power-C-owned") { entry.taskPower().state.value?.owners?.any { it.conversationId == c && it.work == TaskPowerWork.Model } == true }
        assertSystemPower("concurrent-model", held = true, service = true)
        val observed = requireNotNull(entry.taskPower().state.value!!.checkedAtMillis)
        withTimeout(75_000) {
            while (requireNotNull(entry.taskPower().state.value!!.checkedAtMillis) - observed < 55_000) delay(100)
        }
        assertSystemPower("renewed-with-waiters", held = true, service = true)
        assertEquals(1, roundA.requests.get()); assertEquals(1, roundB.requests.get()); assertEquals(1, roundC.requests.get())
        TaskNotificationFixture.sendStop(context, ::report)
        await("power-notification-stopped-and-finalized") { runtime.activeConversations.value.isEmpty() && runtime.taskPowerOwners.value.isEmpty() }
        assertSystemPower("notification-finished", held = false, service = false)
        assertFalse(data.chats().getMessages(a).any { it.role == MessageRole.Tool && it.status == MessageStatus.Complete })
        assertFalse(data.chats().getMessages(b).any { it.role == MessageRole.Tool && it.status == MessageStatus.Complete })
    }

    @Test fun taskPowerStoppingOneModelOwnerRetainsTheOtherUntilNormalCompletion() = withPowerFixture { runtime, saved, model, namespace ->
        val (a, roundA) = powerSend(runtime, saved, model, namespace, "model-A")
        val (b, roundB) = powerSend(runtime, saved, model, namespace, "model-B")
        await("power-two-model-owners") { entry.taskPower().state.value?.owners?.size == 2 }
        assertSystemPower("two-models", held = true, service = true)
        withContext(Dispatchers.Main) { runtime.stopFromAssistant(a) }
        await("power-only-B-remains") { runtime.taskPowerOwners.value.map { it.conversationId } == listOf(b) &&
            entry.taskPower().state.value?.owners?.map { it.conversationId } == listOf(b) }
        assertSystemPower("one-model-stopped", held = true, service = true)
        roundB.release.countDown()
        await("power-B-completed") { runtime.taskPowerOwners.value.isEmpty() &&
            data.chats().getMessages(b).any { it.content == PrefixText + FinalText && it.status == MessageStatus.Complete } }
        assertSystemPower("normal-completion", held = false, service = false)
        assertEquals(1, roundA.requests.get()); assertEquals(1, roundB.requests.get())
    }

    @Test fun taskPowerNewWorkDuringIdleGraceCannotBeStoppedByThePreviousIdleCallback() = withPowerFixture { runtime, saved, model, namespace ->
        val (a, roundA) = powerSend(runtime, saved, model, namespace, "grace-A")
        assertSystemPower("grace-A", held = true, service = true)
        val serviceId = requireNotNull(entry.taskPower().state.value).serviceId
        // Prepare Room/provider work before the idle interval so this specifically exercises the same service.
        val (b, roundB) = preparePowerRequest(saved, model, namespace, "grace-B")
        roundA.release.countDown()
        await("power-grace-A-completed") { a !in runtime.activeConversations.value && runtime.taskPowerOwners.value.isEmpty() }
        withContext(Dispatchers.Main) {
            assertExpectedSettings(saved.getJSONObject("expected"))
            check(runtime.sendFromAssistant(b, roundB.instruction))
        }
        await("power-grace-B-received") { model.checkFailure(); roundB.received.count == 0L }
        assertEquals(serviceId, entry.taskPower().state.value!!.serviceId)
        delay(1_000)
        assertSystemPower("new-owner-after-grace", held = true, service = true)
        assertEquals(b, entry.taskPower().state.value!!.owners.single().conversationId)
        roundB.release.countDown()
        await("power-grace-B-completed") { runtime.taskPowerOwners.value.isEmpty() }
        assertSystemPower("grace-final", held = false, service = false)
    }

    private class Round(val instruction: String, val phoneTool: Boolean,
        val toolName: String = "observe_screen", val toolArguments: String = "{}") {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        @Volatile var observationAdvertised = false
        @Volatile var advertisedTools: Set<String> = emptySet()
    }

    /** A single delayed SSE response per armed round; no fallback response or real-provider routing. */
    private class GatedLocalModel(private val namespace: String) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val baseUrl = "http://127.0.0.1:$port/v1"
        private val failure = AtomicReference<Throwable?>()
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        @Volatile private var round: Round? = null
        @Volatile private var closed = false
        init {
            thread(isDaemon = true, name = "phone-target-qa-model") {
                while (!closed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    sockets.add(socket)
                    thread(isDaemon = true, name = "phone-target-qa-response") {
                        try { socket.use { respond(it) } }
                        catch (error: Throwable) { if (!closed) failure.compareAndSet(null, error) }
                        finally { sockets.remove(socket) }
                    }
                }
            }
        }
        fun prepare(instruction: String, phoneTool: Boolean,
            toolName: String = "observe_screen", toolArguments: String = "{}"): Round {
            checkFailure()
            check(toolName in setOf("observe_screen", "force_stop_app")) { "Only the two synthetic QA tools are supported" }
            val arguments = JSONObject(toolArguments)
            when (toolName) {
                "observe_screen" -> check(arguments.length() == 0) { "Synthetic observation must not carry action arguments" }
                "force_stop_app" -> check(arguments.length() == 1 &&
                    arguments.getString("app_name") == "Phone target QA nonexistent $namespace") {
                    "Synthetic force-stop may only name the UUID-owned nonexistent app"
                }
            }
            return Round(instruction, phoneTool, toolName, toolArguments).also { round = it }
        }
        fun checkFailure() { failure.get()?.let { throw AssertionError("Local synthetic model failed", it) } }
        private fun respond(socket: Socket) {
            socket.soTimeout = 30_000
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val buffer = StringBuilder()
                while (true) {
                    val value = input.read()
                    check(value >= 0) { "Unexpected EOF in local HTTP headers" }
                    if (value == 10) return buffer.toString().trimEnd('\r')
                    check(buffer.length < 16_384)
                    buffer.append(value.toChar())
                }
            }
            check(line() == "POST /v1/chat/completions HTTP/1.1") { "Unexpected local HTTP route" }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
            }
            check(headers["host"] == "127.0.0.1:$port" && headers["x-phone-target-qa"] == namespace)
            check("authorization" !in headers && "x-api-key" !in headers) { "Credentials forbidden in synthetic requests" }
            val length = requireNotNull(headers["content-length"]).toInt()
            check(length in 1..250_000)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                check(count > 0)
                offset += count
            }
            val request = JSONObject(bytes.toString(Charsets.UTF_8))
            val active = requireNotNull(round) { "Local request without an armed QA owner" }
            check(active.requests.incrementAndGet() == 1) { "Unexpected follow-up request: phone data must not be requested" }
            check(request.getString("model") == Model)
            val messages = request.getJSONArray("messages")
            var users = 0
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                when (message.getString("role")) {
                    "user" -> { users++; check(message.get("content") == active.instruction) { "Non-synthetic user content refused" } }
                    "system" -> check(message.get("content") is String)
                    else -> error("Fresh QA request contained history or tool results")
                }
            }
            check(users == 1)
            val tools = request.optJSONArray("tools") ?: JSONArray()
            active.advertisedTools = (0 until tools.length()).map {
                tools.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet()
            active.observationAdvertised = "observe_screen" in active.advertisedTools
            val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
            fun event(delta: JSONObject, finish: String? = null) {
                val choice = JSONObject().put("index", 0).put("delta", delta)
                if (finish != null) choice.put("finish_reason", finish)
                out.write("data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n")
                out.flush()
            }
            event(JSONObject().put("content", PrefixText))
            active.received.countDown()
            check(active.release.await(90, TimeUnit.SECONDS)) { "Delayed synthetic response was never released" }
            if (active.phoneTool) {
                val call = JSONObject().put("index", 0).put("id", "phone-target-qa-observe").put("type", "function")
                    .put("function", JSONObject().put("name", active.toolName).put("arguments", active.toolArguments))
                event(JSONObject().put("tool_calls", JSONArray().put(call)))
                event(JSONObject(), "tool_calls")
            } else {
                event(JSONObject().put("content", FinalText))
                event(JSONObject(), "stop")
            }
            out.write("data: [DONE]\n\n")
            out.flush()
        }
        override fun close() {
            closed = true
            server.close()
            synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } } }
            round?.release?.countDown()
        }
    }

    companion object {
        private const val Prefix = "phone-target-qa-"
        private const val ProbePrefix = "phone-target-qa-keystore-"
        private const val Model = "phone-target-local-qa"
        private const val ProviderName = "Phone target local QA"
        private const val PrefixText = "PHONE_TARGET_QA_WAITING"
        private const val FinalText = "_PLAIN_TEXT_COMPLETED"
        private val ProcessToken = UUID.randomUUID().toString()
    }
}
