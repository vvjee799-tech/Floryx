package com.flowchat.app

import android.content.Context
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.FlowChatRoot
import com.flowchat.app.presentation.SplashTransition
import com.flowchat.app.presentation.overlay.FloatingAssistantManager
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appSettingsStore: AppSettingsStore
    @Inject lateinit var floatingAssistantManager: FloatingAssistantManager
    @Inject lateinit var deviceAssistantGateway: DeviceAssistantGateway
    private var nativeSplashDismissed by mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { startingWindow ->
                startingWindow.remove()
                nativeSplashDismissed = true
            }
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appSettingsStore.state
                    .map { settings -> settings.powerModeEnabled }
                    .distinctUntilChanged()
                    .collect { floatingAssistantManager.syncEnabled() }
            }
        }
        setContent {
            var appAppearance by remember { mutableStateOf(AppAppearance.load(this)) }
            val systemDark = isSystemInDarkTheme()
            val useDarkSystemBars = when (appAppearance) {
                AppAppearance.System -> systemDark
                AppAppearance.Dark -> true
                AppAppearance.Light -> false
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !useDarkSystemBars
                    isAppearanceLightNavigationBars = !useDarkSystemBars
                }
            }
            FlowChatTheme(appAppearance = appAppearance) {
                SplashTransition(appAppearance = appAppearance, launchReady = nativeSplashDismissed) {
                    FlowChatRoot(appAppearance = appAppearance, onAppAppearanceChange = { appearance ->
                        appAppearance = appearance
                        AppAppearance.save(this, appearance)
                    })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        floatingAssistantManager.onAppForeground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Some activity-only launches have no platform SplashScreenView. Let a
        // focused app frame finish before using focus as the handoff fallback.
        if (hasFocus && !nativeSplashDismissed) {
            window.decorView.postOnAnimation {
                window.decorView.post {
                    if (window.decorView.hasWindowFocus()) nativeSplashDismissed = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        deviceAssistantGateway.refreshConnection()
        deviceAssistantGateway.refreshAccessibilityConnection()
        floatingAssistantManager.onAppForeground()
    }

    override fun onStop() {
        floatingAssistantManager.onAppBackground()
        super.onStop()
    }
}
