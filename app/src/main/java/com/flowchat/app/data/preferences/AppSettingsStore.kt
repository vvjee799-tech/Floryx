package com.flowchat.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.flowchat.app.domain.device.ExecutionPermissionMode
import com.flowchat.app.domain.device.PowerConnectionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.flowchat.app.domain.assistant.AssistantModelSelection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AppSettings(
    val memoryEnabled: Boolean = true,
    val powerModeEnabled: Boolean = false,
    val powerConnectionMode: PowerConnectionMode = PowerConnectionMode.Shizuku,
    val backgroundExecutionEnabled: Boolean = false,
    val webSearchDisclosureAccepted: Boolean = false,
    val installId: String = "",
    val usageTrackingEnabled: Boolean = false,
    val commandExecutionEnabled: Boolean = false,
    val photoAnswerEnabled: Boolean = true,
    val floatingBallEnabled: Boolean = false,
    val defaultWorkspace: com.flowchat.app.domain.workspace.WorkspaceLocation? = null,
    /** Process-local: even switching away and back invalidates an older phone plan. */
    val deviceConfigurationVersion: Long = 0L,
    val assistantModelSelection: AssistantModelSelection? = null,
    val executionPermissionMode: ExecutionPermissionMode = ExecutionPermissionMode.RequestReview
)

@Singleton
class AppSettingsStore private constructor(
    private val preferences: SharedPreferences?,
    initial: AppSettings
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE),
        initial = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).loadSettings()
    )

    internal constructor(initial: AppSettings) : this(null, initial)

    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<AppSettings> = mutableState.asStateFlow()

    @Synchronized fun setAssistantModelSelection(selection: AssistantModelSelection?) {
        mutableState.value = mutableState.value.copy(assistantModelSelection = selection)
        preferences?.edit()?.apply {
            if (selection == null) remove("assistant_model_selection")
            else putString("assistant_model_selection", Json.encodeToString(selection))
        }?.apply()
    }

    fun setUsageTrackingEnabled(enabled: Boolean) = update("usage_tracking_enabled", enabled) { copy(usageTrackingEnabled = enabled) }

    @Synchronized fun setDefaultWorkspace(location: com.flowchat.app.domain.workspace.WorkspaceLocation?) {
        mutableState.value = mutableState.value.copy(defaultWorkspace = location)
        preferences?.edit()?.putString("default_workspace", com.flowchat.app.domain.workspace.WorkspaceCodec.encode(location))?.apply()
    }

    fun setCommandExecutionEnabled(enabled: Boolean) = update(CommandExecutionKey, enabled) { copy(commandExecutionEnabled = enabled) }

    fun setPhotoAnswerEnabled(enabled: Boolean) = update("photo_answer_enabled", enabled) { copy(photoAnswerEnabled = enabled) }

    fun setFloatingBallEnabled(enabled: Boolean) = update(FloatingBallEnabledKey, enabled) { copy(floatingBallEnabled = enabled) }


    fun setMemoryEnabled(enabled: Boolean) = update(MemoryEnabledKey, enabled) { copy(memoryEnabled = enabled) }

    fun setPowerModeEnabled(enabled: Boolean) =
        update(PowerModeEnabledKey, enabled) { copy(powerModeEnabled = enabled) }

    fun setBackgroundExecutionEnabled(enabled: Boolean) =
        update("background_execution_enabled", enabled) { copy(backgroundExecutionEnabled = enabled) }

    @Synchronized fun setPowerConnectionMode(mode: PowerConnectionMode) {
        val previous = mutableStateOfOrCurrent()
        mutableState.value = previous.copy(powerConnectionMode = mode,
            deviceConfigurationVersion = previous.deviceConfigurationVersion + if (mode != previous.powerConnectionMode) 1 else 0)
        preferences?.edit()?.putString(PowerConnectionModeKey, mode.name)?.apply()
    }

    @Synchronized fun setExecutionPermissionMode(mode: ExecutionPermissionMode) {
        mutableState.value = mutableState.value.copy(executionPermissionMode = mode)
        preferences?.edit()?.putString(ExecutionPermissionModeKey, mode.name)?.apply()
    }

    private fun mutableStateOfOrCurrent(): AppSettings = mutableState.value

    fun acceptWebSearchDisclosure() =
        update(WebSearchDisclosureKey, true) { copy(webSearchDisclosureAccepted = true) }

    @Synchronized private fun update(key: String, value: Boolean, transform: AppSettings.() -> AppSettings) {
        val previous = mutableState.value
        val next = previous.transform()
        val targetChanged = previous.powerModeEnabled != next.powerModeEnabled ||
            previous.backgroundExecutionEnabled != next.backgroundExecutionEnabled
        mutableState.value = next.copy(deviceConfigurationVersion = previous.deviceConfigurationVersion + if (targetChanged) 1 else 0)
        preferences?.edit()?.putBoolean(key, value)?.apply()
    }

    private companion object {
        const val PreferencesName = "app_controls"
        const val CommandExecutionKey = "command_execution_enabled"
        const val MemoryEnabledKey = "memory_enabled"
        const val PowerModeEnabledKey = "power_mode_enabled"
        const val PowerConnectionModeKey = "power_connection_mode"
        const val ExecutionPermissionModeKey = "execution_permission_mode"
        const val AppUsageEnabledKey = "app_usage_tool_enabled"
        const val RecentActivityEnabledKey = "recent_activity_tool_enabled"
        const val OpenAppEnabledKey = "open_app_tool_enabled"
        const val DeviceAssistantEnabledKey = "device_assistant_enabled"
        const val FloatingAssistantEnabledKey = "floating_assistant_enabled"
        const val FloatingBallEnabledKey = "floating_ball_enabled"
        const val ForceStopEnabledKey = "force_stop_tool_enabled"
        const val WebSearchDisclosureKey = "web_search_disclosure_accepted"
        const val InstallIdKey = "install_id"

        fun SharedPreferences.loadSettings(): AppSettings {
            val installId = getString(InstallIdKey, null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { edit().putString(InstallIdKey, it).apply() }
            val powerModeEnabled = if (contains(PowerModeEnabledKey)) {
                getBoolean(PowerModeEnabledKey, false)
            } else {
                val migrated = getBoolean(AppUsageEnabledKey, false) ||
                    getBoolean(RecentActivityEnabledKey, false) ||
                    getBoolean(DeviceAssistantEnabledKey, false) ||
                    getBoolean(FloatingAssistantEnabledKey, false) ||
                    getBoolean(ForceStopEnabledKey, false)
                edit().putBoolean(PowerModeEnabledKey, migrated).apply()
                migrated
            }
            val photoAnswerEnabled = getBoolean("photo_answer_enabled", true)
            val floatingBallEnabled = if (contains(FloatingBallEnabledKey)) {
                getBoolean(FloatingBallEnabledKey, false)
            } else {
                (powerModeEnabled || photoAnswerEnabled).also { migrated ->
                    edit().putBoolean(FloatingBallEnabledKey, migrated).apply()
                }
            }
            return AppSettings(
                memoryEnabled = getBoolean(MemoryEnabledKey, true),
                usageTrackingEnabled = getBoolean("usage_tracking_enabled", false),
                powerModeEnabled = powerModeEnabled,
                backgroundExecutionEnabled = getBoolean("background_execution_enabled", false),
                powerConnectionMode = PowerConnectionMode.entries.firstOrNull {
                    it.name == getString(PowerConnectionModeKey, null)
                } ?: PowerConnectionMode.Shizuku,
                webSearchDisclosureAccepted = getBoolean(WebSearchDisclosureKey, false),
                installId = installId,
                commandExecutionEnabled = getBoolean(CommandExecutionKey, false),
                photoAnswerEnabled = photoAnswerEnabled,
                floatingBallEnabled = floatingBallEnabled,
                defaultWorkspace = com.flowchat.app.domain.workspace.WorkspaceCodec.decode(getString("default_workspace", null)),
                assistantModelSelection = getString("assistant_model_selection", null)?.let { encoded ->
                    runCatching { Json.decodeFromString<AssistantModelSelection>(encoded) }.getOrNull()
                },
                executionPermissionMode = ExecutionPermissionMode.entries.firstOrNull {
                    it.name == getString(ExecutionPermissionModeKey, null)
                } ?: ExecutionPermissionMode.RequestReview
            )
        }
    }
}
