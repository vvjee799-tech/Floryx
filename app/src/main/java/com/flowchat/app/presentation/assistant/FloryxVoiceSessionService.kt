package com.flowchat.app.presentation.assistant

import android.Manifest
import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.device.DeviceAssistantGateway
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Main-process session deliberately shares the application's single task runtime. */
@AndroidEntryPoint
class FloryxVoiceSessionService : VoiceInteractionSessionService() {
    @Inject lateinit var panels: AssistantPanelFactory
    @Inject lateinit var bridge: FloatingAssistantBridge
    @Inject lateinit var deviceAssistantGateway: DeviceAssistantGateway
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        FloryxVoiceSession(AppLocale.wrapContext(this), panels, bridge, deviceAssistantGateway)
}

internal class FloryxVoiceSession(
    private val context: Context,
    private val panels: AssistantPanelFactory,
    private val bridge: FloatingAssistantBridge,
    private val deviceAssistantGateway: DeviceAssistantGateway
) : VoiceInteractionSession(context) {
    private var presenter: AssistantPanelPresenter? = null
    private var visibleScope: CoroutineScope? = null
    private var pageJob: Job? = null
    private var panelLease: java.io.Closeable? = null
    private var panel: AssistantPanelView? = null
    init { setTheme(R.style.Theme_FlowChat_Assistant) }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        clearPanel()
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { finish(); return }
        val controller = panels.create().also { presenter = it }
        panelLease = bridge.showAssistantPanel()
        val view = AssistantPanelView(context, controller, ::finish, {
            controller.selectForFullChat()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }, ::finish, ::requestVoicePermission, followUpWindowMs = AssistantVoiceCapture.FOLLOW_UP_TIMEOUT_MS,
            offlineVoicePack = XasrVoicePack.forDevice(context)).also { panel = it }
        setContentView(view)
        visibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            deviceAssistantGateway.refreshConnection()
            deviceAssistantGateway.refreshAccessibilityConnection()
            scope.launch { deviceAssistantGateway.reconnectAccessibilityAfterStop() }
            scope.launch { controller.state.collect(view::render) }
            scope.launch {
                combine(bridge.uiInteractionCount, bridge.screenCaptureCount, bridge.displayCaptureCount) { a, b, c -> a + b + c > 0 }
                    .collect { if (it) finish() }
            }
        }
    }

    @Deprecated("Primary focused activity compatibility callback")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        val controller = presenter ?: return
        pageJob?.cancel()
        pageJob = visibleScope?.launch {
            val page = withTimeoutOrNull(2_000) {
                withContext(Dispatchers.IO) { runCatching { AssistPageContext.read(structure, content, context.packageName) }.getOrNull() }
            }
            if (presenter === controller) controller.updatePage(page)
        }
    }

    override fun onHide() { clearPanel(); super.onHide() }
    override fun onDestroy() { clearPanel(); super.onDestroy() }
    override fun onBackPressed() = finish()
    override fun onCloseSystemDialogs() = finish()
    private fun clearPanel() {
        panel?.cancelVoiceDownload()
        panel?.cancelRecording(); panel = null
        pageJob?.cancel(); pageJob = null
        visibleScope?.cancel(); visibleScope = null
        presenter?.close(); presenter = null
        panelLease?.close(); panelLease = null
    }

    private fun requestVoicePermission(granted: () -> Unit, denied: () -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            granted()
            return
        }
        try {
            startAssistantActivity(Intent(context, AssistantPanelActivity::class.java))
            finish()
        } catch (_: RuntimeException) {
            denied()
        }
    }
}
