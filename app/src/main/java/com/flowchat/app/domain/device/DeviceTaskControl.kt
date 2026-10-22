package com.flowchat.app.domain.device

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A user returning to FlowChat takes control away from the active phone task. */
@Singleton
class DeviceTaskControl @Inject constructor() {
    class Task internal constructor(internal val startedAtReturn: Long) {
        @Volatile internal var armed = false
        @Volatile internal var closed = false
        @Volatile var interrupted = false
            internal set
    }

    private var appInForeground = true
    private val mutableReturns = MutableStateFlow(0L)
    val foregroundReturns = mutableReturns.asStateFlow()
    @Volatile var currentTask: Task? = null
        private set

    @Synchronized fun begin(): Task {
        currentTask?.closed = true
        return Task(mutableReturns.value).also { currentTask = it }
    }

    @Synchronized fun setAppInForeground(foreground: Boolean) {
        if (foreground && !appInForeground) {
            currentTask?.takeIf { it.armed }?.interrupted = true
            mutableReturns.value += 1
        }
        appInForeground = foreground
    }

    @Synchronized fun arm(task: Task) {
        task.armed = true
        if (task.startedAtReturn != mutableReturns.value) task.interrupted = true
        checkCanAct(task)
    }

    fun checkCanAct(task: Task?) {
        if (task != null && (task.interrupted || task.closed || currentTask !== task)) {
            throw CancellationException("User took control of the phone task.")
        }
    }

    @Synchronized fun finish(task: Task?) {
        task?.closed = true
        if (currentTask === task) currentTask = null
    }
}
