package com.flowchat.app.data.workspace

import com.flowchat.app.domain.workspace.*
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Fixed read-only access to this conversation's selected Linux copy. Never runs a shell. */
internal class ExecutionWorkspaceFileAccess(private val root: File, private val boundary: File = root.parentFile) : WorkspaceFileAccess {
    private fun resolve(path: String, allowRoot: Boolean = false): File {
        val relative = WorkspacePaths.relative(path, allowRoot)
        // Reject links in every component, including the owner/phone roots. The caller also
        // refuses inspection while this owner's command resource could still be writing.
        var cursor: File? = root.absoluteFile
        require(root.absolutePath.startsWith(boundary.absolutePath + File.separator)) { "Invalid execution workspace boundary." }
        while (cursor != null) {
            require(!Files.isSymbolicLink(cursor.toPath())) { "Execution workspace links cannot be inspected: ${cursor.name}." }
            if (cursor.absoluteFile == boundary.absoluteFile) break
            cursor = cursor.parentFile
        }
        var file = root.absoluteFile
        if (relative.isNotEmpty()) relative.split('/').forEach { part ->
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath())) { "Execution workspace links cannot be inspected." }
        }
        return file
    }

    private fun attributes(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    override fun status(location: WorkspaceLocation) = WorkspaceStatus(WorkspaceAccess.ReadOnly)
    override fun exists(location: WorkspaceLocation, path: String) = Files.exists(resolve(path).toPath(), NOFOLLOW_LINKS)
    override fun stat(location: WorkspaceLocation, path: String): WorkspaceSource {
        val file = resolve(path)
        val info = attributes(file)
        require(info.isRegularFile) { "Only regular execution files can be read; links, pipes and devices are refused." }
        val version = WorkspacePaths.digest("${info.fileKey()}|${info.size()}|${info.lastModifiedTime()}".toByteArray())
        return WorkspaceSource(path, info.size(), info.lastModifiedTime().toMillis(), "application/octet-stream", version, "weak_metadata")
    }

    override fun openRead(location: WorkspaceLocation, path: String, byteOffset: Long, checkCancelled: () -> Unit): WorkspaceInput {
        checkCancelled()
        stat(location, path)
        val channel = FileChannel.open(resolve(path).toPath(), READ, NOFOLLOW_LINKS)
        return try {
            channel.position(byteOffset)
            WorkspaceInput(Channels.newInputStream(channel), "seek")
        } catch (error: Throwable) { channel.close(); throw error }
    }

    override fun list(location: WorkspaceLocation, path: String, offset: Int, limit: Int): WorkspacePage {
        require(offset in 0..100000 && limit in 1..50) { "Invalid directory page." }
        val directory = resolve(path, true)
        require(attributes(directory).isDirectory) { "Not an execution directory." }
        val rows = mutableListOf<WorkspaceFile>()
        var count = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (count++ < offset) continue
                if (rows.size == limit) return WorkspacePage(rows, offset + limit)
                val info = attributes(entry.toFile())
                val name = entry.fileName.toString()
                rows += WorkspaceFile(if (path.isEmpty()) name else "$path/$name", name, info.isDirectory,
                    info.size().takeIf { info.isRegularFile }, info.lastModifiedTime().toMillis())
            }
        }
        return WorkspacePage(rows, null)
    }

    override fun read(location: WorkspaceLocation, path: String, maxBytes: Int): ByteArray =
        openRead(location, path, 0) {}.use { BoundedWorkspaceIo.read(it.stream, maxBytes) }
    override fun write(location: WorkspaceLocation, path: String, bytes: ByteArray): Unit = error("Read-only execution inspection.")
    override fun rename(location: WorkspaceLocation, path: String, newName: String): String = error("Read-only execution inspection.")
}
