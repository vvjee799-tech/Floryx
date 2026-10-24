package com.flowchat.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.flowchat.app.domain.device.PowerConnectionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val memoryEnabled: Boolean = true,
    val powerModeEnabled: Boolean = false,
    val powerConnectionMode: PowerConnectionMode = PowerConnectionMode.Shizuku,
    val webSearchDisclosureAccepted: Boolean = false,
    val installId: String = "",
    val usageTrackingEnabled: Boolean = false,
    val commandExecutionEnabled: Boolean = false,
    val defaultWorkspace: com.flowchat.app.domain.workspace.WorkspaceLocation? = null
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

    fun setUsageTrackingEnabled(enabled: Boolean) = update("usage_tracking_enabled", enabled) { copy(usageTrackingEnabled = enabled) }

    fun setDefaultWorkspace(location: com.flowchat.app.domain.workspace.WorkspaceLocation?) {
        mutableState.value = mutableState.value.copy(defaultWorkspace = location)
        preferences?.edit()?.putString("default_workspace", com.flowchat.app.domain.workspace.WorkspaceCodec.encode(location))?.apply()
    }

    fun setCommandExecutionEnabled(enabled: Boolean) = update(CommandExecutionKey, enabled) { copy(commandExecutionEnabled = enabled) }

    fun setMemoryEnabled(enabled: Boolean) = update(MemoryEnabledKey, enabled) { copy(memoryEnabled = enabled) }

    fun setPowerModeEnabled(enabled: Boolean) =
        update(PowerModeEnabledKey, enabled) { copy(powerModeEnabled = enabled) }

    fun setPowerConnectionMode(mode: PowerConnectionMode) {
        mutableState.value = mutableState.value.copy(powerConnectionMode = mode)
        preferences?.edit()?.putString(PowerConnectionModeKey, mode.name)?.apply()
    }

    fun acceptWebSearchDisclosure() =
        update(WebSearchDisclosureKey, true) { copy(webSearchDisclosureAccepted = true) }

    private fun update(key: String, value: Boolean, transform: AppSettings.() -> AppSettings) {
        mutableState.value = mutableState.value.transform()
        preferences?.edit()?.putBoolean(key, value)?.apply()
    }

    private companion object {
        const val PreferencesName = "app_controls"
        const val CommandExecutionKey = "command_execution_enabled"
        const val MemoryEnabledKey = "memory_enabled"
        const val PowerModeEnabledKey = "power_mode_enabled"
        const val PowerConnectionModeKey = "power_connection_mode"
        const val AppUsageEnabledKey = "app_usage_tool_enabled"
        const val RecentActivityEnabledKey = "recent_activity_tool_enabled"
        const val OpenAppEnabledKey = "open_app_tool_enabled"
        const val DeviceAssistantEnabledKey = "device_assistant_enabled"
        const val FloatingAssistantEnabledKey = "floating_assistant_enabled"
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
            return AppSettings(
                memoryEnabled = getBoolean(MemoryEnabledKey, true),
                usageTrackingEnabled = getBoolean("usage_tracking_enabled", false),
                powerModeEnabled = powerModeEnabled,
                powerConnectionMode = PowerConnectionMode.entries.firstOrNull {
                    it.name == getString(PowerConnectionModeKey, null)
                } ?: PowerConnectionMode.Shizuku,
                webSearchDisclosureAccepted = getBoolean(WebSearchDisclosureKey, false),
                installId = installId,
                commandExecutionEnabled = getBoolean(CommandExecutionKey, false),
                defaultWorkspace = com.flowchat.app.domain.workspace.WorkspaceCodec.decode(getString("default_workspace", null))
            )
        }
    }
}
