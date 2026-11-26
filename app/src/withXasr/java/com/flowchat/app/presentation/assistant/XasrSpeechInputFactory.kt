package com.flowchat.app.presentation.assistant

import android.os.Build
import java.io.File

internal object XasrSpeechInputFactory {
    val supported = Build.SUPPORTED_ABIS.any { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }

    init {
        // Debug-only experimental wake word; instrumentation may install a PCM-injected controller first.
        if (WakeWordPlugins.factory == null) {
            WakeWordPlugins.factory = { context -> KwsWakeWordController.create(context) }
        }
    }

    fun create(directory: File): AssistantSpeechInput =
        XasrSpeechInput(locateModelDirectory = { directory })
}
