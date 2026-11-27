package com.flowchat.app.diagnostics.nativevoice

import android.content.Context
import com.flowchat.app.BuildConfig
import com.flowchat.app.domain.workspace.WorkspaceLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Selects between real and local voice runtimes in debug builds and keeps test workspace lookup debug-only. */
internal object NativeVoiceBackendFactory {
    fun create(context: Context): VoiceTaskBackend =
        if (BuildConfig.NATIVE_VOICE_REAL_RUNTIME) {
            RealVoiceTaskRuntime(context)
        } else {
            LocalVoiceTaskRuntime(context)
        }

    suspend fun resolveWorkspace(context: Context, defaultWorkspace: WorkspaceLocation?): WorkspaceLocation? {
        val isolatedDir = NativeVoiceWorkspaceProvider.workspaceDir(context)
        val hasIsolatedFiles = withContext(Dispatchers.IO) {
            isolatedDir.isDirectory && isolatedDir.listFiles()?.any { it.isFile } == true
        }
        return if (hasIsolatedFiles) {
            NativeVoiceWorkspaceProvider.workspaceLocation(context)
        } else {
            defaultWorkspace
        }
    }
}
