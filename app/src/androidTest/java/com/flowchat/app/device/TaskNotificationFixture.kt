package com.flowchat.app.device

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/** Select the actual TaskExecutionService notification, even when the ROM adds a channel summary. */
internal object TaskNotificationFixture {
    fun current(context: Context, report: (String) -> Unit = {}): Notification? {
        val candidates = context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.notification.channelId == "assistant_tasks" }
        report("task-notification-candidates=" + candidates.map {
            "id=${it.id},tag=${it.tag},uid=${it.uid},package=${it.packageName}," +
                "summary=${it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0},actions=${it.notification.actions?.size ?: 0}"
        })
        // ID and untagged identity are the existing TaskExecutionService foreground contract.
        return candidates.singleOrNull { it.id == 109 && it.tag == null &&
            it.packageName == context.packageName && it.uid == context.applicationInfo.uid &&
            it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }?.notification
    }

    fun sendStop(context: Context, report: (String) -> Unit) {
        val notification = checkNotNull(current(context, report)) { "The exact task service notification is unavailable." }
        check(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        val stop = checkNotNull(notification.actions).single().actionIntent
        check(stop.creatorPackage == context.packageName && stop.creatorUid == context.applicationInfo.uid)
        stop.send()
    }
}
