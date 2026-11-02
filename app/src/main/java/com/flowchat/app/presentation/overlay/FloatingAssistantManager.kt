package com.flowchat.app.presentation.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.flowchat.app.data.preferences.AppSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FloatingAssistantManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val bridge: FloatingAssistantBridge
) {
    fun syncEnabled() {
        val enabled = settingsStore.state.value.powerModeEnabled || settingsStore.state.value.photoAnswerEnabled
        if (enabled && FloatingAssistantPermission.isGranted(context)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingAssistantService::class.java)
            )
        } else if (!enabled) {
            context.stopService(Intent(context, FloatingAssistantService::class.java))
        }
    }

    fun onAppForeground() {
        bridge.setAppInForeground(true)
        syncEnabled()
    }

    fun onAppBackground() {
        bridge.setAppInForeground(false)
    }
}

object FloatingAssistantPermission {
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
}
