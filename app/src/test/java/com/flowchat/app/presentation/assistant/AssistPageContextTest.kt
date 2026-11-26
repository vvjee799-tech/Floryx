package com.flowchat.app.presentation.assistant

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class AssistPageContextTest {
    @Test fun staleAndFuturePageSnapshotsCannotBeSent() {
        val page = AssistPageContext("test.source", "visible text", capturedAt = 1_000)
        assertTrue(page.isFresh(1_000))
        assertTrue(page.isFresh(61_000))
        assertFalse(page.isFresh(61_001))
        assertFalse(page.isFresh(999))
    }
    @Test fun allPasswordVariationsAreExcludedButNormalFieldsAreReadable() {
        for (type in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            assertTrue(AssistPageContext.isPassword(InputType.TYPE_CLASS_TEXT or type or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        }
        assertTrue(AssistPageContext.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(AssistPageContext.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(AssistPageContext.isPassword(InputType.TYPE_CLASS_NUMBER))
    }
}
