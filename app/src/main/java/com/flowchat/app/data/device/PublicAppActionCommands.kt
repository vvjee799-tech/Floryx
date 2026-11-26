package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.PublicAppActions

/** No arbitrary intent, package, extra or UI fallback can cross this RPC. */
internal class PublicAppActionCommands(private val executor: DeviceCommandExecutor) {
    data class Result(val success: Boolean, val code: String?, val outcome: DeviceActionOutcome)

    fun launch(tool: String, arguments: String, userId: Int): Result {
        fun rejected(code: String) = Result(false, code, DeviceActionOutcome.NotDispatched)
        val request = runCatching { PublicAppActions.parse(tool, arguments) }.getOrNull()
            ?: return rejected("invalid_public_app_action")
        if (userId != 0) return rejected("public_app_user_unsupported")
        fun read(vararg argv: String): String? = executor.execute(argv.toList()).takeIf { it.success }?.output?.trim()
        if (read("getprop", "ro.product.model") != "V2302A" || read("getprop", "ro.product.manufacturer") != "vivo" ||
            read("getprop", "ro.build.version.sdk") != "36") return rejected("public_app_device_unsupported")
        if (read("am", "get-current-user") != "$userId") return rejected("public_app_user_changed")
        val registration = request.registration
        val version = read("cmd", "package", "list", "packages", "--show-versioncode", "--user", "$userId", registration.packageName)
        if (version != "package:${registration.packageName} versionCode:${registration.versionCode}")
            return rejected("public_app_version_unsupported")
        val query = executor.execute(listOf("cmd", "package", "query-activities", "--components", "--user", "$userId") +
            PublicAppActions.intentArguments(request) + listOf("-p", registration.packageName))
        // Require exactly the one registered exported handler, even when other apps are installed.
        if (!query.success || query.output.trim() != registration.component) return rejected("public_app_entry_unavailable_or_ambiguous")
        val finalVersion = read("cmd", "package", "list", "packages", "--show-versioncode", "--user", "$userId", registration.packageName)
        if (finalVersion != version || read("am", "get-current-user") != "$userId") return rejected("public_app_target_changed")
        if (request.completion == PublicAppActions.CalendarCompletion.Save) {
            // A fresh editor is a separate document. Bring its owning app in front first, so
            // finishing it returns to the calendar rather than looking like user takeover of Floryx.
            // No existing editor/task is cleared or reused for the new event.
            val launchers = read("cmd", "package", "query-activities", "--components", "--user", "$userId",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", registration.packageName)
                ?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
            val launcher = launchers.singleOrNull()?.takeIf { it.substringBefore('/') == registration.packageName &&
                it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) }
                ?: return rejected("public_app_launcher_unavailable_or_ambiguous")
            val parent = executor.execute(listOf("am", "start", "-W", "--user", "$userId", "--display", "0",
                "-f", "0x10000000", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", launcher))
            if (!parent.success || parent.output.lineSequence().none { it.trim() == "Status: ok" })
                return Result(false, "public_app_parent_navigation_unconfirmed", DeviceActionOutcome.Unverified)
            if (read("am", "get-current-user") != "$userId" ||
                read("cmd", "package", "list", "packages", "--show-versioncode", "--user", "$userId", registration.packageName) != version)
                return Result(false, "public_app_target_changed_after_navigation", DeviceActionOutcome.Unverified)
        }
        val launched = executor.execute(PublicAppActions.launchArguments(request, userId))
        // After am starts, failure/timeout is uncertain. Never automatically fall back to UI or retry.
        val okay = launched.success && launched.output.lineSequence().any { it.trim() == "Status: ok" } &&
            launched.output.lineSequence().none { it.trim().startsWith("Error", true) || it.contains("Permission Denial", true) }
        return Result(okay, if (okay) null else "public_app_launch_unconfirmed",
            if (okay) DeviceActionOutcome.Dispatched else DeviceActionOutcome.Unverified)
    }
}
