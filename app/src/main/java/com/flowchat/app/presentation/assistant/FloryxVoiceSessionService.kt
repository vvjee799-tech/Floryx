package com.flowchat.app.presentation.assistant

import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.flowchat.app.MainActivity
import com.flowchat.app.R
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
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        FloryxVoiceSession(AppLocale.wrapContext(this), panels, bridge)
}

internal class FloryxVoiceSession(
    private val context: Context,
    private val panels: AssistantPanelFactory,
    private val bridge: FloatingAssistantBridge
) : VoiceInteractionSession(context) {
    private var presenter: AssistantPanelPresenter? = null
    private var visibleScope: CoroutineScope? = null
    private var pageJob: Job? = null
    private var panelLease: java.io.Closeable? = null
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
        }, ::finish)
        setContentView(view)
        visibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
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
        pageJob?.cancel(); pageJob = null
        visibleScope?.cancel(); visibleScope = null
        presenter?.close(); presenter = null
        panelLease?.close(); panelLease = null
    }
}
