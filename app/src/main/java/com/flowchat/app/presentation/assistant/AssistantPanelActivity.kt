package com.flowchat.app.presentation.assistant

import android.Manifest
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Fallback for ACTION_ASSIST, quick settings, and OEMs that don't expose a voice session gesture. */
@AndroidEntryPoint
class AssistantPanelActivity : ComponentActivity() {
    @Inject lateinit var panels: AssistantPanelFactory
    @Inject lateinit var bridge: FloatingAssistantBridge
    @Inject lateinit var deviceAssistantGateway: DeviceAssistantGateway
    private var presenter: AssistantPanelPresenter? = null
    private var panelLease: java.io.Closeable? = null
    private var panel: AssistantPanelView? = null
    private var accessibilityReconnect: Job? = null
    private var microphoneResult: ((Boolean) -> Unit)? = null
    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = microphoneResult
        microphoneResult = null
        callback?.invoke(granted)
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrapContext(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { finish(); return }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val controller = panels.create().also { presenter = it }
        savedInstanceState?.getString("input")?.let(controller::updateInput)
        val view = AssistantPanelView(this, controller, ::finish, {
            controller.selectForFullChat()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }, ::finish, ::requestVoicePermission, followUpWindowMs = AssistantVoiceCapture.FOLLOW_UP_TIMEOUT_MS,
            offlineVoicePack = XasrVoicePack.forDevice(this)).also { panel = it }
        setContentView(view)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { controller.state.collect(view::render) }
                launch {
                    combine(bridge.uiInteractionCount, bridge.screenCaptureCount, bridge.displayCaptureCount) { a, b, c -> a + b + c > 0 }
                        .collect { if (it) finish() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        panelLease = bridge.showAssistantPanel()
        deviceAssistantGateway.refreshConnection()
        deviceAssistantGateway.refreshAccessibilityConnection()
        accessibilityReconnect?.cancel()
        accessibilityReconnect = lifecycleScope.launch { deviceAssistantGateway.reconnectAccessibilityAfterStop() }
    }
    override fun onResume() { super.onResume(); panel?.refreshVoicePermissionState() }
    override fun onStop() {
        accessibilityReconnect?.cancel()
        accessibilityReconnect = null
        microphoneResult = null
        panel?.cancelVoiceDownload()
        panel?.cancelRecording()
        panelLease?.close()
        panelLease = null
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("input", presenter?.state?.value?.input)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        panel = null
        presenter?.close()
        presenter = null
        super.onDestroy()
    }

    private fun requestVoicePermission(granted: () -> Unit, denied: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            granted()
            return
        }
        val prefs = getSharedPreferences("assistant_entry", Context.MODE_PRIVATE)
        val acknowledged = prefs.getBoolean("voice_setup_acknowledged", false)
        val requestedBefore = prefs.getBoolean("voice_permission_requested", false)
        val launchRequest = {
            prefs.edit().putBoolean("voice_permission_requested", true).apply()
            microphoneResult = { allowed -> if (allowed) granted() else denied() }
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
        when {
            !acknowledged -> AlertDialog.Builder(this)
                .setTitle(R.string.assistant_voice_start)
                .setMessage(R.string.assistant_voice_setup)
                .setNegativeButton(android.R.string.cancel) { _, _ -> denied() }
                .setPositiveButton(R.string.assistant_approve) { _, _ ->
                    prefs.edit().putBoolean("voice_setup_acknowledged", true).apply()
                    launchRequest()
                }
                .show()
            !requestedBefore || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) ->
                launchRequest()
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.assistant_voice_permission_settings_title)
                .setMessage(R.string.assistant_voice_permission_settings_message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> denied() }
                .setPositiveButton(R.string.assistant_open_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                    denied()
                }
                .show()
        }
    }
}
