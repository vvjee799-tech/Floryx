package com.flowchat.app.domain.assistant

import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.domain.validation.ProviderConfigValidator
import kotlinx.serialization.Serializable

/** An explicit choice for new assistant tasks, independent of conversation sort order. */
@Serializable
data class AssistantModelSelection(
    val providerId: String,
    val modelName: String,
    val enableThinking: Boolean = true,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.Auto
) {
    companion object {
        fun from(conversation: Conversation) = AssistantModelSelection(conversation.providerId,
            conversation.modelName, conversation.enableThinking, conversation.thinkingEffort)
    }
}

data class AssistantTaskDestination(val conversationId: String, val selection: AssistantModelSelection,
    val provider: ProviderConfig) {
    fun matches(conversation: Conversation, current: ProviderConfig) =
        conversation.id == conversationId && !conversation.isArchived &&
            AssistantModelSelection.from(conversation) == selection && current == provider && current.isEnabled
}

object AssistantConfiguration {
    fun provider(selection: AssistantModelSelection?, available: List<ProviderConfig>): ProviderConfig? =
        selection?.takeIf { it.modelName.isNotBlank() }?.let { chosen ->
            available.singleOrNull { it.id == chosen.providerId && it.isEnabled &&
                ProviderConfigValidator.validate(it).isEmpty() }
        }
}
