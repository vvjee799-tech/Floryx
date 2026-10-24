package com.flowchat.app.data.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.flowchat.app.domain.workspace.*

class AndroidWorkspaceFileAccess(context: Context) : WorkspaceFileAccess {
    private val resolver = context.contentResolver
    private data class Entry(val uri: Uri, val name: String, val mime: String, val size: Long?, val modified: Long, val flags: Int)
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    fun select(uri: Uri, flags: Int): WorkspaceLocation {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Select a folder with the system file picker." }
        val granted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(granted and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Folder read permission was not granted." }
        resolver.takePersistableUriPermission(uri, granted)
        val root = document(root(uri))
        check(root.mime == Document.MIME_TYPE_DIR) { "The selected item is not a folder." }
        val id = DocumentsContract.getTreeDocumentId(uri)
        val display = if (uri.authority == "com.android.externalstorage.documents") {
            id.substringAfter(':', root.name).ifBlank { root.name }
        } else root.name
        return WorkspaceLocation(uri.toString(), root.name, display)
    }

    override fun status(location: WorkspaceLocation): WorkspaceStatus = try {
        val uri = Uri.parse(location.treeUri)
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri && it.isReadPermission }
        if (permission == null) WorkspaceStatus(WorkspaceAccess.Unavailable, "Folder permission needs to be granted again.")
        else {
            val entry = document(root(uri))
            check(entry.mime == Document.MIME_TYPE_DIR) { "The folder is no longer available." }
            WorkspaceStatus(if (permission.isWritePermission && entry.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0)
                WorkspaceAccess.ReadWrite else WorkspaceAccess.ReadOnly)
        }
    } catch (error: Exception) { WorkspaceStatus(WorkspaceAccess.Unavailable, error.message) }

    override fun list(location: WorkspaceLocation, path: String, offset: Int, limit: Int): WorkspacePage {
        require(offset in 0..100_000 && limit in 1..100)
        val parent = resolve(location, WorkspacePaths.relative(path, allowRoot = true)) ?: error("Folder not found.")
        check(parent.mime == Document.MIME_TYPE_DIR) { "This path is not a folder." }
        val result = mutableListOf<WorkspaceFile>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(location.treeUri), DocumentsContract.getDocumentId(parent.uri))
        var next: Int? = null
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                if (index++ < offset) continue
                if (result.size == limit) { next = offset + limit; break }
                val entry = entry(cursor, Uri.parse(location.treeUri))
                result += WorkspaceFile(listOf(path.takeIf { it != "." }.orEmpty(), entry.name).filter { it.isNotEmpty() }.joinToString("/"),
                    entry.name, entry.mime == Document.MIME_TYPE_DIR, entry.size, entry.modified)
            }
        } ?: error("The folder could not be read.")
        return WorkspacePage(result, next)
    }

    override fun read(location: WorkspaceLocation, path: String, maxBytes: Int): ByteArray {
        val entry = resolve(location, WorkspacePaths.relative(path)) ?: error("File not found: $path")
        check(entry.mime != Document.MIME_TYPE_DIR) { "Choose a file, not a folder." }
        require(entry.size == null || entry.size <= maxBytes) { "The file exceeds the transfer size limit." }
        return requireNotNull(resolver.openInputStream(entry.uri)) { "Could not open the file." }.use { input ->
            BoundedWorkspaceIo.read(input, maxBytes)
        }
    }

    override fun exists(location: WorkspaceLocation, path: String): Boolean = resolve(location, WorkspacePaths.relative(path)) != null

    override fun write(location: WorkspaceLocation, path: String, bytes: ByteArray) {
        check(status(location).access == WorkspaceAccess.ReadWrite) { "The workspace is not writable. Select the folder again." }
        val parts = WorkspacePaths.relative(path).split('/')
        var parent = document(root(Uri.parse(location.treeUri)))
        for (part in parts.dropLast(1)) {
            parent = child(location, parent, part) ?: run {
                val created = DocumentsContract.createDocument(resolver, parent.uri, Document.MIME_TYPE_DIR, part)
                    ?: error("Could not create folder: $part")
                document(created)
            }
            check(parent.mime == Document.MIME_TYPE_DIR) { "A parent path is not a folder." }
        }
        val existing = child(location, parent, parts.last())
        if (existing != null) check(existing.mime != Document.MIME_TYPE_DIR && existing.flags and Document.FLAG_SUPPORTS_WRITE != 0) {
            "The destination file is not writable."
        }
        val extension = parts.last().substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val target = existing?.uri ?: DocumentsContract.createDocument(resolver, parent.uri, mime, parts.last())
            ?: error("Could not create the destination file.")
        requireNotNull(resolver.openOutputStream(target, "wt")) { "Could not write the destination file." }.use { it.write(bytes) }
    }

    override fun rename(location: WorkspaceLocation, path: String, newName: String): String {
        check(status(location).access == WorkspaceAccess.ReadWrite) { "The workspace is not writable." }
        require('/' !in WorkspacePaths.relative(newName)) { "Use a file name without folders." }
        val old = resolve(location, WorkspacePaths.relative(path)) ?: error("File not found.")
        check(old.mime != Document.MIME_TYPE_DIR && old.flags and Document.FLAG_SUPPORTS_RENAME != 0) { "This file cannot be renamed." }
        val target = path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" }
        check(!exists(location, target)) { "A file with that name already exists." }
        val renamed = DocumentsContract.renameDocument(resolver, old.uri, newName) ?: error("Rename failed.")
        check(document(renamed).name == newName) { "The provider changed the requested file name." }
        return target
    }

    private fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun resolve(location: WorkspaceLocation, path: String): Entry? {
        var current = document(root(Uri.parse(location.treeUri)))
        if (path.isEmpty()) return current
        for (part in path.split('/')) {
            check(current.mime == Document.MIME_TYPE_DIR) { "A parent path is not a folder." }
            current = child(location, current, part) ?: return null
        }
        return current
    }
    private fun child(location: WorkspaceLocation, parent: Entry, name: String): Entry? {
        val tree = Uri.parse(location.treeUri)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
        var result: Entry? = null
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) {
                check(result == null) { "The folder contains ambiguous duplicate names." }
                result = entry(cursor, tree)
            }
        } ?: error("The folder could not be read.")
        return result
    }
    private fun document(uri: Uri): Entry = resolver.query(uri, columns, null, null, null)?.use { cursor ->
        check(cursor.moveToFirst()) { "The file or folder no longer exists." }
        Entry(uri, cursor.getString(1), cursor.getString(2), if (cursor.isNull(3)) null else cursor.getLong(3), cursor.getLong(4), cursor.getInt(5))
    } ?: error("The file or folder could not be read.")
    private fun entry(cursor: android.database.Cursor, tree: Uri) = Entry(
        DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), cursor.getString(1), cursor.getString(2),
        if (cursor.isNull(3)) null else cursor.getLong(3), cursor.getLong(4), cursor.getInt(5))
}
