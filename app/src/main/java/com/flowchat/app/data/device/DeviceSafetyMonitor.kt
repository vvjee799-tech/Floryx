package com.flowchat.app.data.device

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.device.DeviceSafetyBoundary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Screen broadcasts latch a boundary even when the screen is restored before a model returns. */
@Singleton
class DeviceSafetyMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    val boundary: DeviceSafetyBoundary
) {
    val state get() = boundary.state
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Readings may already say awake again when a queued SCREEN_OFF arrives.
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                boundary.updateScreen(state.value.screenLocked, false, System.currentTimeMillis())
            }
            refresh()
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() = boundary.updateScreen(
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }.getOrNull(),
        runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull(),
        System.currentTimeMillis()
    )

    fun check(target: DeviceExecutionTarget? = null) {
        refresh()
        target?.let { boundary.requireCurrent(it.safetyRevision) }
        boundary.requireScreenAvailable()
    }
}
