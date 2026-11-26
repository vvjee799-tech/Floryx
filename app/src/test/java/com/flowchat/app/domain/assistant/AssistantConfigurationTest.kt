package com.flowchat.app.domain.assistant

import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AssistantConfigurationTest {
    private val flash = ProviderConfig(id = "flash", displayName = "Flash", baseUrl = "https://example.test/v1", defaultModel = "flash")
    private val other = flash.copy(id = "other", defaultModel = "other-model")
    private val conversation = Conversation(id = "task", providerId = flash.id, modelName = "selected-flash",
        enableThinking = true, thinkingEffort = ThinkingEffort.Max)
    private val chosen = AssistantModelSelection.from(conversation)

    @Test fun missingDeletedDisabledOrInvalidChoiceNeverFallsBackToAnotherProvider() {
        assertNull(AssistantConfiguration.provider(null, listOf(other, flash)))
        assertNull(AssistantConfiguration.provider(chosen, listOf(other)))
        assertNull(AssistantConfiguration.provider(chosen, listOf(other, flash.copy(isEnabled = false))))
        assertNull(AssistantConfiguration.provider(chosen, listOf(other, flash.copy(baseUrl = "invalid"))))
        assertNull(AssistantConfiguration.provider(chosen.copy(modelName = ""), listOf(other, flash)))
        assertEquals(flash, AssistantConfiguration.provider(chosen, listOf(other, flash)))
    }

    @Test fun existingTaskKeepsItsConfigurationWhenTheNewTaskDefaultChanges() {
        val destination = AssistantTaskDestination(conversation.id, chosen, flash)
        assertTrue(destination.matches(conversation, flash))
        assertFalse(destination.matches(conversation.copy(providerId = other.id, modelName = other.defaultModel), other))
        assertFalse(destination.matches(conversation.copy(thinkingEffort = ThinkingEffort.Auto), flash))
        assertFalse(destination.matches(conversation, flash.copy(baseUrl = "https://changed.test/v1")))
        assertFalse(destination.matches(conversation.copy(isArchived = true), flash))
    }

    @Test fun persistedChoicePreservesExactModelAndThinkingWithoutSelectingFromHistory() {
        assertEquals(chosen, Json.decodeFromString<AssistantModelSelection>(Json.encodeToString(chosen)))
        assertEquals("selected-flash", chosen.modelName)
        assertEquals(ThinkingEffort.Max, chosen.thinkingEffort)
    }
}
