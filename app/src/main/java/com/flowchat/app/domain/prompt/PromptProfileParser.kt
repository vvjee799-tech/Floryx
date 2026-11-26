package com.flowchat.app.domain.prompt

data class PromptProfileSource(
    val name: String,
    val content: String
)

data class PromptProfileConfig(
    val thinkingFormat: String? = null,
    val memory: PromptMemoryConfig = PromptMemoryConfig()
)

data class PromptMemoryConfig(
    val enabled: Boolean = false,
    val topN: Int = 5
)

object PromptProfileParser {
    fun selectActiveProfile(sources: List<PromptProfileSource>): PromptProfileConfig {
        val profiles = sources.map { parseProfile(it.content) }
        val selected = profiles.firstOrNull { it.active } ?: profiles.singleOrNull()
        return selected?.toConfig() ?: PromptProfileConfig()
    }

    fun selectActiveThinkingFormat(sources: List<PromptProfileSource>): String? {
        return selectActiveProfile(sources).thinkingFormat
    }

    private fun parseProfile(content: String): PromptProfile {
        val lines = content.lines()
        var active = false
        var thinkingFormat: String? = null
        var memoryEnabled = false
        var memoryTopN = 5
        var section: String? = null
        var index = 0

        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) {
                index++
                continue
            }

            val sectionName = line.sectionNameOrNull()
            if (sectionName != null) {
                section = sectionName
                index++
                continue
            }

            if (section == null && line.startsWithKey("active")) {
                active = line.substringAfter("=").trim().equals("true", ignoreCase = true)
                index++
                continue
            }

            if (section == "thinking" && line.startsWithKey("format")) {
                val parsed = parseTomlString(lines, index)
                thinkingFormat = parsed.value
                index = parsed.nextIndex
                continue
            }

            if (section == "memory" && line.startsWithKey("enabled")) {
                memoryEnabled = line.substringAfter("=").trim().equals("true", ignoreCase = true)
                index++
                continue
            }

            if (section == "memory" && line.startsWithKey("top_n")) {
                memoryTopN = line.substringAfter("=")
                    .trim()
                    .toIntOrNull()
                    ?.takeIf { it in 1..5 }
                    ?: 5
                index++
                continue
            }

            index++
        }

        return PromptProfile(
            active = active,
            thinkingFormat = thinkingFormat,
            memoryEnabled = memoryEnabled,
            memoryTopN = memoryTopN
        )
    }

    private fun parseTomlString(lines: List<String>, startIndex: Int): ParsedString {
        val firstValue = lines[startIndex].substringAfter("=", missingDelimiterValue = "").trim()
        if (firstValue.startsWith(TomlMultilineStringDelimiter)) {
            val afterOpening = firstValue.removePrefix(TomlMultilineStringDelimiter)
            val sameLineClose = afterOpening.indexOf(TomlMultilineStringDelimiter)
            if (sameLineClose >= 0) {
                return ParsedString(afterOpening.substring(0, sameLineClose), startIndex + 1)
            }

            val builder = StringBuilder()
            if (afterOpening.isNotEmpty()) {
                builder.appendLine(afterOpening)
            }
            var index = startIndex + 1
            while (index < lines.size) {
                val line = lines[index]
                val closeIndex = line.indexOf(TomlMultilineStringDelimiter)
                if (closeIndex >= 0) {
                    builder.append(line.substring(0, closeIndex))
                    return ParsedString(builder.toString().trimIndent().trim(), index + 1)
                }
                builder.appendLine(line)
                index++
            }
            return ParsedString(builder.toString().trimIndent().trim(), lines.size)
        }

        return ParsedString(parseBasicString(firstValue), startIndex + 1)
    }

    private fun parseBasicString(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith('"')) return trimmed
        val closingIndex = trimmed.indexOfLast { it == '"' }
        if (closingIndex <= 0) return trimmed.removePrefix("\"")
        val body = trimmed.substring(1, closingIndex)
        return body
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun String.sectionNameOrNull(): String? =
        if (startsWith("[") && endsWith("]")) {
            removePrefix("[")
                .removeSuffix("]")
                .trim()
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }

    private fun String.startsWithKey(key: String): Boolean =
        startsWith("$key ") || startsWith("$key=")

    private data class PromptProfile(
        val active: Boolean,
        val thinkingFormat: String?,
        val memoryEnabled: Boolean,
        val memoryTopN: Int
    ) {
        fun toConfig(): PromptProfileConfig =
            PromptProfileConfig(
                thinkingFormat = thinkingFormat?.trim()?.takeIf { it.isNotEmpty() },
                memory = PromptMemoryConfig(
                    enabled = memoryEnabled,
                    topN = memoryTopN.takeIf { it in 1..5 } ?: 5
                )
            )
    }

    private data class ParsedString(
        val value: String,
        val nextIndex: Int
    )

    private const val TomlMultilineStringDelimiter = "\"\"\""
}
