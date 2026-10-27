package com.flowchat.app.ui.theme

import android.content.Context
import android.app.UiModeManager
import android.os.Build

enum class AppAppearance(val storageValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun load(context: Context): AppAppearance {
            val value = context.applicationContext
                .getSharedPreferences(AppAppearancePrefsName, Context.MODE_PRIVATE)
                .getString(AppAppearanceKey, Dark.storageValue)
            return entries.firstOrNull { it.storageValue == value } ?: Dark
        }

        fun save(context: Context, appearance: AppAppearance) {
            context.applicationContext
                .getSharedPreferences(AppAppearancePrefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(AppAppearanceKey, appearance.storageValue)
                .apply()
            syncStartingTheme(context, appearance)
        }

        /** Keep Android 12's persisted starting-window theme aligned with the Compose theme. */
        fun syncStartingTheme(context: Context, appearance: AppAppearance = load(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                    when (appearance) {
                        System -> UiModeManager.MODE_NIGHT_AUTO
                        Light -> UiModeManager.MODE_NIGHT_NO
                        Dark -> UiModeManager.MODE_NIGHT_YES
                    }
                )
            }
        }
    }
}

private const val AppAppearancePrefsName = "app_appearance"
private const val AppAppearanceKey = "appearance"
