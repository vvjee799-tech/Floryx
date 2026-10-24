package com.flowchat.app.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.*
import com.flowchat.app.data.preferences.*
import com.flowchat.app.data.repository.RoomChatRepository
import com.flowchat.app.data.usage.*
import com.flowchat.app.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageAccountingTest {
    @Test fun parsesReportedUsageWithoutEstimatingCosts() {
        assertNull(DeepSeekUsage.parse("""{"usage":{"prompt_tokens":-1,"completion_tokens":4}}"""))
        assertNull(DeepSeekUsage.parse("""{"usage":{"total_tokens":20}}"""))
        assertEquals(800L, DeepSeekUsage.parse("""{"usage":{"prompt_tokens":1000,"completion_tokens":20,"prompt_cache_miss_tokens":200}}""")!!.cacheHit)
    }

    @Test fun trailingUsageDeduplicatesPersistsAndHonorsToggle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "usage-accounting-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, FlowChatDatabase::class.java, name).allowMainThreadQueries().build()
        val db = open()
        val settings = AppSettingsStore(AppSettings(usageTrackingEnabled = true))
        val tracker = UsageTracker(db, settings)
        val repository = RoomChatRepository(db.conversationDao(), db.messageDao())
        val conversation = repository.createConversation("p", "deepseek-flash")
        val usage = """data: {"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":100,"prompt_cache_hit_tokens":600,"prompt_cache_miss_tokens":400}}"""
        var body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n$usage\n\n$usage\n\ndata: [DONE]\n\n"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val replies = ArrayDeque<String>()
        val http = HttpClient(MockEngine { respond(if (replies.isEmpty()) body else replies.removeFirst(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) }) {
            install(io.ktor.client.plugins.HttpTimeout)
            install(ContentNegotiation) { json(json) }
        }
        val client = KtorOpenAiCompatibleClient(http, json).apply { usageTracker = tracker }
        val request = ChatRequest("deepseek-flash", listOf(ChatRequestMessage("user", "test")), 0.0, 1.0, null, usageConversationId = conversation.id)
        val provider = ProviderConfig(id = "p", displayName = "Test", baseUrl = "https://api.deepseek.com", defaultModel = request.model)
        try {
            val events = client.streamChat(request, provider, null).toList()
            assertEquals(listOf(ChatDelta.Content("ok"), ChatDelta.Done), events)
            var records = tracker.observe(conversation.id).first()
            assertEquals(1, records.size)
            assertEquals(1000L, records.single().inputTokens)
            assertNull(records.single().costLowNanos)
            settings.setUsageTrackingEnabled(false)
            client.streamChat(request, provider, null).toList()
            assertEquals(1, tracker.observe(conversation.id).first().size)
            settings.setUsageTrackingEnabled(true)
            body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            client.streamChat(request, provider, null).toList()
            records = tracker.observe(conversation.id).first()
            assertEquals(2, records.size)
            assertEquals(1, records.count { it.inputTokens == null })
            // Non-streaming fallback is a separate billed attempt; the empty stream remains unknown.
            body = """{"choices":[{"message":{"content":"fallback"},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":10}}"""
            replies.add("data: [DONE]\n\n")
            replies.add(body)
            assertTrue(client.streamChat(request, provider, null).toList().contains(ChatDelta.Content("fallback")))
            records = tracker.observe(conversation.id).first()
            assertEquals(4, records.size)
            assertEquals(2, records.count { it.inputTokens == null })
            assertEquals(50L, records.last().inputTokens)
            // Same-chunk usage is recorded even when the selected model has no verified tariff.
            body = "data: {\"choices\":[{\"delta\":{\"content\":\"unknown rate\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}\n\n"
            client.streamChat(request.copy(model = "deepseek-chat"), provider, null).toList()
            records = tracker.observe(conversation.id).first()
            assertEquals(100L, records.last().inputTokens)
            assertNull(records.last().costLowNanos)

        } finally { http.close(); db.close() }
        val reopened = open()
        try {
            assertEquals(5, reopened.usageDao().observe(conversation.id).first().size)
            RoomChatRepository(reopened.conversationDao(), reopened.messageDao()).deleteConversation(conversation.id)
            assertTrue(reopened.usageDao().observe(conversation.id).first().isEmpty())
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
