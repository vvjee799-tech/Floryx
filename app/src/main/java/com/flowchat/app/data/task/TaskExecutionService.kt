package com.flowchat.app.data.task

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.R
import com.flowchat.app.presentation.chat.ChatTaskRuntime
import com.flowchat.app.domain.chat.TaskPowerOwner
import com.flowchat.app.domain.chat.TaskPowerWork
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The visible lifetime of user-started tasks is independent of Activity and overlay permission. */
@AndroidEntryPoint
class TaskExecutionService : Service() {
    @Inject lateinit var runtime: ChatTaskRuntime
    @Inject lateinit var powerMonitor: TaskPowerMonitor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var powerSession: TaskPowerSession? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Channel, getString(R.string.task_notification_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, AssistantPanelActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TaskExecutionService::class.java).setAction(Stop), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notification(owners: Set<TaskPowerOwner>) = NotificationCompat.Builder(this, Channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.task_notification_title))
            .setContentText(if (owners.isNotEmpty() && owners.all { it.work in setOf(TaskPowerWork.Finalizing, TaskPowerWork.WaitingForCleanup) })
                getString(R.string.task_status_finishing) else getString(R.string.task_notification_active, owners.map { it.conversationId }.distinct().size))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.stop), stop).build()
        ServiceCompat.startForeground(this, NotificationId, notification(runtime.taskPowerOwners.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        val session = TaskPowerSession(TaskPowerLease(this), powerMonitor)
        powerSession = session
        scope.launch {
            runtime.taskPowerOwners.collectLatest { owners ->
                session.update(owners)
                if (owners.isEmpty()) {
                    delay(750)
                    if (runtime.taskPowerOwners.value.isEmpty()) {
                        powerMonitor.stopping(session.id)
                        stopSelf()
                    }
                } else if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@TaskExecutionService,
                        android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    manager.notify(NotificationId, notification(owners))
                }
                if (owners.any { it.needsPower }) while (true) {
                    delay(TaskPowerLease.RenewMillis)
                    session.update(owners)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Stop) runtime.stopAllTasks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        powerSession?.close()
        powerSession = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val Channel = "assistant_tasks"
        private const val NotificationId = 109
        private const val Stop = "com.flowchat.STOP_TASKS"
    }
}
