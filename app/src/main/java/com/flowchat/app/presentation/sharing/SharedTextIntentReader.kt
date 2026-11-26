package com.flowchat.app.presentation.sharing

import android.content.Intent
import android.os.SystemClock
import com.flowchat.app.domain.sharing.SharedText

object SharedTextIntentReader {
    /** Do not read referrer/calling-package extras, HTML, streams, or URI content. */
    fun read(intent: Intent, callingPackage: String?, now: Long = SystemClock.elapsedRealtime()): SharedText? =
        runCatching {
            val textExtra = when (intent.action) {
                Intent.ACTION_SEND -> Intent.EXTRA_TEXT
                Intent.ACTION_PROCESS_TEXT -> Intent.EXTRA_PROCESS_TEXT
                else -> return null
            }
            val otherTextExtra = if (textExtra == Intent.EXTRA_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT
            if (intent.type != "text/plain" || intent.data != null || intent.hasExtra(otherTextExtra) ||
                intent.selector != null || intent.hasExtra(Intent.EXTRA_STREAM)) return null
            val clip = intent.clipData
            if (clip != null && (clip.itemCount != 1 || clip.getItemAt(0).uri != null || clip.getItemAt(0).intent != null)) return null
            // Both editable and read-only selections are imported as reference only. No result
            // text is ever returned, so EXTRA_PROCESS_TEXT_READONLY cannot enable source writes.
            val raw = intent.getCharSequenceExtra(textExtra) ?: return null
            if (raw.length > SharedText.MaxCharacters) return null
            val text = raw.toString()
            if (!SharedText.validText(text)) return null
            // A conflicting second representation must not be silently omitted from the preview.
            if (clip?.getItemAt(0)?.text?.toString()?.let { it != text } == true) return null
            SharedText(text, callingPackage?.takeIf { it.length <= 255 && it.matches(Regex("[A-Za-z0-9_.]+")) }, now)
        }.getOrNull()
}
