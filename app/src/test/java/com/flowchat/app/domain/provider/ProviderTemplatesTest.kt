package com.flowchat.app.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTemplatesTest {
    @Test
    fun exposesOneCustomEditableProvider() {
        val provider = ProviderTemplates.defaultCustomProvider()

        assertEquals("provider-custom", provider.id)
        assertEquals("Custom configuration", provider.displayName)
        assertEquals("", provider.baseUrl)
        assertEquals("", provider.defaultModel)
    }

    @Test
    fun exposesPopularProviderPresetsAsEditableFillTemplates() {
        val presets = ProviderTemplates.popularPresets()

        assertEquals(
            listOf("preset-chatgpt", "preset-deepseek", "preset-gemini"),
            presets.take(3).map { it.id }
        )
        assertTrue(presets.all { it.displayName.isNotBlank() })
        assertTrue(presets.all { it.baseUrl.startsWith("https://") })
        assertTrue(presets.all { it.defaultModel.isNotBlank() })
        assertEquals("ChatGPT", presets[0].displayName)
        assertEquals("https://api.openai.com/v1", presets[0].baseUrl)
        assertEquals("gpt-5.4-mini", presets[0].defaultModel)
        assertEquals("DeepSeek", presets[1].displayName)
        assertEquals("https://api.deepseek.com", presets[1].baseUrl)
        assertEquals("deepseek-v4-flash", presets[1].defaultModel)
        assertTrue(presets.any { it.baseUrl == "https://api.anthropic.com/v1" })
        assertTrue(presets.all { it.iconResName == null })
    }
}
