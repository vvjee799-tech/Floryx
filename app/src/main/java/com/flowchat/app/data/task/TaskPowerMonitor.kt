package com.flowchat.app.data.task

import com.flowchat.app.domain.chat.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class TaskPowerMonitor @Inject constructor() {
    private val mutableState = MutableStateFlow<TaskPowerState?>(null)
    private val lockReaders = linkedMapOf<String, () -> Boolean>()
    val state = mutableState.asStateFlow()

    @Synchronized internal fun started(readHeld: () -> Boolean): String = UUID.randomUUID().toString().also {
        mutableState.value = TaskPowerState(it, TaskServiceState.Running)
        lockReaders[it] = readHeld
        refreshOtherLeases()
    }

    @Synchronized internal fun observed(id: String, owners: Set<TaskPowerOwner>, held: Boolean?, failure: TaskPowerFailure?) {
        mutableState.update { old ->
            if (old?.serviceId != id || old.serviceState == TaskServiceState.Destroyed) old else old.copy(
                owners = owners,
                recentOwners = (old.recentOwners.filter { previous -> owners.none { it.runId == previous.runId } } + owners)
                    .takeLast(32).toSet(),
                leaseHeld = held, failure = failure, checkedAtMillis = System.currentTimeMillis())
        }
        refreshOtherLeases()
    }

    internal fun stopping(id: String) = lifecycle(id, TaskServiceState.Stopping)
    @Synchronized internal fun destroyed(id: String) {
        lifecycle(id, TaskServiceState.Destroyed)
        if (mutableState.value?.serviceId == id && mutableState.value?.leaseHeld == false) lockReaders.remove(id)
        refreshOtherLeases()
    }

    /** Read-only: never acquires a lock or revives a task. Keep failed old generations until actually readable/released. */
    @Synchronized fun recheck() {
        mutableState.update { old -> old?.let { state ->
            val reader = lockReaders[state.serviceId] ?: return@let state
            val held = runCatching(reader).getOrNull()
            state.copy(leaseHeld = held, failure = if (held == null) TaskPowerFailure.ReadFailed else null,
                checkedAtMillis = System.currentTimeMillis())
        } }
        refreshOtherLeases()
    }

    private fun refreshOtherLeases() {
        val current = mutableState.value?.serviceId
        val oldIds = lockReaders.keys.filter { it != current }
        oldIds.forEach { id -> if (runCatching { lockReaders.getValue(id)() }.getOrNull() == false) lockReaders.remove(id) }
        mutableState.update { it?.copy(otherUnverifiedLeases = lockReaders.keys.count { id -> id != current }) }
    }
    private fun lifecycle(id: String, lifecycle: TaskServiceState) {
        mutableState.update { old -> if (old?.serviceId == id) old.copy(serviceState = lifecycle) else old }
    }
}

internal interface TaskWakeLock : AutoCloseable {
    val isHeld: Boolean
    fun renew()
}

/** Reads the actual lock after every operation, including failures. Old callbacks cannot alter a new service. */
internal class TaskPowerSession(private val lock: TaskWakeLock, private val monitor: TaskPowerMonitor) : AutoCloseable {
    val id = monitor.started { lock.isHeld }
    private var owners = emptySet<TaskPowerOwner>()
    private var closed = false

    fun update(work: Set<TaskPowerOwner>) {
        if (closed) return
        owners = work
        val needsPower = work.any { it.needsPower }
        val failure = try {
            if (needsPower) lock.renew() else lock.close()
            null
        } catch (_: Exception) { if (needsPower) TaskPowerFailure.AcquireFailed else TaskPowerFailure.ReleaseFailed }
        observe(failure)
    }

    private fun observe(failure: TaskPowerFailure?) {
        val held = try { lock.isHeld } catch (_: Exception) {
            monitor.observed(id, owners, null, failure ?: TaskPowerFailure.ReadFailed)
            return
        }
        monitor.observed(id, owners, held, failure)
    }

    override fun close() {
        if (closed) return
        closed = true
        owners = emptySet()
        val failure = try { lock.close(); null } catch (_: Exception) { TaskPowerFailure.ReleaseFailed }
        observe(failure)
        monitor.destroyed(id)
    }
}
