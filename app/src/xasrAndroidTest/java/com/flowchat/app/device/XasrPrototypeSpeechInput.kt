package com.flowchat.app.device

import android.content.res.AssetManager
import com.flowchat.app.presentation.assistant.XasrModelPack
import com.flowchat.app.presentation.assistant.XasrSpeechInput
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal typealias XasrPrototypeSpeechInput = XasrSpeechInput

/** Only stages files from the signed test APK into a new, test-owned private directory. */
internal class StagedXasrFiles(private val assets: AssetManager, private val path: File) {
    private val lock = Mutex()
    private var owned = false
    private var prepared = false

    suspend fun prepare(): File = lock.withLock {
        if (!prepared) {
            check(path.mkdir()) { "Cannot create a new X-ASR test cache" }
            owned = true
            val buffer = ByteArray(128 * 1024)
            for (name in XasrModelPack.files.keys) {
                assets.open(name, AssetManager.ACCESS_STREAMING).use { source ->
                    path.resolve(name).outputStream().use { sink ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                        }
                    }
                }
            }
            prepared = true
        }
        path
    }

    suspend fun sizeBytes(): Long = lock.withLock { XasrModelPack.files.keys.sumOf { path.resolve(it).length() } }

    suspend fun remove() = lock.withLock {
        if (owned) check(path.deleteRecursively()) { "Cannot remove the X-ASR test cache" }
    }
}

