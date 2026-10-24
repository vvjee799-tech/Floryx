package com.flowchat.app.data.workspace

import com.flowchat.app.domain.workspace.*

/** Document access is separate from transfer state so both can be verified without a device. */
interface WorkspaceFileAccess {
    fun status(location: WorkspaceLocation): WorkspaceStatus
    fun list(location: WorkspaceLocation, path: String, offset: Int, limit: Int): WorkspacePage
    fun read(location: WorkspaceLocation, path: String, maxBytes: Int): ByteArray
    fun exists(location: WorkspaceLocation, path: String): Boolean
    fun write(location: WorkspaceLocation, path: String, bytes: ByteArray)
    fun rename(location: WorkspaceLocation, path: String, newName: String): String
}
