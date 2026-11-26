package com.flowchat.app.domain.repository

import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.MemoryDraft
import com.flowchat.app.domain.model.MemorySnapshot

interface MemoryRepository {
    suspend fun retrieve(userMessage: String, topN: Int): List<MemoryRecord>
    suspend fun snapshot(): MemorySnapshot
    suspend fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, canWrite: () -> Boolean): Boolean
    suspend fun update(id: String, summary: String)
    suspend fun getAll(): List<MemoryRecord>
    suspend fun delete(id: String)
    suspend fun clear()
}
