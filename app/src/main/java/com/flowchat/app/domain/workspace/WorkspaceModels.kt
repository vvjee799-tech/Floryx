package com.flowchat.app.domain.workspace

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WorkspaceLocation(val treeUri: String, val name: String, val displayPath: String = name) {
    val key: String get() = WorkspacePaths.digest(treeUri.toByteArray()).take(20)
}

object WorkspaceCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(location: WorkspaceLocation?): String? = location?.let { json.encodeToString(it) }
    fun decode(value: String?): WorkspaceLocation? = value?.let { runCatching { json.decodeFromString<WorkspaceLocation>(it) }.getOrNull() }
}

enum class WorkspaceAccess { None, ReadOnly, ReadWrite, Unavailable }
data class WorkspaceStatus(val access: WorkspaceAccess = WorkspaceAccess.None, val detail: String? = null)
data class WorkspaceFile(val path: String, val name: String, val directory: Boolean, val size: Long?, val modified: Long)
data class WorkspacePage(val files: List<WorkspaceFile>, val nextOffset: Int?)
data class WorkspaceText(val text: String, val revision: String, val bytes: Int)
/** Provider metadata is weak version evidence, never a whole-file content revision. */
data class WorkspaceSource(val path: String, val size: Long?, val modified: Long, val mime: String,
    val version: String?, val versionConfidence: String)
data class WorkspaceRange(val source: WorkspaceSource, val requestedOffset: Long, val start: Long, val end: Long,
    val text: String?, val hex: String?, val eof: Boolean, val accessMode: String, val skippedBytes: Long,
    val sourceBytesRead: Int, val formatHint: String? = null)
data class WorkspaceSearchHit(val start: Long, val end: Long, val context: WorkspaceRange)
data class WorkspaceSearch(val source: WorkspaceSource, val start: Long, val end: Long, val eof: Boolean,
    val nextOffset: Long?, val hits: List<WorkspaceSearchHit>, val accessMode: String, val skippedBytes: Long)
data class WorkspaceInspectionHint(val category: String, val marker: String, val offset: Long, val context: WorkspaceRange)
data class WorkspaceInspectionHints(val source: WorkspaceSource, val scannedBytes: Long, val eof: Boolean,
    val hints: List<WorkspaceInspectionHint>, val scanLimit: Int, val accessMode: String,
    val structure: WorkspaceTextStructure)

/** Physical byte lines only. Prefix categories do not interpret shell quoting, continuations or here-documents. */
data class WorkspaceTextLine(val number: Long, val start: Long, val end: Long, val lfTerminated: Boolean,
    val kind: String, val firstNonblank: Long, val prefix: WorkspaceRange)

data class WorkspaceTextStructure(val lfCount: Long, val observedLines: Long, val wholeFileLines: Long?,
    val longestObservedLineBytes: Long, val prefixCounts: Map<String, Long>, val samples: List<WorkspaceTextLine>)
data class WorkspaceSave(val path: String, val revision: String, val conflict: Boolean, val workspaceName: String? = null)

@Serializable
data class WorkspacePending(
    val location: WorkspaceLocation,
    val path: String,
    val baseline: String?,
    val localPath: String,
    val error: String? = null
)

data class WorkspaceUiState(
    val location: WorkspaceLocation? = null,
    val status: WorkspaceStatus = WorkspaceStatus(),
    val busy: Boolean = false,
    val pending: List<WorkspacePending> = emptyList(),
    val error: String? = null,
    val lastSave: WorkspaceSave? = null
)

object WorkspacePaths {
    fun relative(path: String, allowRoot: Boolean = false): String {
        if (allowRoot && (path.isEmpty() || path == ".")) return ""
        require(path.isNotEmpty() && path.length <= 1024 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path) {
            "Use a relative path inside the selected workspace."
        }
        val parts = path.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." || ':' in it || it.any(Char::isISOControl) }) {
            "Invalid workspace path."
        }
        return parts.joinToString("/")
    }
    fun owner(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid conversation identifier." }
        return value
    }
    fun within(root: File, path: String): File {
        val result = File(root, relative(path)).canonicalFile
        require(result.path.startsWith(root.canonicalPath + File.separator)) { "The path leaves the workspace." }
        return result
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
