package com.flowchat.app.data.appusage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.flowchat.app.domain.model.AppUsageItem
import com.flowchat.app.domain.model.AppUsageSummary
import com.flowchat.app.domain.model.RecentAppActivity
import com.flowchat.app.domain.model.RecentAppEvent
import com.flowchat.app.domain.repository.AppUsageReader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AndroidAppUsageReader @Inject constructor(
    @ApplicationContext private val context: Context
) : AppUsageReader {
    override fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override suspend fun getUsageSummary(range: String): AppUsageSummary = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) throw UsageAccessNotGrantedException()
        val (start, end, normalizedRange) = usageWindow(range)
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: throw IllegalStateException("UsageStatsManager unavailable")
        val packageManager = context.packageManager
        val items = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .orEmpty()
            .filter { it.totalTimeInForeground > 0L }
            .map { stat ->
                AppUsageItem(
                    packageName = stat.packageName,
                    appName = packageManager.safeAppName(stat.packageName),
                    totalForegroundTimeMillis = stat.totalTimeInForeground,
                    lastTimeUsedMillis = stat.lastTimeUsed
                )
            }
            .groupBy { it.packageName }
            .map { (_, appItems) ->
                val first = appItems.first()
                AppUsageItem(
                    packageName = first.packageName,
                    appName = first.appName,
                    totalForegroundTimeMillis = appItems.sumOf { it.totalForegroundTimeMillis },
                    lastTimeUsedMillis = appItems.maxOf { it.lastTimeUsedMillis }
                )
            }
            .sortedByDescending { it.totalForegroundTimeMillis }
        AppUsageSummary(
            range = normalizedRange,
            startTimeMillis = start,
            endTimeMillis = end,
            totalForegroundTimeMillis = items.sumOf { it.totalForegroundTimeMillis },
            items = items
        )
    }

    override suspend fun getRecentActivity(hours: Int): RecentAppActivity = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) throw UsageAccessNotGrantedException()
        val normalizedHours = hours.coerceIn(1, 24)
        val end = System.currentTimeMillis()
        val start = end - normalizedHours * 60L * 60L * 1000L
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: throw IllegalStateException("UsageStatsManager unavailable")
        val packageManager = context.packageManager
        val usageEvents = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        val events = mutableListOf<RecentAppEvent>()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.isForegroundEvent()) {
                events += RecentAppEvent(
                    packageName = event.packageName.orEmpty(),
                    appName = packageManager.safeAppName(event.packageName.orEmpty()),
                    timestampMillis = event.timeStamp
                )
            }
        }
        RecentAppActivity(
            hours = normalizedHours,
            events = events.asReversed().distinctBy { it.packageName }.take(MaxRecentEvents)
        )
    }

    private fun usageWindow(range: String): Triple<Long, Long, String> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val normalized = when (range) {
            "yesterday", "last_7_days" -> range
            else -> "today"
        }
        return when (normalized) {
            "yesterday" -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val end = calendar.timeInMillis
                calendar.add(Calendar.DATE, -1)
                Triple(calendar.timeInMillis, end, normalized)
            }
            "last_7_days" -> {
                calendar.timeInMillis = now
                calendar.add(Calendar.DATE, -7)
                Triple(calendar.timeInMillis, now, normalized)
            }
            else -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Triple(calendar.timeInMillis, now, normalized)
            }
        }
    }

    private fun UsageEvents.Event.isForegroundEvent(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            eventType == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
        }

    private fun PackageManager.safeAppName(packageName: String): String =
        runCatching {
            val appInfo = getApplicationInfo(packageName, 0)
            getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)

    private companion object {
        const val MaxRecentEvents = 20
    }
}

class UsageAccessNotGrantedException : IllegalStateException("App usage access is not enabled.")
