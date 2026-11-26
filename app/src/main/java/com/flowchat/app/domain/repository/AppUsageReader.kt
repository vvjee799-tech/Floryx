package com.flowchat.app.domain.repository

import com.flowchat.app.domain.model.AppUsageSummary
import com.flowchat.app.domain.model.RecentAppActivity

interface AppUsageReader {
    fun hasUsageAccess(): Boolean
    suspend fun getUsageSummary(range: String): AppUsageSummary
    suspend fun getRecentActivity(hours: Int): RecentAppActivity
}
