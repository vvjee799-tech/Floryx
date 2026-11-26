package com.flowchat.app.presentation.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import com.flowchat.app.R
import java.util.Locale

internal interface AssistantSpeechInput {
    interface Listener {
        fun ready()
        fun partial(text: String)
        fun endOfSpeech()
        fun result(text: String)
        fun error(message: Int)
    }

    fun start(listener: Listener)
    fun close()
}

internal interface AssistantAudioGuard {
    interface Lease : AutoCloseable {
        override fun close()
    }

    sealed interface AcquireResult {
        data class Acquired(val lease: Lease) : AcquireResult
        data class Blocked(@StringRes val message: Int) : AcquireResult
    }

    fun acquireCapture(onInterrupt: (message: Int) -> Unit): AcquireResult
}

internal fun isCallAudioMode(mode: Int): Boolean =
    mode == AudioManager.MODE_IN_CALL ||
        mode == AudioManager.MODE_RINGTONE ||
        mode == AudioManager.MODE_IN_COMMUNICATION

internal fun isExternalVoiceDevice(device: AudioDeviceInfo): Boolean =
    when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

internal class SystemAssistantAudioGuard(private val context: Context) : AssistantAudioGuard {
    override fun acquireCapture(onInterrupt: (message: Int) -> Unit): AssistantAudioGuard.AcquireResult {
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return AssistantAudioGuard.AcquireResult.Blocked(R.string.assistant_voice_interrupted)
        if (isCallAudioMode(manager.mode)) {
            return AssistantAudioGuard.AcquireResult.Blocked(R.string.assistant_voice_in_call)
        }
        if (manager.activeRecordingConfigurations.isNotEmpty()) {
            return AssistantAudioGuard.AcquireResult.Blocked(R.string.assistant_voice_mic_busy)
        }
        val mainHandler = Handler(Looper.getMainLooper())
        var active = true
        val initialExternalIds = manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::isExternalVoiceDevice)
            .mapTo(mutableSetOf()) { it.id }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (active && change < 0) {
                    val reason = if (isCallAudioMode(manager.mode)) R.string.assistant_voice_in_call
                        else R.string.assistant_voice_focus_lost
                    onInterrupt(reason)
                }
            }, mainHandler)
            .build()
        if (manager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            val reason = if (isCallAudioMode(manager.mode)) R.string.assistant_voice_in_call
                else R.string.assistant_voice_focus_lost
            return AssistantAudioGuard.AcquireResult.Blocked(reason)
        }
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val changed = addedDevices.any { isExternalVoiceDevice(it) && initialExternalIds.add(it.id) }
                if (active && changed) onInterrupt(R.string.assistant_voice_device_changed)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val changed = removedDevices.any { isExternalVoiceDevice(it) && initialExternalIds.remove(it.id) }
                if (active && changed) onInterrupt(R.string.assistant_voice_device_changed)
            }
        }
        manager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        val modeListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener { mode ->
                if (active && isCallAudioMode(mode)) onInterrupt(R.string.assistant_voice_in_call)
            }.also { manager.addOnModeChangedListener(context.mainExecutor, it) }
        } else null
        return AssistantAudioGuard.AcquireResult.Acquired(object : AssistantAudioGuard.Lease {
            override fun close() {
                if (!active) return
                active = false
                runCatching { manager.unregisterAudioDeviceCallback(deviceCallback) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    modeListener?.let { runCatching { manager.removeOnModeChangedListener(it) } }
                }
                runCatching { manager.abandonAudioFocusRequest(focusRequest) }
            }
        })
    }
}

internal class AndroidAssistantSpeechInput(private val context: Context) : AssistantSpeechInput {
    private var recognizer: SpeechRecognizer? = null

    override fun start(listener: AssistantSpeechInput.Listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isAvailable(context)) {
            listener.error(R.string.assistant_voice_unavailable)
            return
        }
        val locale = context.resources.configuration.locales[0]
        val language = if (locale.language == "zh" && locale.country.isEmpty()) Locale.SIMPLIFIED_CHINESE else locale
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val audioManager = context.getSystemService(AudioManager::class.java)
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { engine ->
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = listener.ready()
                override fun onPartialResults(results: Bundle?) = listener.partial(transcript(results))
                override fun onEndOfSpeech() = listener.endOfSpeech()
                override fun onResults(results: Bundle?) = listener.result(transcript(results))
                override fun onError(error: Int) = listener.error(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.assistant_voice_no_speech
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.assistant_voice_permission
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.assistant_voice_mic_busy
                    SpeechRecognizer.ERROR_AUDIO -> when {
                        audioManager != null && isCallAudioMode(audioManager.mode) -> R.string.assistant_voice_in_call
                        audioManager != null && audioManager.activeRecordingConfigurations.isNotEmpty() -> R.string.assistant_voice_mic_busy
                        else -> R.string.assistant_voice_interrupted
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.assistant_voice_unavailable
                    else -> R.string.assistant_voice_interrupted
                })
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        recognizer?.startListening(request)
    }

    override fun close() {
        val engine = recognizer ?: return
        recognizer = null
        engine.destroy()
    }

    private fun transcript(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    companion object {
        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}

private val VOICE_PUNCTUATION = setOf(' ', '\t', '\n', '\r', '。', '！', '？', '.', '!', '?', ',', '，', '、', '~', '～')

internal fun normalizeVoiceCommand(transcript: String): String =
    transcript.trim().trim { it in VOICE_PUNCTUATION }.lowercase(Locale.ROOT)

private val LOCAL_STOP_COMMANDS = setOf(
    "停止", "停", "停下", "暂停", "别说了", "不要说了", "别读了", "不要读了",
    "闭嘴", "取消", "算了", "停止播报", "停止任务", "stop", "cancel"
)

private val FOLLOWUP_DISMISS_COMMANDS = setOf(
    "不用了", "不用", "没事了", "好了", "谢谢", "没有了", "no", "nothing", "no thanks"
)

internal fun isLocalVoiceStopCommand(transcript: String): Boolean =
    normalizeVoiceCommand(transcript) in LOCAL_STOP_COMMANDS

internal fun isFollowUpDismissCommand(transcript: String): Boolean {
    val normalized = normalizeVoiceCommand(transcript)
    return normalized in LOCAL_STOP_COMMANDS || normalized in FOLLOWUP_DISMISS_COMMANDS
}

internal fun isLikelySelfEcho(transcript: String, spokenText: String): Boolean {
    val candidate = normalizeVoiceCommand(transcript)
    val spoken = normalizeVoiceCommand(spokenText)
    if (candidate.isEmpty() || spoken.isEmpty()) return false
    if (candidate == spoken) return true
    return spoken.length <= 12 && spoken.contains(candidate)
}

/** A session is retired before delivering its final transcript or releasing the microphone. */
internal class AssistantVoiceCapture(
    private val inputFactory: () -> AssistantSpeechInput,
    private val update: (status: Int?, partial: String) -> Unit,
    private val submit: (transcript: String, followUp: Boolean) -> Unit,
    private val audioGuard: AssistantAudioGuard? = null
) {
    constructor(
        inputFactory: () -> AssistantSpeechInput,
        update: (status: Int?, partial: String) -> Unit,
        submit: (String) -> Unit,
        audioGuard: AssistantAudioGuard? = null
    ) : this(inputFactory, update, { transcript, _ -> submit(transcript) }, audioGuard)

    private val handler = Handler(Looper.getMainLooper())
    private var active: Session? = null
    val isActive get() = active != null
    val isFollowUp get() = active?.followUp == true

    fun start(followUp: Boolean = false, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (isActive) return
        begin(inputFactory(), followUp, timeoutMs, acquireGuard = true, onFinished = null)
    }

    /**
     * Starts a capture with audio that is already owned by another component (the experimental
     * wake-word session). The caller already holds the audio guard, so it must not be acquired
     * twice, and [onFinished] releases the wake-word session when this capture retires.
     */
    fun startWith(
        input: AssistantSpeechInput,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        alreadyGuarded: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        if (isActive) {
            input.close()
            return
        }
        begin(input, followUp = false, timeoutMs, acquireGuard = !alreadyGuarded, onFinished = onFinished)
    }

    private fun begin(
        input: AssistantSpeechInput,
        followUp: Boolean,
        timeoutMs: Long,
        acquireGuard: Boolean,
        onFinished: (() -> Unit)?
    ) {
        val session = Session(input, followUp, onFinished)
        if (acquireGuard && audioGuard != null) {
            when (val acquired = audioGuard.acquireCapture { message ->
                handler.post { session.interrupt(message) }
            }) {
                is AssistantAudioGuard.AcquireResult.Blocked -> {
                    session.input.close()
                    session.onFinished?.let { runCatching { it() } }
                    if (followUp && acquired.message == R.string.assistant_voice_no_speech) update(null, "")
                    else update(acquired.message, "")
                    return
                }
                is AssistantAudioGuard.AcquireResult.Acquired -> session.lease = acquired.lease
            }
        }
        active = session
        update(if (followUp) R.string.assistant_voice_followup_listening else R.string.assistant_voice_preparing, "")
        session.windowMs = timeoutMs.coerceAtLeast(500L)
        // A follow-up window counts from microphone readiness; loading an offline model must not consume it.
        handler.postDelayed(
            session.timeout,
            if (followUp) maxOf(session.windowMs, FOLLOW_UP_PREPARE_TIMEOUT_MS) else session.windowMs
        )
        try {
            session.input.start(session)
        } catch (_: SecurityException) {
            session.error(R.string.assistant_voice_permission)
        } catch (_: RuntimeException) {
            session.error(R.string.assistant_voice_interrupted)
        }
    }

    fun cancel() {
        if (retire(active) != null) update(null, "")
    }

    private fun retire(session: Session?): Session? {
        if (session == null || active !== session) return null
        active = null
        handler.removeCallbacks(session.timeout)
        try {
            session.input.close()
        } finally {
            session.lease?.close()
            session.lease = null
            session.onFinished?.let { runCatching { it() } }
        }
        return session
    }

    private inner class Session(
        val input: AssistantSpeechInput,
        val followUp: Boolean,
        val onFinished: (() -> Unit)? = null
    ) : AssistantSpeechInput.Listener {
        var lease: AssistantAudioGuard.Lease? = null
        var windowMs = DEFAULT_TIMEOUT_MS
        private var windowArmed = false
        private var speechStarted = false
        private var partial = ""
        private var status = if (followUp) R.string.assistant_voice_followup_listening else R.string.assistant_voice_listening
        val timeout = Runnable {
            if (followUp) {
                if (retire(this) != null) update(null, "")
            } else {
                error(R.string.assistant_voice_interrupted)
            }
        }

        override fun ready() {
            if (followUp && !windowArmed && !speechStarted && active === this) {
                windowArmed = true
                restartTimeout(windowMs)
            }
            show()
        }
        override fun partial(text: String) {
            if (text.isNotBlank()) {
                partial = text.take(32_000)
                speechHeard()
            }
            show()
        }
        override fun endOfSpeech() {
            speechHeard()
            status = R.string.assistant_voice_transcribing
            show()
        }

        /** Once the user is speaking, the short window no longer cuts the utterance; the overall cap applies. */
        private fun speechHeard() {
            if (!followUp || speechStarted || active !== this) return
            speechStarted = true
            windowArmed = true
            restartTimeout(DEFAULT_TIMEOUT_MS)
        }

        private fun restartTimeout(delayMs: Long) {
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, delayMs)
        }

        override fun result(text: String) {
            if (retire(this) == null) return
            val transcript = text.trim().take(32_000)
            if (transcript.isBlank()) {
                update(if (followUp) null else R.string.assistant_voice_no_speech, "")
                return
            }
            update(null, "")
            submit(transcript, followUp)
        }

        override fun error(message: Int) {
            if (retire(this) == null) return
            if (followUp && message == R.string.assistant_voice_no_speech) update(null, "")
            else update(message, "")
        }

        fun interrupt(message: Int) = error(message)

        private fun show() {
            if (active === this) update(status, partial)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val FOLLOW_UP_TIMEOUT_MS = 6_000L
        /** Longest a follow-up may wait for the recognizer to open the microphone before quietly closing. */
        const val FOLLOW_UP_PREPARE_TIMEOUT_MS = 10_000L
    }
}

/** Listens for explicit local stop commands during TTS playback without submitting non-stop echoes. */
internal class PlaybackBargeInController(
    private val inputFactory: () -> AssistantSpeechInput,
    private val spokenText: () -> String,
    private val onLocalStop: () -> Unit
) {
    private var active: Session? = null
    val isActive get() = active != null

    fun start() {
        if (isActive) return
        val session = Session(inputFactory())
        active = session
        try {
            session.input.start(session)
        } catch (_: SecurityException) {
            retire(session)
        } catch (_: RuntimeException) {
            retire(session)
        }
    }

    fun stop() {
        retire(active)
    }

    private fun retire(session: Session?): Session? {
        if (session == null || active !== session) return null
        active = null
        session.input.close()
        return session
    }

    private inner class Session(val input: AssistantSpeechInput) : AssistantSpeechInput.Listener {
        override fun ready() = Unit
        override fun endOfSpeech() = Unit
        override fun partial(text: String) { maybeTriggerStop(text) }
        override fun result(text: String) {
            if (active !== this) return
            val triggered = maybeTriggerStop(text)
            if (!triggered) retire(this)
        }
        override fun error(message: Int) { retire(this) }

        private fun maybeTriggerStop(text: String): Boolean {
            if (active !== this) return false
            if (!isLocalVoiceStopCommand(text) || isLikelySelfEcho(text, spokenText())) return false
            if (retire(this) != null) onLocalStop()
            return true
        }
    }
}
