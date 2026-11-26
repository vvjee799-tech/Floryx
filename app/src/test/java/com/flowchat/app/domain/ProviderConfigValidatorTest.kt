package com.flowchat.app.domain

import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.validation.ProviderConfigValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigValidatorTest {
    @Test
    fun rejectsBlankBaseUrl() {
        val errors = ProviderConfigValidator.validate(
            ProviderConfig(displayName = "OpenAI", baseUrl = "", defaultModel = "gpt-4o-mini")
        )

        assertTrue(errors.contains(ProviderConfigValidator.Error.BlankBaseUrl))
    }

    @Test
    fun rejectsInvalidBaseUrl() {
        val errors = ProviderConfigValidator.validate(
            ProviderConfig(displayName = "OpenAI", baseUrl = "not a url", defaultModel = "gpt-4o-mini")
        )

        assertTrue(errors.contains(ProviderConfigValidator.Error.InvalidBaseUrl))
    }

    @Test
    fun rejectsBlankModel() {
        val errors = ProviderConfigValidator.validate(
            ProviderConfig(displayName = "OpenAI", baseUrl = "https://api.openai.com/v1", defaultModel = " ")
        )

        assertTrue(errors.contains(ProviderConfigValidator.Error.BlankModel))
    }

    @Test
    fun acceptsValidHttpsProvider() {
        val errors = ProviderConfigValidator.validate(
            ProviderConfig(displayName = "OpenAI", baseUrl = "https://api.openai.com/v1", defaultModel = "gpt-4o-mini")
        )

        assertEquals(emptyList<ProviderConfigValidator.Error>(), errors)
    }

    @Test
    fun rejectsCleartextPublicProvider() {
        val errors = ProviderConfigValidator.validate(
            ProviderConfig(displayName = "Unsafe", baseUrl = "http://api.example.com/v1", defaultModel = "model")
        )

        assertTrue(errors.contains(ProviderConfigValidator.Error.InsecurePublicUrl))
    }

    @Test
    fun rejectsPublicHostsContainingPrivateIpv4Octets() {
        val publicHosts = listOf(
            "10.1.2.3.example", "example.10.1.2.3.test", "127.0.0.1.example",
            "192.168.1.2.example", "172.16.0.3.example"
        )

        publicHosts.forEach { host ->
            assertEquals("host=$host", listOf(ProviderConfigValidator.Error.InsecurePublicUrl),
                ProviderConfigValidator.validateBaseUrl("http://$host/v1"))
        }
    }

    @Test
    fun rejectsPublicIpv4AddressesOutsidePrivateRanges() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1").forEach { host ->
            assertEquals("host=$host", listOf(ProviderConfigValidator.Error.InsecurePublicUrl),
                ProviderConfigValidator.validateBaseUrl("http://$host/v1"))
        }
    }

    @Test
    fun rejectsUserInfoThatOnlyLooksLikeALocalHost() {
        assertEquals(listOf(ProviderConfigValidator.Error.InsecurePublicUrl),
            ProviderConfigValidator.validateBaseUrl("http://127.0.0.1@public.example/v1"))
    }

    @Test
    fun allowsCleartextLocalNetworkProvider() {
        val localHosts = listOf("localhost", "127.0.0.1", "127.2.3.4", "[::1]",
            "192.168.1.20", "10.0.0.8", "10.255.255.255", "172.16.0.3", "172.31.255.255")

        localHosts.forEach { host ->
            val errors = ProviderConfigValidator.validate(
                ProviderConfig(displayName = "Ollama", baseUrl = "http://$host:11434/v1", defaultModel = "llama3.2")
            )
            assertEquals("host=$host", emptyList<ProviderConfigValidator.Error>(), errors)
        }
    }
}
