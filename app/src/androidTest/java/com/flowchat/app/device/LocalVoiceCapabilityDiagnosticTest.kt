package com.flowchat.app.device

import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** OEM preflight without opening the microphone or sending a task request. */
class LocalVoiceCapabilityDiagnosticTest {
    @Test fun probesOnDeviceRecognitionAndEmbeddedChineseSynthesis() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLocalVoiceCapabilities") == "true")
        val context = instrumentation.targetContext
        val directory = File(context.getExternalFilesDir(null), "iqoo-voice-capabilities").apply { mkdirs() }
        val output = File(directory, "w01.json")
        val result = JSONObject().put("on_device_recognition_available",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && withContext(Dispatchers.Main) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            })
        val ready = CompletableDeferred<Int>()
        val engine = withContext(Dispatchers.Main) { TextToSpeech(context) { ready.complete(it) } }
        val audio = File.createTempFile("local-voice-", ".wav", context.cacheDir)
        try {
            val initialization = withTimeoutOrNull(8_000) { ready.await() }
            result.put("tts_init", initialization ?: JSONObject.NULL)
            result.put("tts_default_engine", engine.defaultEngine)
            if (initialization == TextToSpeech.SUCCESS) {
                val chinese = engine.voices.orEmpty().filter { it.locale.language == "zh" }
                result.put("chinese_voices", JSONArray(chinese.map { voice ->
                    JSONObject().put("locale", voice.locale.toLanguageTag()).put("name", voice.name)
                        .put("needs_network", voice.isNetworkConnectionRequired)
                }))
                val voice = chinese.filterNot { it.isNetworkConnectionRequired }
                    .maxWithOrNull(compareBy({ it.locale.country == "CN" }, { it.quality }))
                if (voice != null) {
                    val finished = CompletableDeferred<Boolean>()
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { finished.complete(true) }
                        @Deprecated("Compatibility callback for older speech engines")
                        override fun onError(utteranceId: String?) { finished.complete(false) }
                        override fun onError(utteranceId: String?, errorCode: Int) { finished.complete(false) }
                    })
                    val accepted = engine.setVoice(voice) == TextToSpeech.SUCCESS &&
                        engine.synthesizeToFile("三加四等于七。", null, audio, "voice-capability") == TextToSpeech.SUCCESS
                    val completed = accepted && withTimeoutOrNull(10_000) { finished.await() } == true
                    result.put("embedded_voice", voice.name)
                        .put("chinese_synthesis_completed", completed)
                        .put("synthesized_bytes", audio.length())
                }
            }
        } finally {
            engine.shutdown()
            audio.delete()
            output.writeText(result.toString(2))
        }
    }
}
