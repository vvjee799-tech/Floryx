package com.flowchat.app

import android.app.Application
import com.flowchat.app.ui.theme.AppAppearance
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FlowChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppAppearance.syncStartingTheme(this)
    }
}
