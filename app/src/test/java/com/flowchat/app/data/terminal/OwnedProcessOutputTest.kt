package com.flowchat.app.data.terminal

import org.junit.Assert.*
import org.junit.Test

class OwnedProcessOutputTest {
    @Test fun supervisorFrameCanCrossEveryBoundaryWithoutChangingAdjacentOutput() {
        val text = "中文无换行\u001emarker:EXIT:7\u001f尾部"
        for (split in 0..text.length) {
            val out = StringBuilder()
            val codes = mutableListOf<Int>()
            val parser = OwnedProcessOutput("marker:", out::append, codes::add)
            parser.accept(text.take(split)); parser.accept(text.drop(split)); parser.finish()
            assertEquals("中文无换行尾部", out.toString())
            assertEquals(listOf(7), codes)
        }
    }

    @Test fun incompleteOrDifferentControlTextIsPreservedAndMemoryIsBoundedByPrefix() {
        val content = "raw\u001eother:EXIT:0\u001f\u001emarker:EXIT:9999bad\u001f\u001emarker:EX"
        val out = StringBuilder()
        val parser = OwnedProcessOutput("marker:", out::append) { fail("Invalid frame") }
        content.forEach { parser.accept(it.toString()) }
        parser.finish()
        assertEquals(content, out.toString())
    }
}
