package com.flowchat.app.presentation.assistant

import android.content.Context
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.assistant.AssistantConfiguration
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.assistant.AssistantTaskDestination
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.validation.ProviderConfigValidator
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import com.flowchat.app.presentation.chat.DeviceActionConfirmationUi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AssistantConfigurationChoice(val label: String, val selection: AssistantModelSelection)

data class AssistantPanelState(
    val taskStatus: com.flowchat.app.presentation.chat.TaskPresentation = com.flowchat.app.presentation.chat.TaskPresentation(com.flowchat.app.presentation.chat.TaskDisplayPhase.Ready),
    val ready: Boolean = false,
    val conversationId: String? = null,
    val input: String = "",
    val output: String = "",
    val modelName: String = "",
    val running: Boolean = false,
    val submitting: Boolean = false,
    val paused: Boolean = false,
    val error: String? = null,
    val page: AssistPageContext? = null,
    val includePage: Boolean = false,
    val confirmation: DeviceActionConfirmationUi? = null,
    val configurationLabel: String = "",
    val configurationChoices: List<AssistantConfigurationChoice> = emptyList(),
    internal val voicePlayback: VoicePlaybackState = VoicePlaybackState.Idle,
    internal val voicePlaybackText: String = "",
    internal val followUpReadyGeneration: Long = 0L
)

@Singleton
class AssistantPanelFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: ChatTaskRuntime,
    private val chats: ChatRepository,
    private val providers: ProviderRepository,
    private val settings: AppSettingsStore,
    private val voicePlayback: AssistantVoicePlayback
) {
    fun create() = create(voicePlayback)

    internal fun create(playback: AssistantVoicePlayback) =
        AssistantPanelPresenter(com.flowchat.app.locale.AppLocale.wrapContext(context), runtime, chats, providers, settings, playback)
}

/** The visible panel owns subscriptions and text input; the application runtime owns execution. */
class AssistantPanelPresenter internal constructor(
    private val context: Context,
    private val runtime: ChatTaskRuntime,
    private val chats: ChatRepository,
    private val providers: ProviderRepository,
    private val settings: AppSettingsStore,
    private val voicePlayback: AssistantVoicePlayback
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("assistant_entry", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AssistantPanelState())
    val state = mutable.asStateFlow()
    private var observer: Job? = null
    private var acceptingPage = true

    init {
        scope.launch {
            combine(
                voicePlayback.state,
                voicePlayback.speakingText,
                voicePlayback.completedGeneration
            ) { playback, spoken, completedGen -> Triple(playback, spoken, completedGen) }
                .collect { (playback, spoken, completedGen) ->
                    mutable.update {
                        it.copy(
                            voicePlayback = playback,
                            voicePlaybackText = spoken,
                            followUpReadyGeneration = completedGen
                        )
                    }
                }
        }
        scope.launch {
            try {
                val owner = preferences.getString("conversation_id", null)?.takeIf { id ->
                        chats.getConversation(id)?.isArchived == false
                    }
                    ?: runtime.activeConversations.value.singleOrNull()
                if (owner != null) observe(owner)
                mutable.update { it.copy(ready = true) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(ready = true, error = e.localizedMessage) } }
        }
        scope.launch {
            combine(providers.observeProviders(), settings.state) { available, values -> available to values.assistantModelSelection }
                .collect { (available, selected) ->
                    val choices = available.filter { it.isEnabled && ProviderConfigValidator.validate(it).isEmpty() }.flatMap { provider ->
                        ((provider.conversationModels ?: listOf(provider.defaultModel)) + listOfNotNull(
                            selected?.modelName?.takeIf { selected.providerId == provider.id })).filter { it.isNotBlank() }.distinct().map { model ->
                            AssistantConfigurationChoice("${provider.displayName} · $model", AssistantModelSelection(provider.id, model,
                                selected?.enableThinking ?: true, selected?.thinkingEffort ?: com.flowchat.app.domain.model.ThinkingEffort.Auto))
                        }
                    }
                    mutable.update { value -> value.copy(configurationChoices = choices,
                        configurationLabel = if (value.conversationId == null) {
                            AssistantConfiguration.provider(selected, available)?.let { "${it.displayName} · ${selected!!.modelName}" }
                                ?: context.getString(R.string.assistant_choose_configuration)
                        } else value.configurationLabel) }
                }
        }
    }

    fun updateInput(value: String) { mutable.update { it.copy(input = value.take(32_000), error = null) } }
    fun updatePage(page: AssistPageContext?) {
        if (acceptingPage) mutable.update { it.copy(page = page, includePage = false) }
    }
    fun includePage(include: Boolean) { mutable.update { it.copy(includePage = include && it.page != null) } }

    fun chooseConfiguration(choice: AssistantConfigurationChoice) {
        if (state.value.running || state.value.submitting || state.value.paused || choice !in state.value.configurationChoices) return
        settings.setAssistantModelSelection(choice.selection)
        newTask()
    }

    fun newTask() {
        if (state.value.running || state.value.submitting || state.value.paused) return
        voicePlayback.stop()
        observer?.cancel(); observer = null
        preferences.edit().remove("conversation_id").apply()
        acceptingPage = true
        mutable.update { it.copy(conversationId = null, output = "", modelName = "", error = null, confirmation = null,
            taskStatus = com.flowchat.app.presentation.chat.TaskPresentation(com.flowchat.app.presentation.chat.TaskDisplayPhase.Ready),
            configurationLabel = state.value.configurationChoices.firstOrNull {
                it.selection.providerId == settings.state.value.assistantModelSelection?.providerId &&
                    it.selection.modelName == settings.state.value.assistantModelSelection?.modelName
            }?.label ?: context.getString(R.string.assistant_choose_configuration)) }
    }

    private fun observe(owner: String) {
        observer?.cancel()
        mutable.update { it.copy(conversationId = owner) }
        observer = scope.launch {
            runtime.observeFromAssistant(owner).collect { chat ->
                val latest = chat.messages.lastOrNull { it.role == MessageRole.Assistant }
                mutable.update { previous -> previous.copy(
                    output = latest?.content.orEmpty().takeLast(16_000),
                    modelName = chat.currentConversation?.modelName.orEmpty(),
                    running = chat.isStreaming, paused = chat.deviceTaskPaused,
                    taskStatus = com.flowchat.app.presentation.chat.TaskPresentation.from(chat),
                    confirmation = chat.pendingDeviceActionConfirmation,
                    configurationLabel = chat.currentConversation?.let { conversation ->
                        "${chat.providers.firstOrNull { it.id == conversation.providerId }?.displayName ?: conversation.providerId} · ${conversation.modelName}"
                    }.orEmpty(),
                    error = chat.errorMessage ?: latest?.content?.takeIf { latest.status == MessageStatus.Failed }
                ) }
            }
        }
    }

    fun send(onAccepted: () -> Unit) {
        val value = state.value
        val page = value.page.takeIf { value.includePage }
        submit(value, value.input, page) {
            if (page == null) onAccepted()
        }
    }

    internal fun submitVoice(transcript: String, followUp: Boolean = false) {
        if (isLocalVoiceStopCommand(transcript) || (followUp && isFollowUpDismissCommand(transcript))) {
            stop()
            return
        }
        val value = state.value
        if (!canRecordVoice(value)) return
        submit(value, transcript, value.page.takeIf { value.includePage }, voice = true) {}
    }

    internal fun stopVoicePlayback() = voicePlayback.stop()

    fun summarizePage() {
        val value = state.value
        val page = value.page ?: return
        if (value.input.isNotBlank() || value.paused) return
        submit(value, "", page, summarizePage = true) {}
    }

    private fun submit(value: AssistantPanelState, instruction: String, page: AssistPageContext?,
        voice: Boolean = false, summarizePage: Boolean = false, onAccepted: () -> Unit) {
        if (!value.ready || value.submitting || value.running || (!summarizePage && instruction.isBlank()) ||
            (summarizePage && page == null)) return
        if (page != null && !page.isFresh()) {
            mutable.update { it.copy(page = null, includePage = false, error = context.getString(R.string.assistant_page_expired)) }
            return
        }
        mutable.update { it.copy(submitting = true, error = null) }
        scope.launch {
            try {
                var owner = value.conversationId?.takeIf { chats.getConversation(it)?.isArchived == false }
                val available = providers.getProvidersOnce()
                if (owner == null) {
                    val selected = settings.state.value.assistantModelSelection
                    val provider = AssistantConfiguration.provider(selected, available)
                        ?: error(context.getString(R.string.assistant_choose_configuration))
                    val created = chats.createConversation(provider.id, requireNotNull(selected).modelName)
                    owner = created.id
                    chats.updateThinkingSettings(owner, selected.enableThinking, selected.thinkingEffort)
                    preferences.edit().putString("conversation_id", owner).apply()
                    observe(owner)
                }
                val conversation = chats.getConversation(owner) ?: error(context.getString(R.string.assistant_send_failed))
                val selected = AssistantModelSelection.from(conversation)
                val provider = AssistantConfiguration.provider(selected, available)
                    ?: error(context.getString(R.string.assistant_choose_configuration))
                val onCompletedAnswer = if (voice) voicePlayback.expectResult() else null
                check(runtime.sendFromAssistant(owner, instruction, page?.asAttachment(),
                    AssistantTaskDestination(owner, selected, provider), onCompletedAnswer,
                    pageAttachmentName = context.getString(R.string.assistant_page_attachment),
                    summarizePage = summarizePage,
                    titleHint = if (summarizePage) context.getString(R.string.assistant_summarize_page) else null)) {
                    context.getString(R.string.assistant_send_failed)
                }
                acceptingPage = false
                mutable.update { it.copy(input = "", page = null, includePage = false) }
                onAccepted()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(error = e.localizedMessage ?: context.getString(R.string.assistant_send_failed)) } }
              finally { mutable.update { it.copy(submitting = false) } }
        }
    }

    fun stop() {
        voicePlayback.stop()
        state.value.conversationId?.let(runtime::stopFromAssistant)
    }
    fun confirm(approved: Boolean) {
        val value = state.value
        val owner = value.conversationId ?: return
        value.confirmation?.let { runtime.respondFromAssistant(owner, it.requestId, approved) }
    }
    fun selectForFullChat() { state.value.conversationId?.let(runtime::selectConversation) }
    fun close() { scope.cancel(); mutable.update { it.copy(page = null, includePage = false) } }
}

internal fun canRecordVoice(state: AssistantPanelState): Boolean =
    state.ready && !state.running && !state.submitting && !state.paused && state.input.isBlank()
