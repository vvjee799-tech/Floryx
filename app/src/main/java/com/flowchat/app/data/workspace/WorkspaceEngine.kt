package com.flowchat.app.data.workspace

import com.flowchat.app.domain.workspace.*
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Explicit, bounded transfers. Nothing recursively mirrors a user's entire folder. */
class WorkspaceEngine(private val files: WorkspaceFileAccess, private val commandRoot: File, private val journalRoot: File) {
    @Serializable private data class Tracked(val transfer: WorkspacePending, val pending: Boolean = false)
    @Serializable private data class Journal(val entries: List<Tracked> = emptyList())
    private val json = Json { ignoreUnknownKeys = true }

    fun status(location: WorkspaceLocation) = files.status(location)
    fun list(location: WorkspaceLocation, path: String = "", offset: Int = 0, limit: Int = 50): WorkspacePage {
        readable(location)
        return files.list(location, WorkspacePaths.relative(path, true), offset, limit)
    }
    fun read(location: WorkspaceLocation, path: String): WorkspaceText {
        readable(location)
        val bytes = files.read(location, WorkspacePaths.relative(path), TextLimit)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text) { "This is a binary file. Import it for processing with a command." }
        return WorkspaceText(text, WorkspacePaths.digest(bytes), bytes.size)
    }

    fun executionDirectory(owner: String, location: WorkspaceLocation): String {
        val folder = WorkspacePaths.within(ownerRoot(owner), "phone/${location.key}")
        check(folder.isDirectory || folder.mkdirs()) { "Could not prepare the command workspace." }
        return "/workspace/phone/${location.key}"
    }

    @Synchronized fun importFiles(owner: String, location: WorkspaceLocation, paths: List<String>): List<String> {
        readable(location)
        require(paths.size in 1..32) { "Choose between 1 and 32 files per transfer." }
        executionDirectory(owner, location)
        var total = 0L
        val imported = mutableListOf<String>()
        for (path in paths.distinct()) {
            val normalized = WorkspacePaths.relative(path)
            val bytes = files.read(location, normalized, FileLimit)
            total += bytes.size
            require(total <= BatchLimit) { "The transfer exceeds 64 MiB. Import fewer files." }
            val localPath = stagedPath(location, normalized)
            val target = WorkspacePaths.within(ownerRoot(owner), localPath)
            val entries = load(owner).entries.toMutableList()
            val previous = entries.firstOrNull { it.transfer.localPath == localPath }
            if (target.exists()) {
                val digest = localDigest(target)
                check(digest == WorkspacePaths.digest(bytes) || (previous != null && !previous.pending && digest == previous.transfer.baseline)) {
                    "Unsaved command changes exist for $normalized. Save them before importing again."
                }
            }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            entries.removeAll { it.transfer.localPath == localPath }
            entries += Tracked(WorkspacePending(location, normalized, WorkspacePaths.digest(bytes), localPath))
            store(owner, entries)
            imported += normalized
        }
        return imported
    }

    /** Register destinations before a command starts, so cancellation/restart cannot lose their identity. */
    @Synchronized fun prepareOutputs(owner: String, location: WorkspaceLocation, paths: List<String>) {
        require(paths.size <= 32) { "Choose at most 32 output files." }
        if (paths.isNotEmpty()) writable(location)
        executionDirectory(owner, location)
        val entries = load(owner).entries.toMutableList()
        for (path in paths.distinct()) {
            val normalized = WorkspacePaths.relative(path)
            val localPath = stagedPath(location, normalized)
            val previous = entries.firstOrNull { it.transfer.localPath == localPath }
            val baseline = if (previous != null) previous.transfer.baseline else remoteDigest(location, normalized)
            entries.removeAll { it.transfer.localPath == localPath }
            entries += Tracked(WorkspacePending(location, normalized, baseline, localPath), pending = true)
            WorkspacePaths.within(ownerRoot(owner), localPath).parentFile?.mkdirs()
        }
        store(owner, entries)
    }

    @Synchronized fun write(owner: String, location: WorkspaceLocation, path: String, text: String, expectedRevision: String?): WorkspaceSave {
        writable(location)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= TextLimit) { "Text is limited to 256 KiB; use a command for larger files." }
        val normalized = WorkspacePaths.relative(path)
        val localPath = "pending/${UUID.randomUUID()}"
        val target = WorkspacePaths.within(ownerRoot(owner), localPath)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        val transfer = WorkspacePending(location, normalized, expectedRevision, localPath)
        store(owner, load(owner).entries + Tracked(transfer, true))
        return publishPending(owner, localPath)
    }

    @Synchronized fun publishFiles(owner: String, location: WorkspaceLocation, paths: List<String>): List<WorkspaceSave> {
        validatePublishBatch(owner, location, paths)
        val results = mutableListOf<WorkspaceSave>()
        var total = 0L
        for (path in paths.distinct()) {
            val normalized = WorkspacePaths.relative(path)
            val localPath = stagedPath(location, normalized)
            val local = WorkspacePaths.within(ownerRoot(owner), localPath)
            check(local.isFile) { "Output file does not exist: $normalized" }
            total += local.length()
            require(total <= BatchLimit) { "The transfer exceeds 64 MiB. Save fewer files." }
            val entries = load(owner).entries.toMutableList()
            val old = entries.firstOrNull { it.transfer.localPath == localPath }
            entries.removeAll { it.transfer.localPath == localPath }
            entries += Tracked(old?.transfer ?: WorkspacePending(location, normalized, null, localPath), true)
            store(owner, entries)
            results += publishPending(owner, localPath)
        }
        return results
    }

    @Synchronized fun validatePublishBatch(owner: String, location: WorkspaceLocation, paths: List<String>) {
        require(paths.size in 1..32) { "Choose between 1 and 32 output files." }
        var total = 0L
        for (path in paths.distinct()) {
            val local = WorkspacePaths.within(ownerRoot(owner), stagedPath(location, path))
            check(local.isFile) { "Output file does not exist: $path" }
            require(local.length() <= FileLimit) { "The file exceeds the transfer size limit: $path" }
            total += local.length()
            require(total <= BatchLimit) { "The transfer exceeds 64 MiB. Save fewer files." }
        }
    }

    @Synchronized fun publishPending(owner: String, localPath: String): WorkspaceSave {
        val entries = load(owner).entries.toMutableList()
        val index = entries.indexOfFirst { it.transfer.localPath == localPath && it.pending }
        check(index >= 0) { "No pending output was found." }
        val transfer = entries[index].transfer
        try {
            writable(transfer.location)
            val local = WorkspacePaths.within(ownerRoot(owner), transfer.localPath)
            check(local.isFile) { "The command has not created this output file." }
            val bytes = local.inputStream().use { BoundedWorkspaceIo.read(it, FileLimit) }
            val digest = WorkspacePaths.digest(bytes)
            val current = remoteDigest(transfer.location, transfer.path)
            // A retry after an interrupted confirmation of the write must not create another copy.
            if (current == digest) {
                entries[index] = entries[index].copy(pending = false, transfer = transfer.copy(baseline = current, error = null))
                store(owner, entries)
                return WorkspaceSave(transfer.path, digest, false, transfer.location.name)
            }
            val conflict = current != transfer.baseline
            val destination = if (conflict) conflictPath(transfer.path) else transfer.path
            files.write(transfer.location, destination, bytes)
            check(remoteDigest(transfer.location, destination) == digest) { "The saved file could not be verified. The local result was retained." }
            entries[index] = entries[index].copy(pending = false, transfer = transfer.copy(baseline = if (conflict) transfer.baseline else digest, error = null))
            store(owner, entries)
            return WorkspaceSave(destination, digest, conflict, transfer.location.name)
        } catch (error: Exception) {
            entries[index] = entries[index].copy(transfer = transfer.copy(error = error.message))
            store(owner, entries)
            throw error
        }
    }

    @Synchronized fun pending(owner: String): List<WorkspacePending> = load(owner).entries.filter {
        it.pending && runCatching { WorkspacePaths.within(ownerRoot(owner), it.transfer.localPath).isFile }.getOrDefault(false)
    }.map { it.transfer }

    @Synchronized fun rename(location: WorkspaceLocation, path: String, newName: String, expectedRevision: String): String {
        writable(location)
        check(remoteDigest(location, WorkspacePaths.relative(path)) == expectedRevision) { "The file changed. Read it again before renaming." }
        return files.rename(location, path, newName)
    }

    private fun ownerRoot(owner: String): File = File(commandRoot, WorkspacePaths.owner(owner)).apply { mkdirs() }
    private fun stagedPath(location: WorkspaceLocation, path: String) = "phone/${location.key}/${WorkspacePaths.relative(path)}"
    private fun readable(location: WorkspaceLocation) {
        check(files.status(location).access in setOf(WorkspaceAccess.ReadOnly, WorkspaceAccess.ReadWrite)) {
            "The workspace is unavailable. Select the folder again in Settings > Workspace."
        }
    }
    private fun writable(location: WorkspaceLocation) {
        check(files.status(location).access == WorkspaceAccess.ReadWrite) { "The workspace is not writable. Select the folder again in Settings > Workspace." }
    }
    private fun remoteDigest(location: WorkspaceLocation, path: String): String? =
        if (files.exists(location, path)) WorkspacePaths.digest(files.read(location, path, FileLimit)) else null
    private fun localDigest(file: File) = WorkspacePaths.digest(file.inputStream().use { BoundedWorkspaceIo.read(it, FileLimit) })
    private fun conflictPath(path: String): String {
        val name = path.substringAfterLast('/')
        val suffix = " (Floryx ${UUID.randomUUID().toString().take(8)})"
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val changed = name.take(dot) + suffix + name.substring(dot)
        return path.substringBeforeLast('/', "").let { if (it.isEmpty()) changed else "$it/$changed" }
    }
    private fun load(owner: String): Journal {
        val file = File(journalRoot, "${WorkspacePaths.owner(owner)}.json")
        return if (!file.exists()) Journal() else json.decodeFromString<Journal>(file.readText())
    }
    private fun store(owner: String, entries: List<Tracked>) {
        check(journalRoot.isDirectory || journalRoot.mkdirs()) { "Could not save workspace transfer state." }
        val target = File(journalRoot, "${WorkspacePaths.owner(owner)}.json")
        val temporary = File(journalRoot, "${WorkspacePaths.owner(owner)}.tmp")
        temporary.outputStream().use { output -> output.write(json.encodeToString(Journal(entries)).toByteArray()); output.fd.sync() }
        try {
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val TextLimit = 256 * 1024
        const val FileLimit = 20 * 1024 * 1024
        const val BatchLimit = 64L * 1024 * 1024
    }
}

internal object BoundedWorkspaceIo {
    fun read(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            require(output.size().toLong() + size <= limit) { "The file exceeds the transfer size limit." }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
}
