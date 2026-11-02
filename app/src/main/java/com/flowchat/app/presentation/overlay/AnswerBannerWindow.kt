package com.flowchat.app.presentation.overlay

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flowchat.app.R
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme

/** A real overlay window for live answer text, independent of notification permissions and DND. */
internal class AnswerBannerWindow(
    private val owner: FloatingAssistantService,
    private val onOpen: (String) -> Unit,
    private val onDismiss: () -> Unit
) {
    private val manager = owner.getSystemService(WindowManager::class.java)
    private var current by mutableStateOf<AnswerBannerState?>(null)
    private var view: ComposeView? = null
    private var attached = false

    fun render(value: AnswerBannerState?, visible: Boolean) {
        current = value
        if (!visible || value?.hasContent != true) { hide(); return }
        if (attached) return
        val content = view ?: ComposeView(owner).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setContent {
                FlowChatTheme(appAppearance = AppAppearance.load(owner)) {
                    current?.let { AnswerBanner(it, onOpen = { onOpen(it.conversationId) }, onDismiss = onDismiss) }
                }
            }
        }.also { view = it }
        val density = owner.resources.displayMetrics.density
        val width = minOf(owner.resources.displayMetrics.widthPixels - (24 * density).toInt(), (460 * density).toInt())
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * density).toInt()
            setTitle(WindowTitle)
        }
        runCatching { manager.addView(content, params) }.onSuccess { attached = true }
    }

    fun hide() {
        if (attached) view?.let { runCatching { manager.removeViewImmediate(it) } }
        attached = false
    }

    companion object { const val WindowTitle = "FloryxAnswerBanner" }
}

@Composable
internal fun AnswerBanner(value: AnswerBannerState, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(value.taskId, value.text, scroll.maxValue) {
        if (value.isStreaming) scroll.scrollTo(scroll.maxValue)
    }
    Surface(color = Color(0xFF24231F), contentColor = Color(0xFFE8E4D9), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (value.isStreaming) R.string.answer_output_status else R.string.answer_complete_status),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFD8CFAF), modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                Text(value.text, style = MaterialTheme.typography.bodyLarge, color = if (value.isError) Color(0xFFFFB4AB) else Color(0xFFE8E4D9),
                    modifier = Modifier.heightIn(max = 180.dp).verticalScroll(scroll)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = stringResource(R.string.answer_banner_open), onClick = onOpen))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.answer_banner_close), tint = Color(0xFFB5AF9F), modifier = Modifier.size(20.dp))
            }
        }
    }
}
