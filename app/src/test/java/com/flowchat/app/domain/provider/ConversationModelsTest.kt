package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationModelsTest {
    private val provider = ProviderConfig(displayName = "Test", baseUrl = "https://example.test/v1", defaultModel = "default")

    @Test fun explicitSelectionHidesUncheckedDefaultActiveAndNewlyDiscoveredModels() {
        assertEquals(listOf("chosen", "manual"), provider.copy(conversationModels = listOf("chosen", "manual"))
            .conversationModelNames(listOf("default", "discovered", "chosen"), "older-active"))
    }

    @Test fun legacyConfigurationsKeepAutomaticDiscoveryAndTheirActiveModel() {
        assertEquals(listOf("older-active", "default", "discovered"), provider.conversationModelNames(listOf("default", "discovered"), "older-active"))
    }

    @Test fun modelSelectionCleansBlanksAndDuplicatesButPreservesCaseSensitiveIdsAndOrder() {
        assertEquals(listOf("model-a", "Model-A", "模型"), cleanModelSelection(listOf(" model-a ", "", "model-a", "Model-A", "模型")))
    }
}
