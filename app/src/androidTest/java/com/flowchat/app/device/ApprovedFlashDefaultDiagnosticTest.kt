package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.assistant.AssistantModelSelection
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Applies the user's explicit official-Flash choice; no generation or conversation edits. */
class ApprovedFlashDefaultDiagnosticTest {
    @Test fun verifyOnlyTheCurrentTestCalendarIdentityIsAbsent() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyCurrentFlashCleanup") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null), "flash-d1")
        val arguments = org.json.JSONObject(java.io.File(directory, "baseline-results.json").readText()).getJSONObject("calendar_identity")
        val title = arguments.getString("title")
        check(title.startsWith("Floryx 自动验收 D1-")); arguments.remove("completion")
        val fixture = AutonomyRuntimeFixture(context)
        try {
            ActionOutcomeServiceReadiness().ensureService(); fixture.connect()
            val receipt = requireNotNull(fixture.real.inspectCalendarEvents(arguments.toString()))
            java.io.File(directory, "cleanup-receipt.txt").writeText(receipt.toString())
            check(receipt.rows.isEmpty()) { "The exact test event is still present; do not repeat deletion blindly" }
            CalendarSyntheticLedger.deleted(context, title)
        } finally { fixture.close(false) }
    }

    @Test fun persistTheExplicitOfficialFlashChoiceForNewTasks() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("setApprovedOfficialFlashDefault") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvider("provider-deepseek"))
        check(java.net.URI(provider.baseUrl).host == "api.deepseek.com" && provider.defaultModel == "deepseek-flash" && provider.isEnabled)
        val runtime = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        val selected = AssistantModelSelection(provider.id, provider.defaultModel)
        runtime.settings().setAssistantModelSelection(selected)
        check(context.getSharedPreferences("app_controls", android.content.Context.MODE_PRIVATE).edit().commit())
        assertEquals(selected, AppSettingsStore(context).state.value.assistantModelSelection)
    }
}
