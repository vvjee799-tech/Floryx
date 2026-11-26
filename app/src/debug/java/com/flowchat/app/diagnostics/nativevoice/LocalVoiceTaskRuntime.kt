package com.flowchat.app.diagnostics.nativevoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.room.Room
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.data.repository.RoomChatRepository
import com.flowchat.app.data.task.TaskRunJournal
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.assistant.AssistantTaskDestination
import com.flowchat.app.domain.model.AppUsageSummary
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.MemoryDraft
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.MemorySnapshot
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.RecentAppActivity
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.prompt.PromptProfileConfig
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.domain.repository.AppUsageReader
import com.flowchat.app.domain.repository.MemoryRepository
import com.flowchat.app.domain.repository.PromptProfileRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/** Test backend for the Dev APK. The regular app's accounts, task store and tools are excluded. */
internal class LocalVoiceTaskRuntime(context: Context) : VoiceTaskBackend {
    override val runtimeMode: String = "local"
    private val database = Room.databaseBuilder(context, FlowChatDatabase::class.java, "native-voice-local.db").build()
    override val chats = RoomChatRepository(database.conversationDao(), database.messageDao())
    override val journal = TaskRunJournal(database.taskRunDao())
    private val provider = ProviderConfig(
        id = "native-voice-local",
        displayName = "Native voice local replay",
        baseUrl = "https://native-voice-local.invalid/v1",
        defaultModel = "native-voice-local"
    )
    private val data = LocalData(provider)
    private val client = object : ChatCompletionClient {
        private val handler = Handler(Looper.getMainLooper())

        override fun streamChat(request: ChatRequest, provider: ProviderConfig, apiKey: String?) = callbackFlow {
            check(provider == this@LocalVoiceTaskRuntime.provider && apiKey == null)
            check(request.messages.any { it.role == "user" && !it.content.isNullOrBlank() })
            val owner = requireNotNull(request.usageConversationId)
            Log.i(LOG_TAG, "local_started owner=$owner")
            // A scheduled producer still attempts delivery after its collector has been stopped.
            handler.postDelayed({
                val reply = "本地短答 " + UUID.randomUUID().toString().take(8)
                val delivered = trySend(ChatDelta.Content(reply)).isSuccess
                Log.i(LOG_TAG, "local_reply owner=$owner delivered=$delivered")
                if (delivered) trySend(ChatDelta.Done)
                channel.close()
            }, 1_200L)
            awaitClose()
        }
    }
    override val runtime = ChatTaskRuntime(
        chatRepository = chats,
        providerRepository = data,
        chatClient = client,
        appUsageReader = data,
        appLauncher = object : AppLauncher {
            override suspend fun openApp(appName: String): String {
                Log.w(LOG_TAG, "blocked_device_action")
                error("Local replay cannot open apps")
            }
        },
        promptProfileRepository = data,
        memoryRepository = data,
        appSettingsStore = AppSettingsStore(AppSettings(memoryEnabled = false, installId = provider.id)),
        taskJournal = journal
    )

    override suspend fun createConversation(): Conversation {
        val conversation = chats.createConversation(provider.id, provider.defaultModel)
        chats.updateThinkingSettings(conversation.id, false, ThinkingEffort.Auto)
        return requireNotNull(chats.getConversation(conversation.id))
    }

    override suspend fun submit(conversation: Conversation, text: String): Boolean =
        runtime.sendFromAssistant(
            conversation.id, text,
            destination = AssistantTaskDestination(conversation.id, AssistantModelSelection.from(conversation), provider)
        )

    companion object {
        const val LOG_TAG = "NativeVoiceLocal"
    }

    private class LocalData(private val provider: ProviderConfig) :
        ProviderRepository, AppUsageReader, PromptProfileRepository, MemoryRepository {
        override fun observeProviders() = flowOf(listOf(provider))
        override suspend fun getProvider(id: String) = provider.takeIf { it.id == id }
        override suspend fun getProvidersOnce() = listOf(provider)
        override suspend fun getApiKey(config: ProviderConfig): String? = null
        override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = error("Local provider is read-only")
        override suspend fun deleteProvider(id: String) = error("Local provider is read-only")
        override suspend fun ensureTemplates() = Unit
        override fun hasUsageAccess() = false
        override suspend fun getUsageSummary(range: String) = AppUsageSummary(range, 0, 0, 0, emptyList())
        override suspend fun getRecentActivity(hours: Int) = RecentAppActivity(hours, emptyList())
        override suspend fun getActiveProfile() = PromptProfileConfig()
        override suspend fun getActiveThinkingFormat(): String? = null
        override suspend fun retrieve(userMessage: String, topN: Int) = emptyList<MemoryRecord>()
        override suspend fun snapshot() = MemorySnapshot(emptyList(), 0)
        override suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean) = false
        override suspend fun update(id: String, summary: String) = Unit
        override suspend fun getAll() = emptyList<MemoryRecord>()
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
    }
}
