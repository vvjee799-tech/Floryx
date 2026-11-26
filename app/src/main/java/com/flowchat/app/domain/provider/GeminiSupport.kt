package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ProviderConfig
import java.net.URI

/** Only Google's own endpoint uses the native protocol; gateways keep their configured protocol. */
fun ProviderConfig.isGoogleGemini(): Boolean = apiHost() == "generativelanguage.googleapis.com"

fun ProviderConfig.geminiApiBaseUrl(): String {
    require(isGoogleGemini()) { "A direct Google Gemini endpoint is required." }
    val uri = URI(baseUrl)
    val version = uri.path.orEmpty().split('/').firstOrNull { it == "v1" || it == "v1beta" } ?: "v1beta"
    // Accept existing /v1beta/openai configurations without copying keys into URLs.
    return URI(uri.scheme, null, uri.host, uri.port, "/$version", null, null).toASCIIString()
}

fun geminiModelName(value: String): String = value.trim().removePrefix("models/").also {
    require(it.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Gemini model name." }
}
