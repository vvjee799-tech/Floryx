package com.flowchat.app.presentation.sharing

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.sharing.SharedTextStageResult
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.chat.titleResource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** External shares open a preview. Only the visible summary action submits from here. */
@AndroidEntryPoint
class SharedTextActivity : ComponentActivity() {
    private val model: SharedTextPreviewModel by viewModels()
    private var renderedChoices: List<SharedTextChoice> = emptyList()
    private var rendering = false
    private var lastSummaryMarkdown: String? = null
    private var lastSummarySpanned: Spanned? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrapContext(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Even an editable PROCESS_TEXT caller must never replace its selection on our return.
        // This applies to cancellation, invalid input, successful draft transfer and recreation.
        setResult(RESULT_CANCELED)
        val selectedText = intent.action == Intent.ACTION_PROCESS_TEXT
        if (isLocked()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model.initialize(intent, callingPackage, savedInstanceState != null)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF9F7F2.toInt())
        }
        fun label(resource: Int, size: Float = 15f) = TextView(this).apply {
            setText(resource); textSize = size; setTextColor(0xFF38352F.toInt()); setPadding(0, 12, 0, 12)
        }.also { root.addView(it) }
        val shareTitle = if (selectedText) R.string.process_text_title else R.string.share_text_title
        val title = label(shareTitle, 24f)
        val source = label(R.string.share_text_source_unknown).apply { id = R.id.share_text_source }
        val configuration = label(R.string.assistant_choose_configuration).apply { visibility = View.GONE }
        val status = label(R.string.assistant_preparing).apply { visibility = View.GONE }
        val summarize = Button(this).apply {
            id = R.id.share_summary_action
            setText(R.string.share_summary_action)
            isAllCaps = false
            filterTouchesWhenObscured = true
            visibility = View.GONE
            setOnClickListener {
                if (isLocked()) { finish(); return@setOnClickListener }
                model.summarize(getString(R.string.share_summary_title), getString(R.string.share_summary_attachment))
            }
        }
        root.addView(summarize, LinearLayout.LayoutParams(-1, -2))
        val preview = TextView(this).apply {
            id = R.id.share_text_preview; textSize = 16f; setTextIsSelectable(true); setTextColor(0xFF38352F.toInt())
            isSaveEnabled = false
        }
        root.addView(ScrollView(this).apply { addView(preview) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val notice = label(if (selectedText) R.string.process_text_notice else R.string.share_text_notice)
        val destinationLabel = label(R.string.share_text_destination)
        val choices = Spinner(this).apply {
            id = R.id.share_text_destination
            isSaveEnabled = false // Do not restore a destination independently of the in-memory payload.
            contentDescription = getString(R.string.share_text_destination)
        }
        root.addView(choices, LinearLayout.LayoutParams(-1, -2))
        val error = label(R.string.share_text_invalid).apply { id = R.id.share_text_error; visibility = View.GONE }
        val add = Button(this).apply {
            id = R.id.share_text_add; setText(R.string.share_text_add)
            setOnClickListener {
                if (isLocked()) { finish(); return@setOnClickListener }
                model.add {
                    startActivity(Intent(this@SharedTextActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
        root.addView(add, LinearLayout.LayoutParams(-1, -2))
        val stop = Button(this).apply {
            id = R.id.share_summary_stop
            setText(R.string.stop)
            visibility = View.GONE
            setOnClickListener { model.stopSummary() }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))
        val openChat = Button(this).apply {
            id = R.id.share_summary_open_chat
            setText(R.string.assistant_full_chat)
            visibility = View.GONE
            setOnClickListener {
                model.selectSummaryChat()
                startActivity(Intent(this@SharedTextActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
        root.addView(openChat, LinearLayout.LayoutParams(-1, -2))
        val cancel = Button(this).apply {
            id = R.id.share_text_cancel; setText(R.string.cancel); setOnClickListener { finish() }
        }
        root.addView(cancel, LinearLayout.LayoutParams(-1, -2))
        choices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!rendering) model.select(renderedChoices.getOrNull(position - 1)?.destination)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val padding = (20 * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom + padding)
            insets
        }
        setContentView(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    rendering = true
                    val summary = state.summary
                    val draftControls = if (summary == null) View.VISIBLE else View.GONE
                    notice.visibility = draftControls
                    destinationLabel.visibility = draftControls
                    choices.visibility = draftControls
                    add.visibility = draftControls
                    title.setText(if (summary == null) shareTitle else R.string.share_summary_title)
                    configuration.visibility = if (summary == null) View.GONE else View.VISIBLE
                    configuration.text = summary?.configuration
                    status.visibility = if (summary?.status?.showStatus == true) View.VISIBLE else View.GONE
                    summary?.let { status.setText(it.status.titleResource()) }
                    stop.visibility = if (summary?.running == true) View.VISIBLE else View.GONE
                    openChat.visibility = if (summary == null) View.GONE else View.VISIBLE
                    cancel.setText(if (summary == null) R.string.cancel else R.string.assistant_close)
                    summarize.visibility = if (state.offerSummary) View.VISIBLE else View.GONE
                    summarize.setText(if (state.startingSummary) R.string.assistant_preparing else R.string.share_summary_action)
                    summarize.isEnabled = !state.busy
                    if (summary != null) {
                        if (summary.output != lastSummaryMarkdown || lastSummarySpanned == null) {
                            lastSummaryMarkdown = summary.output
                            lastSummarySpanned = MarkdownSpannedRenderer.render(
                                source = summary.output,
                                codeBackgroundColor = 0xFFEDEAE2.toInt(),
                                linkColor = 0xFF2D604F.toInt(),
                                quoteStripeColor = 0xFFB8B2A6.toInt()
                            )
                            preview.text = lastSummarySpanned
                        }
                    } else {
                        lastSummaryMarkdown = null
                        lastSummarySpanned = null
                        preview.text = state.payload?.text.orEmpty()
                    }
                    source.text = (summary?.sourcePackage ?: state.payload?.sourcePackage)?.let { getString(R.string.share_text_source, it) }
                        ?: getString(R.string.share_text_source_unknown)
                    if (renderedChoices != state.choices || choices.adapter == null) {
                        renderedChoices = state.choices
                        choices.adapter = ArrayAdapter(this@SharedTextActivity, android.R.layout.simple_spinner_dropdown_item,
                            listOf(getString(R.string.share_text_choose)) + renderedChoices.map {
                                "${it.title} · ${it.destination.provider.displayName} · ${it.destination.modelName}"
                            })
                    }
                    choices.setSelection(renderedChoices.indexOfFirst { it.destination == state.selected } + 1)
                    choices.isEnabled = !state.busy && !state.invalid
                    add.isEnabled = !state.busy && !state.invalid && state.payload != null &&
                        renderedChoices.any { it.destination == state.selected }
                    val errorResource = when {
                        state.invalid -> if (selectedText) R.string.process_text_invalid else R.string.share_text_invalid
                        state.summaryError != null -> state.summaryError
                        state.result == SharedTextStageResult.Expired -> R.string.share_text_expired
                        state.result == SharedTextStageResult.DestinationChanged -> R.string.share_text_changed
                        state.result == SharedTextStageResult.Busy -> R.string.share_text_busy
                        state.result == SharedTextStageResult.AttachmentExists -> R.string.share_text_attachment_exists
                        state.result == SharedTextStageResult.AlreadyAdded -> R.string.share_text_already_added
                        selectedText && state.choices.isEmpty() -> R.string.share_text_no_chats
                        else -> null
                    }
                    error.text = errorResource?.let(::getString) ?: summary?.error?.takeIf { summary.output.isBlank() }
                    error.visibility = if (error.text.isNullOrBlank()) View.GONE else View.VISIBLE
                    rendering = false
                }
            }
        }
    }

    private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    override fun onResume() { super.onResume(); if (isLocked()) finish() }
}
