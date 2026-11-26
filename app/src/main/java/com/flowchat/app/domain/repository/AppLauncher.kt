package com.flowchat.app.domain.repository

interface AppLauncher {
    suspend fun openApp(appName: String): String

    /** Returns a uniquely launchable label or package explicitly named by the user. */
    suspend fun resolveQuotedApp(instruction: String): String? = null
}
