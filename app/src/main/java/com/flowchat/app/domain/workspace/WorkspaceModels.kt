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
