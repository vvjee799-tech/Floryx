package com.flowchat.app.presentation

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.flowchat.app.presentation.common.findActivity

/** A window-local hint while animating; system power and display policies still apply. */
@Composable
internal fun SplashRefreshRate(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        var previousRate: Float? = null

        fun requestRate() {
            if (!enabled || window == null || previousRate != null) return
            val display = view.display ?: return
            val currentMode = display.mode
            val rate = display.supportedModes
                .asSequence()
                .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
                .maxOfOrNull { it.refreshRate } ?: currentMode.refreshRate
            previousRate = window.attributes.preferredRefreshRate
            window.attributes = window.attributes.apply { preferredRefreshRate = rate }
        }

        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = requestRate()
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        if (enabled && window != null) {
            view.addOnAttachStateChangeListener(listener)
            if (view.isAttachedToWindow) requestRate()
        }
        onDispose {
            view.removeOnAttachStateChangeListener(listener)
            previousRate?.let { rate ->
                window?.let { it.attributes = it.attributes.apply { preferredRefreshRate = rate } }
            }
        }
    }
}
