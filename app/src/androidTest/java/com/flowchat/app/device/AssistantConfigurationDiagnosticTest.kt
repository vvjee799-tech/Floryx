package com.flowchat.app.device

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.repository.ProviderRepository
import com.flowchat.app.presentation.assistant.AssistantPanelPresenter
import com.flowchat.app.presentation.assistant.AssistantVoicePlayback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** No real provider calls; exercises the real presenter/runtime/Room on the designated phone. */
class AssistantConfigurationDiagnosticTest {
    @Test fun newTasksRequireAnExplicitChoiceAndExistingTasksKeepTheirOwnConfiguration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runAssistantConfigurationDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("assistant_entry", Context.MODE_PRIVATE)
        val savedOwner = prefs.getString("conversation_id", null)
        val fixture = AutonomyRuntimeFixture(context)
        val preferenceName = "assistant-config-qa-${java.util.UUID.randomUUID()}"
        val isolatedContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferenceName, mode)
        }
        var panel: AssistantPanelPresenter? = null
        var passed = false
        try {
            val persisted = com.flowchat.app.data.preferences.AppSettingsStore(isolatedContext)
            val selection = AssistantModelSelection(fixture.provider.id, "exact/model-id", true, ThinkingEffort.Max)
            persisted.setAssistantModelSelection(selection)
            assertEquals(selection, com.flowchat.app.data.preferences.AppSettingsStore(isolatedContext).state.value.assistantModelSelection)
            prefs.edit().remove("conversation_id").commit()
            fixture.responder = { _, _ -> listOf(ChatDelta.Content("CONFIGURATION_QA_OK"), ChatDelta.Done) }
            fixture.create()
            val providers = object : ProviderRepository {
                override fun observeProviders() = flowOf(listOf(fixture.provider))
                override suspend fun getProvidersOnce() = listOf(fixture.provider)
                override suspend fun getProvider(id: String) = fixture.provider.takeIf { it.id == id }
                override suspend fun getApiKey(config: ProviderConfig): String? = error("Configuration resolution must not select by key availability")
                override suspend fun upsertProvider(config: ProviderConfig, apiKey: String?) = error("Read only")
                override suspend fun deleteProvider(id: String) = error("Read only")
                override suspend fun ensureTemplates() = Unit
            }
            panel = withContext(Dispatchers.Main) {
                AssistantPanelPresenter(context, fixture.runtime, fixture.chats, providers, fixture.settings, AssistantVoicePlayback(context))
            }
            fixture.await { panel.state.value.ready && panel.state.value.configurationChoices.size == 1 }
            withContext(Dispatchers.Main) { panel.updateInput("configuration fixture"); panel.send {} }
            fixture.await { !panel.state.value.submitting && panel.state.value.error != null }
            assertEquals(0, fixture.modelCalls.get())
            assertEquals("configuration fixture", panel.state.value.input)
            assertNull(panel.state.value.conversationId)
            withContext(Dispatchers.Main) { panel.chooseConfiguration(panel.state.value.configurationChoices.single()); panel.send {} }
            fixture.await { panel.state.value.output == "CONFIGURATION_QA_OK" && !panel.state.value.running }
            val first = requireNotNull(panel.state.value.conversationId)
            assertEquals(1, fixture.modelCalls.get())
            // A later default cannot retarget a saved task, even when it is invalid.
            fixture.settings.setAssistantModelSelection(AssistantModelSelection("deleted-provider", "different-model"))
            withContext(Dispatchers.Main) { panel.updateInput("continue the same fixture"); panel.send {} }
            fixture.await { fixture.modelCalls.get() == 2 && !panel.state.value.running && !panel.state.value.submitting }
            assertEquals(first, panel.state.value.conversationId)
            withContext(Dispatchers.Main) { panel.newTask(); panel.updateInput("new fixture with invalid choice"); panel.send {} }
            fixture.await { !panel.state.value.submitting && panel.state.value.error != null }
            assertEquals(2, fixture.modelCalls.get())
            assertNull(panel.state.value.conversationId)
            passed = true
        } finally {
            withContext(Dispatchers.Main) { panel?.close() }
            prefs.edit().apply { if (savedOwner == null) remove("conversation_id") else putString("conversation_id", savedOwner) }.commit()
            fixture.close(passed)
            context.deleteSharedPreferences(preferenceName)
        }
    }
}
