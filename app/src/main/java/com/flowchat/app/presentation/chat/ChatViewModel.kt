package com.flowchat.app.presentation.chat

import androidx.lifecycle.ViewModel
import com.flowchat.app.data.attachment.ExtractedAttachment
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.model.ThinkingEffort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** UI facade. The application runtime owns execution and survives this ViewModel being cleared. */
@HiltViewModel
class ChatViewModel @Inject constructor(internal val runtime: ChatTaskRuntime) : ViewModel() {
    fun recheckTaskPower() = runtime.recheckTaskPower()
    val providerModelOptions get() = runtime.providerModelOptions
    val usageRecords get() = runtime.usageRecords
    val terminalState get() = runtime.terminalState
    val uiState get() = runtime.uiState
    val workspaceState get() = runtime.workspaceState
    val balanceState get() = runtime.balanceState

    fun loadModelOptions() = runtime.loadModelOptions()
    fun loadOlderMessages() = runtime.loadOlderMessages()
    fun renameConversation(id: String, title: String) = runtime.renameConversation(id, title)
    fun pinConversation(id: String, pinned: Boolean) = runtime.pinConversation(id, pinned)
    fun archiveConversation(id: String, archived: Boolean) = runtime.archiveConversation(id, archived)
    suspend fun exportConversation(id: String) = runtime.exportConversation(id)
    fun setUsageTrackingEnabled(enabled: Boolean) = runtime.setUsageTrackingEnabled(enabled)
    fun setPhotoAnswerEnabled(enabled: Boolean) = runtime.setPhotoAnswerEnabled(enabled)
    fun setFloatingBallEnabled(enabled: Boolean) = runtime.setFloatingBallEnabled(enabled)
    suspend fun openAnswerConversation(id: String) = runtime.openAnswerConversation(id)
    fun refreshWorkspace() = runtime.refreshWorkspace()
    fun workspacePickerFailed(message: String?) = runtime.workspacePickerFailed(message)
    fun selectWorkspace(uri: String, flags: Int, conversationId: String?) = runtime.selectWorkspace(uri, flags, conversationId)
    fun setWorkspaceDefault(enabled: Boolean) = runtime.setWorkspaceDefault(enabled)
    fun clearWorkspace() = runtime.clearWorkspace()
    fun retryWorkspaceSave(localPath: String) = runtime.retryWorkspaceSave(localPath)
    fun refreshBalance() = runtime.refreshBalance()
    fun updateInput(value: String) = runtime.updateInput(value)
    fun setThinkingEnabled(enabled: Boolean) = runtime.setThinkingEnabled(enabled)
    fun setThinkingEffort(effort: ThinkingEffort) = runtime.setThinkingEffort(effort)
    fun setMemoryEnabled(enabled: Boolean) = runtime.setMemoryEnabled(enabled)
    fun setPowerModeEnabled(enabled: Boolean) = runtime.setPowerModeEnabled(enabled)
    fun setBackgroundExecutionEnabled(enabled: Boolean) = runtime.setBackgroundExecutionEnabled(enabled)
    fun setCommandExecutionEnabled(enabled: Boolean) = runtime.setCommandExecutionEnabled(enabled)
    fun prepareTerminal() = runtime.prepareTerminal()
    fun installDeveloperTools() = runtime.installDeveloperTools()
    fun stopTerminalCommand(id: String) = runtime.stopTerminalCommand(id)
    fun recheckTerminalCleanup(id: String) = runtime.recheckTerminalCleanup(id)
    fun setPowerConnectionMode(mode: com.flowchat.app.domain.device.PowerConnectionMode) = runtime.setPowerConnectionMode(mode)
    fun setExecutionPermissionMode(mode: com.flowchat.app.domain.device.ExecutionPermissionMode) = runtime.setExecutionPermissionMode(mode)
    fun requestShizukuPermission() = runtime.requestShizukuPermission()
    fun refreshShizukuConnection() = runtime.refreshShizukuConnection()
    fun recheckExecutionDisplayCleanup() = runtime.recheckExecutionDisplayCleanup()
    fun recheckRootRequestCleanup() = runtime.recheckRootRequestCleanup()
    fun refreshAccessibilityConnection() = runtime.refreshAccessibilityConnection()
    fun refreshDeviceAssistantConnections() = runtime.refreshDeviceAssistantConnections()
    fun openAccessibilitySettings() = runtime.openAccessibilitySettings()
    fun confirmDeviceAction() = runtime.confirmDeviceAction()
    fun cancelDeviceAction() = runtime.cancelDeviceAction()
    fun deleteMemory(id: String) = runtime.deleteMemory(id)
    fun clearMemories() = runtime.clearMemories()
    fun attachTextFile(name: String, text: String) = runtime.attachTextFile(name, text)
    fun loadAttachment(reader: () -> ExtractedAttachment, errorText: (Throwable) -> String) = runtime.loadAttachment(reader, errorText)
    fun clearAttachment() = runtime.clearAttachment()
    fun reportError(message: String) = runtime.reportError(message)
    fun dismissUsageAccessPermissionRequest() = runtime.dismissUsageAccessPermissionRequest()
    fun selectConversation(id: String) = runtime.selectConversation(id)
    fun deleteConversation(id: String) = runtime.deleteConversation(id)
    fun newConversation() = runtime.newConversation()
    fun updateConversationSettings(
        assistantName: String,
        assistantAvatarPath: String?,
        showAvatars: Boolean,
        systemPrompt: String,
        temperature: Double,
        topP: Double,
        maxTokens: Int?
    ) = runtime.updateConversationSettings(assistantName, assistantAvatarPath, showAvatars, systemPrompt, temperature, topP, maxTokens)
    fun updateConversationModel(provider: ProviderConfig, modelName: String) = runtime.updateConversationModel(provider, modelName)
    fun send() = runtime.send()
    fun correctCalendarTime(runId: String) = runtime.correctCalendarTime(runId)
    fun resumeDeviceTask() = runtime.resumeDeviceTask()
    fun resumeInterruptedTask() = runtime.resumeInterruptedTask()
    fun continueWithFreshBudget() = runtime.continueWithFreshBudget()
    fun acknowledgeTaskRecovery(runId: String) = runtime.acknowledgeTaskRecovery(runId)
    fun endInterruptedTask() = runtime.endInterruptedTask()
    fun updateMemory(id: String, summary: String) = runtime.updateMemory(id, summary)
    fun stop() = runtime.stop()
    fun cancelQueuedDeviceTask(owner: String, runId: String) {
        val progress = runtime.taskProgress.value[owner]
        if (progress?.phase == DeviceTaskPhase.WaitingForDevice && progress.runId == runId) runtime.stopFromAssistant(owner)
    }
    fun retryLastFailed() = runtime.retryLastFailed()
}
