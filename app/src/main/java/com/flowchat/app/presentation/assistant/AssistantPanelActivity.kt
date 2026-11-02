package com.flowchat.app.presentation.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.flowchat.app.MainActivity
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.overlay.FloatingAssistantBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Fallback for ACTION_ASSIST, quick settings, and OEMs that don't expose a voice session gesture. */
@AndroidEntryPoint
class AssistantPanelActivity : ComponentActivity() {
    @Inject lateinit var panels: AssistantPanelFactory
    @Inject lateinit var bridge: FloatingAssistantBridge
    private var presenter: AssistantPanelPresenter? = null
    private var panelLease: java.io.Closeable? = null

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
        }, ::finish)
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

    override fun onStart() { super.onStart(); panelLease = bridge.showAssistantPanel() }
    override fun onStop() { panelLease?.close(); panelLease = null; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("input", presenter?.state?.value?.input)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { presenter?.close(); presenter = null; super.onDestroy() }
}
