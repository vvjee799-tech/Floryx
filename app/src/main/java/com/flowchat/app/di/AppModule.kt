package com.flowchat.app.di

import android.content.Context
import androidx.room.Room
import com.flowchat.app.data.appusage.AndroidAppUsageReader
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.data.db.ConversationDao
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.db.MIGRATION_1_2
import com.flowchat.app.data.db.MIGRATION_2_3
import com.flowchat.app.data.db.MIGRATION_3_4
import com.flowchat.app.data.db.MIGRATION_4_5
import com.flowchat.app.data.db.MIGRATION_5_6
import com.flowchat.app.data.db.MIGRATION_6_7
import com.flowchat.app.data.db.MIGRATION_8_9
import com.flowchat.app.data.db.MIGRATION_9_10
import com.flowchat.app.data.db.MIGRATION_11_12
import com.flowchat.app.data.db.MIGRATION_12_13
import com.flowchat.app.data.db.MIGRATION_13_14
import com.flowchat.app.data.db.MIGRATION_14_15
import com.flowchat.app.data.db.MIGRATION_10_11
import com.flowchat.app.data.db.MIGRATION_7_8
import com.flowchat.app.data.db.MessageDao
import com.flowchat.app.data.db.ProviderDao
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.data.network.ChatCompletionClient
import com.flowchat.app.data.chat.TaskContextManager
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.network.KtorModelCatalogClient
import com.flowchat.app.data.network.ModelCatalogClient
import com.flowchat.app.data.network.TavilySearchClient
import com.flowchat.app.data.network.WebSearchClient
import com.flowchat.app.data.repository.FileMemoryRepository
import com.flowchat.app.data.repository.FilePromptProfileRepository
import com.flowchat.app.data.repository.KeystoreWebSearchSettingsRepository
import com.flowchat.app.data.repository.RoomChatRepository
import com.flowchat.app.data.repository.RoomProviderRepository
import com.flowchat.app.data.security.ApiKeyStore
import com.flowchat.app.data.security.KeystoreApiKeyStore
import com.flowchat.app.domain.repository.ChatRepository
import com.flowchat.app.domain.repository.AppUsageReader
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.domain.repository.MemoryRepository
import com.flowchat.app.domain.repository.PromptProfileRepository
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.domain.repository.WebSearchSettingsRepository
import com.flowchat.app.domain.device.DeviceAssistantGateway
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @ContextDispatcher
    fun provideContextDispatcher(): kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(CIO) {
        install(HttpTimeout)
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FlowChatDatabase =
        Room.databaseBuilder(context, FlowChatDatabase::class.java, "flowchat.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .build()

    @Provides fun provideProviderDao(database: FlowChatDatabase): ProviderDao = database.providerDao()
    @Provides fun provideConversationDao(database: FlowChatDatabase): ConversationDao = database.conversationDao()
    @Provides fun provideMessageDao(database: FlowChatDatabase): MessageDao = database.messageDao()
    @Provides @Singleton fun provideTaskContextManager(client: ChatCompletionClient, repository: ChatRepository): TaskContextManager =
        TaskContextManager(client, repository)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds abstract fun bindApiKeyStore(impl: KeystoreApiKeyStore): ApiKeyStore
    @Binds abstract fun bindAppUsageReader(impl: AndroidAppUsageReader): AppUsageReader
    @Binds abstract fun bindAppLauncher(impl: AndroidAppLauncher): AppLauncher
    @Binds abstract fun bindDeviceAssistantGateway(
        impl: ShizukuDeviceAssistantGateway
    ): DeviceAssistantGateway
    @Binds abstract fun bindChatRepository(impl: RoomChatRepository): ChatRepository
    @Binds abstract fun bindMemoryRepository(impl: FileMemoryRepository): MemoryRepository
    @Binds abstract fun bindPromptProfileRepository(impl: FilePromptProfileRepository): PromptProfileRepository
    @Binds abstract fun bindProviderRepository(impl: RoomProviderRepository): ProviderRepository
    @Binds abstract fun bindChatCompletionClient(impl: KtorOpenAiCompatibleClient): ChatCompletionClient
    @Binds abstract fun bindModelCatalogClient(impl: KtorModelCatalogClient): ModelCatalogClient
    @Binds abstract fun bindWebSearchClient(impl: TavilySearchClient): WebSearchClient
    @Binds abstract fun bindWebSearchSettingsRepository(
        impl: KeystoreWebSearchSettingsRepository
    ): WebSearchSettingsRepository
}
