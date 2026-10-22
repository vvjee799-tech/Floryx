package com.flowchat.app.data.applauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.flowchat.app.domain.repository.AppLauncher
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.domain.device.UnavailableDeviceAssistantGateway
import com.flowchat.app.domain.device.DeviceTaskControl
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
    override suspend fun openApp(appName: String): String {
        currentCoroutineContext().ensureActive()
        val task = taskControl.currentTask
        taskControl.checkCanAct(task)
        val app = withContext(Dispatchers.IO) {
            selectLaunchableApp(context.packageManager.launchableApps(), appName)
        }
        currentCoroutineContext().ensureActive()
        taskControl.checkCanAct(task)
        if (deviceGateway.connectionState.value.isConnected) {
            val result = deviceGateway.launchApp(app.packageName, app.activityName)
            check(result.success) { result.summary }
            return app.label
        }
        withContext(Dispatchers.Main) {
            currentCoroutineContext().ensureActive()
            taskControl.checkCanAct(task)
            context.startActivity(
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

internal fun selectLaunchableApp(apps: List<LaunchableApp>, query: String): LaunchableApp {
    val key = query.normalizedAppKey()
    require(key.isNotEmpty()) { "App name is required." }

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
