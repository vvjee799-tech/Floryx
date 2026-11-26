package com.flowchat.app.presentation.chat

import com.flowchat.app.data.network.ModelCatalogClient
import com.flowchat.app.domain.provider.isGoogleGemini
import com.flowchat.app.domain.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface ProviderModelOptions {
    data object Loading : ProviderModelOptions
    data class Ready(val names: List<String>) : ProviderModelOptions
    data class Failed(val message: String) : ProviderModelOptions
}

/** Loads on menu open; provider updates invalidate the cache, and failures remain retryable. */
internal class ProviderModelCatalog(
    private val scope: CoroutineScope,
    private val providers: ProviderRepository,
    private val client: ModelCatalogClient?
) {
    private val mutableState = MutableStateFlow<Map<String, ProviderModelOptions>>(emptyMap())
    val state = mutableState.asStateFlow()
    private val versions = mutableMapOf<String, Long>()
    private var job: Job? = null

    fun load() {
        val catalog = client ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            for (provider in providers.getProvidersOnce().filter { it.isEnabled && it.isGoogleGemini() }) {
                provider.conversationModels?.let { names ->
                    mutableState.update { it + (provider.id to ProviderModelOptions.Ready(names)) }
                    versions[provider.id] = provider.updatedAt
                }
                if (provider.conversationModels != null) continue
                if (versions[provider.id] == provider.updatedAt && mutableState.value[provider.id] is ProviderModelOptions.Ready) continue
                mutableState.update { it + (provider.id to ProviderModelOptions.Loading) }
                var key: String? = null
                try {
                    key = providers.getApiKey(provider)
                    val names = withTimeout(45_000) { catalog.listModels(provider, key) }
                    check(names.isNotEmpty()) { "No available Gemini chat models." }
                    versions[provider.id] = provider.updatedAt
                    mutableState.update { it + (provider.id to ProviderModelOptions.Ready(names)) }
                } catch (_: TimeoutCancellationException) {
                    mutableState.update { it + (provider.id to ProviderModelOptions.Failed("Gemini model list timed out.")) }
                } catch (cancelled: CancellationException) {
                    mutableState.update { it - provider.id }
                    throw cancelled
                } catch (failure: Exception) {
                    val message = failure.message.orEmpty().let { if (key.isNullOrBlank()) it else it.replace(key!!, "[REDACTED]") }
                    mutableState.update { it + (provider.id to ProviderModelOptions.Failed(message)) }
                }
            }
        }
    }
}
