package com.flowchat.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.security.ApiKeyStore
import com.flowchat.app.domain.model.ProviderConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderRepositoryTest {
    private lateinit var database: FlowChatDatabase
    private lateinit var keyStore: FakeApiKeyStore
    private lateinit var repository: RoomProviderRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FlowChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyStore = FakeApiKeyStore()
        repository = RoomProviderRepository(database.providerDao(), database.conversationDao(), keyStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun savingProviderWithoutApiKeyDoesNotMarkKeyAsSaved() = runTest {
        val provider = ProviderConfig(
            id = "provider-custom",
            displayName = "Custom",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "test-model"
        )

        repository.upsertProvider(provider, apiKey = null)

        val saved = repository.getProvider(provider.id)
        assertNull(saved?.apiKeyAlias)
        assertNull(repository.getApiKey(saved!!))
    }

    @Test
    fun savingProviderConfigWithoutApiKeyPreservesExistingSavedKey() = runTest {
        val provider = ProviderConfig(
            id = "provider-custom",
            displayName = "Custom",
            baseUrl = "https://api.example.com/v1",
            defaultModel = "test-model"
        )
        repository.upsertProvider(provider, apiKey = "sk-test")
        val savedWithKey = repository.getProvider(provider.id)!!

        repository.upsertProvider(savedWithKey.copy(defaultModel = "other-model"), apiKey = null)

        val savedAgain = repository.getProvider(provider.id)!!
        assertEquals(savedWithKey.apiKeyAlias, savedAgain.apiKeyAlias)
        assertEquals("sk-test", repository.getApiKey(savedAgain))
    }

    @Test fun selectedConversationModelsPersistWithoutReplacingTheSavedKey() = runTest {
        val provider = ProviderConfig(id = "models", displayName = "Models", baseUrl = "https://example.test/v1", defaultModel = "beta")
        repository.upsertProvider(provider, "saved-key")
        val saved = requireNotNull(repository.getProvider(provider.id))
        repository.upsertProvider(saved.copy(conversationModels = listOf(" beta ", "manual-模型", "beta")), null)
        val reread = requireNotNull(repository.getProvider(provider.id))
        assertEquals(listOf("beta", "manual-模型"), reread.conversationModels)
        assertEquals(saved.apiKeyAlias, reread.apiKeyAlias)
        assertEquals("saved-key", repository.getApiKey(reread))
        repository.upsertProvider(reread.copy(defaultModel = "new-default"), null)
        assertEquals(listOf("beta", "manual-模型", "new-default"), repository.getProvider(provider.id)!!.conversationModels)
    }

    private class FakeApiKeyStore : ApiKeyStore {
        private val values = mutableMapOf<String, String>()

        override suspend fun save(alias: String, value: String) {
            values[alias] = value
        }

        override suspend fun read(alias: String): String? = values[alias]

        override suspend fun delete(alias: String) {
            values.remove(alias)
        }
    }
}
