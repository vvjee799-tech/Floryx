package com.flowchat.app.domain.appusage

import com.flowchat.app.domain.model.AppUsageSummary
import com.flowchat.app.domain.model.RecentAppActivity

object AppUsageToolFormatter {
    fun formatSummary(summary: AppUsageSummary): String = buildString {
        appendLine("# App usage summary")
        appendLine("This tool only reports app foreground usage metadata. It does not expose in-app messages, feed content, account content, passwords, or private screen text.")
        appendLine()
        appendLine("<app_usage_summary range=\"${summary.range.escapeXml()}\">")
        appendLine("<total_foreground_time>${summary.totalForegroundTimeMillis.formatDuration()}</total_foreground_time>")
        summary.items.take(MaxItems).forEach { item ->
            appendLine("<app name=\"${item.appName.escapeXml()}\" package=\"${item.packageName.escapeXml()}\">")
            appendLine("<foreground_time>${item.totalForegroundTimeMillis.formatDuration()}</foreground_time>")
            appendLine("<last_used>${item.lastTimeUsedMillis}</last_used>")
            appendLine("</app>")
        }
        append("</app_usage_summary>")
    }

    fun formatRecentActivity(activity: RecentAppActivity): String = buildString {
        appendLine("# Recent app activity")
        appendLine("This tool reports recent foreground app switches only. It does not expose content inside apps.")
        appendLine()
        appendLine("<recent_app_activity hours=\"${activity.hours}\">")
        activity.events.take(MaxEvents).forEach { event ->
            appendLine("<event app=\"${event.appName.escapeXml()}\" package=\"${event.packageName.escapeXml()}\" timestamp=\"${event.timestampMillis}\" />")
        }
        append("</recent_app_activity>")
    }

    private fun Long.formatDuration(): String {
        val totalMinutes = (this / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours} 小时 ${minutes} 分钟"
            hours > 0L -> "${hours} 小时"
            else -> "${minutes} 分钟"
        }
    }

    private fun String.escapeXml(): String = buildString {
        this@escapeXml.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                }
            )
        }
    }

    private const val MaxItems = 10
    private const val MaxEvents = 20
}
