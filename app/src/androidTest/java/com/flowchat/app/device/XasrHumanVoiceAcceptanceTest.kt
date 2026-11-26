package com.flowchat.app.device

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
import com.flowchat.app.presentation.assistant.AssistantPanelPresenter
import com.flowchat.app.presentation.assistant.AssistantPanelView
import com.flowchat.app.presentation.assistant.AssistantVoicePlayback
import com.flowchat.app.presentation.assistant.VoicePlaybackResult
import com.flowchat.app.presentation.assistant.VoicePlaybackSpeaker
import com.flowchat.app.presentation.assistant.XasrVoicePack
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The operator reads the displayed synthetic prompts into the iQOO microphone. */
class XasrHumanVoiceAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val harness = SystemAssistantDiagnosticTest()
    private val assistant by lazy { EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java) }
    private val data by lazy { EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java) }
    private val entry by lazy { EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java) }
    private val audio by lazy { checkNotNull(context.getSystemService(AudioManager::class.java)) }

    @Test
    fun tenHumanSamplesThenTwoOfficialArithmeticTurnsIfQualified() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runXasrHumanVoice") == "true")
        check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val root = context.noBackupFilesDir.resolve("assistant-voice")
        check(!root.exists())
        val pack = checkNotNull(XasrVoicePack.forDevice(context))
        val out = File(checkNotNull(context.getExternalFilesDir(null)), "xasr-human-voice")
        check(out.mkdir())
        val settings = entry.settings()
        val official = settings.state.value.assistantModelSelection
        val officialRetry = args.getString("retryOfficialHumanVoice") == "true"
        val retainedRows = args.getString("retainedHumanVoiceRows")?.let { encoded ->
            val stored = JSONArray(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
            val retainedCases = if (officialRetry) samples else samples.drop(3)
            check(stored.length() == retainedCases.size)
            retainedCases.mapIndexed { index, sample ->
                stored.getJSONObject(index).also { check(it.getString("id") == sample.id) }
            }
        }
        check(!officialRetry || retainedRows != null)
        val requested = when {
            officialRetry -> emptyList()
            retainedRows == null -> samples
            else -> samples.take(3)
        }
        val localRows = JSONArray()
        val result = JSONObject().put("samples", localRows).put("userReady", false)
            .put("newSamplesRequested", requested.size)
        fun save() = out.resolve("result.json").writeText(result.toString(2))
        save()
        try {
            harness.fixture { fixture ->
                fixture.model.release()
                val silent = AssistantVoicePlayback(VoicePlaybackSpeaker { VoicePlaybackResult.Completed })
                withPanel(fixture.owner, pack, silent) { panel ->
                    panel.guide("正在准备语音包。准备好之前请保持安静。")
                    panel.download(pack)
                    if (officialRetry) return@withPanel
                    panel.guide("真人语音测试已准备好。\n请在电脑端回复「准备好了」。")
                    if (!awaitOperator(out)) {
                        result.put("status", "waiting_for_operator_timed_out")
                        save()
                        return@withPanel
                    }
                    result.put("userReady", true)
                    save()
                    if (retainedRows != null) {
                        panel.guide("即将补录 3 条。请先看手机，等绿色提示后再发音。")
                        delay(4_000)
                    }
                    for ((index, sample) in requested.withIndex()) {
                        val requestsBefore = fixture.model.requests.get()
                        val initialUsers = userMessages(fixture.owner).map { it.id }.toSet()
                        val progress = if (retainedRows == null) "第 ${index + 1} / ${requested.size} 条"
                            else "补录 ${index + 1} / ${requested.size}"
                        val turn = panel.read(progress, sample.text, fixture.owner, initialUsers)
                        if (turn.transcript != null) {
                            harness.await("local-human-turn-${index + 1}-idle") {
                                val last = entry.journal().latest(fixture.owner)
                                last?.state == "Completed" && panel.presenter.state.value.let { !it.running && !it.submitting }
                            }
                        }
                        val sent = fixture.model.requests.get() - requestsBefore
                        localRows.put(turn.toJson(sample)
                            .put("localRequests", sent)
                            .put("manualSends", 0)
                            .put("passed", turn.transcript?.let(sample::matches) == true && sent == 1))
                        result.put("passed", (0 until localRows.length()).count { localRows.getJSONObject(it).getBoolean("passed") })
                        save()
                        signal("SAMPLE ${index + 1}/${requested.size}")
                    }
                    retainedRows?.forEach { localRows.put(it.put("retainedFromFirstSession", true)) }
                    result.put("passed", (0 until localRows.length()).count { localRows.getJSONObject(it).getBoolean("passed") })
                    save()
                    panel.guide("本轮朗读已结束。正在核对结果，请稍等。")
                }
                if (officialRetry) {
                    checkNotNull(retainedRows).forEach { localRows.put(it) }
                    result.put("passed", (0 until localRows.length()).count { localRows.getJSONObject(it).getBoolean("passed") })
                } else if (!result.getBoolean("userReady")) return@fixture
                val correct = result.getInt("passed")
                val qualified = correct >= 9 && (0..1).all { index -> localRows.getJSONObject(index).getBoolean("passed") }
                result.put("qualificationMet", qualified)
                if (qualified && args.getString("runOfficialHumanVoice") == "true") {
                    check(official?.providerId == "provider-deepseek" && official.modelName == "deepseek-flash")
                    val provider = checkNotNull(data.providers().getProvider(official.providerId))
                    val uri = URI(provider.baseUrl)
                    check(provider.isEnabled && uri.scheme == "https" && uri.host == "api.deepseek.com" && uri.userInfo == null)
                    check(!data.providers().getApiKey(provider).isNullOrBlank())
                    result.put("status", "official_pending")
                    save()
                    val completed = runOfficial(out, pack, provider.id, official.modelName, officialRetry) {
                        result.put("userReady", true)
                        save()
                    }
                    result.put("status", if (completed) "official_complete" else "waiting_for_operator_timed_out")
                } else {
                    result.put("status", if (qualified) "local_qualified" else "local_below_threshold")
                }
                save()
            }
        } finally {
            check(root.deleteRecursively())
            save()
        }
    }

    private suspend fun runOfficial(
        out: File,
        pack: XasrVoicePack,
        providerId: String,
        modelName: String,
        waitForOperator: Boolean,
        onOperatorReady: () -> Unit
    ): Boolean {
        val settings = entry.settings()
        val controls = context.getSharedPreferences("app_controls", android.content.Context.MODE_PRIVATE)
        val usageWasEnabled = settings.state.value.usageTrackingEnabled
        val hadUsageKey = controls.contains("usage_tracking_enabled")
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        try {
            settings.setUsageTrackingEnabled(true)
            for ((index, sample) in samples.take(2).withIndex()) {
                val report = JSONObject().put("sample", sample.id).put("passed", false)
                val dir = out.resolve(sample.id).apply { check(mkdir()) }
                val owner = data.chats().createConversation(providerId, modelName)
                var wire: OfficialAssistantRequestWire? = null
                try {
                    data.chats().updateConversationWorkspace(owner.id, null)
                    data.chats().updateConversationSettings(owner.id, "", null, false, false, "", 0.7, 1.0, 256, ThinkingEffort.Auto)
                    var requestAt = 0L
                    wire = OfficialAssistantRequestWire(data.client() as KtorOpenAiCompatibleClient, dir,
                        "W01-human-${sample.id}", 16_384) { payload ->
                        val messages = payload.getJSONArray("messages")
                        check(messages.length() == 2)
                        val system = messages.getJSONObject(0)
                        check(system.getString("role") == "system" && system.getString("content") == ReplyPolicy.instructions)
                        val user = messages.getJSONObject(1)
                        check(user.getString("role") == "user" && sample.matches(user.getString("content")))
                        val tools = payload.optJSONArray("tools")
                        if (tools != null) for (tool in 0 until tools.length()) {
                            check(tools.getJSONObject(tool).getJSONObject("function").getString("name") in
                                setOf(AttachmentTools.Read, TaskContextTools.Read))
                        }
                        check(payload.getInt("max_tokens") in 1..256)
                        requestAt = SystemClock.elapsedRealtime()
                    }
                    val realWire = wire
                    var started = true
                    withPanel(owner.id, pack) { panel ->
                        if (waitForOperator && index == 0) {
                            panel.guide("正式测试已准备好。\n请在电脑端回复「准备好了」，绿灯亮后再读。")
                            started = awaitOperator(out)
                            if (!started) return@withPanel
                            onOperatorReady()
                            panel.guide("正式测试即将开始。请看手机顶部，等绿色提示。")
                            delay(4_000)
                        }
                        val generation = panel.presenter.state.value.followUpReadyGeneration
                        val turn = panel.read("正式测试 ${index + 1} / 2", sample.text, owner.id, emptySet())
                        report.put("voice", turn.toJson(sample)).put("manualSends", 0)
                        check(turn.transcript?.let(sample::matches) == true)
                        harness.await("official-human-${sample.id}-complete") {
                            val state = entry.journal().latest(owner.id)?.state
                            check(state !in setOf("Failed", "Paused", "Stopped"))
                            state == "Completed"
                        }
                        val messages = data.chats().getMessages(owner.id)
                        val user = messages.single { it.role == MessageRole.User }
                        val answer = messages.single { it.role == MessageRole.Assistant }
                        check(user.attachmentText == null && user.attachmentImagePath == null)
                        check(answer.status == MessageStatus.Complete && checkNotNull(sample.answer).containsMatchIn(answer.content))
                        harness.await("official-human-${sample.id}-shown") {
                            withContext(Dispatchers.Main) { panel.output.text.toString() == answer.content }
                        }
                        report.put("answer", answer.content)
                            .put("clickToOfficialRequestMs", requestAt - turn.clicked)
                            .put("clickToVisibleAnswerMs", SystemClock.elapsedRealtime() - turn.clicked)
                        panel.guide("正在播报第 ${index + 1} 条结果。")
                        harness.await("official-human-${sample.id}-tts-completed") {
                            panel.presenter.state.value.followUpReadyGeneration > generation
                        }
                        check(realWire.requestCount == 1)
                        report.put("passed", true).put("ttsCompleted", true)
                    }
                    if (!started) return false
                } finally {
                    withContext(NonCancellable) {
                        try {
                            withContext(Dispatchers.Main) { runtime.stopFromAssistant(owner.id) }
                            harness.await("official-human-${sample.id}-idle") { owner.id !in runtime.activeConversations.value }
                            wire?.reportUsage(data.usage().observe(owner.id).first())
                            data.chats().deleteConversation(owner.id)
                            report.put("fixtureRemoved", data.chats().getConversation(owner.id) == null)
                        } finally {
                            wire?.active = false
                            report.put("officialRequests", wire?.requestCount ?: 0)
                            dir.resolve("result.json").writeText(report.toString(2))
                        }
                    }
                }
            }
        } finally {
            settings.setUsageTrackingEnabled(usageWasEnabled)
            if (!hadUsageKey) check(controls.edit().remove("usage_tracking_enabled").commit())
        }
        return true
    }

    private suspend fun awaitOperator(out: File): Boolean {
        signal("READY")
        val start = out.resolve("start.flag")
        if (withTimeoutOrNull(240_000) { while (!start.isFile) delay(150); true } != true) return false
        check(start.delete())
        return true
    }

    private suspend fun <T> withPanel(
        owner: String,
        pack: XasrVoicePack,
        silent: AssistantVoicePlayback? = null,
        block: suspend (Panel) -> T
    ): T {
        val prefs = context.getSharedPreferences("assistant_entry", android.content.Context.MODE_PRIVATE)
        check(prefs.edit().putString("conversation_id", owner).commit())
        val activity = harness.open(AssistantPanelActivity::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val panel = withContext(Dispatchers.Main) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val presenter = if (silent == null) assistant.panels().create() else assistant.panels().create(silent)
            val view = AssistantPanelView(activity, presenter, {}, {}, { error("Unexpected manual send") },
                requestVoicePermission = { granted, _ -> granted() }, offlineVoicePack = pack)
            val banner = TextView(activity).apply {
                val density = resources.displayMetrics.density
                val pad = (16 * density).toInt()
                textSize = 22f
                setPadding(pad, pad, pad, pad)
                val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                lp.topMargin = (44 * density).toInt()
                lp.marginStart = pad
                lp.marginEnd = pad
                view.addView(this, lp)
            }
            activity.setContentView(view)
            scope.launch { presenter.state.collect(view::render) }
            Panel(activity, presenter, view, banner)
        }
        try {
            harness.await("human-panel-owner-ready") {
                panel.presenter.state.value.conversationId == owner &&
                    withContext(Dispatchers.Main) { panel.voice.isEnabled }
            }
            return block(panel)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                panel.view.cancelVoiceDownload()
                panel.view.cancelRecording()
                panel.presenter.stopVoicePlayback()
                panel.presenter.close()
                scope.cancel()
                activity.finish()
            }
        }
    }

    private inner class Panel(
        val activity: AssistantPanelActivity,
        val presenter: AssistantPanelPresenter,
        val view: AssistantPanelView,
        val banner: TextView
    ) {
        val voice: Button = view.findViewById(R.id.assistant_voice)
        val output: TextView = view.findViewById(R.id.assistant_output)
        private val status: TextView = view.findViewById(R.id.assistant_status)
        private val error: TextView = view.findViewById(R.id.assistant_error)
        private val download: Button = view.findViewById(R.id.assistant_voice_download)

        suspend fun guide(text: String, listening: Boolean = false) = withContext(Dispatchers.Main) {
            banner.text = text
            banner.setTextColor(Color.WHITE)
            banner.setBackgroundColor(if (listening) 0xFF10624B.toInt() else 0xFF2A3335.toInt())
        }

        suspend fun download(pack: XasrVoicePack) {
            harness.await("human-pack-download-visible") { withContext(Dispatchers.Main) { download.isShown && download.isEnabled } }
            withContext(Dispatchers.Main) { check(download.performClick()) }
            withTimeout(180_000) {
                while (!pack.isReady()) {
                    withContext(Dispatchers.Main) { check(!error.isShown) { "Voice pack download error: ${error.text}" } }
                    delay(100)
                }
            }
            harness.await("human-pack-voice-enabled") { withContext(Dispatchers.Main) { !download.isShown && voice.isEnabled } }
        }

        suspend fun read(label: String, text: String, owner: String, initialUsers: Set<String>): Turn {
            guide("$label · 准备\n等绿色提示后再读\n\n$text")
            delay(1_500)
            harness.await("human-mic-idle") { audio.activeRecordingConfigurations.isEmpty() }
            val clicked = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { check(voice.isEnabled && voice.performClick()) }
            val ready = withTimeoutOrNull(20_000) {
                while (!withContext(Dispatchers.Main) {
                        check(!error.isShown) { "Voice startup error: ${error.text}" }
                        status.isShown && status.text.toString().startsWith(activity.getString(R.string.assistant_voice_listening))
                    }) delay(35)
                SystemClock.elapsedRealtime()
            } ?: error("Microphone was not ready")
            guide("$label · 现在请读\n\n$text", listening = true)
            val final = withTimeoutOrNull(25_000) {
                var newUser: String?
                do {
                    val added = userMessages(owner).filter { it.id !in initialUsers }
                    check(added.size <= 1)
                    newUser = added.singleOrNull()?.content
                    if (newUser != null || withContext(Dispatchers.Main) { error.isShown }) break
                    delay(80)
                } while (true)
                newUser
            }
            val recordedAt = SystemClock.elapsedRealtime()
            guide("$label · 已结束。请等下一条。")
            withContext(Dispatchers.Main) { view.cancelRecording() }
            return Turn(clicked, ready - clicked, final?.let { recordedAt - clicked }, final)
        }
    }

    private suspend fun userMessages(owner: String) = data.chats().getMessages(owner).filter { it.role == MessageRole.User }

    private fun signal(text: String) = instrumentation.sendStatus(
        0, Bundle().apply { putString("stream", "\nW01_HUMAN $text\n") }
    )

    private data class Turn(val clicked: Long, val readyMs: Long, val transcriptMs: Long?, val transcript: String?) {
        fun toJson(sample: Sample): JSONObject {
            val recognized = transcript?.let(::normalized).orEmpty()
            val expected = normalized(sample.text)
            val keepTranscript = recognized.isNotEmpty() && expected.contains(recognized)
            return JSONObject().put("id", sample.id).put("prompt", sample.text)
                .put("readyMs", readyMs).put("transcriptMs", transcriptMs ?: JSONObject.NULL)
                .put("transcript", transcript.takeIf { keepTranscript } ?: JSONObject.NULL)
                .put("transcriptRedacted", transcript != null && !keepTranscript)
                .put("transcriptMatched", transcript != null && recognized == expected)
        }
    }

    private data class Sample(val id: String, val text: String, val answer: Regex? = null) {
        fun matches(transcript: String): Boolean = normalized(transcript) == normalized(text)
    }

    private companion object {
        val samples = listOf(
            Sample("multiply", "请问六乘八等于多少？", answerNumber("四十八|48")),
            Sample("add", "请算一下三加四等于多少？", answerNumber("七|7")),
            Sample("music", "今天适合听音乐吗？"),
            Sample("offline", "请用一句话解释什么是离线识别。"),
            Sample("numbers", "把一百二十六加上三十四。"),
            Sample("deepseek-intro", "请用一句话介绍 DeepSeek。"),
            Sample("deepseek-spelling", "DeepSeek 这个名字怎么拼写？"),
            Sample("two-names", "请比较 DeepSeek 和 YouTube 这两个名字。"),
            Sample("time", "请把明天上午九点半这几个字重复一遍。"),
            Sample("summary", "帮我把这句话概括成五个字，周末准备去图书馆借两本书。")
        )

        fun answerNumber(answer: String): Regex {
            val digits = "零〇一二三四五六七八九十百千万0-9"
            return Regex("(?<![$digits])(?:$answer)(?![$digits])")
        }

        fun normalized(text: String): String {
            val clean = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT).replace("×", "乘").replace("+", "加")
                .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")
            val numbers = mapOf("一百二十六" to "126", "三十四" to "34", "一" to "1", "二" to "2",
                "两" to "2", "三" to "3", "四" to "4", "五" to "5", "六" to "6", "七" to "7", "八" to "8", "九" to "9")
            return numbers.entries.fold(clean) { value, (spoken, digit) -> value.replace(spoken, digit) }
        }
    }
}
