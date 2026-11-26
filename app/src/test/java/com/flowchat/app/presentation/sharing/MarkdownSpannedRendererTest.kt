package com.flowchat.app.presentation.sharing

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownSpannedRendererTest {
    private val codeBg = 0xFFEDEAE2.toInt()
    private val linkColor = 0xFF2D604F.toInt()
    private val quoteColor = 0xFFB8B2A6.toInt()

    private fun render(source: String): Spanned =
        MarkdownSpannedRenderer.render(source, codeBg, linkColor, quoteColor)

    @Test
    fun rendersBoldItalicStrikethroughAndInlineCodeWithoutRawMarkers() {
        val spanned = render("摘要：**周六上午10点**有*手作课*，~~取消~~，地点 `A102`。")
        val text = spanned.toString()

        assertEquals("摘要：周六上午10点有手作课，取消，地点 A102。", text)
        assertFalse(text.contains("**"))
        assertFalse(text.contains("~~"))
        assertFalse(text.contains("`"))

        val boldStart = text.indexOf("周六上午10点")
        val boldSpans = spanned.getSpans(boldStart, boldStart + "周六上午10点".length, StyleSpan::class.java)
        assertTrue(boldSpans.any { it.style == Typeface.BOLD })

        val italicStart = text.indexOf("手作课")
        val italicSpans = spanned.getSpans(italicStart, italicStart + "手作课".length, StyleSpan::class.java)
        assertTrue(italicSpans.any { it.style == Typeface.ITALIC })

        val strikeStart = text.indexOf("取消")
        assertTrue(spanned.getSpans(strikeStart, strikeStart + 2, StrikethroughSpan::class.java).isNotEmpty())

        val codeStart = text.indexOf("A102")
        assertTrue(spanned.getSpans(codeStart, codeStart + 4, TypefaceSpan::class.java).any { it.family == "monospace" })
        assertTrue(spanned.getSpans(codeStart, codeStart + 4, BackgroundColorSpan::class.java).any { it.backgroundColor == codeBg })
    }

    @Test
    fun rendersHeadingsListsQuotesTablesAndCodeBlocks() {
        val markdown = """
            # 总结标题

            - 第一条
            - 第二条
              1. 子步骤

            > 重要提醒

            | 项 | 值 |
            | --- | --- |
            | 教室 | B201 |

            ```kotlin
            val ok = true
            ```
        """.trimIndent()
        val spanned = render(markdown)
        val text = spanned.toString()

        assertTrue(text.contains("总结标题"))
        assertTrue(text.contains("• 第一条\n• 第二条"))
        assertTrue(text.contains("  1. 子步骤"))
        assertTrue(text.contains("重要提醒"))
        assertTrue(text.contains("项 | 值\n教室 | B201"))
        assertTrue(text.contains("val ok = true"))
        assertFalse(text.contains("```"))

        val titleStart = text.indexOf("总结标题")
        assertTrue(spanned.getSpans(titleStart, titleStart + 4, RelativeSizeSpan::class.java).any { it.sizeChange == 1.25f })
        val quoteStart = text.indexOf("重要提醒")
        assertTrue(spanned.getSpans(quoteStart, quoteStart + 4, QuoteSpan::class.java).isNotEmpty())
        val headerStart = text.indexOf("项")
        assertTrue(spanned.getSpans(headerStart, headerStart + 1, StyleSpan::class.java).any { it.style == Typeface.BOLD })
    }

    @Test
    fun preservesPlainTextAndIntrawordUnderscoresCharacterForCharacter() {
        val samples = listOf(
            "周六上午10点有手作课，须提前预约。",
            "LOCAL_PREFIX_LOCAL_FINISHED",
            "   "
        )
        for (sample in samples) {
            assertEquals(sample, render(sample).toString())
        }
        assertEquals("", render("").toString())
    }

    @Test
    fun stylesOnlySafeLinksWithoutInteractiveUrlSpan() {
        val spanned = render("[官网](https://example.com) 和 [危险](javascript:alert(1)) 和 [文件](file:///sdcard/a)")
        val text = spanned.toString()

        assertEquals("官网 和 危险 和 文件", text)
        assertTrue(spanned.getSpans(0, text.length, URLSpan::class.java).isEmpty())

        val safeStart = text.indexOf("官网")
        assertTrue(spanned.getSpans(safeStart, safeStart + 2, ForegroundColorSpan::class.java).any { it.foregroundColor == linkColor })
        assertTrue(spanned.getSpans(safeStart, safeStart + 2, UnderlineSpan::class.java).isNotEmpty())

        val unsafeStart = text.indexOf("危险")
        assertTrue(spanned.getSpans(unsafeStart, unsafeStart + 2, ForegroundColorSpan::class.java).isEmpty())
        val fileStart = text.indexOf("文件")
        assertTrue(spanned.getSpans(fileStart, fileStart + 2, UnderlineSpan::class.java).isEmpty())
    }

    @Test
    fun handlesUnclosedStreamingMarkdownSafely() {
        val spanned = render("正在总结 **要点")
        assertEquals("正在总结 **要点", spanned.toString())
    }
}
