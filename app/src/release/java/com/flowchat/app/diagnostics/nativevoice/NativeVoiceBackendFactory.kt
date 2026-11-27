package com.flowchat.app.diagnostics.nativevoice

import android.content.Context
import com.flowchat.app.domain.workspace.WorkspaceLocation

/** Always binds the live Floryx task runtime and default user workspace in release builds. */
internal object NativeVoiceBackendFactory {
    fun create(context: Context): VoiceTaskBackend = RealVoiceTaskRuntime(context)

    @Suppress("RedundantSuspendModifier")
    suspend fun resolveWorkspace(context: Context, defaultWorkspace: WorkspaceLocation?): WorkspaceLocation? =
        defaultWorkspace
}
