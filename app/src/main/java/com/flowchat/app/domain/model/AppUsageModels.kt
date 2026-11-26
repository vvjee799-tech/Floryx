package com.flowchat.app.domain.model

data class AppUsageSummary(
    val range: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val totalForegroundTimeMillis: Long,
    val items: List<AppUsageItem>
)

data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val totalForegroundTimeMillis: Long,
    val lastTimeUsedMillis: Long
)

data class RecentAppActivity(
    val hours: Int,
    val events: List<RecentAppEvent>
)

data class RecentAppEvent(
    val packageName: String,
    val appName: String,
    val timestampMillis: Long
)
