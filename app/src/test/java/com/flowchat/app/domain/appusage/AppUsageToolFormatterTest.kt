package com.flowchat.app.domain.appusage

import com.flowchat.app.domain.model.AppUsageItem
import com.flowchat.app.domain.model.AppUsageSummary
import com.flowchat.app.domain.model.RecentAppActivity
import com.flowchat.app.domain.model.RecentAppEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageToolFormatterTest {
    @Test
    fun formatsSummaryForToolResultWithoutRawPrivateContent() {
        val text = AppUsageToolFormatter.formatSummary(
            AppUsageSummary(
                range = "today",
                startTimeMillis = 1_000L,
                endTimeMillis = 3_600_000L,
                totalForegroundTimeMillis = 2_400_000L,
                items = listOf(
                    AppUsageItem(
                        packageName = "com.tencent.mm",
                        appName = "WeChat",
                        totalForegroundTimeMillis = 1_200_000L,
                        lastTimeUsedMillis = 3_500_000L
                    )
                )
            )
        )

        assertTrue(text.contains("<app_usage_summary range=\"today\">"))
        assertTrue(text.contains("<total_foreground_time>40 分钟</total_foreground_time>"))
        assertTrue(text.contains("<app name=\"WeChat\" package=\"com.tencent.mm\">"))
        assertFalse(text.contains("message content"))
    }

    @Test
    fun formatsRecentActivityEventsForToolResult() {
        val text = AppUsageToolFormatter.formatRecentActivity(
            RecentAppActivity(
                hours = 3,
                events = listOf(
                    RecentAppEvent(
                        packageName = "tv.danmaku.bili",
                        appName = "Bilibili",
                        timestampMillis = 3_600_000L
                    )
                )
            )
        )

        assertTrue(text.contains("<recent_app_activity hours=\"3\">"))
        assertTrue(text.contains("<event app=\"Bilibili\" package=\"tv.danmaku.bili\""))
    }
}
