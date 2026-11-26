package com.flowchat.app.data.usage

import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.db.UsageRecord
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.provider.apiHost
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class UsageTracker @Inject constructor(private val database: FlowChatDatabase, private val settings: AppSettingsStore) {
    fun observe(conversationId: String) = database.usageDao().observe(conversationId)

    suspend fun begin(request: ChatRequest, provider: ProviderConfig): UsageRecord? {
        if (!settings.state.value.usageTrackingEnabled) return null
        val conversationId = request.usageConversationId ?: return null
        if (!supports(request.model, provider)) return null
        val record = UsageRecord(UUID.randomUUID().toString(), conversationId, request.model, System.currentTimeMillis())
        return try { database.usageDao().insert(record); record }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // Accounting must not interrupt a reply (e.g. conversation deleted).
    }

    suspend fun report(record: UsageRecord?, body: String): Boolean {
        record ?: return false
        val usage = DeepSeekUsage.parse(body) ?: return false
        try {
            database.usageDao().update(record.copy(inputTokens = usage.input, outputTokens = usage.output,
                cacheHitTokens = usage.cacheHit))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        return true
    }

    companion object {
        fun supports(model: String, provider: ProviderConfig?) =
            model.lowercase().substringAfterLast('/').startsWith("deepseek-") || provider?.apiHost() == "api.deepseek.com"
    }
}
