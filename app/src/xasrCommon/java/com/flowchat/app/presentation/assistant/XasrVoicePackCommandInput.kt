package com.flowchat.app.presentation.assistant

/**
 * Builds the X-ASR input that replays the wake session's command audio instead of opening a
 * second microphone. Only compiled with the pinned sherpa-onnx AAR.
 */
internal fun XasrVoicePack.commandInput(openAudio: () -> XasrPcmInput): AssistantSpeechInput? =
    modelDirectory()?.let { directory ->
        XasrSpeechInput(openAudio = openAudio, locateModelDirectory = { directory })
    }