package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.provider.apiHost
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class KtorModelCatalogClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) : ModelCatalogClient {
    override suspend fun listModels(provider: ProviderConfig, apiKey: String?): List<String> {
        val primaryResult = runCatching {
            requestOpenAiCompatibleModels(provider, apiKey)
        }
        primaryResult.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        if (provider.shouldTryOllamaTags()) {
            val ollamaResult = runCatching {
                requestOllamaTags(provider.ollamaTagsEndpoint(), apiKey)
            }
            ollamaResult.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        val error = primaryResult.exceptionOrNull()
        if (error != null) {
            throw error
        }
        return emptyList()
    }

    private suspend fun requestOpenAiCompatibleModels(provider: ProviderConfig, apiKey: String?): List<String> {
        val response = httpClient.get(provider.modelsEndpoint()) {
            header(HttpHeaders.Accept, "application/json")
            if (provider.apiHost() == "api.anthropic.com") {
                header("anthropic-version", "2023-06-01")
                if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
            } else if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (provider.customHeadersJson.isNotBlank()) {
                runCatching { json.parseToJsonElement(provider.customHeadersJson).jsonObject }.getOrNull()
                    ?.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
            }
        }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw ModelCatalogException("请先填写或保存 API Key")
        }
        if (!response.status.isSuccess()) {
            throw ModelCatalogException("模型列表获取失败：HTTP ${response.status.value}")
        }
        return json.decodeFromString<OpenAiModelsResponse>(response.bodyAsText()).toModelIds()
    }

    private suspend fun requestOllamaTags(url: String, apiKey: String?): List<String> {
        val response = httpClient.get(url) {
            header(HttpHeaders.Accept, "application/json")
            if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }
        if (!response.status.isSuccess()) {
            throw ModelCatalogException("Ollama 模型列表获取失败：HTTP ${response.status.value}")
        }
        return response.body<OllamaTagsResponse>().toModelNames()
    }

    private fun ProviderConfig.modelsEndpoint(): String =
        baseUrl.trimEnd('/') + "/models"

    private fun ProviderConfig.shouldTryOllamaTags(): Boolean {
        val normalized = baseUrl.trimEnd('/').lowercase()
        return normalized.contains("ollama") ||
            normalized.contains("localhost") ||
            normalized.contains("127.0.0.1") ||
            normalized.endsWith("/v1")
    }

    private fun ProviderConfig.ollamaTagsEndpoint(): String {
        val trimmed = baseUrl.trimEnd('/')
        val withoutOpenAiVersion = if (trimmed.endsWith("/v1")) {
            trimmed.removeSuffix("/v1")
        } else {
            trimmed
        }
        val withoutApi = withoutOpenAiVersion.removeSuffix("/api")
        return "$withoutApi/api/tags"
    }
}

class ModelCatalogException(message: String) : Exception(message)
