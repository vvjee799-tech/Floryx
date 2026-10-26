package com.flowchat.app.presentation.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small source contracts for wiring that is otherwise difficult to observe from a fake device. */
class ChatViewModelContractTest {
    @Test
    fun rawKtorTimeoutMessageIsMappedToReadableChatError() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()

        assertTrue(source.contains("throwable.userFacingChatError()"))
        assertTrue(source.contains("private fun Throwable.userFacingChatError(): String"))
        assertTrue(source.contains("Request timeout has expired"))
    }

    @Test
    fun newConversationPrefersSavedValidProviderOverBlankCustomDraft() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val newConversationStart = source.indexOf("fun newConversation()")
        val updateSettingsStart = source.indexOf("fun updateConversationSettings(", newConversationStart)
        val block = source.substring(newConversationStart, updateSettingsStart)

        assertTrue(source.contains("import com.flowchat.app.domain.validation.ProviderConfigValidator"))
        assertTrue(block.contains("val providers = providerRepository.getProvidersOnce()"))
        assertTrue(block.contains("providers.firstOrNull { provider ->"))
        assertTrue(block.contains("ProviderConfigValidator.validate(provider).isEmpty()"))
        assertTrue(block.contains("providerRepository.getApiKey(provider) != null"))
        assertTrue(block.contains("providers.firstOrNull { provider -> ProviderConfigValidator.validate(provider).isEmpty() }"))
    }

    @Test
    fun toolCallRoundsCreateAssistantSegmentsAroundToolMessages() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val sendStart = source.indexOf("fun send()")
        val collectStart = source.indexOf("private suspend fun collectAssistantResponse", sendStart)
        val sendBlock = source.substring(sendStart, collectStart)

        assertTrue(sendBlock.contains("var assistant = chatRepository.appendMessage("))
        assertTrue(sendBlock.contains("ensureVisibleAssistantSegmentBeforeToolCall("))
        assertTrue(sendBlock.contains("val toolResultMessages = executeToolCalls("))
        assertTrue(sendBlock.contains("calls = response.toolCalls"))
        assertTrue(sendBlock.contains("conversationId = conversation.id"))
        assertTrue(sendBlock.contains("modelName = conversation.modelName"))
        assertTrue(sendBlock.contains("assistant = chatRepository.appendMessage("))
        assertTrue(sendBlock.contains("activeAssistantMessageId = assistant.id"))
        assertTrue(source.contains("private suspend fun ensureVisibleAssistantSegmentBeforeToolCall("))
        assertTrue(source.contains("ReplyPolicy.toolPreface"))
    }
}
