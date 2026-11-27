package com.flowchat.app.data.applauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.UnavailableDeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.data.device.FlowChatAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class AndroidAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceGateway: DeviceAssistantGateway = UnavailableDeviceAssistantGateway,
    private val taskControl: DeviceTaskControl = DeviceTaskControl()
) : AppLauncher {
    override suspend fun resolveQuotedApp(instruction: String): String? {
        val name = quotedAppName(instruction) ?: return null
        return withContext(Dispatchers.IO) {
            name.takeIf { matchesExactLaunchableApp(context.packageManager.launchableApps(), it) }
        }
    }

    override suspend fun openApp(appName: String): String {
        currentCoroutineContext().ensureActive()
        deviceGateway.checkExecutionTarget()
        val task = taskControl.currentTask
        taskControl.checkCanAct(task)
        val app = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            selectLaunchableApp(manager.launchableApps(), appName,
                if (isGenericBrowserQuery(appName)) manager.defaultBrowserPackage() else null)
        }
        if (app.packageName == context.packageName) {
            throw IllegalArgumentException(
                "Cannot open Floryx itself during phone automation. Returning to Floryx pauses the task and returns control to the user."
            )
        }
        currentCoroutineContext().ensureActive()
        taskControl.checkCanAct(task)
        deviceGateway.checkExecutionTarget()
        val background = currentCoroutineContext()[DeviceExecutionTarget]?.backgroundExecution ?: deviceGateway.backgroundExecutionRequested
        if (background || deviceGateway.connectionState.value.isConnected) {
            val result = deviceGateway.launchApp(app.packageName, app.activityName)
            check(result.success) { result.summary }
            return app.label
        }
        withContext(Dispatchers.Main) {
            currentCoroutineContext().ensureActive()
            taskControl.checkCanAct(task)
            deviceGateway.checkExecutionTarget()
            (FlowChatAccessibilityService.current() ?: context).startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.packageName, app.activityName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        // startActivity returning normally does not prove OEM background-launch checks allowed it.
        FlowChatAccessibilityService.current()?.let { service ->
            repeat(10) {
                delay(200)
                if (service.observeScreen()?.packageName == app.packageName) return app.label
            }
            error("Android did not bring ${app.label} to the foreground. Check background launch permission or connect Root/Shizuku.")
        }
        return app.label
    }
}

internal data class LaunchableApp(
    val label: String,
    val packageName: String,
    val activityName: String
)

private val quotedAppLaunch = Regex(
    """^\s*(?:(?:请\s*)?(?:帮我\s*)?(?:打开|启动)\s*|(?:please\s+)?(?:open|launch)\s+)""" +
        """(?:「([^」\r\n]{1,160})」|『([^』\r\n]{1,160})』|“([^”\r\n]{1,160})”|"([^"\r\n]{1,160})")""",
    RegexOption.IGNORE_CASE
)

internal fun quotedAppName(instruction: String): String? =
    quotedAppLaunch.find(instruction)?.groupValues?.drop(1)?.firstNotNullOfOrNull { value ->
        value.trim().takeIf { it.isNotEmpty() }
    }

internal fun matchesExactLaunchableApp(apps: List<LaunchableApp>, name: String): Boolean {
    val app = try {
        selectLaunchableApp(apps, name)
    } catch (_: IllegalArgumentException) {
        return false
    }
    return name.equals(app.label, ignoreCase = true) || name.equals(app.packageName, ignoreCase = true)
}

internal fun selectLaunchableApp(apps: List<LaunchableApp>, query: String, defaultBrowserPackage: String? = null): LaunchableApp {
    val key = query.normalizedAppKey()
    require(key.isNotEmpty()) { "App name is required." }

    if (isGenericBrowserQuery(query)) return apps.singleOrNull { it.packageName == defaultBrowserPackage }
        ?: throw IllegalArgumentException("Choose a default browser in Android settings or name the intended browser explicitly.")

    val exactMatches = apps.filter { app ->
        app.label.normalizedAppKey() == key || app.packageName.normalizedAppKey() == key
    }
    if (exactMatches.size == 1) return exactMatches.single()
    if (exactMatches.size > 1) throw ambiguousAppMatch(query, exactMatches)

    val partialMatches = apps.filter { app ->
        val label = app.label.normalizedAppKey()
        val packageName = app.packageName.normalizedAppKey()
        label.contains(key) || key.contains(label) || packageName.contains(key)
    }
    return when (partialMatches.size) {
        1 -> partialMatches.single()
        0 -> throw IllegalArgumentException("No launchable app matches \"$query\".")
        else -> throw ambiguousAppMatch(query, partialMatches)
    }
}

internal fun isGenericBrowserQuery(query: String) = query.normalizedAppKey() in setOf("浏览器", "browser", "webbrowser", "internetbrowser")

private fun PackageManager.defaultBrowserPackage(): String? {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE)
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return info?.activityInfo?.packageName?.takeUnless { it == "android" }
}

private fun PackageManager.launchableApps(): List<LaunchableApp> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(launcherIntent, 0)
    }
    return resolved.mapNotNull { info ->
        val activity = info.activityInfo ?: return@mapNotNull null
        LaunchableApp(
            label = info.loadLabel(this).toString().ifBlank { activity.packageName },
            packageName = activity.packageName,
            activityName = activity.name
        )
    }.distinctBy { it.packageName }
}

private fun String.normalizedAppKey(): String =
    lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private fun ambiguousAppMatch(query: String, matches: List<LaunchableApp>): IllegalArgumentException =
    IllegalArgumentException(
        "Multiple launchable apps match \"$query\": ${matches.take(5).joinToString { it.label }}."
    )
