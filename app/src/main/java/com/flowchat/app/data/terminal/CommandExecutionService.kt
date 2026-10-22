package com.flowchat.app.data.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class CommandExecutionService : Service() {
    @Inject lateinit var terminal: TerminalManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Channel, getString(R.string.terminal_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CommandExecutionService::class.java).setAction(Stop), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(108, Notification.Builder(this, Channel)
            .setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle(getString(R.string.terminal_title))
            .setContentText(getString(R.string.terminal_notification)).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build())
        var sawWork = false
        scope.launch {
            terminal.state.map { it.linux.preparing || it.commands.any { command -> command.isRunning } }.distinctUntilChanged().collectLatest { active ->
                if (active) sawWork = true else {
                    delay(if (sawWork) 750 else 15_000)
                    stopSelf()
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Stop) terminal.cancelAll()
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val Channel = "terminal_commands"; private const val Stop = "com.flowchat.STOP_COMMANDS" }
}
