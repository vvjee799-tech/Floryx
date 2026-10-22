package com.flowchat.app.data.device

import android.content.Context
import androidx.annotation.Keep
import com.flowchat.app.BuildConfig
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.shizuku.IFlowChatShizukuService
import kotlin.math.roundToInt
import org.json.JSONObject
import com.flowchat.app.data.terminal.ManagedCommandRegistry
import com.flowchat.app.data.terminal.CommandProcessSpec
import com.flowchat.app.domain.terminal.CommandEnvironment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Keep
class FlowChatShizukuService : IFlowChatShizukuService.Stub {
    private val commandExecutor: DeviceCommandExecutor
    private var rootCommands = false
    private val shellCommands by lazy { ManagedCommandRegistry(if (rootCommands) "root-" else "shizuku-") }
    private val shellJson = Json { encodeDefaults = true }

    constructor() : super() {
        commandExecutor = ProcessDeviceCommandExecutor()
    }

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    internal constructor(commandExecutor: DeviceCommandExecutor) : this(commandExecutor, false)

    internal constructor(commandExecutor: DeviceCommandExecutor, rootCommands: Boolean) : super() {
        this.commandExecutor = commandExecutor
        this.rootCommands = rootCommands
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun probeCapabilities(): String = buildSet {
        add(DeviceCapability.ShellCommands.name)
        if (runCommand("dumpsys", "battery").success) add(DeviceCapability.DeviceStatus.name)
        if (foregroundPackage() != null) add(DeviceCapability.ForegroundApp.name)
        if (currentBrightness() != null) add(DeviceCapability.ScreenBrightness.name)
        if (currentMediaVolume() != null) add(DeviceCapability.MediaVolume.name)
        if (runCommand("am", "get-current-user").success) add(DeviceCapability.ForceStopApp.name)
    }.joinToString(",")

    override fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): String =
        shellJson.encodeToString(shellCommands.start(command, CommandEnvironment.Android, workingDirectory, timeoutSeconds,
            CommandProcessSpec(listOf("/system/bin/sh", "-c", "cd ${ManagedCommandRegistry.shellQuote(workingDirectory)} || exit; $command"), root = rootCommands)))

    override fun readShellCommand(commandId: String): String = shellJson.encodeToString(shellCommands.read(commandId))
    override fun cancelShellCommand(commandId: String): String = shellJson.encodeToString(shellCommands.cancel(commandId))

    override fun getDeviceStatus(): String {
        val batteryDump = runCommand("dumpsys", "battery").output
        val level = Regex("level:\\s*(\\d+)").find(batteryDump)?.groupValues?.get(1)
        val scale = Regex("scale:\\s*(\\d+)").find(batteryDump)?.groupValues?.get(1)
        val batteryPercent = level?.toIntOrNull()?.let { raw ->
            val maximum = scale?.toIntOrNull()?.takeIf { it > 0 } ?: 100
            (raw * 100 / maximum).coerceIn(0, 100)
        }
        val storageLine = runCommand("df", "-k", "/data").output.lineSequence()
            .filter { it.isNotBlank() }
            .lastOrNull()
        val storageParts = storageLine?.trim()?.split(Regex("\\s+"))
        val freeStorageKb = storageParts?.getOrNull(3)?.toLongOrNull()
        val brightness = currentBrightness()?.let { (it * 100f / 255f).roundToInt().coerceIn(0, 100) }
        val volume = currentMediaVolume()?.let { current ->
            (current.value * 100f / current.max.coerceAtLeast(1)).roundToInt().coerceIn(0, 100)
        }
        val readings = buildList {
            batteryPercent?.let { add("battery=$it%") }
            freeStorageKb?.let { add("free_storage_mb=${it / 1024}") }
            brightness?.let { add("brightness=$it%") }
            volume?.let { add("media_volume=$it%") }
        }
        return response(
            readings.isNotEmpty(),
            readings.joinToString(", ").ifBlank { "Device status could not be read." },
            errorCode = "device_status_unavailable".takeIf { readings.isEmpty() }
        )
    }

    override fun getForegroundApp(): String {
        val packageName = foregroundPackage()
            ?: return response(false, "The foreground app could not be determined.", errorCode = "foreground_unavailable")
        return response(true, "foreground_package=$packageName")
    }

    override fun launchApp(packageName: String, activityName: String): String {
        if (!PackageNamePattern.matches(packageName) || !Regex("[A-Za-z0-9_.$]+").matches(activityName)) {
            return response(false, "Invalid app component.", errorCode = "invalid_app_component")
        }
        val started = runCommand("am", "start", "-W", "-n", "$packageName/$activityName")
        if (!started.success || started.output.lineSequence().any {
            it.trim().startsWith("Error", ignoreCase = true) || it.contains("Permission Denial", ignoreCase = true)
        }) {
            return response(false, "Android could not launch the app.", errorCode = "app_launch_failed")
        }
        val foreground = foregroundPackage()
        return response(foreground == packageName,
            if (foreground == packageName) "App is in the foreground: $packageName."
            else "Launch was requested but the target app is not in the foreground. Current app: ${foreground ?: "unknown"}.",
            after = foreground, errorCode = "app_not_foreground".takeIf { foreground != packageName })
    }

    override fun setScreenBrightness(percent: Int): String {
        val normalized = percent.coerceIn(1, 100)
        val beforeRaw = currentBrightness()
            ?: return response(false, "Current screen brightness could not be read.", errorCode = "brightness_unavailable")
        val before = (beforeRaw * 100f / 255f).roundToInt().coerceIn(0, 100)
        val targetRaw = (normalized * 255f / 100f).roundToInt().coerceIn(1, 255)
        val command = runCommand("settings", "put", "system", "screen_brightness", targetRaw.toString())
        if (!command.success) {
            return response(false, command.output.ifBlank { "Brightness change failed." }, errorCode = "brightness_write_failed")
        }
        val afterRaw = currentBrightness()
            ?: return response(false, "Brightness changed but verification failed.", before = "$before%", errorCode = "brightness_verify_failed")
        val after = (afterRaw * 100f / 255f).roundToInt().coerceIn(0, 100)
        return response(
            success = kotlin.math.abs(after - normalized) <= 2,
            summary = "Screen brightness is $after%.",
            before = "$before%",
            after = "$after%",
            reversible = true,
            errorCode = "brightness_verify_failed".takeIf { kotlin.math.abs(after - normalized) > 2 }
        )
    }

    override fun setMediaVolume(percent: Int): String {
        val normalized = percent.coerceIn(0, 100)
        val before = currentMediaVolume()
            ?: return response(false, "Current media volume could not be read.", errorCode = "volume_unavailable")
        val target = (normalized * before.max / 100f).roundToInt().coerceIn(0, before.max)
        val command = runCommand(
            "cmd", "media_session", "volume", "--stream", "3", "--set", target.toString()
        )
        if (!command.success) {
            return response(false, command.output.ifBlank { "Media volume change failed." }, errorCode = "volume_write_failed")
        }
        val after = currentMediaVolume()
            ?: return response(false, "Media volume changed but verification failed.", errorCode = "volume_verify_failed")
        val beforePercent = (before.value * 100f / before.max.coerceAtLeast(1)).roundToInt()
        val afterPercent = (after.value * 100f / after.max.coerceAtLeast(1)).roundToInt()
        return response(
            success = after.value == target,
            summary = "Media volume is $afterPercent%.",
            before = "$beforePercent%",
            after = "$afterPercent%",
            reversible = true,
            errorCode = "volume_verify_failed".takeIf { after.value != target }
        )
    }

    override fun forceStopPackage(packageName: String): String {
        val normalized = packageName.trim()
        if (!PackageNamePattern.matches(normalized) || normalized == FlowChatPackageName) {
            return response(false, "The requested package is not allowed.", errorCode = "invalid_package")
        }
        val result = runCommand("am", "force-stop", "--user", "current", normalized)
        return if (result.success) {
            response(true, "Force stopped package $normalized.")
        } else {
            response(false, result.output.ifBlank { "Force stop failed." }, errorCode = "force_stop_failed")
        }
    }

    override fun enablePowerMode(): String {
        val usageAccess = runCommand(
            "cmd", "appops", "set", FlowChatPackageName, "GET_USAGE_STATS", "allow"
        )
        val overlayAccess = runCommand(
            "cmd", "appops", "set", FlowChatPackageName, "SYSTEM_ALERT_WINDOW", "allow"
        )
        val existingServices = runCommand(
            "settings", "get", "secure", "enabled_accessibility_services"
        )
        if (!existingServices.success) {
            return response(false, "Accessibility settings could not be read.", errorCode = "power_mode_partial_failure")
        }
        val enabledServices = existingServices.output
            .takeUnless { it == "null" }
            .orEmpty()
            .split(':')
            .filter { it.isNotBlank() }
            .toMutableSet()
            .apply { add(FlowChatAccessibilityServiceName) }
            .joinToString(":")
        val accessibilityService = runCommand(
            "settings",
            "put",
            "secure",
            "enabled_accessibility_services",
            enabledServices
        )
        val accessibilityMaster = runCommand(
            "settings", "put", "secure", "accessibility_enabled", "1"
        )
        val failures = buildList {
            if (!usageAccess.success) add("usage access")
            if (!overlayAccess.success) add("floating window")
            if (!accessibilityService.success || !accessibilityMaster.success) add("page control")
        }
        return if (failures.isEmpty()) {
            response(true, "Power mode permissions are enabled.")
        } else {
            response(
                false,
                "Power mode could not enable: ${failures.joinToString()}.",
                errorCode = "power_mode_partial_failure"
            )
        }
    }

    private fun currentBrightness(): Int? =
        runCommand("settings", "get", "system", "screen_brightness")
            .takeIf { it.success }
            ?.output
            ?.trim()
            ?.toIntOrNull()

    private fun currentMediaVolume(): MediaVolume? {
        val output = runCommand("cmd", "media_session", "volume", "--stream", "3", "--get")
            .takeIf { it.success }
            ?.output
            ?: return null
        val match = Regex("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)]").find(output) ?: return null
        return MediaVolume(
            value = match.groupValues[1].toInt(),
            max = match.groupValues[3].toInt()
        )
    }

    private fun foregroundPackage(): String? {
        val activities = runCommand("dumpsys", "activity", "activities").output
        return Regex("mResumedActivity:.*? ([A-Za-z0-9._]+)/").find(activities)?.groupValues?.get(1)
            ?: Regex("topResumedActivity=.*? ([A-Za-z0-9._]+)/").find(activities)?.groupValues?.get(1)
            ?: runCommand("dumpsys", "window", "windows").output.let { windows ->
                Regex("mCurrentFocus=.*? ([A-Za-z0-9._]+)/").find(windows)?.groupValues?.get(1)
            }
    }

    private fun runCommand(vararg command: String): DeviceCommandResult =
        commandExecutor.execute(command.toList())

    private fun response(
        success: Boolean,
        summary: String,
        before: String? = null,
        after: String? = null,
        reversible: Boolean = false,
        errorCode: String? = null
    ): String = JSONObject().apply {
        put("success", success)
        put("summary", summary)
        before?.let { put("before", it) }
        after?.let { put("after", it) }
        put("reversible", reversible)
        errorCode?.let { put("errorCode", it) }
    }.toString()

    private data class MediaVolume(val value: Int, val max: Int)

    private companion object {
        const val FlowChatPackageName = BuildConfig.APPLICATION_ID
        const val FlowChatAccessibilityServiceName =
            "$FlowChatPackageName/com.flowchat.app.data.device.FlowChatAccessibilityService"
        val PackageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
