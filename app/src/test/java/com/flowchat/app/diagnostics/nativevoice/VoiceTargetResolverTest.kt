package com.flowchat.app.diagnostics.nativevoice

import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTargetResolverTest {
    private val primary = ProviderConfig(
        id = "p-primary",
        displayName = "Primary",
        baseUrl = "https://example.test/v1",
        defaultModel = "model-primary"
    )
    private val secondary = ProviderConfig(
        id = "p-secondary",
        displayName = "Secondary",
        baseUrl = "https://second.example.test/v1",
        defaultModel = "model-secondary"
    )

    @Test
    fun explicitValidSelectionWinsOverRecentConversationAndOtherProviders() = runBlocking {
        val selected = AssistantModelSelection(secondary.id, "chosen-model", false, ThinkingEffort.Low)
        val recent = Conversation(id = "c1", providerId = primary.id, modelName = "chat-model")

        val resolved = resolveVoiceTaskTarget(selected, listOf(primary, secondary), listOf(recent)) { true }

        assertEquals(selected to secondary, resolved)
    }

    @Test
    fun nullSelectionFallsBackToMostRecentNonArchivedConversation() = runBlocking {
        val archived = Conversation(id = "c-arch", providerId = primary.id, modelName = "arch-model", isArchived = true)
        val recent = Conversation(
            id = "c-recent",
            providerId = secondary.id,
            modelName = "recent-model",
            enableThinking = false,
            thinkingEffort = ThinkingEffort.High
        )

        val resolved = resolveVoiceTaskTarget(null, listOf(primary, secondary), listOf(archived, recent)) { true }

        assertEquals(AssistantModelSelection.from(recent) to secondary, resolved)
    }

    @Test
    fun nullSelectionWithoutConversationsPrefersEnabledValidProviderWithApiKey() = runBlocking {
        val resolved = resolveVoiceTaskTarget(null, listOf(primary, secondary), emptyList()) {
            it.id == secondary.id
        }

        assertEquals(AssistantModelSelection(secondary.id, secondary.defaultModel) to secondary, resolved)
    }

    @Test
    fun staleSelectionPointingToDeletedProviderFallsBackCleanly() = runBlocking {
        val stale = AssistantModelSelection("deleted-provider", "ghost-model")

        val resolved = resolveVoiceTaskTarget(stale, listOf(primary, secondary), emptyList()) {
            it.id == primary.id
        }

        assertEquals(AssistantModelSelection(primary.id, primary.defaultModel) to primary, resolved)
    }

    @Test
    fun explicitSelectionPointingToDisabledExistingProviderReturnsNull() = runBlocking {
        val chosen = AssistantModelSelection(primary.id, primary.defaultModel)
        val disabledPrimary = primary.copy(isEnabled = false)

        val resolved = resolveVoiceTaskTarget(chosen, listOf(disabledPrimary, secondary), emptyList()) { true }

        assertNull(resolved)
    }
}
