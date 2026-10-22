package com.flowchat.app.device

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.terminal.LinuxRuntime
import com.flowchat.app.data.terminal.ManagedCommandRegistry
import com.flowchat.app.data.terminal.SafeTarExtractor
import com.flowchat.app.domain.terminal.CommandEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.tar.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TerminalArchiveDiagnosticTest {
    @Test fun freshInitializationAndSymlinkContainmentWorkOnAndroid() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runTerminalDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "terminal-extract-qa-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val staging = File(directory, "malicious").toPath()
            Files.createDirectories(staging.resolve("shallow"))
            val compressed = ByteArrayOutputStream()
            TarArchiveOutputStream(GZIPOutputStream(compressed)).use { tar ->
                listOf("deep/parent/alias" to "../../shallow", "deep/parent/alias/escape" to "../../outside").forEach { (name, target) ->
                    tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })
                    tar.closeArchiveEntry()
                }
            }
            assertTrue(runCatching { SafeTarExtractor.extractGzip(ByteArrayInputStream(compressed.toByteArray()), staging) { _, _ -> } }.isFailure)
            assertFalse(Files.exists(staging.resolve("shallow/escape"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = File(directory, "fresh").apply { mkdirs() } }
            val linux = LinuxRuntime(isolated)
            linux.prepare()
            assertTrue(linux.state.value.toString(), linux.state.value.ready)
            val registry = ManagedCommandRegistry()
            val command = "printf FRESH_LINUX_OK; test -x /usr/local/bin/flowchat-install-tools"
            val started = registry.start(command, CommandEnvironment.Linux, "/workspace", 10, linux.processSpec("archive-qa", command, "/workspace"))
            withTimeout(15_000) { while (registry.read(started.id).isRunning) delay(50) }
            val result = registry.read(started.id)
            assertEquals(result.output + result.error, 0, result.exitCode)
            assertTrue(result.output.contains("FRESH_LINUX_OK"))
        } finally { directory.deleteRecursively() }
    }
}
