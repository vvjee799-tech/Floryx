package com.flowchat.app.data.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test

class SafeTarExtractorTest {
    private fun archive(name: String, text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            val payload = text.toByteArray()
            val entry = TarArchiveEntry(name).apply { size = payload.size.toLong(); mode = 493 }
            tar.putArchiveEntry(entry); tar.write(payload); tar.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }
    @Test fun extractsFilesAndRetainsTheirContents() {
        val root = Files.createTempDirectory("flowchat-tar")
        try {
            SafeTarExtractor.extractGzip(ByteArrayInputStream(archive("./bin/script", "echo hello")), root) { _, _ -> }
            assertEquals("echo hello", String(Files.readAllBytes(root.resolve("bin/script"))))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun rejectsParentTraversalBeforeWritingOutsideTheStagingDirectory() {
        val root = Files.createTempDirectory("flowchat-tar")
        val output = root.resolve("staging").also { Files.createDirectory(it) }
        try {
            assertTrue(runCatching { SafeTarExtractor.extractGzip(ByteArrayInputStream(archive("../../escape", "bad")), output) { _, _ -> } }.isFailure)
            assertFalse(Files.exists(root.resolve("escape")))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun rejectsOversizedDeclaredMemberWithoutInflatingIt() {
        val root = Files.createTempDirectory("flowchat-tar")
        val header = ByteArray(512)
        TarArchiveEntry("large").apply { size = 65L * 1024 * 1024 }.writeEntryHeader(header)
        val compressed = ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(header); it.write(ByteArray(1024)) } }.toByteArray()
        try {
            assertTrue(runCatching { SafeTarExtractor.extractGzip(ByteArrayInputStream(compressed), root) { _, _ -> } }.isFailure)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun rejectsLinkTraversalThroughAnEarlierDirectoryAlias() {
        val root = Files.createTempDirectory("flowchat-tar-link")
        try {
            Files.createDirectory(root.resolve("shallow"))
            try {
                Files.createSymbolicLink(root.resolve("probe"), root.resolve("shallow"))
                Files.delete(root.resolve("probe"))
            } catch (unsupported: Exception) { assumeNoException("Host must support symbolic links", unsupported) }
            val bytes = ByteArrayOutputStream()
            TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
                listOf("deep/parent/alias" to "../../shallow", "deep/parent/alias/escape" to "../../outside").forEach { (name, target) ->
                    tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })
                    tar.closeArchiveEntry()
                }
            }
            assertTrue(runCatching { SafeTarExtractor.extractGzip(ByteArrayInputStream(bytes.toByteArray()), root) { _, _ -> } }.isFailure)
            assertFalse(Files.exists(root.resolve("shallow/escape"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally { root.toFile().deleteRecursively() }
    }
}
