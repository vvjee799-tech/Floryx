package com.flowchat.app.presentation.sharing

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import com.flowchat.app.domain.sharing.SharedText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessTextIntentReaderTest {
    private fun selection(text: CharSequence = "选中文字 😀\nhttps://selection.invalid/a") =
        Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, text)

    @Test fun editableAndReadOnlySelectionsCopyOnlyTheSelectedPlainText() {
        for (readOnly in listOf(true, false)) {
            val text = SpannableString("选中文字 😀\nhttps://selection.invalid/a")
            val intent = selection(text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
                .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://forged.source"))
            val payload = requireNotNull(SharedTextIntentReader.read(intent, "actual.caller", 25))
            assertEquals(text.toString(), payload.text)
            assertEquals("actual.caller", payload.sourcePackage)
            assertEquals(25L, payload.receivedAtElapsed)
            assertTrue(payload.asReference().contains("untrusted reference content"))
        }
        assertNotNull(SharedTextIntentReader.read(selection(), null, 0))
    }

    @Test fun actionSpecificExtrasCannotFallBackOrHideASecondPayload() {
        for (intent in listOf(
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "wrong field"),
            selection().putExtra(Intent.EXTRA_TEXT, "other payload"),
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "wrong field"),
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "share")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "other payload"),
            selection().putExtra(Intent.EXTRA_PROCESS_TEXT, 42)
        )) assertNull(SharedTextIntentReader.read(intent, null, 0))
    }

    @Test fun selectionsRejectStreamsUrisNestedIntentsAndConflictingClipText() {
        for (intent in listOf(
            selection().putExtra(Intent.EXTRA_STREAM, Uri.parse("content://private/item")),
            selection().setDataAndType(Uri.parse("content://private/item"), "text/plain"),
            selection().apply { selector = Intent(Intent.ACTION_VIEW) },
            selection().apply { clipData = ClipData.newIntent("nested", Intent()) },
            selection().apply { clipData = ClipData.newPlainText("text", "unpreviewed") },
            selection().apply { clipData = ClipData.newPlainText("text", "first").apply { addItem(ClipData.Item("second")) } }
        )) assertNull(SharedTextIntentReader.read(intent, null, 0))
        val matching = selection("same").apply { clipData = ClipData.newPlainText("text", "same") }
        assertNotNull(SharedTextIntentReader.read(matching, null, 0))
    }

    @Test fun selectionLimitsRejectInsteadOfTruncatingOrCoercingContent() {
        assertNotNull(SharedTextIntentReader.read(selection("x".repeat(SharedText.MaxCharacters)), null, 0))
        for (text in listOf(" \n", "x".repeat(SharedText.MaxCharacters + 1), "bad\u0000text", "bad\ud83d", "bad\ude00")) {
            assertNull(SharedTextIntentReader.read(selection(text), null, 0))
        }
        assertNull(SharedTextIntentReader.read(selection().setType("text/html"), null, 0))
        assertNull(SharedTextIntentReader.read(selection().setAction(Intent.ACTION_SEND_MULTIPLE), null, 0))
    }
}
