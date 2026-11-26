package com.flowchat.app.presentation.assistant

import java.io.File

internal object XasrSpeechInputFactory {
    val supported = false

    fun create(directory: File): AssistantSpeechInput = error("X-ASR runtime is unavailable for ${directory.name}")
}
