package com.flowchat.app.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.view.View
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
import com.flowchat.app.presentation.assistant.AssistantSpeechInput
import com.flowchat.app.presentation.assistant.SystemAssistantAudioGuard
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real iQOO speaker->mic multi-turn voice follow-up + official deepseek-flash + local stop barge-in. */
class XasrIqooW05AcceptanceTest {
    private class ManualBargeInInput : AssistantSpeechInput {
        @Volatile var listener: AssistantSpeechInput.Listener? = null
        override fun start(listener: AssistantSpeechInput.Listener) { this.listener = listener }
        override fun close() { listener = null }
    }

    @Test
    fun assistantPanelFollowUpWindowContinuesSameTaskAndStopsTtsLocally() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runXasrIqooW05Flash") == "true")
        requireIqoo()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val directory = File(externalFiles, "assistant-xasr-iqoo-w05").apply { check(mkdir()) }
        val turn1Dir = File(directory, "turn1").apply { check(mkdir()) }
        val turn2Dir = File(directory, "turn2").apply { check(mkdir()) }
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
        val assistant = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
        val settings = entry.settings()
        val selected = requireNotNull(settings.state.value.assistantModelSelection)
        check(selected.providerId == "provider-deepseek" && selected.modelName == "deepseek-flash")
        val provider = requireNotNull(data.providers().getProvider(selected.providerId))
        val endpoint = URI(provider.baseUrl)
        check(provider.isEnabled && endpoint.scheme == "https" && endpoint.host == "api.deepseek.com" && endpoint.userInfo == null)
        check(!data.providers().getApiKey(provider).isNullOrBlank())
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val harness = SystemAssistantDiagnosticTest()
        val files = StagedXasrFiles(assets, context.cacheDir.resolve("assistant-xasr-iqoo-w05-cache"))
        val prefs = context.getSharedPreferences("assistant_entry", Context.MODE_PRIVATE)
        val controls = context.getSharedPreferences("app_controls", Context.MODE_PRIVATE)
        val usageKey = "usage_tracking_enabled"
        val backgroundKey = "background_execution_enabled"
        val hadUsage = controls.contains(usageKey)
        val hadBackground = controls.contains(backgroundKey)
        val oldUsage = settings.state.value.usageTrackingEnabled
        val oldBackground = settings.state.value.backgroundExecutionEnabled
        val turn1Sample = iqooSpeechFixtures[0] // 请问六乘八等于多少？
        val turn2Sample = iqooSpeechFixtures[1] // 请算一下三加四等于多少？
        val evidence = JSONObject().put("model", selected.modelName)
        fun save() = File(directory, "result.json").writeText(evidence.toString(2))
        try {
            harness.fixture { fixture ->
                check(runtime.activeConversations.value.isEmpty())
                settings.setUsageTrackingEnabled(true)
                settings.setBackgroundExecutionEnabled(false)
                val owner = data.chats().createConversation(provider.id, selected.modelName)
                fun validateTurn(expectedMessageCount: Int, sample: AcousticFixture): (JSONObject) -> Unit = { payload ->
                    val messages = payload.getJSONArray("messages")
                    check(messages.length() == expectedMessageCount) {
                        "Expected $expectedMessageCount messages, got ${messages.length()}"
                    }
                    check(messages.getJSONObject(0).getString("content") == ReplyPolicy.instructions)
                    val lastUser = messages.getJSONObject(messages.length() - 1).getString("content")
                    check(sample.exact(lastUser)) { "Unexpected user transcript: $lastUser" }
                    val toolList = payload.optJSONArray("tools")
                    val tools = if (toolList == null) emptyList() else
                        (0 until toolList.length()).map { toolList.getJSONObject(it).getJSONObject("function").getString("name") }
                    check(tools.all { it in setOf(AttachmentTools.Read, TaskContextTools.Read) })
                    check(payload.getInt("max_tokens") in 1..256)
                }
                val client = data.client() as KtorOpenAiCompatibleClient
                val wire1 = OfficialAssistantRequestWire(client, turn1Dir, "W05-iqoo-turn1", 16_384, validateTurn(2, turn1Sample))
                val wire2 = OfficialAssistantRequestWire(client, turn2Dir, "W05-iqoo-turn2", 16_384, validateTurn(4, turn2Sample))
                    .apply { active = false }
                try {
                    data.chats().updateConversationWorkspace(owner.id, null)
                    data.chats().updateConversationSettings(owner.id, "", null, false, false, "", 0.7, 1.0, 256, ThinkingEffort.Auto)
                    check(prefs.edit().putString("conversation_id", owner.id).commit())
                    val panel = harness.open(AssistantPanelActivity::class.java)
                    ensureTestMicrophonePermission(panel, harness)
                    val capture1 = IqooAcousticCapture(assets, files, turn1Sample::exact)
                    val capture2 = IqooAcousticCapture(assets, files, turn2Sample::exact)
                    var captureIndex = 0
                    val bargeIn = ManualBargeInInput()
                    val presenter = withContext(Dispatchers.Main) { assistant.panels().create() }
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    try {
                        val view = withContext(Dispatchers.Main) {
                            AssistantPanelView(
                                context = panel,
                                presenter = presenter,
                                onClose = {},
                                onFullChat = {},
                                onAccepted = { error("Voice used manual send") },
                                requestVoicePermission = { granted, _ -> granted() },
                                voiceAvailable = { true },
                                speechInput = { if (++captureIndex == 1) capture1 else capture2 },
                                audioGuard = SystemAssistantAudioGuard(context),
                                followUpWindowMs = 15_000L,
                                bargeInInput = { bargeIn },
                                hasVoicePermission = { true }
                            ).also {
                                panel.setContentView(it)
                                scope.launch { presenter.state.collect(it::render) }
                            }
                        }
                        val voice = view.findViewById<Button>(R.id.assistant_voice)
                        val status = view.findViewById<TextView>(R.id.assistant_status)
                        harness.await("w05-flash-panel-ready") { withContext(Dispatchers.Main) { voice.isEnabled } }

                        // Turn 1: built-in speaker -> built-in mic -> official Flash -> vivo TTS plays & finishes.
                        val turn1Playback = scope.async {
                            presenter.state.first { it.voicePlayback == VoicePlaybackState.Speaking }
                            val started = SystemClock.elapsedRealtime()
                            val state = presenter.state.first { it.voicePlayback != VoicePlaybackState.Speaking }
                            evidence.put("turn1TtsDurationMs", SystemClock.elapsedRealtime() - started)
                            state.voicePlayback
                        }
                        val turn1ClickAt = SystemClock.elapsedRealtime()
                        withContext(Dispatchers.Main) { check(voice.performClick()) }
                        val rec1 = capture1.replay(context, turn1Sample.asset, turn1ClickAt)
                        check(rec1.transcript?.let(turn1Sample::exact) == true)
                        check(rec1.inputDevice == AudioDeviceInfo.TYPE_BUILTIN_MIC && !rec1.cutOffPlayback)
                        harness.await("w05-turn1-request") { wire1.requestCount == 1 }
                        harness.await("w05-turn1-complete") {
                            data.chats().getMessages(owner.id).count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } == 1 &&
                                owner.id !in runtime.activeConversations.value
                        }
                        wire1.active = false
                        wire2.active = true
                        check(withTimeout(15_000) { turn1Playback.await() } == VoicePlaybackState.Idle)

                        // Follow-up window opens automatically without tapping Voice.
                        harness.await("w05-followup-listening") {
                            withContext(Dispatchers.Main) {
                                captureIndex == 2 &&
                                    status.visibility == View.VISIBLE &&
                                    status.text.toString() == panel.getString(R.string.assistant_voice_followup_listening)
                            }
                        }
                        val rec2 = capture2.replay(context, turn2Sample.asset, SystemClock.elapsedRealtime())
                        check(rec2.transcript?.let(turn2Sample::exact) == true)
                        check(rec2.inputDevice == AudioDeviceInfo.TYPE_BUILTIN_MIC && !rec2.cutOffPlayback)
                        harness.await("w05-turn2-request") { wire2.requestCount == 1 }
                        harness.await("w05-turn2-complete") {
                            data.chats().getMessages(owner.id).count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } == 2 &&
                                owner.id !in runtime.activeConversations.value
                        }
                        // Turn 2 vivo TTS starts speaking -> local "停止" interrupts playback with 0 extra requests.
                        harness.await("w05-turn2-speaking") {
                            presenter.state.value.voicePlayback == VoicePlaybackState.Speaking && bargeIn.listener != null
                        }
                        withContext(Dispatchers.Main) {
                            bargeIn.listener!!.partial("停止")
                        }
                        harness.await("w05-turn2-stopped-locally") {
                            presenter.state.value.voicePlayback == VoicePlaybackState.Idle
                        }
                        check(wire1.requestCount == 1 && wire2.requestCount == 1 && fixture.model.requests.get() == 0)
                        val msgs = data.chats().getMessages(owner.id)
                        check(msgs.count { it.role == MessageRole.User } == 2)
                        check(msgs.count { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete } == 2)
                        evidence.put("passed", true)
                            .put("officialRequests", wire1.requestCount + wire2.requestCount)
                            .put("userMessagesSameConversation", 2)
                            .put("turn1Transcript", rec1.transcript)
                            .put("turn2Transcript", rec2.transcript)
                            .put("turn1Answer", msgs.filter { it.role == MessageRole.Assistant }[0].content)
                            .put("turn2Answer", msgs.filter { it.role == MessageRole.Assistant }[1].content)
                            .put("localStopRequests", 0)
                            .put("manualSendActions", 0)
                        save()
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            capture1.close()
                            capture2.close()
                            presenter.stopVoicePlayback()
                            presenter.close()
                            scope.cancel()
                            panel.finish()
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.Main) { runtime.stopFromAssistant(owner.id) }
                        harness.await("w05-flash-idle") { owner.id !in runtime.activeConversations.value }
                        val usageRecords = data.usage().observe(owner.id).first()
                        val usageArr = JSONArray()
                        usageRecords.forEach { r ->
                            usageArr.put(
                                JSONObject()
                                    .put("model", r.model)
                                    .put("inputTokens", r.inputTokens ?: JSONObject.NULL)
                                    .put("outputTokens", r.outputTokens ?: JSONObject.NULL)
                                    .put("cacheHitTokens", r.cacheHitTokens ?: JSONObject.NULL)
                            )
                        }
                        evidence.put("usageRecords", usageArr)
                        data.chats().deleteConversation(owner.id)
                        evidence.put("fixtureRemoved", data.chats().getConversation(owner.id) == null)
                        wire1.active = false
                        wire2.active = false
                        save()
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
