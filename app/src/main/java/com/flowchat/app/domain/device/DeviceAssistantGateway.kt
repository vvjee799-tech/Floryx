package com.flowchat.app.domain.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.flowchat.app.domain.terminal.CommandSnapshot

enum class DeviceCapability {
    DeviceStatus,
    ForegroundApp,
    ScreenBrightness,
    MediaVolume,
    ForceStopApp,
    ScreenObservation,
    UiElementAction,
    TextInput,
    ScreenSwipe,
    Navigation,
    ShellCommands
}

enum class PowerConnectionMode {
    Shizuku,
    Root
}

enum class ShizukuConnectionStatus {
    NotInstalled,
    NotRunning,
    PermissionRequired,
    PermissionDenied,
    Connecting,
    ConnectedAdb,
    ConnectedRoot,
    RootUnavailable,
    RootPermissionRequired,
    RootPermissionDenied,
    ConnectedDirectRoot,
    Unsupported,
    Error
}

data class ShizukuConnectionState(
    val status: ShizukuConnectionStatus = ShizukuConnectionStatus.NotInstalled,
    val capabilities: Set<DeviceCapability> = emptySet(),
    val detail: String? = null
) {
    val isConnected: Boolean
        get() = status == ShizukuConnectionStatus.ConnectedAdb ||
            status == ShizukuConnectionStatus.ConnectedRoot ||
            status == ShizukuConnectionStatus.ConnectedDirectRoot
}

data class DeviceToolResult(
    val success: Boolean,
    val summary: String,
    val before: String? = null,
    val after: String? = null,
    val reversible: Boolean = false,
    val errorCode: String? = null,
    val screen: DeviceScreenSnapshot? = null
) {
    fun asToolContent(): String = buildString {
        append(if (success) "success" else "failed")
        append(": ")
        append(summary)
        before?.let { append("\nbefore: ").append(it) }
        after?.let { append("\nafter: ").append(it) }
        if (reversible) append("\nreversible: true")
        errorCode?.let { append("\nerror_code: ").append(it) }
        screen?.let { append('\n').append(it.asToolContent()) }
    }
}

interface DeviceAssistantGateway {
    val connectionState: StateFlow<ShizukuConnectionState>
    val accessibilityState: StateFlow<AccessibilityConnectionState>
        get() = MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable))

    fun refreshConnection()
    fun requestPermission()
    fun refreshAccessibilityConnection() = Unit
    fun openAccessibilitySettings() = Unit
    suspend fun getDeviceStatus(): DeviceToolResult
    suspend fun getForegroundApp(): DeviceToolResult
    suspend fun setScreenBrightness(percent: Int): DeviceToolResult
    suspend fun setMediaVolume(percent: Int): DeviceToolResult
    suspend fun forceStopApp(appName: String): DeviceToolResult
    suspend fun enablePowerMode(): DeviceToolResult
    suspend fun launchApp(packageName: String, activityName: String): DeviceToolResult = unsupportedAutomation()
    suspend fun observeScreen(): DeviceToolResult = unsupportedAutomation()
    suspend fun tapUiElement(index: Int, longPress: Boolean = false, screenId: String? = null): DeviceToolResult = unsupportedAutomation()
    suspend fun inputText(index: Int, text: String, clearExisting: Boolean, screenId: String? = null): DeviceToolResult = unsupportedAutomation()
    suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int): DeviceToolResult =
        unsupportedAutomation()
    suspend fun pressBack(): DeviceToolResult = unsupportedAutomation()
    suspend fun pressHome(): DeviceToolResult = unsupportedAutomation()
    suspend fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): CommandSnapshot = error("Power connection unavailable.")
    suspend fun readShellCommand(commandId: String): CommandSnapshot = error("Power connection unavailable.")
    suspend fun cancelShellCommand(commandId: String): CommandSnapshot = error("Power connection unavailable.")

    private fun unsupportedAutomation() = DeviceToolResult(
        success = false,
        summary = "Accessibility automation is unavailable.",
        errorCode = "accessibility_unavailable"
    )
}

object UnavailableDeviceAssistantGateway : DeviceAssistantGateway {
    override val connectionState: StateFlow<ShizukuConnectionState> =
        MutableStateFlow(ShizukuConnectionState())
    override val accessibilityState: StateFlow<AccessibilityConnectionState> =
        MutableStateFlow(AccessibilityConnectionState(AccessibilityConnectionStatus.Unavailable))

    override fun refreshConnection() = Unit
    override fun requestPermission() = Unit
    override fun refreshAccessibilityConnection() = Unit
    override fun openAccessibilitySettings() = Unit

    override suspend fun getDeviceStatus() = unavailable()
    override suspend fun getForegroundApp() = unavailable()
    override suspend fun setScreenBrightness(percent: Int) = unavailable()
    override suspend fun setMediaVolume(percent: Int) = unavailable()
    override suspend fun forceStopApp(appName: String) = unavailable()
    override suspend fun enablePowerMode() = unavailable()
    override suspend fun observeScreen() = unavailable()
    override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?) = unavailable()
    override suspend fun inputText(index: Int, text: String, clearExisting: Boolean, screenId: String?) = unavailable()
    override suspend fun swipeScreen(direction: DeviceSwipeDirection, distancePercent: Int) = unavailable()
    override suspend fun pressBack() = unavailable()
    override suspend fun pressHome() = unavailable()

    private fun unavailable() = DeviceToolResult(
        success = false,
        summary = "Shizuku is not connected.",
        errorCode = "shizuku_unavailable"
    )
}
