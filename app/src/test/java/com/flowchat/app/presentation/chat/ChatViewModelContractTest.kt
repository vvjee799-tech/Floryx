package com.flowchat.app.presentation.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelContractTest {
    @Test
    fun rawKtorTimeoutMessageIsMappedToReadableChatError() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()

        assertTrue(source.contains("throwable.userFacingChatError()"))
        assertTrue(source.contains("private fun Throwable.userFacingChatError(): String"))
        assertTrue(source.contains("Request timeout has expired"))
    }

    @Test
    fun stopMarksActiveAssistantMessageAsStoppedInsteadOfLeavingItStreaming() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val modelSource = File("src/main/java/com/flowchat/app/domain/model/Message.kt").readText()

        assertTrue(modelSource.contains("Stopped"))
        assertTrue(source.contains("private var activeAssistantMessageId: String? = null"))
        assertTrue(source.contains("private var activeAssistantContent = \"\""))
        assertTrue(source.contains("private var activeAssistantReasoningContent = \"\""))
        assertTrue(source.contains("chatRepository.updateMessage("))
        assertTrue(source.contains("MessageStatus.Stopped"))
        assertTrue(source.contains("activeAssistantReasoningContent"))
        assertTrue(source.contains("throwable is CancellationException"))
    }

    @Test
    fun reasoningDurationIsUpdatedOnlyWhileReasoningIsStreaming() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val repositorySource = File("src/main/java/com/flowchat/app/domain/repository/ChatRepository.kt").readText()
        val reasoningStart = source.indexOf("private suspend fun appendReasoningDelta(")
        val reasoningEnd = source.indexOf("private suspend fun appendContentDelta(", reasoningStart)
        val reasoningBlock = source.substring(reasoningStart, reasoningEnd)
        val contentEnd = source.indexOf("private fun Throwable.userFacingChatError()", reasoningEnd)
        val contentBlock = source.substring(reasoningEnd, contentEnd)

        assertTrue(source.contains("private var activeAssistantReasoningStartedAt: Long? = null"))
        assertTrue(source.contains("private var activeAssistantReasoningDurationMillis = 0L"))
        assertTrue(repositorySource.contains("reasoningDurationMillis: Long? = null"))
        assertTrue(reasoningBlock.contains("val startedAt = activeAssistantReasoningStartedAt ?: System.currentTimeMillis().also"))
        assertTrue(reasoningBlock.contains("activeAssistantReasoningDurationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)"))
        assertTrue(reasoningBlock.contains("reasoningDurationMillis = activeAssistantReasoningDurationMillis"))
        assertTrue(contentBlock.contains("reasoningDurationMillis = null"))
        assertFalse(contentBlock.contains("activeAssistantReasoningDurationMillis = (System.currentTimeMillis()"))
    }

    @Test
    fun sendClearsInputAndDropsStaleTextFieldEcho() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val updateInputStart = source.indexOf("fun updateInput(value: String)")
        val updateInputEnd = source.indexOf("fun setThinkingEnabled(", updateInputStart)
        val updateInputBlock = source.substring(updateInputStart, updateInputEnd)
        val sendStart = source.indexOf("fun send()")
        val sendEnd = source.indexOf("private suspend fun appendReasoningDelta", sendStart)
        val sendBlock = source.substring(sendStart, sendEnd)

        assertTrue(source.contains("private var pendingClearedInputEcho: String? = null"))
        assertTrue(updateInputBlock.contains("val pendingEcho = pendingClearedInputEcho"))
        assertTrue(updateInputBlock.contains("if (pendingEcho != null && pendingEcho == value)"))
        assertTrue(updateInputBlock.contains("if (pendingEcho != null && value.isBlank() && input.value.isBlank())"))
        assertTrue(updateInputBlock.contains("pendingClearedInputEcho = null"))
        assertTrue(sendBlock.contains("val rawText = if (resume == null) input.value"))
        assertTrue(sendBlock.contains("val text = rawText.trim()"))
        assertTrue(sendBlock.contains("pendingClearedInputEcho = rawText"))
        assertTrue(sendBlock.contains("input.value = \"\""))
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
    fun appUsageToolsAreAvailableAndPublishToolCallTimelineMessages() {
        val source = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val uiStateSource = File("src/main/java/com/flowchat/app/presentation/chat/ChatUiState.kt").readText()
        val messageSource = File("src/main/java/com/flowchat/app/domain/model/Message.kt").readText()

        assertTrue(source.contains("import com.flowchat.app.domain.repository.AppUsageReader"))
        assertTrue(source.contains("private val appUsageReader: AppUsageReader"))
        assertTrue(messageSource.contains("Tool(\"tool\")"))
        assertTrue(source.contains("private val toolCallStatus = MutableStateFlow<ToolCallStatusUi?>(null)").not())
        assertTrue(source.contains("private val usageAccessPermissionRequest = MutableStateFlow<UsageAccessPermissionRequestUi?>(null)"))
        assertTrue(source.contains("toolCallStatus = toolStatus").not())
        assertTrue(source.contains("usageAccessPermissionRequest = permissionRequest"))
        assertTrue(uiStateSource.contains("val toolCallStatus: ToolCallStatusUi? = null").not())
        assertTrue(uiStateSource.contains("val usageAccessPermissionRequest: UsageAccessPermissionRequestUi? = null"))
        assertTrue(uiStateSource.contains("data class UsageAccessPermissionRequestUi("))
        assertTrue(source.contains("AgentToolDefinitions.withLifestyleTools("))
        assertTrue(source.contains("AgentToolDefinitions.AppUsageSummaryToolName -> executeAppUsageSummaryTool("))
        assertTrue(source.contains("AgentToolDefinitions.RecentAppActivityToolName -> executeRecentAppActivityTool("))
        assertTrue(source.contains("appUsageReader.getUsageSummary(range)"))
        assertTrue(source.contains("appUsageReader.getRecentActivity(hours)"))
        assertTrue(source.contains("requestUsageAccessPermission("))
        assertTrue(source.contains("fun dismissUsageAccessPermissionRequest()"))
        assertTrue(source.contains("ToolCallStatusUi(").not())
        assertTrue(source.contains("ToolCallPhase.Running").not())
        assertTrue(source.contains("ToolCallPhase.Complete").not())
        assertTrue(source.contains("ToolCallPhase.Failed").not())
        assertTrue(source.contains("chatRepository.appendMessage("))
        assertTrue(source.contains("MessageRole.Tool"))
        assertTrue(source.contains("MessageStatus.Streaming"))
        assertTrue(source.contains("toolMessage.id"))
        assertTrue(source.contains("toolName"))
        assertTrue(source.contains("MessageStatus.Complete"))
        assertTrue(source.contains("failedToolMessageContent(toolName, message)"))
        assertTrue(source.contains("MessageStatus.Failed"))
        assertTrue(source.contains("private fun failedToolMessageContent(toolName: String, detail: String): String"))
        assertTrue(source.contains("Open Android system Usage Access settings and allow Floryx before retrying this tool."))
        assertTrue(source.contains("Ask the user to enable Usage Access in Floryx settings.").not())
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
