package com.flowchat.app.domain.device

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit user interaction latches takeover until a new task, except during confirmation. */
@Singleton
class DeviceTaskControl @Inject constructor() {
    class Task internal constructor(internal var allowedThroughReturn: Long, internal val validateTarget: () -> Unit = {}) {
        val id: String = java.util.UUID.randomUUID().toString()
        internal val finishActions = mutableListOf<() -> Unit>()
        @Volatile internal var armed = false
        @Volatile internal var closed = false
        internal var awaitingUserConfirmation = false
        @Volatile var interrupted = false
            internal set
    }

    // A cold system-assistant entry can start a task before MainActivity has ever appeared.
    private var appInForeground = false
    private val mutableReturns = MutableStateFlow(0L)
    val controlChanges = mutableReturns.asStateFlow()
    @Volatile var currentTask: Task? = null
        private set

    @Synchronized fun begin(): Task {
        finish(currentTask)
        return Task(mutableReturns.value).also { currentTask = it }
    }

    /** Capture the user's current control boundary without taking another conversation's device. */
    @Synchronized fun prepare(validateTarget: () -> Unit = {}): Task = Task(mutableReturns.value, validateTarget)

    /** Called only after the caller has acquired the device execution lock. */
    @Synchronized fun activate(task: Task) {
        task.validateTarget()
        if (task.allowedThroughReturn != mutableReturns.value) task.interrupted = true
        if (task.closed || task.interrupted) throw CancellationException("User took control of the phone task.")
        currentTask?.takeIf { it !== task }?.let(::finish)
        currentTask = task
    }

    @Synchronized fun setAppInForeground(foreground: Boolean) {
        if (foreground && !appInForeground) {
            val returnCount = mutableReturns.value + 1
            currentTask?.let { task ->
                if (task.awaitingUserConfirmation) task.allowedThroughReturn = returnCount
                else if (task.armed) task.interrupted = true
            }
            mutableReturns.value = returnCount
        }
        appInForeground = foreground
    }

    /** The floating window observed a user touch between automation operations. */
    @Synchronized fun requestUserTakeover(): Boolean {
        val task = currentTask ?: return false
        if (!task.armed || task.closed || task.interrupted || task.awaitingUserConfirmation) return false
        task.interrupted = true
        mutableReturns.value += 1
        return true
    }

    @Synchronized fun arm(task: Task) {
        task.armed = true
        if (task.allowedThroughReturn != mutableReturns.value) task.interrupted = true
        checkCanAct(task)
    }

    suspend fun <T> withUserConfirmation(task: Task, action: suspend () -> T): T {
        synchronized(this) {
            arm(task)
            task.awaitingUserConfirmation = true
        }
        return try {
            action()
        } finally {
            synchronized(this) { task.awaitingUserConfirmation = false }
        }
    }

    fun checkCanAct(task: Task?) {
        task?.validateTarget?.invoke()
        if (task != null && (task.interrupted || task.closed || currentTask !== task)) {
            throw CancellationException("User took control of the phone task.")
        }
    }

    @Synchronized fun finish(task: Task?) {
        if (task != null && !task.closed) {
            task.closed = true
            val actions = task.finishActions.toList()
            task.finishActions.clear()
            actions.forEach { it() }
        }
        if (currentTask === task) currentTask = null
    }

    @Synchronized fun onFinish(task: Task, action: () -> Unit) {
        if (task.closed) action() else task.finishActions.add(action)
    }
}
