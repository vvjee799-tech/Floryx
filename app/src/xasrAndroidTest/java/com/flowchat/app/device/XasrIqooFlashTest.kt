package com.flowchat.app.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.R
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.SystemAssistantDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.chat.AttachmentTools
import com.flowchat.app.domain.chat.ReplyPolicy
import com.flowchat.app.domain.chat.TaskContextTools
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ThinkingEffort
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.AssistantPanelView
import com.flowchat.app.presentation.assistant.VoicePlaybackState
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Two text-only arithmetic trials. Only full matching microphone transcripts can reach Flash. */
class XasrIqooFlashTest {
    @Test
    fun assistantPanelSendsRecognizedSpeechOnceAndPlaysTheCompletedAnswer() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runXasrIqooFlash") == "true")
        requireIqoo()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val preflight = JSONObject(File(externalFiles, "assistant-xasr-iqoo-mic/result.json").readText())
        val acousticRows = preflight.getJSONArray("samples")
        val retryAdd = args.getString("w01FlashMode", "both").let { mode ->
            check(mode in setOf("both", "retry-unsubmitted-add"))
            mode == "retry-unsubmitted-add"
        }
        val samples = if (retryAdd) listOf(iqooSpeechFixtures[1] to Regex("七|7"))
            else iqooSpeechFixtures.take(2).zip(listOf(Regex("四十八|48"), Regex("七|7")))
        check(samples.all { (sample, _) ->
            (0 until acousticRows.length()).map(acousticRows::getJSONObject).any {
                it.getString("id") == sample.id && it.optBoolean("acousticRoutesVerified") && it.optBoolean("exact") &&
                    !it.optBoolean("prematureEndpoint")
            }
        }) { "The same iQOO candidate must pass both arithmetic microphone preflights" }
        val directory = File(externalFiles, "assistant-xasr-iqoo-flash")
        if (retryAdd) {
            val prior = JSONObject(directory.resolve("multiply/flash-reservation.json").readText())
            val skipped = JSONObject(directory.resolve("add/result.json").readText())
            check(prior.getInt("requests") == 1 && prior.getString("sample") == "W01-multiply")
            check(skipped.getInt("officialRequests") == 0 && skipped.getBoolean("fixtureRemoved"))
            check(!directory.resolve("add/flash-reservation.json").exists())
        } else check(directory.mkdir()) { "Use a fresh Flash evidence directory" }
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        val assistant = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
        val settings = entry.settings()
        val selected = requireNotNull(settings.state.value.assistantModelSelection)
        check(selected.providerId == "provider-deepseek" && selected.modelName == "deepseek-flash")
        val provider = requireNotNull(data.providers().getProvider(selected.providerId))
        val endpoint = URI(provider.baseUrl)
        check(provider.isEnabled && endpoint.scheme == "https" && endpoint.host == "api.deepseek.com" && endpoint.userInfo == null)
        check(!data.providers().getApiKey(provider).isNullOrBlank()) { "No configured key for the selected official provider" }
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val harness = SystemAssistantDiagnosticTest()
        val files = StagedXasrFiles(assets, context.cacheDir.resolve("assistant-xasr-iqoo-cache"))
        val prefs = context.getSharedPreferences("assistant_entry", Context.MODE_PRIVATE)
        val controls = context.getSharedPreferences("app_controls", Context.MODE_PRIVATE)
        val usageKey = "usage_tracking_enabled"
        val backgroundKey = "background_execution_enabled"
        val hadUsage = controls.contains(usageKey)
        val hadBackground = controls.contains(backgroundKey)
        val oldUsage = settings.state.value.usageTrackingEnabled
        val oldBackground = settings.state.value.backgroundExecutionEnabled
        try {
            harness.fixture { fixture ->
                check(runtime.activeConversations.value.isEmpty())
                settings.setUsageTrackingEnabled(true)
                settings.setBackgroundExecutionEnabled(false)
                for ((sample, expectedAnswer) in samples) {
                    val caseName = if (retryAdd) "add-retry" else sample.id
                    val caseDir = File(directory, caseName).apply { check(mkdir()) }
                    val evidence = JSONObject().put("result", "started").put("model", selected.modelName)
                    fun save() = File(caseDir, "result.json").writeText(evidence.toString(2))
                    val owner = data.chats().createConversation(provider.id, selected.modelName)
                    var requestAt = 0L
                    val wire = OfficialAssistantRequestWire(data.client() as KtorOpenAiCompatibleClient, caseDir,
                        "W01-${sample.id}", 16_384) { payload ->
                        val messages = payload.getJSONArray("messages")
                        check(messages.length() == 2) { "W01 requires exactly the app policy and synthetic user message" }
                        val system = messages.getJSONObject(0)
                        check(system.getString("role") == "system" && system.getString("content") == ReplyPolicy.instructions) {
                            "W01 system context does not match the built-in reply policy"
                        }
                        val user = messages.getJSONObject(1)
                        check(user.getString("role") == "user" && sample.exact(user.get("content") as String)) {
                            "W01 user content does not match the fixed calculation"
                        }
                        val toolList = payload.optJSONArray("tools")
                        val tools = if (toolList == null) emptyList() else
                            (0 until toolList.length()).map { toolList.getJSONObject(it).getJSONObject("function").getString("name") }
                        check(tools.all { it in setOf(AttachmentTools.Read, TaskContextTools.Read) }) {
                            "W01 request exposed tools outside the isolated conversation"
                        }
                        check(payload.getInt("max_tokens") in 1..256) { "W01 output reservation exceeds its cap" }
                        requestAt = SystemClock.elapsedRealtime()
                    }
                    try {
                        data.chats().updateConversationWorkspace(owner.id, null)
                        data.chats().updateConversationSettings(owner.id, "", null, false, false, "", 0.7, 1.0, 256, ThinkingEffort.Auto)
                        check(prefs.edit().putString("conversation_id", owner.id).commit())
                        val panel = harness.open(AssistantPanelActivity::class.java)
                        ensureTestMicrophonePermission(panel, harness)
                        val capture = IqooAcousticCapture(assets, files, sample::exact)
                        val presenter = withContext(Dispatchers.Main) { assistant.panels().create() }
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                        try {
                            val view = withContext(Dispatchers.Main) {
                                AssistantPanelView(panel, presenter, {}, {}, { error("Voice used manual send") },
                                    requestVoicePermission = { granted, _ -> granted() },
                                    voiceAvailable = { true }, speechInput = { capture }).also {
                                    panel.setContentView(it)
                                    scope.launch { presenter.state.collect(it::render) }
                                }
                            }
                            harness.await("flash-voice-panel-${sample.id}") {
                                withContext(Dispatchers.Main) {
                                    presenter.state.value.conversationId == owner.id &&
                                        view.findViewById<Button>(R.id.assistant_voice).isEnabled
                                }
                            }
                            val playback = scope.async {
                                presenter.state.first { it.voicePlayback == VoicePlaybackState.Speaking }
                                val started = SystemClock.elapsedRealtime()
                                val audio = context.getSystemService(AudioManager::class.java)
                                val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                                    .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                                val state = presenter.state.first { it.voicePlayback != VoicePlaybackState.Speaking }
                                evidence.put("ttsDurationMs", SystemClock.elapsedRealtime() - started)
                                    .put("speakerAvailable", output)
                                state.voicePlayback
                            }
                            val clicked = SystemClock.elapsedRealtime()
                            withContext(Dispatchers.Main) { check(view.findViewById<Button>(R.id.assistant_voice).performClick()) }
                            val recorded = capture.replay(context, sample.asset, clicked)
                            recorded.writeTo(evidence, keepTranscript = recorded.transcript?.let(sample::exact) == true)
                            save()
                            check(recorded.transcript?.let(sample::exact) == true)
                            check(recorded.inputDevice == AudioDeviceInfo.TYPE_BUILTIN_MIC && !recorded.cutOffPlayback)
                            harness.await("flash-request-${sample.id}") {
                                val state = entry.journal().latest(owner.id)?.state
                                check(state !in setOf("Failed", "Stopped", "Paused")) { "W01 request ended as $state" }
                                wire.requestCount == 1
                            }
                            evidence.put("audioEndToRequestMs", requestAt - recorded.clipEndedAt)
                            harness.await("flash-completed-${sample.id}") {
                                val state = entry.journal().latest(owner.id)?.state
                                check(state !in setOf("Failed", "Stopped", "Paused")) { "W01 official arithmetic ended as $state" }
                                state == "Completed" && owner.id !in runtime.activeConversations.value
                            }
                            val messages = data.chats().getMessages(owner.id)
                            val user = messages.single { it.role == MessageRole.User }
                            val answer = messages.single { it.role == MessageRole.Assistant }
                            check(user.content == recorded.transcript && user.attachmentText == null && user.attachmentImagePath == null)
                            check(answer.status == MessageStatus.Complete && expectedAnswer.containsMatchIn(answer.content))
                            harness.await("flash-answer-visible-${sample.id}") {
                                withContext(Dispatchers.Main) { view.findViewById<TextView>(R.id.assistant_output).text.toString() == answer.content }
                            }
                            evidence.put("audioEndToVisibleMs", SystemClock.elapsedRealtime() - recorded.clipEndedAt)
                                .put("answer", answer.content).put("manualSendActions", 0).put("userMessages", 1)
                            val tts = withTimeout(15_000) { playback.await() }
                            evidence.put("ttsState", tts.name)
                            check(tts == VoicePlaybackState.Idle)
                            check(wire.requestCount == 1 && fixture.model.requests.get() == 0)
                            evidence.put("result", "passed")
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main) {
                                capture.close()
                                presenter.stopVoicePlayback()
                                presenter.close()
                                scope.cancel()
                                panel.finish()
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                withContext(Dispatchers.Main) { runtime.stopFromAssistant(owner.id) }
                                harness.await("flash-fixture-idle-${sample.id}") { owner.id !in runtime.activeConversations.value }
                                wire.reportUsage(data.usage().observe(owner.id).first())
                                check(data.chats().getMessages(owner.id).filter { it.role == MessageRole.User }.all { sample.exact(it.content) })
                                data.chats().deleteConversation(owner.id)
                                evidence.put("fixtureRemoved", data.chats().getConversation(owner.id) == null)
                            } finally {
                                wire.active = false
                                evidence.put("officialRequests", wire.requestCount)
                                save()
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                settings.setUsageTrackingEnabled(oldUsage)
                settings.setBackgroundExecutionEnabled(oldBackground)
                check(controls.edit().apply {
                    if (!hadUsage) remove(usageKey)
                    if (!hadBackground) remove(backgroundKey)
                }.commit())
                withContext(Dispatchers.IO) { files.remove() }
            }
        }
    }
}
