package com.flowchat.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryRecord(
    val id: String,
    val goal: String,
    val summary: String,
    val quality: String,
    val timestamp: Long,
    val key: String = "",
    val category: String = "",
    val formatVersion: Int = 0
)

data class MemoryDraft(val key: String, val category: String, val summary: String)
data class MemorySnapshot(val records: List<MemoryRecord>, val revision: Long)
