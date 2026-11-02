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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var powerLease: TaskPowerLease? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Channel, getString(R.string.task_notification_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, AssistantPanelActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TaskExecutionService::class.java).setAction(Stop), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notification(count: Int) = NotificationCompat.Builder(this, Channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.task_notification_title))
            .setContentText(getString(R.string.task_notification_active, count))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.stop), stop).build()
        ServiceCompat.startForeground(this, NotificationId, notification(runtime.activeConversations.value.size),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        powerLease = TaskPowerLease(this)
        scope.launch {
            runtime.backgroundWorkActive.collectLatest { active ->
                try {
                    if (active) while (true) {
                        powerLease?.renew()
                        delay(TaskPowerLease.RenewMillis)
                    }
                } finally { powerLease?.close() }
            }
        }
        scope.launch {
            runtime.activeConversations.collectLatest { active ->
                if (active.isEmpty()) {
                    delay(750)
                    stopSelf()
                } else if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@TaskExecutionService,
                        android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    manager.notify(NotificationId, notification(active.size))
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
        powerLease?.close()
        powerLease = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val Channel = "assistant_tasks"
        private const val NotificationId = 109
        private const val Stop = "com.flowchat.STOP_TASKS"
    }
}
