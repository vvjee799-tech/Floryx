package com.flowchat.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiHttp

/** Android's TLS/proxy stack and HTTP/2, isolated from other providers' existing transport. */
@Module
@InstallIn(SingletonComponent::class)
object GeminiNetworkModule {
    @Provides
    @Singleton
    @GeminiHttp
    fun provideGeminiHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        engine {
            config {
                // Retrying a generation request can bill twice. Surface failures for an explicit retry.
                retryOnConnectionFailure(false)
                followRedirects(false)
                followSslRedirects(false)
            }
        }
        install(HttpTimeout)
        install(ContentNegotiation) { json(json) }
    }
}
