package com.flowchat.app.presentation.sharing

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.flowchat.app.domain.sharing.SharedText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTextIntentReaderTest {
    private fun share(text: CharSequence = "文字 😀 https://example.invalid/a?q=1") =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    @Test fun textAndUrlAreCopiedWithoutResolvingOrTrustingReferrerExtras() {
        val intent = share().putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://forged.source"))
        val value = requireNotNull(SharedTextIntentReader.read(intent, null, 50))
        assertEquals(intent.getCharSequenceExtra(Intent.EXTRA_TEXT).toString(), value.text)
        assertNull(value.sourcePackage)
        assertEquals(50L, value.receivedAtElapsed)
        assertTrue(value.asReference().contains("untrusted reference content"))
        assertTrue(value.asReference().contains("Links have not been opened"))
    }

    @Test fun onlySystemCallingIdentityIsUsedForSource() {
        assertEquals("real.source", SharedTextIntentReader.read(share(), "real.source", 0)?.sourcePackage)
        assertNull(SharedTextIntentReader.read(share(), "bad\nsource", 0)?.sourcePackage)
    }

    @Test fun rejectsUnsupportedActionsTypesAndEmptyText() {
        for (intent in listOf(share().setAction(Intent.ACTION_VIEW), share().setAction(Intent.ACTION_SEND_MULTIPLE),
            share().setType("text/html"), share().setType("image/png"), share(" \n\t"),
            Intent(Intent.ACTION_SEND).setType("text/plain"))) {
            assertNull(SharedTextIntentReader.read(intent, null, 0))
        }
    }

    @Test fun rejectsStreamDataUriAndNestedIntents() {
        assertNull(SharedTextIntentReader.read(share().putExtra(Intent.EXTRA_STREAM, Uri.parse("content://private/item")), null, 0))
        assertNull(SharedTextIntentReader.read(share().setDataAndType(Uri.parse("content://private/item"), "text/plain"), null, 0))
        assertNull(SharedTextIntentReader.read(share().apply { selector = Intent(Intent.ACTION_VIEW) }, null, 0))
        assertNull(SharedTextIntentReader.read(share().apply { clipData = ClipData.newIntent("nested", Intent()) }, null, 0))
    }

    @Test fun checksClipDataInsteadOfReadingUnpreviewedContent() {
        val matching = share("same").apply { clipData = ClipData.newPlainText("text", "same") }
        assertNotNull(SharedTextIntentReader.read(matching, null, 0))
        matching.clipData = ClipData.newPlainText("text", "different")
        assertNull(SharedTextIntentReader.read(matching, null, 0))
        matching.clipData = ClipData.newPlainText("text", "same").apply { addItem(ClipData.Item("second")) }
        assertNull(SharedTextIntentReader.read(matching, null, 0))
        matching.clipData = ClipData("file", arrayOf("text/plain"), ClipData.Item(Uri.parse("content://private/item")))
        assertNull(SharedTextIntentReader.read(matching, null, 0))
    }

    @Test fun rejectsOversizeMalformedUnicodeControlsAndWrongExtraTypeWithoutTruncation() {
        assertNotNull(SharedTextIntentReader.read(share("x".repeat(SharedText.MaxCharacters)), null, 0))
        for (text in listOf("x".repeat(SharedText.MaxCharacters + 1), "bad\u0000text", "bad\ud83d", "bad\ude00")) {
            assertNull(SharedTextIntentReader.read(share(text), null, 0))
        }
        assertNull(SharedTextIntentReader.read(share().putExtra(Intent.EXTRA_TEXT, 42), null, 0))
    }

    @Test fun freshnessRejectsExpiryAndClockReset() {
        val value = requireNotNull(SharedTextIntentReader.read(share(), null, 100))
        assertTrue(value.isFresh(100))
        assertTrue(value.isFresh(100 + SharedText.MaxAgeMillis - 1))
        assertFalse(value.isFresh(100 + SharedText.MaxAgeMillis))
        assertFalse(value.isFresh(99))
    }
}
