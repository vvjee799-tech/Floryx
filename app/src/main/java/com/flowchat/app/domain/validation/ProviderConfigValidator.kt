package com.flowchat.app.domain.validation

import com.flowchat.app.domain.model.ProviderConfig
import java.net.URI

object ProviderConfigValidator {
    enum class Error {
        BlankBaseUrl,
        InvalidBaseUrl,
        InsecurePublicUrl,
        BlankModel,
        BlankName
    }

    fun validate(config: ProviderConfig): List<Error> {
        val errors = mutableListOf<Error>()
        if (config.displayName.isBlank()) {
            errors += Error.BlankName
        }
        errors += validateBaseUrl(config.baseUrl)
        if (config.defaultModel.isBlank()) {
            errors += Error.BlankModel
        }
        return errors
    }

    /** Model discovery can run before a name or default model has been selected. */
    fun validateBaseUrl(baseUrl: String): List<Error> {
        if (baseUrl.isBlank()) {
            return listOf(Error.BlankBaseUrl)
        }
        val uri = baseUrl.toHttpUri()
        return when {
            uri == null -> listOf(Error.InvalidBaseUrl)
            uri.scheme == "http" && !uri.host.isLocalNetworkHost() -> listOf(Error.InsecurePublicUrl)
            else -> emptyList()
        }
    }

    private fun String.toHttpUri(): URI? = runCatching {
        val uri = URI(trim())
        uri.takeIf { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrBlank() }
    }.getOrNull()

    private fun String?.isLocalNetworkHost(): Boolean {
        val host = this?.lowercase()?.removeSurrounding("[", "]") ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }
}
