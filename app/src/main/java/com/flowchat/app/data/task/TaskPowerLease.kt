package com.flowchat.app.data.task

import android.content.Context
import android.os.PowerManager

/** Short, renewable lease held only by the visible service for an actively executing task. */
internal class TaskPowerLease(context: Context) : AutoCloseable {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Tag).apply { setReferenceCounted(false) }

    fun renew() { lock.acquire(TimeoutMillis) }
    override fun close() { if (lock.isHeld) lock.release() }

    companion object {
        const val Tag = "Floryx:UserTask"
        const val TimeoutMillis = 120_000L
        const val RenewMillis = 60_000L
    }
}
