package com.flowchat.app.presentation.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class VoicePlaybackState { Idle, Speaking, Interrupted, Unavailable }

internal enum class VoicePlaybackResult { Completed, Interrupted, Unavailable }

internal fun interface VoicePlaybackSpeaker {
    suspend fun speak(text: String): VoicePlaybackResult
}

/** The spoken response can finish after the panel yields its window to a device action. */
@Singleton
class AssistantVoicePlayback internal constructor(private val speaker: VoicePlaybackSpeaker) {
    internal constructor(speak: suspend (String) -> Boolean) : this(VoicePlaybackSpeaker { text ->
        if (speak(text)) VoicePlaybackResult.Completed else VoicePlaybackResult.Unavailable
    })

    @Inject constructor(@ApplicationContext context: Context) : this(VoicePlaybackSpeaker { playOnDevice(context, it) })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(VoicePlaybackState.Idle)
    internal val state = mutable.asStateFlow()
    private val speakingTextMutable = MutableStateFlow("")
    internal val speakingText = speakingTextMutable.asStateFlow()
    private val completedGenerationMutable = MutableStateFlow(0L)
    internal val completedGeneration = completedGenerationMutable.asStateFlow()
    private var generation = 0L
    private var playback: Job? = null

    internal fun expectResult(): (String) -> Unit {
        stop()
        val expected = generation
        return { answer ->
            if (generation == expected) {
                val text = answer.trim().take(500).let {
                    if (answer.trim().length <= it.length) it
                    else it.indexOfLast { mark -> mark in "。！？.!?\n" }.takeIf { boundary -> boundary >= 80 }
                        ?.let { boundary -> it.take(boundary + 1) } ?: it
                }
                if (text.isNotBlank()) {
                    val runGeneration = ++generation
                    speakingTextMutable.value = text
                    mutable.value = VoicePlaybackState.Speaking
                    playback = scope.launch {
                        val outcome = speaker.speak(text)
                        if (generation == runGeneration) {
                            speakingTextMutable.value = ""
                            mutable.value = when (outcome) {
                                VoicePlaybackResult.Completed -> VoicePlaybackState.Idle
                                VoicePlaybackResult.Interrupted -> VoicePlaybackState.Interrupted
                                VoicePlaybackResult.Unavailable -> VoicePlaybackState.Unavailable
                            }
                            if (outcome == VoicePlaybackResult.Completed) {
                                completedGenerationMutable.value = runGeneration
                            }
                        }
                    }
                }
            }
        }
    }

    internal fun stop() {
        generation++
        playback?.cancel()
        playback = null
        speakingTextMutable.value = ""
        mutable.value = VoicePlaybackState.Idle
    }
}

private fun isRemovablePlaybackOutput(device: AudioDeviceInfo): Boolean =
    device.isSink && isExternalVoiceDevice(device)

private suspend fun playOnDevice(context: Context, text: String): VoicePlaybackResult {
    val manager = context.getSystemService(AudioManager::class.java)
    if (isCallAudioMode(manager.mode)) return VoicePlaybackResult.Interrupted
    val initialized = CompletableDeferred<Int>()
    val engine = try {
        TextToSpeech(context) { initialized.complete(it) }
    } catch (_: RuntimeException) {
        return VoicePlaybackResult.Unavailable
    }
    val mainHandler = Handler(Looper.getMainLooper())
    var focus: AudioFocusRequest? = null
    var noisyReceiver: BroadcastReceiver? = null
    var deviceCallback: AudioDeviceCallback? = null
    var modeListener: AudioManager.OnModeChangedListener? = null
    try {
        if (withTimeoutOrNull(5_000) { initialized.await() } != TextToSpeech.SUCCESS) return VoicePlaybackResult.Unavailable
        val language = if (text.any { it in '\u3400'..'\u9fff' }) Locale.SIMPLIFIED_CHINESE
            else context.resources.configuration.locales[0]
        val voice = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == language.language }
            .maxWithOrNull(compareBy({ it.locale.country == language.country }, { it.quality }))
            ?: return VoicePlaybackResult.Unavailable
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS) return VoicePlaybackResult.Unavailable
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (engine.setAudioAttributes(attributes) != TextToSpeech.SUCCESS) return VoicePlaybackResult.Unavailable
        val finished = CompletableDeferred<VoicePlaybackResult>()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) finished.complete(VoicePlaybackResult.Interrupted)
            }, mainHandler)
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return VoicePlaybackResult.Interrupted
        }
        focus = request
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    finished.complete(VoicePlaybackResult.Interrupted)
                }
            }
        }.also { receiver ->
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any(::isRemovablePlaybackOutput)) {
                    finished.complete(VoicePlaybackResult.Interrupted)
                }
            }
        }.also { callback -> manager.registerAudioDeviceCallback(callback, mainHandler) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener = AudioManager.OnModeChangedListener { mode ->
                if (isCallAudioMode(mode)) {
                    finished.complete(VoicePlaybackResult.Interrupted)
                }
            }.also { listener -> manager.addOnModeChangedListener(context.mainExecutor, listener) }
        }
        if (isCallAudioMode(manager.mode)) return VoicePlaybackResult.Interrupted
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { finished.complete(VoicePlaybackResult.Completed) }
            @Deprecated("Compatibility callback for older speech engines")
            override fun onError(utteranceId: String?) { finished.complete(VoicePlaybackResult.Unavailable) }
            override fun onError(utteranceId: String?, errorCode: Int) { finished.complete(VoicePlaybackResult.Unavailable) }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-result") != TextToSpeech.SUCCESS) {
            return VoicePlaybackResult.Unavailable
        }
        return withTimeoutOrNull(90_000) { finished.await() } ?: VoicePlaybackResult.Unavailable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        return VoicePlaybackResult.Unavailable
    } finally {
        noisyReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        deviceCallback?.let { runCatching { manager.unregisterAudioDeviceCallback(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let { runCatching { manager.removeOnModeChangedListener(it) } }
        }
        engine.stop()
        engine.shutdown()
        focus?.let { manager.abandonAudioFocusRequest(it) }
    }
}
