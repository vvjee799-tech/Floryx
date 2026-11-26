package com.flowchat.app.data.network

import kotlinx.serialization.Serializable

@Serializable
data class GeminiModelsResponse(
    val models: List<GeminiModelDto> = emptyList(),
    val nextPageToken: String? = null
)

@Serializable
data class GeminiModelDto(
    val name: String,
    val supportedGenerationMethods: List<String> = emptyList()
)

fun GeminiModelsResponse.toChatModelNames(): List<String> = models.asSequence()
    .filter { "generateContent" in it.supportedGenerationMethods }
    .map { it.name.trim().removePrefix("models/") }
    .filter { it.startsWith("gemini-") }
    .filterNot { name -> listOf("image", "audio", "tts", "live", "embedding", "transcribe", "translate", "robotics", "computer-use", "nano-banana")
        .any { token -> name.contains(token, ignoreCase = true) } }
    .distinct().sorted().toList()

@Serializable
data class OpenAiModelsResponse(
    val data: List<OpenAiModelDto> = emptyList()
)

@Serializable
data class OpenAiModelDto(
    val id: String
)

@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaModelDto> = emptyList()
)

@Serializable
data class OllamaModelDto(
    val name: String = ""
)

fun OpenAiModelsResponse.toModelIds(): List<String> =
    data.map { it.id }.cleanModelNames()

fun OllamaTagsResponse.toModelNames(): List<String> =
    models.map { it.name }.cleanModelNames()

private fun List<String>.cleanModelNames(): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
