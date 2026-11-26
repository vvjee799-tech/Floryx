package com.flowchat.app.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptProfileParserTest {
    @Test
    fun readsThinkingFormatFromActiveTomlProfile() {
        val profile = PromptProfileParser.selectActiveProfile(
            listOf(
                PromptProfileSource(
                    name = "casual.toml",
                    content = """
                    active = false

                    [thinking]
                    format = "old format"
                    """.trimIndent()
                ),
                PromptProfileSource(
                    name = "roleplay.toml",
                    content = """
                    active = true

                    [thinking]
                    format = ""${'"'}
                    第一行
                    第二行
                    ""${'"'}
                    """.trimIndent()
                )
            )
        )

        assertEquals("第一行\n第二行", profile.thinkingFormat)
    }

    @Test
    fun skipsInjectionWhenThinkingFormatIsBlankOrMissing() {
        assertNull(
            PromptProfileParser.selectActiveProfile(
                listOf(
                    PromptProfileSource(
                        name = "blank.toml",
                        content = """
                        active = true

                        [thinking]
                        format = "   "
                        """.trimIndent()
                    )
                )
            ).thinkingFormat
        )

        assertNull(
            PromptProfileParser.selectActiveProfile(
                listOf(
                    PromptProfileSource(
                        name = "missing.toml",
                        content = """
                        active = true

                        [chat]
                        tone = "casual"
                        """.trimIndent()
                    )
                )
            ).thinkingFormat
        )
    }

    @Test
    fun usesSingleProfileWhenNoExplicitActiveProfileExists() {
        val profile = PromptProfileParser.selectActiveProfile(
            listOf(
                PromptProfileSource(
                    name = "only.toml",
                    content = """
                    [thinking]
                    format = "single profile format"
                    """.trimIndent()
                )
            )
        )

        assertEquals("single profile format", profile.thinkingFormat)
    }

    @Test
    fun returnsNullWhenMultipleProfilesExistWithoutActiveMarker() {
        val profile = PromptProfileParser.selectActiveProfile(
            listOf(
                PromptProfileSource(
                    name = "a.toml",
                    content = """
                    [thinking]
                    format = "A"
                    """.trimIndent()
                ),
                PromptProfileSource(
                    name = "b.toml",
                    content = """
                    [thinking]
                    format = "B"
                    """.trimIndent()
                )
            )
        )

        assertNull(profile.thinkingFormat)
    }

    @Test
    fun readsMemorySettingsFromActiveTomlProfile() {
        val profile = PromptProfileParser.selectActiveProfile(
            listOf(
                PromptProfileSource(
                    name = "memory.toml",
                    content = """
                    active = true

                    [thinking]
                    format = "think"

                    [memory]
                    enabled = true
                    top_n = 3
                    """.trimIndent()
                )
            )
        )

        assertEquals(true, profile.memory.enabled)
        assertEquals(3, profile.memory.topN)
    }

    @Test
    fun memorySettingsDefaultToDisabledWhenMissingOrInvalid() {
        val missing = PromptProfileParser.selectActiveProfile(
            listOf(
                PromptProfileSource(
                    name = "missing.toml",
                    content = """
                    active = true

                    [thinking]
                    format = "think"
                    """.trimIndent()
                )
            )
        )

        val invalid = PromptProfileParser.selectActiveProfile(
            listOf(
                PromptProfileSource(
                    name = "invalid.toml",
                    content = """
                    active = true

                    [memory]
                    enabled = true
                    top_n = 99
                    """.trimIndent()
                )
            )
        )

        assertEquals(false, missing.memory.enabled)
        assertEquals(5, missing.memory.topN)
        assertEquals(true, invalid.memory.enabled)
        assertEquals(5, invalid.memory.topN)
    }
}
