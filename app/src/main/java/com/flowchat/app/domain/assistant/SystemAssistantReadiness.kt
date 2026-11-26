package com.flowchat.app.domain.assistant

/** Default selection alone does not activate the Android 12+ voice assistant entry. */
data class SystemAssistantReadiness(
    val defaultSelected: Boolean?,
    val voiceEntrySupported: Boolean,
    val voiceEntryActive: Boolean?
) {
    enum class State {
        NotSelected,
        VoiceEntryActive,
        VoiceEntryInactive,
        ActivityEntrySelected,
        Unverified
    }

    val state: State
        get() = when {
            defaultSelected == null -> State.Unverified
            !defaultSelected -> State.NotSelected
            !voiceEntrySupported -> State.ActivityEntrySelected
            voiceEntryActive == null -> State.Unverified
            voiceEntryActive -> State.VoiceEntryActive
            else -> State.VoiceEntryInactive
        }

    val ready: Boolean
        get() = state == State.VoiceEntryActive || state == State.ActivityEntrySelected
}
