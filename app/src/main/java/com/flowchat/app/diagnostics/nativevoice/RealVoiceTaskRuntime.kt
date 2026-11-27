package com.flowchat.app.diagnostics.nativevoice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.domain.assistant.AssistantConfiguration
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.assistant.AssistantTaskDestination
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.validation.ProviderConfigValidator
import com.flowchat.app.domain.workspace.WorkspaceLocation
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NativeVoiceDiagnosticsEntryPoint {
    fun runtime(): ChatTaskRuntime
    fun chats(): ChatRepository
    fun providers(): ProviderRepository
    fun settings(): AppSettingsStore
    fun database(): FlowChatDatabase
    fun bridge(): FloatingAssistantBridge
    fun gateway(): DeviceAssistantGateway
}

internal suspend fun resolveVoiceTaskTarget(
    selected: AssistantModelSelection?,
    available: List<ProviderConfig>,
    conversations: List<Conversation>,
    hasApiKey: suspend (ProviderConfig) -> Boolean
): Pair<AssistantModelSelection, ProviderConfig>? {
    AssistantConfiguration.provider(selected, available)?.let { provider ->
        return requireNotNull(selected) to provider
    }
    // If user explicitly chose a still-existing provider that is currently disabled or invalid, respect that choice.
    val selectedStillExists = selected != null && available.any { it.id == selected.providerId }
    if (selectedStillExists) return null

    val recent = conversations.firstOrNull { !it.isArchived }
    if (recent != null) {
        val recentChoice = AssistantModelSelection.from(recent)
        AssistantConfiguration.provider(recentChoice, available)?.let { provider ->
            return recentChoice to provider
        }
    }
    val validProviders = available.filter {
        it.isEnabled && ProviderConfigValidator.validate(it).isEmpty()
    }
    val fallback = validProviders.firstOrNull { hasApiKey(it) } ?: validProviders.firstOrNull()
    return fallback?.let { AssistantModelSelection(it.id, it.defaultModel) to it }
}

/** Main-process runtime backend wired to the user's configured model, tools and device gateway. */
internal class RealVoiceTaskRuntime(context: Context) : VoiceTaskBackend {
    private val appContext = context.applicationContext
    private val entry = EntryPointAccessors.fromApplication(appContext, NativeVoiceDiagnosticsEntryPoint::class.java)
    private val yieldStates = ConcurrentHashMap<String, YieldState>()

    override val runtimeMode: String = "real"
    override val chats: ChatRepository get() = entry.chats()
    override val journal: TaskRunJournal by lazy { TaskRunJournal(entry.database().taskRunDao()) }
    override val runtime: ChatTaskRuntime get() = entry.runtime()

    private val providers: ProviderRepository get() = entry.providers()
    private val settings: AppSettingsStore get() = entry.settings()
    private val bridge: FloatingAssistantBridge get() = entry.bridge()
    private val gateway: DeviceAssistantGateway get() = entry.gateway()

    init {
        bridge.setExternalPanelYieldHandler { owner ->
            currentCoroutineContext().ensureActive()
            gateway.refreshSafetyState()
            val safety = gateway.safetyState.value
            if (safety.screenLocked == true || safety.screenInteractive == false) {
                return@setExternalPanelYieldHandler
            }
            val state = yieldStates[owner] ?: return@setExternalPanelYieldHandler
            if (state.acked) return@setExternalPanelYieldHandler
            state.requested = true
            withTimeoutOrNull(YIELD_ACK_TIMEOUT_MS) { state.signal.await() }
            currentCoroutineContext().ensureActive()
            state.requested = false
            state.acked = true
        }
    }

    private suspend fun resolveWorkspace(defaultWorkspace: WorkspaceLocation?): WorkspaceLocation? =
        NativeVoiceBackendFactory.resolveWorkspace(appContext, defaultWorkspace)

    private suspend fun prepareDeviceConnection() {
        gateway.refreshAccessibilityConnection()
        gateway.refreshConnection()
        gateway.refreshSafetyState()
        val currentSettings = settings.state.value
        val deadline = SystemClock.elapsedRealtime() + CONNECTION_SETTLE_TIMEOUT_MS
        while (currentSettings.powerModeEnabled &&
            SystemClock.elapsedRealtime() < deadline &&
            (gateway.connectionState.value.status == ShizukuConnectionStatus.Connecting ||
                (currentSettings.powerConnectionMode == PowerConnectionMode.Root &&
                    gateway.connectionState.value.status == ShizukuConnectionStatus.RootPermissionRequired))
        ) {
            delay(50L)
        }
    }

    private suspend fun resolveTarget(): Pair<AssistantModelSelection, ProviderConfig>? {
        val available = providers.getProvidersOnce()
        val selected = settings.state.value.assistantModelSelection
        val conversations = chats.observeConversations().first()
        val resolved = resolveVoiceTaskTarget(selected, available, conversations) {
            providers.getApiKey(it) != null
        }
        if (resolved == null) {
            Log.w(
                "NativeVoiceLocal",
                "assistant_model_unresolved hasSelected=${selected != null} " +
                    "selectedExists=${selected != null && available.any { it.id == selected.providerId }} " +
                    "available=${available.size} enabled=${available.count { it.isEnabled }} " +
                    "valid=${available.count { it.isEnabled && ProviderConfigValidator.validate(it).isEmpty() }}"
            )
        }
        return resolved
    }

    override suspend fun createConversation(): Conversation {
        val currentSettings = settings.state.value
        val (chosen, provider) = resolveTarget()
            ?: error("Assistant model configuration is missing or disabled.")
        val conversation = chats.createConversation(provider.id, chosen.modelName)
        chats.updateThinkingSettings(conversation.id, chosen.enableThinking, chosen.thinkingEffort)
        val workspace = resolveWorkspace(currentSettings.defaultWorkspace)
        if (workspace != null) {
            chats.updateConversationWorkspace(conversation.id, workspace)
        }
        return requireNotNull(chats.getConversation(conversation.id))
    }

    override suspend fun reusableConversation(owner: String): Conversation? {
        runtime.awaitStopSettled(owner)
        val currentSettings = settings.state.value
        val (selected, provider) = resolveTarget() ?: return null
        val conversation = chats.getConversation(owner)?.takeUnless { it.isArchived } ?: return null
        if (owner in runtime.activeConversations.value || runtime.isWaitingForUnlock(owner) || runtime.isWaitingForUserConfirmation(owner)) return null
        if (conversation.providerId != provider.id || conversation.modelName != selected.modelName) return null
        val workspace = resolveWorkspace(currentSettings.defaultWorkspace)
        if (workspace != null && conversation.workspace != workspace) {
            chats.updateConversationWorkspace(conversation.id, workspace)
            return chats.getConversation(conversation.id)
        }
        return conversation
    }

    override suspend fun submit(conversation: Conversation, text: String): Boolean {
        prepareDeviceConnection()
        val available = providers.getProvidersOnce()
        val selection = AssistantModelSelection.from(conversation)
        val provider = AssistantConfiguration.provider(selection, available) ?: return false
        yieldStates[conversation.id] = YieldState()
        val accepted = withContext(Dispatchers.Main.immediate) {
            runtime.sendFromAssistant(
                owner = conversation.id,
                text = text,
                destination = AssistantTaskDestination(conversation.id, selection, provider),
                conciseVoiceReply = true
            )
        }
        if (!accepted) clearPanelYield(conversation.id)
        return accepted
    }

    override fun isWaitingForUnlock(owner: String): Boolean = runtime.isWaitingForUnlock(owner)

    override fun isWaitingForUser(owner: String): Boolean = runtime.isWaitingForUserConfirmation(owner)

    override fun waitingForUserPrompt(owner: String): String? = runtime.pendingUserConfirmationPrompt(owner)

    override suspend fun resume(owner: String): Boolean {
        prepareDeviceConnection()
        yieldStates[owner] = YieldState()
        val resumed = withContext(Dispatchers.Main.immediate) {
            runtime.resumeFromAssistant(owner)
        }
        if (!resumed) clearPanelYield(owner)
        return resumed
    }

    override fun isPanelYieldRequested(owner: String): Boolean =
        yieldStates[owner]?.let { it.requested && !it.acked } == true

    override fun ackPanelYield(owner: String) {
        yieldStates[owner]?.let { state ->
            state.requested = false
            state.acked = true
            state.signal.complete(Unit)
        }
    }

    override fun clearPanelYield(owner: String) {
        yieldStates.remove(owner)?.let { state ->
            state.requested = false
            state.signal.complete(Unit)
        }
    }

    private class YieldState {
        @Volatile var requested: Boolean = false
        @Volatile var acked: Boolean = false
        val signal = CompletableDeferred<Unit>()
    }

    companion object {
        private const val CONNECTION_SETTLE_TIMEOUT_MS = 600L
        private const val YIELD_ACK_TIMEOUT_MS = 2_000L
    }
}
