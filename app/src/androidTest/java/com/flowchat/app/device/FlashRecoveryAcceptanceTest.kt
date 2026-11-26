package com.flowchat.app.device

import android.app.UiAutomation
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.data.usage.UsageTracker
import com.flowchat.app.di.AppModule
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.assistant.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Same authorized read-only tasks and official provider as D1. No scripted model decisions. */
class FlashRecoveryAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun sixReadOnlyGoalsFromVerifiedStartsUseOfficialFlash() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runApprovedFlashRecovery") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        val label = arguments.getString("flashRecoveryRun", "candidate1")!!
        check(label.matches(Regex("[a-z0-9-]{1,64}")))
        val ledgerDir = arguments.getString("flashRecoveryLedgerDir")?.takeIf { it.isNotBlank() } ?: "flash-d2"
        val cleanDb = arguments.getString("flashRecoveryCleanDb") == "true"
        val root = File(context.getExternalFilesDir(null), ledgerDir).apply { mkdirs() }
        val directory = File(root, label).apply { check(!exists()); mkdirs() }
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val provider = requireNotNull(data.providers().getProvider("provider-deepseek"))
        check(java.net.URI(provider.baseUrl).host == "api.deepseek.com" && provider.defaultModel == "deepseek-flash" && provider.isEnabled)
        val key = requireNotNull(data.providers().getApiKey(provider))
        val f = AutonomyRuntimeFixture(context, provider, key)
        val http = AppModule.provideHttpClient(AppModule.provideJson())
        val client = KtorOpenAiCompatibleClient(http, AppModule.provideJson()).apply { usageTracker = UsageTracker(f.database, f.settings) }
        // Absolute cumulative caps, supplied only after a separate allowance decision. Never clear the old ledger.
        val requestCap = arguments.getString("flashRecoveryRequestCap")?.toInt() ?: 120
        val inputCap = arguments.getString("flashRecoveryInputCap")?.toLong() ?: 3_000_000L
        val outputCap = arguments.getString("flashRecoveryOutputCap")?.toLong() ?: 128_000L
        check(requestCap in 1..240 && inputCap in 1..6_000_000L && outputCap in 1..256_000L)
        val meter = FlashBaselineDiagnosticTest.Meter(f, client, key, root,
            maxInput = inputCap, maxOutput = outputCap, maxRequests = requestCap)
        f.externalClient = meter; f.settings.setUsageTrackingEnabled(true)
        val results = JSONObject().put("run", label).put("provider_id", provider.id).put("host", "api.deepseek.com")
            .put("model", provider.defaultModel).put("thinking", "enabled/auto").put("isolated_database", f.name).put("cases", JSONArray())
            .put("cumulative_caps", JSONObject().put("requests", requestCap).put("input", inputCap).put("output", outputCap))
        fun save() = File(directory, "results.json").writeText(results.toString(2))
        val targetUrl = "https://developer.android.google.cn/guide/topics/ui/accessibility/principles?hl=zh-cn"
        try {
            ActionOutcomeServiceReadiness().ensureService(); f.connect(); f.create()
            val selected = arguments.getString("flashRecoveryCases")?.split(',')
            for (task in listOf("F01", "F03")) for (variant in listOf("normal", "unrelated", "offscreen")) {
                val id = "$task-$variant"
                if (selected != null && id !in selected) continue
                val row = JSONObject().put("case_id", id).put("mode", "phone-real-model").put("manual_recovery_steps", 0)
                results.getJSONArray("cases").put(row); save()
                try {
                    val stageCommand = if (task == "F01") {
                        val action = if (variant == "unrelated") "android.settings.DISPLAY_SETTINGS" else "android.settings.SETTINGS"
                        "am start -W -a $action"
                    } else {
                        val page = when (variant) { "unrelated" -> "https://developer.android.google.cn/about"; "offscreen" -> targetUrl; else -> "https://developer.android.google.cn/" }
                        "am start -W -a android.intent.action.VIEW -d $page -p com.android.chrome --ez create_new_tab true"
                    }
                    val staging = shell(stageCommand)
                    row.put("staging_response", staging); save()
                    check(staging.contains("Status: ok") && !staging.contains("Error:")) { "Starting state could not be opened: $staging" }
                    val expected = if (task == "F01") "com.android.settings" else "com.android.chrome"
                    f.await {
                        val screen = f.real.observeScreen().screen
                        screen?.packageName == expected && (task != "F03" ||
                            screen.elements.any { it.className.contains("WebView") } &&
                            screen.elements.any { it.displayText.contains("Android Developers") ||
                                it.resourceId == "com.android.chrome:id/url_bar" && it.displayText.startsWith("developer.android.google.cn") })
                    }
                    if (variant == "offscreen") {
                        var progressed = 0
                        for (step in 0..2) {
                            val screen = requireNotNull(f.real.observeScreen().screen)
                            val scrollable = screen.elements.filter { it.scrollable }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
                                ?: break
                            val result = f.real.scrollContainer(scrollable.index, DeviceScrollDirection.Forward, screen.screenId)
                            if (result.actionOutcome != DeviceActionOutcome.ProgressObserved) break
                            progressed++
                        }
                        row.put("setup_progress_scrolls", progressed)
                        check(progressed > 0) { "Offscreen starting state was not established; no model task was started" }
                    }
                    val initial = f.real.observeScreen()
                    check(initial.success && initial.screen?.packageName == expected)
                    if (task == "F01" && variant == "offscreen") check(initial.screen!!.elements.none { it.displayText == "声音与振动" }) {
                        "Sound target remains on the initial page; offscreen variant was not established"
                    }
                    File(directory, "$id-initial-screen.txt").writeText(initial.asToolContent())
                    row.put("verified_start_package", expected)
                    val prompt = if (task == "F01") "帮我打开设置里的声音与振动，查看当前媒体音量，不要修改。"
                        else "用手机浏览器打开 $targetUrl ，读完正文，用中文总结三点，并告诉我原文标题。"
                    row.put("prompt", prompt)
                    val audio = context.getSystemService(AudioManager::class.java)
                    val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    f.owner = f.chats.createConversation(provider.id, provider.defaultModel).id
                    withContext(Dispatchers.Main) { f.runtime.selectConversation(f.owner) }
                    f.await { f.runtime.uiState.value.currentConversation?.id == f.owner }
                    meter.sample = "$label/$id"; meter.firstToolAt = null
                    val started = SystemClock.elapsedRealtime(); val modelStarted = meter.modelMillis
                    check(withContext(Dispatchers.Main) { f.runtime.sendFromAssistant(f.owner, prompt,
                        destination = AssistantTaskDestination(f.owner, AssistantModelSelection(provider.id, provider.defaultModel), provider)) })
                    // The production task already has a durable 900s total cap across <=4 stages.
                    // The harness must not inject a separate 300s user stop during valid continuations.
                    withTimeout(930_000) {
                        while (f.owner in f.runtime.activeConversations.value) {
                            if (f.confirmations.isNotEmpty()) break
                            delay(100)
                        }
                    }
                    row.put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("model_ms", meter.modelMillis - modelStarted)
                        .put("first_tool_request_ms", meter.firstToolAt?.minus(started)).put("app_confirmations", f.confirmations.size)
                    if (f.owner in f.runtime.activeConversations.value) withContext(Dispatchers.Main) { f.runtime.stopFromAssistant(f.owner) }
                    val messages = f.chats.getMessages(f.owner)
                    File(directory, "$id-messages.json").writeText(JSONArray(messages.map { JSONObject().put("role", it.role.name)
                        .put("content", it.content).put("status", it.status.name).put("execution", it.executionContext) }).toString(2))
                    val run = f.journal.latest(f.owner)
                    row.put("runtime_state", run?.state).put("metadata", run?.metadataJson)
                        .put("answer", messages.lastOrNull { it.role == MessageRole.Assistant }?.content)
                        .put("volume_before", volume).put("volume_after", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    val final = f.real.observeScreen(); row.put("final_package", final.screen?.packageName)
                    File(directory, "$id-final-screen.txt").writeText(final.asToolContent())
                    row.put("result", "recorded_requires_independent_review")
                } catch (error: Exception) {
                    row.put("result", "failed").put("first_failure", error.message).put("category", error.javaClass.simpleName)
                    withContext(Dispatchers.Main) { f.runtime.stopFromAssistant(f.owner) }
                    withTimeoutOrNull(10_000) { while (f.owner in f.runtime.activeConversations.value) delay(50) }
                }
                f.confirmations.clear(); save()
            }
        } finally { save(); f.close(cleanDb); http.close() }
    }
}
