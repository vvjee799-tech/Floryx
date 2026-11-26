package com.flowchat.app.presentation.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import com.flowchat.app.BuildConfig
import com.flowchat.app.R
import com.flowchat.app.presentation.chat.titleResource
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared, small native view for the system session and the ACTION_ASSIST/tile fallback. */
internal class AssistantPanelView(
    context: Context,
    private val presenter: AssistantPanelPresenter,
    private val onClose: () -> Unit,
    onFullChat: () -> Unit,
    private val onAccepted: () -> Unit,
    private val requestVoicePermission: (granted: () -> Unit, denied: () -> Unit) -> Unit,
    private val voiceAvailable: () -> Boolean = { AndroidAssistantSpeechInput.isAvailable(context) },
    speechInput: () -> AssistantSpeechInput = { AndroidAssistantSpeechInput(context) },
    audioGuard: AssistantAudioGuard? = SystemAssistantAudioGuard(context),
    private val followUpWindowMs: Long = 0L,
    bargeInInput: (() -> AssistantSpeechInput)? = null,
    private val hasVoicePermission: () -> Boolean = {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    },
    private val offlineVoicePack: XasrVoicePack? = null,
    private val wakeWord: WakeWordController? = null,
    private val wakeWordSettings: WakeWordSettings = WakeWordSettings(context)
) : FrameLayout(context) {
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink = if (dark) 0xFFE8E4D9.toInt() else 0xFF38352F.toInt()
    private val muted = if (dark) 0xFFB5AF9F.toInt() else 0xFF716D62.toInt()
    private val accent = if (dark) 0xFFD8CFAF.toInt() else 0xFF645E4C.toInt()
    private val status = label(13f).apply { id = R.id.assistant_status; setTextColor(muted) }
    private val configuration = button(R.string.assistant_choose_configuration) {
        val choices = presenter.state.value.configurationChoices
        if (choices.isEmpty()) onFullChat()
        else android.app.AlertDialog.Builder(context).setTitle(R.string.assistant_choose_configuration)
            .setItems(choices.map { it.label }.toTypedArray()) { _, index -> presenter.chooseConfiguration(choices[index]) }.show()
    }.apply { id = R.id.assistant_configuration; gravity = Gravity.START }
    private val output = label(15f).apply { id = R.id.assistant_output; setTextIsSelectable(true) }
    private val outputScroll = ScrollView(context).apply { addView(output) }
    private val error = label(13f).apply {
        id = R.id.assistant_error
        setTextColor(if (dark) 0xFFFFB4ABL.toInt() else 0xFFB3261E.toInt())
    }
    private var voiceStatus: Int? = null
    private var partialTranscript = ""
    private var voiceDownload: Job? = null
    private var voiceDownloadPercent = 0
    private var lastFollowUpGeneration = presenter.state.value.followUpReadyGeneration
    private val capture = AssistantVoiceCapture(
        { offlineVoicePack?.newInputIfReady() ?: speechInput() },
        { feedback, partial ->
            voiceStatus = feedback
            partialTranscript = partial
            render(presenter.state.value)
        },
        { transcript, followUp ->
            presenter.submitVoice(stripWakeWord(transcript, wakeWordSettings.keyword), followUp)
        },
        audioGuard
    )
    private val bargeIn = bargeInInput?.let { factory ->
        PlaybackBargeInController(
            factory,
            { presenter.state.value.voicePlaybackText },
            {
                presenter.stopVoicePlayback()
                voiceStatus = null
                render(presenter.state.value)
            }
        )
    }
    private val wake = wakeWord ?: if (BuildConfig.DEBUG) WakeWordPlugins.factory?.invoke(context) else null
    private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeProgrammaticToggle = false
    private val wakeToggle = CheckBox(context).apply {
        id = R.id.assistant_wake_toggle
        textSize = 12f
        setTextColor(muted)
        setOnCheckedChangeListener { _, checked ->
            if (wakeProgrammaticToggle) return@setOnCheckedChangeListener
            val controller = wake ?: return@setOnCheckedChangeListener
            if (!checked) {
                controller.stop()
                wakeWordSettings.enabled = false
                render(presenter.state.value)
                return@setOnCheckedChangeListener
            }
            val keyword = wakeWordSettings.keyword
            if (keyword.isBlank()) {
                wakeProgrammaticToggle = true
                isChecked = false
                wakeProgrammaticToggle = false
                showWakeWordDialog()
                return@setOnCheckedChangeListener
            }
            requestVoicePermission({
                if (controller.start(keyword)) {
                    wakeWordSettings.enabled = true
                } else {
                    wakeProgrammaticToggle = true
                    isChecked = false
                    wakeProgrammaticToggle = false
                }
                render(presenter.state.value)
            }, {
                wakeProgrammaticToggle = true
                isChecked = false
                wakeProgrammaticToggle = false
                showVoiceError(R.string.assistant_voice_permission)
            })
        }
    }
    private val wakeStatus = label(12f).apply { id = R.id.assistant_wake_status }
    private val wakeSetup = button(R.string.assistant_wake_word_set) { showWakeWordDialog() }.apply {
        id = R.id.assistant_wake_setup
        textSize = 12f
        minHeight = dp(36)
    }
    private val wakeRow = LinearLayout(context).apply {
        id = R.id.assistant_wake_row
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        addView(wakeToggle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(wakeStatus, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(wakeSetup, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }
    private val pageToggle = CheckBox(context).apply { id = R.id.assistant_page_toggle; setTextColor(ink); textSize = 13f }
    private val pagePreview = label(13f).apply { setTextIsSelectable(true) }
    private val pagePreviewScroll = ScrollView(context).apply { addView(pagePreview); visibility = View.GONE }
    private var displayedPage: AssistPageContext? = null
    private val preview = button(R.string.assistant_page_preview) {
        pagePreviewScroll.visibility = if (pagePreviewScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
    private val pageRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(pageToggle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); addView(preview)
    }
    private val input = EditText(context).apply {
        id = R.id.assistant_input
        hint = context.getString(R.string.assistant_input_hint)
        setTextColor(ink); setHintTextColor(muted); textSize = 15f
        minLines = 3; maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(if (dark) 0xFF2C2B25.toInt() else 0xFFFDFCF9.toInt(), 12f)
        doAfterTextChanged {
            if (it.toString() != presenter.state.value.input) {
                voiceStatus = null
                presenter.updateInput(it.toString())
            }
        }
        setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEND) false
            else { if (!capture.isActive) presenter.send(onAccepted); true }
        }
    }
    private var summaryActionShown = false
    private val send = button(R.string.send) {
        if (summaryActionShown) presenter.summarizePage() else presenter.send(onAccepted)
    }.apply {
        id = R.id.assistant_send
        setTextColor(if (dark) 0xFF383529.toInt() else Color.WHITE)
        background = rounded(accent, 12f)
    }
    private val stop = button(R.string.stop) {
        if (presenter.state.value.running || presenter.state.value.paused) presenter.stop()
        else presenter.stopVoicePlayback()
    }.apply { id = R.id.assistant_stop }
    private val voice = button(R.string.assistant_voice_start) { toggleVoice() }.apply {
        id = R.id.assistant_voice
        isEnabled = false
    }
    private val downloadVoice = button(R.string.assistant_voice_download) { toggleVoiceDownload() }.apply {
        id = R.id.assistant_voice_download
        visibility = View.GONE
    }
    private val newTask = button(R.string.assistant_new_task) {
        bargeIn?.stop()
        capture.cancel()
        voiceStatus = null
        lastFollowUpGeneration = presenter.state.value.followUpReadyGeneration
        presenter.newTask()
    }.apply { id = R.id.assistant_new_task }
    private val confirmation = label(14f)
    private val confirmationActions = LinearLayout(context).apply {
        addView(button(R.string.assistant_decline) { presenter.confirm(false) })
        addView(button(R.string.assistant_approve) { presenter.confirm(true); onClose() })
    }

    init {
        id = R.id.assistant_panel_root
        setBackgroundColor(0x22000000)
        setOnClickListener { onClose() }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(24), dp(12), dp(24), dp(16))
            background = rounded(if (dark) 0xFF24231F.toInt() else 0xFFF9F7F2.toInt(), 24f)
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(26f).apply { setText(R.string.app_name); typeface = Typeface.create("serif", Typeface.NORMAL); setTextColor(if (dark) accent else 0xFF2D604F.toInt()) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button(R.string.assistant_close) { onClose() }.apply { id = R.id.assistant_close })
        }
        panel.addView(header); panel.addView(configuration); panel.addView(status)
        panel.addView(outputScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(150)).apply { bottomMargin = dp(8) })
        panel.addView(error); panel.addView(confirmation); panel.addView(confirmationActions)
                panel.addView(pagePreviewScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(120)))
        panel.addView(input)
        panel.addView(pageRow)
        panel.addView(downloadVoice)
        panel.addView(LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(button(R.string.assistant_full_chat) { onFullChat() }.apply {
                id = R.id.assistant_full_chat; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(stop); addView(voice); addView(send)
        })
        panel.addView(newTask)
        panel.addView(wakeRow)
        val maxWidth = minOf(resources.displayMetrics.widthPixels, dp(560))
        val scroller = ScrollView(context).apply { addView(panel) }
        addView(scroller, LayoutParams(maxWidth, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        pageToggle.setOnCheckedChangeListener { _, checked -> presenter.includePage(checked) }
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        wake?.let { controller ->
            controller.onWakeWord = { startWakeCommandCapture() }
            wakeScope.launch { controller.state.collect { render(presenter.state.value) } }
        }
    }

    fun render(state: AssistantPanelState) {
        wake?.let { renderWake(it.state.value, state) }
        if (state.voicePlayback == VoicePlaybackState.Speaking) {
            voiceStatus = null
            if (!capture.isActive && hasVoicePermission()) bargeIn?.start() else bargeIn?.stop()
        } else {
            bargeIn?.stop()
        }
        if (state.followUpReadyGeneration > lastFollowUpGeneration) {
            if (followUpWindowMs <= 0) {
                lastFollowUpGeneration = state.followUpReadyGeneration
            } else if (state.voicePlayback == VoicePlaybackState.Idle && isAttachedToWindow && !capture.isActive &&
                canRecordVoice(state) && voiceDownload == null && canUseVoice() && hasVoicePermission()
            ) {
                lastFollowUpGeneration = state.followUpReadyGeneration
                capture.start(followUp = true, timeoutMs = followUpWindowMs)
                return
            } else if (state.voicePlayback == VoicePlaybackState.Interrupted ||
                state.voicePlayback == VoicePlaybackState.Unavailable ||
                state.input.isNotBlank() ||
                state.paused
            ) {
                lastFollowUpGeneration = state.followUpReadyGeneration
            }
        }
        val recording = capture.isActive
        if (input.text.toString() != state.input) { input.setText(state.input); input.setSelection(input.length()) }
        summaryActionShown = state.page != null && state.input.isBlank()
        send.setText(if (summaryActionShown) R.string.assistant_summarize_page else R.string.send)
        send.isEnabled = !recording && state.ready && !state.running && !state.submitting &&
            (state.input.isNotBlank() || (summaryActionShown && !state.paused))
        configuration.text = state.configurationLabel.ifBlank { context.getString(R.string.assistant_choose_configuration) }
        configuration.isEnabled = !recording && state.ready && !state.running && !state.submitting && !state.paused
        newTask.isEnabled = configuration.isEnabled
        input.isEnabled = !recording && !state.submitting
        pageToggle.isEnabled = !recording
        voice.setText(if (recording) R.string.assistant_voice_cancel else R.string.assistant_voice_start)
        val downloading = voiceDownload != null
        voice.isEnabled = recording || (canRecordVoice(state) && !downloading)
        downloadVoice.visibility = if (offlineVoicePack != null && !offlineVoicePack.isReady()) View.VISIBLE else View.GONE
        downloadVoice.isEnabled = downloading || (canRecordVoice(state) && !recording && state.voicePlayback != VoicePlaybackState.Speaking)
        downloadVoice.text = when {
            !downloading -> context.getString(R.string.assistant_voice_download)
            voiceDownloadPercent == 100 -> context.getString(R.string.assistant_voice_download_verifying)
            else -> context.getString(R.string.assistant_voice_download_progress, voiceDownloadPercent)
        }
        val speaking = state.voicePlayback == VoicePlaybackState.Speaking
        stop.visibility = if (state.running || state.paused || speaking) View.VISIBLE else View.GONE
        val statusText = when {
            recording -> voiceStatus
            !state.ready || state.submitting -> R.string.assistant_preparing
            state.taskStatus.showStatus -> state.taskStatus.titleResource()
            speaking -> R.string.assistant_voice_speaking
            else -> null
        }
        status.text = listOfNotNull(statusText?.let(context::getString), partialTranscript.takeIf { recording && it.isNotBlank() })
            .joinToString("\n")
        status.visibility = if (status.text.isNotEmpty()) View.VISIBLE else View.GONE
        output.text = state.output
        outputScroll.visibility = if (state.output.isBlank()) View.GONE else View.VISIBLE
        error.text = state.error ?: when {
            !recording && voiceStatus != null -> voiceStatus?.let(context::getString)
            !recording && state.voicePlayback == VoicePlaybackState.Interrupted ->
                context.getString(R.string.assistant_voice_playback_interrupted)
            state.voicePlayback == VoicePlaybackState.Unavailable ->
                context.getString(R.string.assistant_voice_playback_unavailable)
            else -> null
        }
        error.visibility = if (error.text.isEmpty()) View.GONE else View.VISIBLE
        pageRow.visibility = if (state.page == null) View.GONE else View.VISIBLE
        if (displayedPage !== state.page) { pagePreviewScroll.visibility = View.GONE; displayedPage = state.page }
        pagePreview.text = state.page?.text
        pageToggle.text = context.getString(R.string.assistant_include_page)
        pageToggle.isChecked = state.includePage
        confirmation.text = state.confirmation?.let { "${it.title}\n${it.message}" }
        confirmation.visibility = if (state.confirmation == null) View.GONE else View.VISIBLE
        confirmationActions.visibility = confirmation.visibility
    }

    private fun renderWake(wakeState: WakeWordState, panelState: AssistantPanelState) {
        val controller = wake ?: return
        wakeRow.visibility = VISIBLE
        wakeProgrammaticToggle = true
        wakeToggle.isChecked = wakeState.running
        wakeProgrammaticToggle = false
        wakeToggle.isEnabled = !capture.isActive
        wakeSetup.isEnabled = true
        wakeStatus.text = when {
            wakeState.error != null -> context.getString(wakeState.error)
            wakeState.running && wakeState.status == R.string.assistant_wake_word_listening ->
                context.getString(R.string.assistant_wake_word_listening, wakeState.keyword)
            wakeState.running -> wakeState.status?.let(context::getString)
                ?: context.getString(R.string.assistant_wake_word_listening, wakeState.keyword)
            wakeState.keyword.isBlank() -> context.getString(R.string.assistant_wake_word_off)
            else -> context.getString(R.string.assistant_wake_word_off)
        }
        controller.setPlaybackActive(panelState.voicePlayback == VoicePlaybackState.Speaking)
    }

    private fun startWakeCommandCapture() {
        val controller = wake ?: return
        val input = controller.takeCommandInput()
        if (input == null) {
            controller.onCommandFinished()
            return
        }
        if (!isAttachedToWindow || capture.isActive || !canRecordVoice(presenter.state.value)) {
            input.close()
            controller.onCommandFinished()
            return
        }
        bargeIn?.stop()
        presenter.stopVoicePlayback()
        voiceStatus = null
        capture.startWith(input, alreadyGuarded = true) { controller.onCommandFinished() }
    }

    private fun showWakeWordDialog() {
        val field = EditText(context).apply {
            setText(wakeWordSettings.keyword)
            hint = context.getString(R.string.assistant_wake_word_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.assistant_wake_word_title)
            .setMessage(R.string.assistant_wake_word_dialog_message)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = field.text.toString().trim()
                if (value.isEmpty()) return@setPositiveButton
                val controller = wake
                if (controller == null) {
                    wakeWordSettings.keyword = value
                } else if (controller.state.value.running) {
                    if (controller.updateKeyword(value)) wakeWordSettings.keyword = value
                } else {
                    wakeWordSettings.keyword = value
                    if (wakeToggle.isChecked) {
                        requestVoicePermission({
                            controller.start(value)
                            render(presenter.state.value)
                        }, {
                            showVoiceError(R.string.assistant_voice_permission)
                        })
                    }
                }
                render(presenter.state.value)
            }
            .show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val controller = wake ?: return
        if (wakeWordSettings.enabled && wakeWordSettings.keyword.isNotBlank() &&
            offlineVoicePack?.isReady() == true && hasVoicePermission() && !controller.state.value.running
        ) {
            controller.start(wakeWordSettings.keyword)
        }
    }

    fun cancelRecording() {
        bargeIn?.stop()
        capture.cancel()
    }

    fun cancelVoiceDownload() {
        val job = voiceDownload ?: return
        voiceDownload = null
        job.cancel()
        render(presenter.state.value)
    }

    fun refreshVoicePermissionState() {
        if (voiceStatus == R.string.assistant_voice_permission && hasVoicePermission()) {
            voiceStatus = null
            render(presenter.state.value)
        }
    }

    override fun onDetachedFromWindow() {
        wake?.stop()
        wakeScope.cancel()
        cancelVoiceDownload()
        cancelRecording()
        super.onDetachedFromWindow()
    }

    private fun toggleVoice() {
        if (capture.isActive) {
            cancelRecording()
            return
        }
        if (!canRecordVoice(presenter.state.value) || voiceDownload != null) return
        if (!canUseVoice()) {
            showVoiceError(R.string.assistant_voice_unavailable)
            return
        }
        requestVoicePermission({
            if (isAttachedToWindow && canRecordVoice(presenter.state.value)) {
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                voiceStatus = null
                lastFollowUpGeneration = presenter.state.value.followUpReadyGeneration
                bargeIn?.stop()
                presenter.stopVoicePlayback()
                capture.start()
            }
        }, { showVoiceError(R.string.assistant_voice_permission) })
    }

    private fun canUseVoice(): Boolean = offlineVoicePack?.isReady() == true || voiceAvailable()

    private fun toggleVoiceDownload() {
        if (voiceDownload != null) {
            cancelVoiceDownload()
            return
        }
        val pack = offlineVoicePack ?: return
        if (!downloadVoice.isEnabled || pack.isReady()) return
        lastFollowUpGeneration = presenter.state.value.followUpReadyGeneration
        voiceStatus = null
        voiceDownloadPercent = 0
        val job = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            var failureMessage: Int? = null
            try {
                pack.download { percent ->
                    withContext(Dispatchers.Main) {
                        voiceDownloadPercent = percent
                        render(presenter.state.value)
                    }
                }
            } catch (_: XasrVoicePackStore.InsufficientSpace) {
                failureMessage = R.string.assistant_voice_download_space
            } catch (_: XasrVoicePackStore.InvalidPack) {
                failureMessage = R.string.assistant_voice_download_invalid
            } catch (failure: IOException) {
                Log.w("AssistantVoiceDownload", "Voice pack download failed", failure)
                failureMessage = R.string.assistant_voice_download_failed
            } finally {
                if (voiceDownload === owner) {
                    voiceStatus = failureMessage
                    voiceDownload = null
                    render(presenter.state.value)
                }
            }
        }
        voiceDownload = job
        render(presenter.state.value)
        job.start()
    }

    private fun showVoiceError(message: Int) {
        voiceStatus = message
        render(presenter.state.value)
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun label(size: Float) = TextView(context).apply { setTextColor(ink); textSize = size; setPadding(0, dp(5), 0, dp(5)) }
    private fun button(text: Int, action: () -> Unit) = Button(context).apply {
        setText(text); isAllCaps = false; textSize = 14f; minWidth = dp(64); minimumWidth = dp(64); minHeight = dp(48)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(accent, (muted and 0x00FFFFFF) or 0x77000000)))
        stateListAnimator = null; elevation = 0f; backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x22000000),
            rounded(Color.TRANSPARENT, 12f), rounded(Color.WHITE, 12f))
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = resources.displayMetrics.density * radius
    }
}
