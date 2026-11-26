package com.flowchat.app.data.workspace

import com.flowchat.app.domain.workspace.*
import java.io.Closeable
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class WorkspaceReadFailure(val code: String, message: String, val currentSource: WorkspaceSource? = null) :
    IllegalArgumentException(message)

class WorkspaceInput(val stream: InputStream, val accessMode: String, val skippedBytes: Long = 0) : Closeable {
    override fun close() = stream.close()
}

/** No whole-file reads or hashes. Each source handle is closed, including failed/cancelled scans. */
class WorkspaceReader(private val files: WorkspaceFileAccess) {
    fun source(location: WorkspaceLocation, path: String, expected: String? = null): WorkspaceSource {
        val source = files.stat(location, WorkspacePaths.relative(path))
        if (expected != null && source.version != expected)
            throw WorkspaceReadFailure("source_changed", "The source version differs. Start a new reading from current metadata; do not join old and new ranges.", source)
        return source
    }

    private fun unchanged(location: WorkspaceLocation, before: WorkspaceSource) {
        val after = source(location, before.path)
        if (before != after) throw WorkspaceReadFailure("source_changed", "The file changed while reading. Discard this range and start from current metadata.", after)
    }

    fun range(location: WorkspaceLocation, path: String, offset: Long = 0, maxBytes: Int = 4096,
        expected: String? = null, checkCancelled: () -> Unit = {}): WorkspaceRange {
        require(offset >= 0) { "byte_offset must be non-negative." }
        require(maxBytes in 4..MaxBytes) { "max_bytes must be between 4 and 65536." }
        checkCancelled()
        val source = source(location, path, expected)
        if (source.size != null && offset > source.size)
            throw WorkspaceReadFailure("invalid_byte_offset", "byte_offset exceeds the current file size (${source.size}).", source)
        // At most three bytes of lookbehind let an arbitrary UTF-8 offset include its whole character.
        val begin = (offset - 3).coerceAtLeast(0)
        val lookbehind = (offset - begin).toInt()
        var mode: String; var skipped: Long
        val bytes = files.openRead(location, source.path, begin, checkCancelled).use { input ->
            mode = input.accessMode; skipped = input.skippedBytes
            readUpTo(input.stream, maxBytes + lookbehind + 1, checkCancelled)
        }
        checkCancelled(); unchanged(location, source)
        if (bytes.size < lookbehind)
            throw WorkspaceReadFailure("invalid_byte_offset", "byte_offset exceeds the readable source. Refresh its metadata.", source)
        val rawEof = bytes.size < maxBytes + lookbehind + 1 || source.size == begin + bytes.size
        var start = lookbehind
        if (start < bytes.size && isContinuation(bytes[start])) {
            while (start > 0 && isContinuation(bytes[start])) start--
            val width = utf8Width(bytes[start])
            if (width < 2 || start + width <= lookbehind) start = lookbehind
        }
        val end = minOf(bytes.size, start + maxBytes)
        val buffer = ByteBuffer.wrap(bytes, start, end - start)
        val chars = CharBuffer.allocate(TextCharacters)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(buffer, chars, rawEof && end == bytes.size)
        chars.flip()
        val text = chars.toString()
        val format = if (begin == 0L) formatHint(bytes) else null
        val binary = result.isError || text.any { it == '\u0000' || it.isISOControl() && it !in "\n\r\t\u000C" } ||
            format in setOf("elf", "zip", "gzip", "png", "jpeg", "pdf")
        if (binary) {
            val count = minOf(maxBytes, BinaryBytes, bytes.size - lookbehind)
            val actualEnd = offset + count
            return WorkspaceRange(source, offset, offset, actualEnd, null,
                bytes.copyOfRange(lookbehind, lookbehind + count).hex(),
                rawEof && actualEnd == begin + bytes.size, mode, skipped, bytes.size, format)
        }
        val consumed = buffer.position() - start
        if (consumed == 0 && end > start)
            throw WorkspaceReadFailure("incomplete_utf8_range", "This range cannot contain a complete UTF-8 character. Read again with max_bytes at least 4.", source)
        val actualStart = begin + start
        val actualEnd = actualStart + consumed
        return WorkspaceRange(source, offset, actualStart, actualEnd, text, null,
            rawEof && actualEnd == begin + bytes.size, mode, skipped, bytes.size, format)
    }

    /** Literal UTF-8 search; fixed scan/output bounds, including matches split across source chunks. */
    fun search(location: WorkspaceLocation, path: String, query: String, offset: Long = 0,
        maxScanBytes: Int = 1024 * 1024, maxResults: Int = 8, expected: String? = null,
        checkCancelled: () -> Unit = {}): WorkspaceSearch {
        val needle = query.toByteArray(Charsets.UTF_8)
        require(needle.size in 1..256) { "query must contain between 1 and 256 UTF-8 bytes." }
        require(offset >= 0 && maxScanBytes in needle.size..MaxScanBytes && maxResults in 1..8) { "Invalid bounded search limits." }
        checkCancelled()
        val source = source(location, path, expected)
        if (source.size != null && offset > source.size)
            throw WorkspaceReadFailure("invalid_byte_offset", "byte_offset exceeds the current file size.", source)
        val prefix = IntArray(needle.size)
        for (i in 1 until needle.size) {
            var j = prefix[i - 1]
            while (j > 0 && needle[i] != needle[j]) j = prefix[j - 1]
            if (needle[i] == needle[j]) j++
            prefix[i] = j
        }
        val matches = mutableListOf<Long>()
        var scanned = 0; var matched = 0; var eof = false
        var mode: String; var skipped: Long
        files.openRead(location, source.path, offset, checkCancelled).use { input ->
            mode = input.accessMode; skipped = input.skippedBytes
            val chunk = ByteArray(16 * 1024)
            scan@ while (scanned < maxScanBytes) {
                checkCancelled()
                val count = input.stream.read(chunk, 0, minOf(chunk.size, maxScanBytes - scanned))
                if (count < 0) { eof = true; break }
                if (count == 0) throw WorkspaceReadFailure("source_no_progress", "The provider returned no data. Retry with fresh metadata.", source)
                for (i in 0 until count) {
                    if (i % 4096 == 0) checkCancelled()
                    val value = chunk[i]
                    while (matched > 0 && value != needle[matched]) matched = prefix[matched - 1]
                    if (value == needle[matched]) matched++
                    scanned++
                    if (matched == needle.size) {
                        matches += offset + scanned - needle.size
                        matched = prefix[matched - 1]
                        if (matches.size == maxResults) break@scan
                    }
                }
            }
        }
        checkCancelled(); unchanged(location, source)
        val end = offset + scanned
        eof = eof || source.size == end
        val next = if (eof) null else (end - needle.size + 1).coerceAtLeast(offset + 1)
        val hits = matches.map { hit -> WorkspaceSearchHit(hit, hit + needle.size,
            range(location, path, (hit - 80).coerceAtLeast(0), needle.size + 160, source.version, checkCancelled)) }
        unchanged(location, source)
        return WorkspaceSearch(source, offset, end, eof, next, hits, mode, skipped)
    }

    /** Bounded literal hints, not a script parser or a compatibility verdict. Never executes source data. */
    fun inspectionHints(location: WorkspaceLocation, path: String, maxScanBytes: Int = MaxScanBytes,
        expected: String? = null, checkCancelled: () -> Unit = {}): WorkspaceInspectionHints {
        require(maxScanBytes in 1..MaxScanBytes)
        checkCancelled()
        val source = source(location, path, expected)
        val markers = linkedMapOf(
            "architecture" to listOf("cpu", "abi", "arch", "arm64", "aarch64", "x86"),
            "platform" to listOf("android", "sdk", "api"),
            "kernel" to listOf("kernel"),
            "device" to listOf("model", "device"),
            "dependency" to listOf("require", "depend", "command -v", "which "),
            "interpreter" to listOf("#!")
        )
        val matches = mutableMapOf<String, MutableList<Pair<Long, String>>>()
        val structure = WorkspaceTextStructureScanner()
        val overlap = markers.values.flatten().maxOf { it.length } - 1
        var carry = ""; var scanned = 0; var eof = false
        var scanLimit = maxScanBytes; var mode = ""
        files.openRead(location, source.path, 0, checkCancelled).use { input ->
            mode = input.accessMode
            // A non-seek provider must not repeatedly stream megabytes to collect later contexts.
            if (mode != "seek") scanLimit = minOf(maxScanBytes, 64 * 1024)
            val chunk = ByteArray(16 * 1024)
            while (scanned < scanLimit) {
                checkCancelled()
                val count = input.stream.read(chunk, 0, minOf(chunk.size, scanLimit-scanned))
                if (count < 0) { eof = true; break }
                if (count == 0) throw WorkspaceReadFailure("source_no_progress", "The provider returned no data during the bounded hint scan.", source)
                structure.accept(chunk, count, checkCancelled)
                // ASCII-only folding keeps every byte position intact, including non-UTF-8 input.
                for (i in 0 until count) if (chunk[i].toInt() in 65..90) chunk[i] = (chunk[i]+32).toByte()
                val text = carry + String(chunk, 0, count, Charsets.ISO_8859_1)
                val base = scanned.toLong()-carry.length
                for ((category, terms) in markers) {
                    val known = matches.getOrPut(category) { mutableListOf() }
                    if (known.size >= 2) continue
                    val candidates = terms.flatMap { term -> buildList<Pair<Long, String>> {
                        var start = text.indexOf(term)
                        while (start >= 0 && size < 2) {
                            val offset = base+start
                            if (known.none { kotlin.math.abs(it.first-offset) < 160 } && none { kotlin.math.abs(it.first-offset) < 160 }) add(offset to term)
                            start = text.indexOf(term, start+term.length)
                        }
                    } }.sortedBy { it.first }
                    for (candidate in candidates) if (known.size < 2 && known.none { kotlin.math.abs(it.first-candidate.first) < 160 }) known += candidate
                }
                scanned += count
                carry = text.takeLast(overlap)
            }
        }
        checkCancelled(); unchanged(location, source)
        eof = eof || source.size == scanned.toLong()
        val hints = matches.flatMap { (category, values) -> values.map { (offset, marker) ->
            WorkspaceInspectionHint(category, marker, offset,
                range(location, path, (offset-80).coerceAtLeast(0), 256, source.version, checkCancelled))
        } }
        unchanged(location, source)
        return WorkspaceInspectionHints(source, scanned.toLong(), eof, hints, scanLimit, mode, structure.finish(source, eof, mode))
    }

    companion object {
        const val MaxBytes = 64 * 1024
        const val MaxScanBytes = 8 * 1024 * 1024
        const val MaxStreamSkip = 32L * 1024 * 1024
        const val TextCharacters = 12000
        const val BinaryBytes = 512

        fun skip(input: InputStream, offset: Long, checkCancelled: () -> Unit): Long {
            if (offset > MaxStreamSkip) throw WorkspaceReadFailure("stream_skip_limit",
                "This provider requires streaming. Skipping is limited to 32 MiB; choose a nearer range or explicitly import a bounded copy.")
            val scratch = ByteArray(16 * 1024)
            var skipped = 0L
            while (skipped < offset) {
                checkCancelled()
                val count = input.read(scratch, 0, minOf(scratch.size.toLong(), offset - skipped).toInt())
                if (count < 0) throw WorkspaceReadFailure("invalid_byte_offset", "The source ended before byte_offset.")
                if (count == 0) throw WorkspaceReadFailure("source_no_progress", "The provider returned no data while skipping.")
                skipped += count
            }
            return skipped
        }
        private fun readUpTo(input: InputStream, limit: Int, checkCancelled: () -> Unit): ByteArray {
            val out = ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val chunk = ByteArray(minOf(limit, 16 * 1024))
            while (out.size() < limit) {
                checkCancelled()
                val n = input.read(chunk, 0, minOf(chunk.size, limit - out.size()))
                if (n < 0) break
                if (n == 0) throw WorkspaceReadFailure("source_no_progress", "The provider returned no data while reading.")
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        }
        private fun isContinuation(byte: Byte) = byte.toInt() and 0xc0 == 0x80
        private fun utf8Width(byte: Byte): Int = when (byte.toInt() and 0xff) {
            in 0..0x7f -> 1; in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 0
        }
        fun formatHint(bytes: ByteArray): String? = when {
            bytes.startsWith(0x7f, 0x45, 0x4c, 0x46) -> "elf"
            bytes.startsWith(0x50, 0x4b, 0x03, 0x04) || bytes.startsWith(0x50, 0x4b, 0x05, 0x06) -> "zip"
            bytes.startsWith(0x1f, 0x8b) -> "gzip"
            bytes.startsWith(0x89, 0x50, 0x4e, 0x47) -> "png"
            bytes.startsWith(0xff, 0xd8, 0xff) -> "jpeg"
            bytes.startsWith(0x25, 0x50, 0x44, 0x46) -> "pdf"
            bytes.startsWith(0x23, 0x21) -> "script_shebang"
            else -> null
        }
        private fun ByteArray.startsWith(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xff == prefix[it] }
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
