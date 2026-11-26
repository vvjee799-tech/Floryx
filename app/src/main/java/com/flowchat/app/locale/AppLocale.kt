package com.flowchat.app.locale

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

object AppLocale {
    private const val PREFS_NAME = "flowchat_settings"
    private const val KEY_LANGUAGE = "language"

    fun getLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppLanguage.fromStorageValue(prefs.getString(KEY_LANGUAGE, AppLanguage.System.storageValue))
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.storageValue)
            .apply()
    }

    fun wrapContext(context: Context): ContextWrapper {
        val language = getLanguage(context)
        val languageTag = language.languageTag ?: return ContextWrapper(context)
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return ContextWrapper(context.createConfigurationContext(configuration))
    }
}
