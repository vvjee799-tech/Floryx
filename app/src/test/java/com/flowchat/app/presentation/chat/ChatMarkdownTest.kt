package com.flowchat.app.presentation.chat

import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.TableBlock
import org.junit.Assert.*
import org.junit.Test

class ChatMarkdownTest {
    @Test fun parsesTablesListsCodeAndUnfinishedStreamingFences() {
        val root = ChatMarkdown.parse("# Title\n\n- First\n- Second\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n```kotlin\nval text = \"中\"\n")
        val nodes = mutableListOf<Node>()
        root.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) { nodes += heading; super.visit(heading) }
            override fun visit(bulletList: BulletList) { nodes += bulletList; super.visit(bulletList) }
            override fun visit(fencedCodeBlock: FencedCodeBlock) { nodes += fencedCodeBlock }
            override fun visit(customBlock: CustomBlock) { nodes += customBlock; super.visit(customBlock) }
        })
        assertTrue(nodes.any { it is Heading })
        assertTrue(nodes.any { it is BulletList })
        assertTrue(nodes.any { it is TableBlock })
        assertEquals("val text = \"中\"\n", nodes.filterIsInstance<FencedCodeBlock>().single().literal)
    }

    @Test fun onlyNavigableWebAndEmailLinksAreInteractive() {
        assertEquals("https://example.com/a?q=1", ChatMarkdown.link("https://example.com/a?q=1"))
        assertEquals("mailto:person@example.com", ChatMarkdown.link("mailto:person@example.com"))
        listOf("javascript:alert(1)", "file:///sdcard/a", "intent://settings", "data:text/html,a", "https:/missing-host").forEach {
            assertNull(it, ChatMarkdown.link(it))
        }
    }
}
