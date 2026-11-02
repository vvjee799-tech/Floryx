package com.flowchat.app.presentation.assistant

import android.content.Context
import com.flowchat.app.R
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

data class AssistantPanelState(
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
    val confirmation: DeviceActionConfirmationUi? = null
)

@Singleton
class AssistantPanelFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: ChatTaskRuntime,
    private val chats: ChatRepository,
    private val providers: ProviderRepository
) {
    fun create() = AssistantPanelPresenter(com.flowchat.app.locale.AppLocale.wrapContext(context), runtime, chats, providers)
}

/** The visible panel owns subscriptions and text input; the application runtime owns execution. */
class AssistantPanelPresenter internal constructor(
    private val context: Context,
    private val runtime: ChatTaskRuntime,
    private val chats: ChatRepository,
    private val providers: ProviderRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("assistant_entry", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AssistantPanelState())
    val state = mutable.asStateFlow()
    private var observer: Job? = null
    private var acceptingPage = true

    init {
        scope.launch {
            try {
                val owner = runtime.activeConversations.value.lastOrNull()
                    ?: preferences.getString("conversation_id", null)?.takeIf { id ->
                        chats.getConversation(id)?.isArchived == false
                    }
                if (owner != null) observe(owner)
                mutable.update { it.copy(ready = true) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(ready = true, error = e.localizedMessage) } }
        }
    }

    fun updateInput(value: String) { mutable.update { it.copy(input = value.take(32_000), error = null) } }
    fun updatePage(page: AssistPageContext?) {
        if (acceptingPage) mutable.update { it.copy(page = page, includePage = false) }
    }
    fun includePage(include: Boolean) { mutable.update { it.copy(includePage = include && it.page != null) } }

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
                    confirmation = chat.pendingDeviceActionConfirmation,
                    error = chat.errorMessage ?: latest?.content?.takeIf { latest.status == MessageStatus.Failed }
                ) }
            }
        }
    }

    fun send(onAccepted: () -> Unit) {
        val value = state.value
        if (!value.ready || value.submitting || value.running || value.input.isBlank()) return
        val page = value.page.takeIf { value.includePage }
        if (page != null && !page.isFresh()) {
            mutable.update { it.copy(page = null, includePage = false, error = context.getString(R.string.assistant_page_expired)) }
            return
        }
        mutable.update { it.copy(submitting = true, error = null) }
        scope.launch {
            try {
                var owner = value.conversationId?.takeIf { chats.getConversation(it)?.isArchived == false }
                if (owner == null) {
                    val selected = runtime.uiState.value.currentConversation
                    val available = providers.getProvidersOnce().filter { ProviderConfigValidator.validate(it).isEmpty() }
                    val provider = available.firstOrNull { it.id == selected?.providerId }
                        ?: available.firstOrNull { providers.getApiKey(it) != null } ?: available.firstOrNull()
                        ?: error(context.getString(R.string.assistant_configure_provider))
                    owner = chats.createConversation(provider.id,
                        selected?.modelName?.takeIf { selected.providerId == provider.id } ?: provider.defaultModel).id
                    preferences.edit().putString("conversation_id", owner).apply()
                    observe(owner)
                }
                check(runtime.sendFromAssistant(owner, value.input, page?.asAttachment())) {
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

    fun stop() { state.value.conversationId?.let(runtime::stopFromAssistant) }
    fun confirm(approved: Boolean) {
        val value = state.value
        val owner = value.conversationId ?: return
        value.confirmation?.let { runtime.respondFromAssistant(owner, it.requestId, approved) }
    }
    fun selectForFullChat() { state.value.conversationId?.let(runtime::selectConversation) }
    fun close() { scope.cancel(); mutable.update { it.copy(page = null, includePage = false) } }
}
