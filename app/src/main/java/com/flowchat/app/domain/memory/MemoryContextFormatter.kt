package com.flowchat.app.domain.memory

import com.flowchat.app.domain.model.MemoryRecord

object MemoryContextFormatter {
    fun format(records: List<MemoryRecord>): List<String> =
        records.map { record ->
            record.summary.oneLine().limit(MaxMemoryLineLength)
        }

    private fun String.oneLine(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun String.limit(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength).trimEnd() + "..."

    private const val MaxMemoryLineLength = 180
}
