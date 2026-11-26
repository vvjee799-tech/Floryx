package com.flowchat.app.diagnostics.nativevoice

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.flowchat.app.domain.workspace.WorkspaceLocation
import com.flowchat.app.domain.workspace.WorkspacePaths
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/** Same-UID read-only isolated workspace provider for native voice tasks. */
class NativeVoiceWorkspaceProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        enforceSameAppCaller()
        val columns = projection ?: DEFAULT_ROOT_COLUMNS
        val cursor = MatrixCursor(columns)
        val row = Array<Any?>(columns.size) { index ->
            when (columns[index]) {
                Root.COLUMN_ROOT_ID -> ROOT_ID
                Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                Root.COLUMN_TITLE -> WORKSPACE_NAME
                Root.COLUMN_MIME_TYPES -> "*/*"
                Root.COLUMN_FLAGS -> 0
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        enforceSameAppCaller()
        val target = resolve(documentId)
        val isRoot = documentId == ROOT_ID
        if (!isRoot && !target.exists()) throw FileNotFoundException("Document not found: $documentId")
        val columns = projection ?: DEFAULT_DOCUMENT_COLUMNS
        val cursor = MatrixCursor(columns)
        cursor.addRow(rowFor(columns, requireNotNull(documentId), target, isRoot))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        enforceSameAppCaller()
        val parent = resolve(parentDocumentId)
        val columns = projection ?: DEFAULT_DOCUMENT_COLUMNS
        val cursor = MatrixCursor(columns)
        val rootCanonical = workspaceDir(requireNotNull(context)).canonicalFile
        if (parentDocumentId == ROOT_ID && !parent.exists()) return cursor
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a folder: $parentDocumentId")
        val children = parent.listFiles()
            ?.filter { child ->
                val canonical = child.canonicalFile
                canonical.path.startsWith(rootCanonical.path + File.separator)
            }
            ?.sortedBy { it.name }
            .orEmpty()
        for (child in children) {
            val childId = if (parentDocumentId == ROOT_ID) child.name else "$parentDocumentId/${child.name}"
            cursor.addRow(rowFor(columns, childId, child, isRoot = false))
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (Binder.getCallingUid() != Process.myUid()) return false
        val parent = runCatching { resolve(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { resolve(documentId) }.getOrNull() ?: return false
        if (!parent.isDirectory || !child.exists()) return false
        return child.canonicalFile.path.startsWith(parent.canonicalFile.path + File.separator)
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        enforceSameAppCaller()
        if (mode != "r") throw FileNotFoundException("Isolated workspace is read-only")
        val file = resolve(documentId)
        if (!file.isFile) throw FileNotFoundException("File not found: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun enforceSameAppCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Isolated voice workspace requires same-app caller")
        }
    }

    private fun resolve(documentId: String?): File {
        val id = documentId?.takeIf { it.isNotEmpty() } ?: throw FileNotFoundException("Missing documentId")
        val root = workspaceDir(requireNotNull(context)).canonicalFile
        if (id == ROOT_ID) return root
        val relative = try {
            WorkspacePaths.relative(id)
        } catch (invalid: IllegalArgumentException) {
            throw FileNotFoundException(invalid.message)
        }
        val candidate = File(root, relative).canonicalFile
        if (!candidate.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Path escapes isolated workspace")
        }
        return candidate
    }

    private fun rowFor(columns: Array<out String>, id: String, file: File, isRoot: Boolean): Array<Any?> {
        val isDir = isRoot || file.isDirectory
        val mime = if (isDir) Document.MIME_TYPE_DIR else mimeTypeFor(file.name)
        val size: Long? = if (isDir) 0L else file.length()
        val modified = if (file.exists()) file.lastModified() else 0L
        return Array(columns.size) { index ->
            when (columns[index]) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> if (isRoot) WORKSPACE_NAME else file.name
                Document.COLUMN_MIME_TYPE -> mime
                Document.COLUMN_SIZE -> size
                Document.COLUMN_LAST_MODIFIED -> modified
                Document.COLUMN_FLAGS -> 0
                else -> null
            }
        }
    }

    private fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "txt" || ext == "md" || ext == "log") return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        const val ROOT_ID = "root"
        const val WORKSPACE_NAME = "native-voice-workspace"

        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS
        )

        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )

        fun workspaceDir(context: Context): File =
            File(context.applicationContext.filesDir, WORKSPACE_NAME)

        fun workspaceLocation(context: Context): WorkspaceLocation {
            val authority = "${context.applicationContext.packageName}.native_voice_workspace"
            val treeUri = DocumentsContract.buildTreeDocumentUri(authority, ROOT_ID).toString()
            return WorkspaceLocation(treeUri, WORKSPACE_NAME, "Floryx 隔离工作区")
        }
    }
}
