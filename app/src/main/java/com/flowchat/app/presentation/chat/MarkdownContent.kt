package com.flowchat.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal object ChatMarkdown {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(),
        StrikethroughExtension.create(), AutolinkExtension.create())).build()
    fun parse(source: String): Node = parser.parse(source)
    fun link(destination: String): String? = runCatching {
        val uri = URI(destination.trim())
        when (uri.scheme?.lowercase()) {
            "http", "https" -> destination.trim().takeIf { !uri.host.isNullOrBlank() }
            "mailto" -> destination.trim().takeIf { uri.schemeSpecificPart.isNotBlank() }
            else -> null
        }
    }.getOrNull()
}

@Composable
internal fun MarkdownContent(source: String, modifier: Modifier = Modifier) {
    // Keep parsing off the UI thread, including during a streamed reply.
    var document by remember { mutableStateOf<Node?>(null) }
    LaunchedEffect(source) {
        document = withContext(Dispatchers.Default) { ChatMarkdown.parse(source) }
    }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        SelectionContainer(modifier) {
            val node = document
            if (node == null) Text(source, style = MaterialTheme.typography.bodyLarge)
            else MarkdownChildren(node, 0)
        }
    }
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

@Composable
private fun MarkdownChildren(parent: Node, depth: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        parent.children().forEach { MarkdownBlock(it, depth) }
    }
}

@Composable
private fun MarkdownBlock(node: Node, depth: Int) {
    if (depth > 32) { Text(markdownInline(node).text); return }
    when (node) {
        is Paragraph -> Text(markdownInline(node), style = MaterialTheme.typography.bodyLarge)
        is Heading -> Text(markdownInline(node), style = when (node.level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }, fontWeight = FontWeight.SemiBold)
        is FencedCodeBlock -> MarkdownCode(node.literal, node.info.substringBefore(' '))
        is IndentedCodeBlock -> MarkdownCode(node.literal, "")
        is BulletList -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children().forEach { item -> MarkdownListItem("•", item, depth) }
        }
        is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children().forEachIndexed { index, item -> MarkdownListItem("${node.startNumber + index}.", item, depth) }
        }
        is BlockQuote -> {
            val border = MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.drawBehind { drawLine(border, Offset.Zero, Offset(0f, size.height), 2.dp.toPx()) }
                .padding(start = 14.dp)) { MarkdownChildren(node, depth + 1) }
        }
        is TableBlock -> MarkdownTable(node)
        is ThematicBreak -> HorizontalDivider()
        is HtmlBlock -> Text(node.literal, style = MaterialTheme.typography.bodyLarge)
        else -> MarkdownChildren(node, depth + 1)
    }
}

@Composable
private fun MarkdownListItem(marker: String, node: Node, depth: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 20.dp))
        Box(Modifier.weight(1f)) { MarkdownChildren(node, depth + 1) }
    }
}

@Composable
private fun MarkdownCode(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant,
        androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(12.dp).testTag("markdown-code")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(language, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag("copy-code")) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (copied) R.string.code_copied else R.string.copy_code))
            }
        }
        Text(code.removeSuffix("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp))
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    val rows = table.children().flatMap { it.children() }
    val columns = rows.maxOfOrNull { it.children().size } ?: return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth / columns.coerceAtLeast(1)).coerceIn(112.dp, 240.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).testTag("markdown-table")) {
            rows.forEach { row ->
                Row {
                    row.children().forEach { cell ->
                        Text(markdownInline(cell), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if ((cell as? TableCell)?.isHeader == true) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.width(cellWidth).padding(10.dp))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun markdownInline(parent: Node): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    return remember(parent, colors, uriHandler) {
        buildAnnotatedString {
            fun appendChildren(node: Node, depth: Int) {
                if (depth > 64) return
                node.children().forEach { child ->
                    val style = when (child) {
                        is StrongEmphasis -> SpanStyle(fontWeight = FontWeight.Bold)
                        is Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
                        is Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant)
                        is Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        else -> null
                    }
                    if (style != null) pushStyle(style)
                    when (child) {
                        is org.commonmark.node.Text -> append(child.literal)
                        is Code -> append(child.literal)
                        is SoftLineBreak, is HardLineBreak -> append('\n')
                        is HtmlInline -> append(child.literal)
                        is Link, is Image -> {
                            val destination = if (child is Link) child.destination else (child as Image).destination
                            val safe = ChatMarkdown.link(destination)
                            if (safe != null) pushLink(LinkAnnotation.Url(safe,
                                TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) {
                                runCatching { uriHandler.openUri(safe) }
                            })
                            appendChildren(child, depth + 1)
                            if (safe != null) pop()
                        }
                        else -> appendChildren(child, depth + 1)
                    }
                    if (style != null) pop()
                }
            }
            appendChildren(parent, 0)
        }
    }
}
