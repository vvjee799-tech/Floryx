package com.flowchat.app.data.workspace

import com.flowchat.app.domain.workspace.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One streaming pass, constant memory even for a multi-megabyte physical line. Never parses or executes code. */
internal class WorkspaceTextStructureScanner {
    private data class Sample(val number: Long, val start: Long, val end: Long, val terminated: Boolean,
        val kind: String, val first: Long, val bytes: ByteArray)
    private var position = 0L
    private var lineStart = 0L
    private var first = -1L
    private var lfCount = 0L
    private var observedLines = 0L
    private var longest = 0L
    private val prefix = ByteArray(96)
    private var prefixSize = 0
    private val counts = linkedMapOf<String, Long>()
    private val samples = mutableListOf<Sample>()

    fun accept(bytes: ByteArray, count: Int, checkCancelled: () -> Unit) {
        for (index in 0 until count) {
            if (index % 4096 == 0) checkCancelled()
            val byte = bytes[index]
            if (byte == 10.toByte()) {
                line(true)
                lfCount++
                lineStart = position + 1
                first = -1
                prefixSize = 0
            } else {
                if (first < 0 && byte != 32.toByte() && byte != 9.toByte()) first = position
                if (first >= 0 && prefixSize < prefix.size) prefix[prefixSize++] = byte
            }
            position++
        }
    }

    private fun line(terminated: Boolean) {
        observedLines++
        longest = maxOf(longest, position - lineStart)
        val kind = when {
            first < 0 -> "space_or_tab_only"
            first == 0L && prefixSize >= 2 && prefix[0] == 35.toByte() && prefix[1] == 33.toByte() -> "shebang_prefix"
            prefix[0] == 35.toByte() -> "hash_prefix"
            else -> "other_prefix"
        }
        counts[kind] = (counts[kind] ?: 0L) + 1
        // Samples for each category are bounded independently, so early comments cannot hide later other-prefix lines.
        if (kind != "space_or_tab_only" && samples.count { it.kind == kind } < 2) {
            samples += Sample(observedLines, lineStart, position, terminated, kind, first, prefix.copyOf(prefixSize))
        }
    }

    fun finish(source: WorkspaceSource, eof: Boolean, mode: String): WorkspaceTextStructure {
        if (position > lineStart) line(false)
        return WorkspaceTextStructure(lfCount, observedLines, observedLines.takeIf { eof }, longest, counts.toMap(),
            samples.map { sample ->
                val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(sample.bytes)).toString() }.getOrNull()
                val end = sample.first + sample.bytes.size
                val range = WorkspaceRange(source, sample.first, sample.first, end, text,
                    if (text == null) sample.bytes.joinToString("") { "%02x".format(it) } else null,
                    eof && end == position, mode, 0, sample.bytes.size)
                WorkspaceTextLine(sample.number, sample.start, sample.end, sample.terminated, sample.kind, sample.first, range)
            })
    }
}
