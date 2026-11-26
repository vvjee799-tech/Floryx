package com.flowchat.app.presentation.sharing

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.flowchat.app.presentation.chat.ChatMarkdown
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/** Renders Markdown summaries into Android View spans without opening external links. */
internal object MarkdownSpannedRenderer {
    private const val MaxBlockDepth = 32
    private const val MaxInlineDepth = 64

    fun render(
        source: String,
        codeBackgroundColor: Int,
        linkColor: Int,
        quoteStripeColor: Int
    ): Spanned {
        if (source.isEmpty()) return SpannableStringBuilder("")
        val document = ChatMarkdown.parse(source)
        if (document.firstChild == null) return SpannableStringBuilder(source)
        val builder = SpannableStringBuilder()
        appendBlocks(
            builder = builder,
            parent = document,
            depth = 0,
            listDepth = 0,
            separator = "\n\n",
            codeBackgroundColor = codeBackgroundColor,
            linkColor = linkColor,
            quoteStripeColor = quoteStripeColor
        )
        return builder
    }

    private fun Node.childrenList(): List<Node> = buildList {
        var child = firstChild
        while (child != null) {
            add(child)
            child = child.next
        }
    }

    private fun appendBlocks(
        builder: SpannableStringBuilder,
        parent: Node,
        depth: Int,
        listDepth: Int,
        separator: String,
        codeBackgroundColor: Int,
        linkColor: Int,
        quoteStripeColor: Int
    ) {
        val children = parent.childrenList()
        children.forEachIndexed { index, child ->
            if (index > 0 && builder.isNotEmpty() && !builder.endsWith(separator)) {
                if (separator == "\n\n" && builder.endsWith("\n")) builder.append('\n')
                else builder.append(separator)
            }
            appendBlock(
                builder = builder,
                node = child,
                depth = depth,
                listDepth = listDepth,
                codeBackgroundColor = codeBackgroundColor,
                linkColor = linkColor,
                quoteStripeColor = quoteStripeColor
            )
        }
    }

    private fun appendBlock(
        builder: SpannableStringBuilder,
        node: Node,
        depth: Int,
        listDepth: Int,
        codeBackgroundColor: Int,
        linkColor: Int,
        quoteStripeColor: Int
    ) {
        if (depth > MaxBlockDepth) {
            appendInline(builder, node, 0, codeBackgroundColor, linkColor)
            return
        }
        when (node) {
            is Paragraph -> appendInline(builder, node, 0, codeBackgroundColor, linkColor)
            is Heading -> {
                val start = builder.length
                appendInline(builder, node, 0, codeBackgroundColor, linkColor)
                val end = builder.length
                if (end > start) {
                    val scale = when (node.level) {
                        1 -> 1.25f
                        2 -> 1.15f
                        else -> 1.08f
                    }
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            is FencedCodeBlock -> appendCodeBlock(builder, node.literal, codeBackgroundColor)
            is IndentedCodeBlock -> appendCodeBlock(builder, node.literal, codeBackgroundColor)
            is BulletList -> appendList(
                builder = builder,
                items = node.childrenList(),
                depth = depth,
                listDepth = listDepth,
                codeBackgroundColor = codeBackgroundColor,
                linkColor = linkColor,
                quoteStripeColor = quoteStripeColor
            ) { "• " }
            is OrderedList -> appendList(
                builder = builder,
                items = node.childrenList(),
                depth = depth,
                listDepth = listDepth,
                codeBackgroundColor = codeBackgroundColor,
                linkColor = linkColor,
                quoteStripeColor = quoteStripeColor
            ) { index -> "${(node.markerStartNumber ?: 1) + index}. " }
            is BlockQuote -> {
                val start = builder.length
                appendBlocks(
                    builder = builder,
                    parent = node,
                    depth = depth + 1,
                    listDepth = listDepth,
                    separator = "\n\n",
                    codeBackgroundColor = codeBackgroundColor,
                    linkColor = linkColor,
                    quoteStripeColor = quoteStripeColor
                )
                val end = builder.length
                if (end > start) {
                    builder.setSpan(QuoteSpan(quoteStripeColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            is TableBlock -> appendTable(builder, node, codeBackgroundColor, linkColor)
            is ThematicBreak -> builder.append("───")
            is HtmlBlock -> builder.append(node.literal.removeSuffix("\n"))
            else -> appendBlocks(
                builder = builder,
                parent = node,
                depth = depth + 1,
                listDepth = listDepth,
                separator = "\n",
                codeBackgroundColor = codeBackgroundColor,
                linkColor = linkColor,
                quoteStripeColor = quoteStripeColor
            )
        }
    }

    private fun appendCodeBlock(builder: SpannableStringBuilder, literal: String, codeBackgroundColor: Int) {
        val text = literal.removeSuffix("\n")
        val start = builder.length
        builder.append(text)
        val end = builder.length
        if (end > start) {
            builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(BackgroundColorSpan(codeBackgroundColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun appendList(
        builder: SpannableStringBuilder,
        items: List<Node>,
        depth: Int,
        listDepth: Int,
        codeBackgroundColor: Int,
        linkColor: Int,
        quoteStripeColor: Int,
        markerFor: (Int) -> String
    ) {
        val indent = "  ".repeat(listDepth)
        items.forEachIndexed { index, item ->
            if (index > 0) builder.append('\n')
            val children = item.childrenList()
            if (children.isEmpty()) {
                builder.append(indent).append(markerFor(index).trimEnd())
                return@forEachIndexed
            }
            children.forEachIndexed { blockIndex, child ->
                if (blockIndex > 0) builder.append('\n')
                if (blockIndex == 0 && child !is BulletList && child !is OrderedList) {
                    builder.append(indent).append(markerFor(index))
                } else if (child !is BulletList && child !is OrderedList) {
                    builder.append(indent).append("  ")
                }
                appendBlock(
                    builder = builder,
                    node = child,
                    depth = depth + 1,
                    listDepth = listDepth + 1,
                    codeBackgroundColor = codeBackgroundColor,
                    linkColor = linkColor,
                    quoteStripeColor = quoteStripeColor
                )
            }
        }
    }

    private fun appendTable(
        builder: SpannableStringBuilder,
        table: TableBlock,
        codeBackgroundColor: Int,
        linkColor: Int
    ) {
        val rows = table.childrenList().flatMap { section -> section.childrenList() }.filterIsInstance<TableRow>()
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) builder.append('\n')
            val cells = row.childrenList().filterIsInstance<TableCell>()
            cells.forEachIndexed { cellIndex, cell ->
                if (cellIndex > 0) builder.append(" | ")
                val start = builder.length
                appendInline(builder, cell, 0, codeBackgroundColor, linkColor)
                val end = builder.length
                if (cell.isHeader && end > start) {
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun appendInline(
        builder: SpannableStringBuilder,
        parent: Node,
        depth: Int,
        codeBackgroundColor: Int,
        linkColor: Int
    ) {
        if (depth > MaxInlineDepth) return
        parent.childrenList().forEach { child ->
            val start = builder.length
            when (child) {
                is Text -> builder.append(child.literal)
                is Code -> {
                    builder.append(child.literal)
                    val end = builder.length
                    if (end > start) {
                        builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(BackgroundColorSpan(codeBackgroundColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is SoftLineBreak, is HardLineBreak -> builder.append('\n')
                is HtmlInline -> builder.append(child.literal)
                is StrongEmphasis -> {
                    appendInline(builder, child, depth + 1, codeBackgroundColor, linkColor)
                    val end = builder.length
                    if (end > start) {
                        builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Emphasis -> {
                    appendInline(builder, child, depth + 1, codeBackgroundColor, linkColor)
                    val end = builder.length
                    if (end > start) {
                        builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Strikethrough -> {
                    appendInline(builder, child, depth + 1, codeBackgroundColor, linkColor)
                    val end = builder.length
                    if (end > start) {
                        builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Link, is Image -> {
                    val destination = if (child is Link) child.destination else (child as Image).destination
                    val safe = ChatMarkdown.link(destination)
                    appendInline(builder, child, depth + 1, codeBackgroundColor, linkColor)
                    val end = builder.length
                    if (safe != null && end > start) {
                        builder.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                else -> appendInline(builder, child, depth + 1, codeBackgroundColor, linkColor)
            }
        }
    }
}
