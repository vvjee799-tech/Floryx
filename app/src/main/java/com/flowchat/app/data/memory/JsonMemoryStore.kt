package com.flowchat.app.data.memory

import com.flowchat.app.domain.memory.MemoryPolicy
import com.flowchat.app.domain.model.MemoryDraft
import com.flowchat.app.domain.model.MemoryRecord
import com.flowchat.app.domain.model.MemorySnapshot
import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class JsonMemoryStore(
    private val file: File,
    private val retentionLimit: Int = 80,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
) {
    private var revision = 0L

    fun readAll(): List<MemoryRecord> {
        if (!file.exists()) return emptyList()
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) return emptyList()
        val records = runCatching { json.decodeFromString(ListSerializer(MemoryRecord.serializer()), text) }.getOrDefault(emptyList())
        if (records.all { it.formatVersion == FormatVersion }) return records
        val migrated = records.mapNotNull { record ->
            if (record.formatVersion == FormatVersion) record else MemoryPolicy.migrateLegacy(record.goal)?.let {
                record.copy(goal = it.category, summary = it.summary, key = it.key,
                    category = it.category, quality = "summary", formatVersion = FormatVersion)
            }
        }.distinctBy { it.summary.lowercase() }.takeLast(retentionLimit.coerceAtLeast(1))
        writeAll(migrated)
        return migrated
    }

    fun snapshot(): MemorySnapshot {
        val records = readAll()
        return MemorySnapshot(records, revision)
    }

    fun merge(drafts: List<MemoryDraft>, expectedRevision: Long, timestamp: Long, canWrite: () -> Boolean = { true }): Boolean {
        val records = readAll().toMutableList()
        if (revision != expectedRevision || !canWrite()) return false
        val valid = drafts.filter(MemoryPolicy::valid).take(3)
        if (valid.isEmpty()) return false
        valid.forEach { draft ->
            val index = records.indexOfFirst { it.key == draft.key || it.summary.equals(draft.summary, true) }
            val previous = records.getOrNull(index)
            val record = MemoryRecord(previous?.id ?: UUID.randomUUID().toString(), draft.category,
                draft.summary, "summary", timestamp, draft.key, draft.category, FormatVersion)
            if (index >= 0) records[index] = record else records += record
        }
        writeAll(records.sortedBy { it.timestamp }.takeLast(retentionLimit.coerceAtLeast(1)))
        return true
    }

    fun update(id: String, summary: String) {
        val clean = summary.trim()
        require(MemoryPolicy.validSummary(clean))
        writeAll(readAll().map { if (it.id == id) it.copy(summary = clean) else it })
    }

    fun delete(id: String) { writeAll(readAll().filterNot { it.id == id }) }
    fun clear() { writeAll(emptyList()) }

    fun retrieve(userMessage: String, topN: Int): List<MemoryRecord> {
        val query = userMessage.keywords()
        return readAll().map { it to (it.summary + " " + it.key).keywords().intersect(query).size }
            .filter { (record, score) -> score > 0 || record.category in setOf("preference", "profile") }
            .sortedWith(compareByDescending<Pair<MemoryRecord, Int>> { it.second }.thenByDescending { it.first.timestamp })
            .take(topN.coerceIn(1, 5)).map { it.first }
    }

    private fun writeAll(records: List<MemoryRecord>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(ListSerializer(MemoryRecord.serializer()), records), Charsets.UTF_8)
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        revision++
    }

    private fun String.keywords(): Set<String> = Regex("[\\u4E00-\\u9FFFA-Za-z0-9_]+").findAll(lowercase()).flatMap { match ->
        val value = match.value
        if (value.any { it in '\u4E00'..'\u9FFF' } && value.length > 1) value.windowed(2).asSequence()
        else sequenceOf(value).filter { it.length >= 2 }
    }.toSet()

    companion object { const val FormatVersion = 2 }
}
