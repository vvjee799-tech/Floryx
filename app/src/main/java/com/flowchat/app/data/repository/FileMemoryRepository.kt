package com.flowchat.app.data.repository

import android.content.Context
import com.flowchat.app.data.memory.JsonMemoryStore
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.MemoryDraft
import com.flowchat.app.domain.model.MemorySnapshot
import com.flowchat.app.domain.repository.MemoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class FileMemoryRepository @Inject constructor(
    @ApplicationContext context: Context
) : MemoryRepository {
    private val store = JsonMemoryStore(File(context.filesDir, MemoryStoreFileName))
    private val mutex = Mutex()

    override suspend fun retrieve(userMessage: String, topN: Int): List<MemoryRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.retrieve(userMessage, topN)
            }
        }

    override suspend fun snapshot(): MemorySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { store.snapshot() }
    }

    override suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.merge(drafts, expectedRevision, System.currentTimeMillis(), canWrite)
            }
        }

    override suspend fun update(id: String, summary: String) {
        withContext(Dispatchers.IO) { mutex.withLock { store.update(id, summary) } }
    }

    override suspend fun getAll(): List<MemoryRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.readAll().sortedByDescending { it.timestamp }
            }
        }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.delete(id)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.clear()
            }
        }
    }

    private companion object {
        const val MemoryStoreFileName = "memory_store.json"
    }
}
