package com.flowchat.app.data.terminal

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Only extracts below a fresh staging directory; never follows an archive link outside it. */
internal object SafeTarExtractor {
    fun extractGzip(input: InputStream, destination: Path, setMode: (Path, Int) -> Unit) {
        val root = destination.toAbsolutePath().normalize()
        Files.createDirectories(root)
        val links = mutableListOf<Pair<Path, Path>>()
        var entries = 0
        var total = 0L
        TarArchiveInputStream(GZIPInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                require(++entries <= 50_000) { "Too many runtime archive entries." }
                val name = entry.name.removePrefix("./")
                if (name.isBlank() || name == ".") continue
                val path = root.resolve(name).normalize()
                require(!java.nio.file.Paths.get(name).isAbsolute && path.startsWith(root) && path != root) { "Unsafe runtime archive path." }
                Files.createDirectories(path.parent)
                require(path.parent.toRealPath().startsWith(root.toRealPath())) { "Archive link escapes the runtime." }
                when {
                    entry.isDirectory -> Files.createDirectories(path)
                    entry.isSymbolicLink -> {
                        val parent = path.parent.toRealPath()
                        val target = if (entry.linkName.startsWith('/')) root.toRealPath().resolve(entry.linkName.removePrefix("/")) else parent.resolve(entry.linkName)
                        val normalized = target.normalize()
                        require(normalized.startsWith(root.toRealPath())) { "Unsafe runtime symbolic link." }
                        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Duplicate runtime link." }
                        Files.createSymbolicLink(path, parent.relativize(normalized))
                    }
                    entry.isLink -> {
                        val target = root.resolve(entry.linkName.removePrefix("./")).normalize()
                        require(target.startsWith(root)) { "Unsafe runtime hard link." }
                        links += path to target
                    }
                    entry.isFile -> {
                        require(entry.size in 0..64L * 1024 * 1024) { "Runtime archive member too large." }
                        total += entry.size
                        require(total <= 256L * 1024 * 1024) { "Runtime archive is too large." }
                        require(!Files.isSymbolicLink(path)) { "Runtime archive overwrites a link." }
                        Files.newOutputStream(path).use { output ->
                            val copied = tar.copyTo(output)
                            require(copied == entry.size) { "Incomplete runtime archive." }
                        }
                        setMode(path, entry.mode and 0x1ff)
                    }
                }
            }
        }
        links.forEach { (path, target) ->
            require(target.toRealPath().startsWith(root.toRealPath()) && Files.isRegularFile(target)) { "Invalid runtime hard link." }
            Files.createLink(path, target)
        }
    }
}
