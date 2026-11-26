package com.flowchat.app.presentation.sharing

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.assistant.AssistantConfiguration
import com.flowchat.app.domain.assistant.AssistantTaskDestination
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.sharing.SharedText
import com.flowchat.app.domain.sharing.SharedTextDestination
import com.flowchat.app.domain.sharing.SharedTextStageResult
import com.flowchat.app.domain.validation.ProviderConfigValidator
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import com.flowchat.app.presentation.chat.TaskDisplayPhase
import com.flowchat.app.presentation.chat.TaskPresentation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SharedTextChoice(val title: String, val destination: SharedTextDestination)
data class SharedTextSummary(
    val owner: String,
    val configuration: String,
    val sourcePackage: String?,
    val status: TaskPresentation = TaskPresentation(TaskDisplayPhase.Thinking),
    val running: Boolean = true,
    val output: String = "",
    val error: String? = null
)

data class SharedTextPreviewState(
    val payload: SharedText? = null,
    val choices: List<SharedTextChoice> = emptyList(),
    val selected: SharedTextDestination? = null,
    val invalid: Boolean = false,
    val busy: Boolean = false,
    val result: SharedTextStageResult? = null,
    val offerSummary: Boolean = false,
    val startingSummary: Boolean = false,
    val summaryError: Int? = null,
    val summary: SharedTextSummary? = null
)

@HiltViewModel
class SharedTextPreviewModel @Inject constructor(
    private val chats: ChatRepository,
    private val providers: ProviderRepository,
    private val runtime: ChatTaskRuntime,
    private val settings: AppSettingsStore
) : ViewModel() {
    private var initialized = false
    private val local = MutableStateFlow(SharedTextPreviewState())
    val state = combine(local, chats.observeConversations(), providers.observeProviders()) { value, conversations, configs ->
        value.copy(choices = conversations.filter { !it.isArchived }.mapNotNull { conversation ->
            configs.firstOrNull { it.id == conversation.providerId && it.isEnabled &&
                ProviderConfigValidator.validate(it).isEmpty() }?.takeIf { conversation.modelName.isNotBlank() }?.let { provider ->
                SharedTextChoice(conversation.title, SharedTextDestination(conversation.id, conversation.modelName, provider))
            }
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharedTextPreviewState())

    fun initialize(intent: Intent, caller: String?, recreated: Boolean) {
        if (initialized) return // Rotation retains the same in-memory preview and explicit choice.
        initialized = true
        // Process death must not re-import an old launch Intent or restore a previous destination.
        val payload = if (recreated) null else SharedTextIntentReader.read(intent, caller)
        local.value = SharedTextPreviewState(payload = payload, invalid = payload == null,
            offerSummary = payload != null && intent.action == Intent.ACTION_SEND)
    }

    fun select(choice: SharedTextDestination?) {
        if (!local.value.busy) local.value = local.value.copy(selected = choice, result = null)
    }

    fun add(onAdded: () -> Unit) {
        val value = local.value
        val payload = value.payload ?: return
        val destination = value.selected ?: return
        if (value.busy) return
        local.value = value.copy(busy = true, result = null, summaryError = null)
        viewModelScope.launch {
            try {
                val result = runtime.stageSharedText(payload, destination, SystemClock::elapsedRealtime)
                local.value = local.value.copy(busy = false, result = result,
                    selected = if (result == SharedTextStageResult.DestinationChanged) null else local.value.selected)
                if (result == SharedTextStageResult.Added) onAdded()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Storage/configuration failures never consume the payload or change the destination.
                local.value = local.value.copy(result = SharedTextStageResult.DestinationChanged, selected = null)
            } finally { local.value = local.value.copy(busy = false) }
        }
    }

    fun summarize(instruction: String, attachmentName: String) {
        val value = local.value
        val payload = value.payload ?: return
        if (!value.offerSummary || value.busy) return
        val selected = settings.state.value.assistantModelSelection
        local.value = value.copy(busy = true, startingSummary = true, result = null, summaryError = null)
        viewModelScope.launch {
            var unsentOwner: String? = null
            try {
                val provider = AssistantConfiguration.provider(selected, providers.getProvidersOnce())
                if (selected == null || provider == null) {
                    local.update { it.copy(summaryError = R.string.share_summary_choose_configuration) }
                    return@launch
                }
                val conversation = withContext(NonCancellable) {
                    chats.createConversation(provider.id, selected.modelName).also { unsentOwner = it.id }
                }
                val owner = conversation.id
                chats.updateThinkingSettings(owner, selected.enableThinking, selected.thinkingEffort)
                currentCoroutineContext().ensureActive()
                if (!payload.isFresh(SystemClock.elapsedRealtime())) {
                    local.update { it.copy(summaryError = R.string.share_text_expired) }
                    return@launch
                }
                val destination = AssistantTaskDestination(owner, selected, provider)
                check(runtime.sendSharedTextSummary(destination, instruction, payload, attachmentName, titleHint = instruction))
                unsentOwner = null
                val summary = SharedTextSummary(owner, "${provider.displayName} · ${selected.modelName}", payload.sourcePackage)
                local.update { it.copy(payload = null, busy = false, offerSummary = false, startingSummary = false, summary = summary) }
                runtime.observeFromAssistant(owner).collect { chat ->
                    local.update { state -> state.copy(summary = summary.copy(
                        status = TaskPresentation.from(chat), running = chat.isStreaming,
                        output = chat.messages.lastOrNull { it.role == MessageRole.Assistant }?.content.orEmpty(),
                        error = chat.errorMessage
                    )) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                local.update { it.copy(summaryError = R.string.assistant_send_failed) }
            } finally {
                unsentOwner?.let(runtime::deleteConversation)
                local.update { it.copy(busy = false, startingSummary = false) }
            }
        }
    }

    fun stopSummary() { local.value.summary?.owner?.let(runtime::stopFromAssistant) }

    fun selectSummaryChat() { local.value.summary?.owner?.let(runtime::selectConversation) }
}
