package com.flowchat.app.data.task

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskBackgroundController @Inject constructor(@ApplicationContext private val context: Context) {
    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, TaskExecutionService::class.java))
    }
}
