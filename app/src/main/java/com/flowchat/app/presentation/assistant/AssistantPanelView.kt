package com.flowchat.app.presentation.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import com.flowchat.app.R

/** Shared, small native view for the system session and the ACTION_ASSIST/tile fallback. */
internal class AssistantPanelView(
    context: Context,
    private val presenter: AssistantPanelPresenter,
    private val onClose: () -> Unit,
    onFullChat: () -> Unit,
    private val onAccepted: () -> Unit
) : FrameLayout(context) {
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink = if (dark) 0xFFE8E4D9.toInt() else 0xFF38352F.toInt()
    private val muted = if (dark) 0xFFB5AF9F.toInt() else 0xFF716D62.toInt()
    private val accent = if (dark) 0xFFD8CFAF.toInt() else 0xFF645E4C.toInt()
    private val status = label(13f).apply { id = R.id.assistant_status; setTextColor(muted) }
    private val output = label(15f).apply { id = R.id.assistant_output; setTextIsSelectable(true) }
    private val outputScroll = ScrollView(context).apply { addView(output) }
    private val error = label(13f).apply { setTextColor(if (dark) 0xFFFFB4ABL.toInt() else 0xFFB3261E.toInt()) }
    private val pageToggle = CheckBox(context).apply { id = R.id.assistant_page_toggle; setTextColor(ink); textSize = 13f }
    private val pagePreview = label(13f).apply { setTextIsSelectable(true) }
    private val pagePreviewScroll = ScrollView(context).apply { addView(pagePreview); visibility = View.GONE }
    private var displayedPage: AssistPageContext? = null
    private val preview = button(R.string.assistant_page_preview) {
        pagePreviewScroll.visibility = if (pagePreviewScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
    private val pageRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(pageToggle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)); addView(preview)
    }
    private val input = EditText(context).apply {
        id = R.id.assistant_input
        hint = context.getString(R.string.assistant_input_hint)
        setTextColor(ink); setHintTextColor(muted); textSize = 15f
        minLines = 3; maxLines = 5
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(if (dark) 0xFF2C2B25.toInt() else 0xFFFDFCF9.toInt(), 12f)
        doAfterTextChanged { if (it.toString() != presenter.state.value.input) presenter.updateInput(it.toString()) }
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { presenter.send(onAccepted); true } else false
        }
    }
    private val send = button(R.string.send) { presenter.send(onAccepted) }.apply {
        id = R.id.assistant_send
        setTextColor(if (dark) 0xFF383529.toInt() else Color.WHITE)
        background = rounded(accent, 12f)
    }
    private val stop = button(R.string.stop) { presenter.stop() }.apply { id = R.id.assistant_stop }
    private val confirmation = label(14f)
    private val confirmationActions = LinearLayout(context).apply {
        addView(button(R.string.assistant_decline) { presenter.confirm(false) })
        addView(button(R.string.assistant_approve) { presenter.confirm(true); onClose() })
    }

    init {
        id = R.id.assistant_panel_root
        setBackgroundColor(0x22000000)
        setOnClickListener { onClose() }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(24), dp(12), dp(24), dp(16))
            background = rounded(if (dark) 0xFF24231F.toInt() else 0xFFF9F7F2.toInt(), 24f)
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(26f).apply { setText(R.string.app_name); typeface = Typeface.create("serif", Typeface.NORMAL); setTextColor(if (dark) accent else 0xFF2D604F.toInt()) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button(R.string.assistant_close) { onClose() }.apply { id = R.id.assistant_close })
        }
        panel.addView(header); panel.addView(status)
        panel.addView(outputScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(150)).apply { bottomMargin = dp(8) })
        panel.addView(error); panel.addView(confirmation); panel.addView(confirmationActions)
                panel.addView(pagePreviewScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(120)))
        panel.addView(input)
        panel.addView(pageRow)
        panel.addView(LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(button(R.string.assistant_full_chat) { onFullChat() }.apply {
                id = R.id.assistant_full_chat; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(stop); addView(send)
        })
        val maxWidth = minOf(resources.displayMetrics.widthPixels, dp(560))
        val scroller = ScrollView(context).apply { addView(panel) }
        addView(scroller, LayoutParams(maxWidth, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        pageToggle.setOnCheckedChangeListener { _, checked -> presenter.includePage(checked) }
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }

    fun render(state: AssistantPanelState) {
        if (input.text.toString() != state.input) { input.setText(state.input); input.setSelection(input.length()) }
        send.isEnabled = state.ready && !state.running && !state.submitting && state.input.isNotBlank()
        input.isEnabled = !state.submitting
        stop.visibility = if (state.running || state.paused) View.VISIBLE else View.GONE
        val phase = context.getString(when {
            !state.ready || state.submitting -> R.string.assistant_preparing
            state.confirmation != null -> R.string.assistant_waiting
            state.paused -> R.string.assistant_paused
            state.running -> R.string.assistant_running
            state.output.isNotBlank() -> R.string.assistant_finished
            else -> R.string.assistant_ready
        })
        status.text = phase
        status.visibility = if (state.ready && !state.running && !state.submitting && !state.paused && state.confirmation == null) View.GONE else View.VISIBLE
        output.text = state.output
        outputScroll.visibility = if (state.output.isBlank()) View.GONE else View.VISIBLE
        error.text = state.error
        error.visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
        pageRow.visibility = if (state.page == null) View.GONE else View.VISIBLE
        if (displayedPage !== state.page) { pagePreviewScroll.visibility = View.GONE; displayedPage = state.page }
        pagePreview.text = state.page?.text
        pageToggle.text = context.getString(R.string.assistant_include_page)
        pageToggle.isChecked = state.includePage
        confirmation.text = state.confirmation?.let { "${it.title}\n${it.message}" }
        confirmation.visibility = if (state.confirmation == null) View.GONE else View.VISIBLE
        confirmationActions.visibility = confirmation.visibility
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun label(size: Float) = TextView(context).apply { setTextColor(ink); textSize = size; setPadding(0, dp(5), 0, dp(5)) }
    private fun button(text: Int, action: () -> Unit) = Button(context).apply {
        setText(text); isAllCaps = false; textSize = 14f; minWidth = dp(64); minimumWidth = dp(64); minHeight = dp(48)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(accent, (muted and 0x00FFFFFF) or 0x77000000)))
        stateListAnimator = null; elevation = 0f; backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x22000000),
            rounded(Color.TRANSPARENT, 12f), rounded(Color.WHITE, 12f))
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = resources.displayMetrics.density * radius
    }
}
