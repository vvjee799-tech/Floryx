package com.flowchat.app.domain.chat

import kotlinx.serialization.Serializable

/** Produced by trusted readers, never from model claims or tool success prose. */
data class TaskInformationObservation(
    val sourceSha256: String,
    val versionSha256: String,
    val versionConfidence: String,
    val ranges: List<TaskInformationRange> = emptyList(),
    val scan: TaskInformationScan? = null,
    val factSha256: String? = null
)

@Serializable
data class TaskInformationRange(val start: Long, val end: Long)

/** A negative scan excludes only these bytes for this exact literal query. */
data class TaskInformationScan(val querySha256: String, val range: TaskInformationRange)

@Serializable
data class TaskInformationSourceProgress(
    val sourceSha256: String,
    val versionSha256: String,
    val versionConfidence: String,
    val versionChanges: Int = 0,
    val ranges: List<TaskInformationRange> = emptyList(),
    val scanQuerySha256: String? = null,
    val scannedRanges: List<TaskInformationRange> = emptyList(),
    val factRecorded: Boolean = false
)

@Serializable
data class TaskInformationProgress(val sources: List<TaskInformationSourceProgress> = emptyList()) {
    data class Update(val progress: TaskInformationProgress, val observed: Boolean)

    fun record(observation: TaskInformationObservation?): Update {
        if (observation == null) return Update(this, false)
        val index = sources.indexOfFirst { it.sourceSha256 == observation.sourceSha256 }
        if (index < 0 && sources.size >= MaxSources) return Update(this, false)
        val previous = sources.getOrNull(index)
        var source = previous ?: TaskInformationSourceProgress(observation.sourceSha256,
            observation.versionSha256, observation.versionConfidence)
        if (source.versionSha256 != observation.versionSha256 || source.versionConfidence != observation.versionConfidence) {
            // One bounded refresh is allowed. The transition itself is not progress, and
            // a second change freezes credit for this source rather than renewing it forever.
            source = source.copy(versionSha256 = observation.versionSha256, versionConfidence = observation.versionConfidence,
                versionChanges = (source.versionChanges + 1).coerceAtMost(2), ranges = emptyList(), scannedRanges = emptyList())
            return Update(replace(index, source), false)
        }
        if (source.versionChanges >= 2) return Update(this, false)
        val ranges = merge(source.ranges, observation.ranges)
        // Only the first negative query can earn scan coverage. Synonyms, case changes
        // and endless absent words cannot keep a task alive. Positive contexts use ranges.
        val query = source.scanQuerySha256 ?: observation.scan?.querySha256
        val scans = if (observation.scan?.querySha256 == query)
            merge(source.scannedRanges, listOfNotNull(observation.scan?.range)) else source.scannedRanges
        val newFact = observation.factSha256 != null && !source.factRecorded
        val observed = ranges != source.ranges || scans != source.scannedRanges || newFact
        source = source.copy(ranges = ranges, scanQuerySha256 = query, scannedRanges = scans,
            factRecorded = source.factRecorded || newFact)
        return Update(replace(index, source), observed)
    }

    private fun replace(index: Int, source: TaskInformationSourceProgress): TaskInformationProgress =
        copy(sources = if (index < 0) sources + source else sources.toMutableList().apply { set(index, source) })

    private fun merge(old: List<TaskInformationRange>, added: List<TaskInformationRange>): List<TaskInformationRange> {
        val merged = mutableListOf<TaskInformationRange>()
        for (range in (old + added.filter { it.start >= 0 && it.end > it.start }).sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && range.start <= last.end) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, range.end))
            else merged += range
        }
        // Never evict evidence: eviction would make old reads appear new after rebuilding.
        return if (merged.size > MaxRanges) old else merged
    }

    companion object {
        const val MaxSources = 32
        const val MaxRanges = 64
    }
}
