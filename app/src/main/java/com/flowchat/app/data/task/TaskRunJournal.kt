package com.flowchat.app.data.task

import com.flowchat.app.data.db.TaskRunDao
import com.flowchat.app.data.db.TaskRunEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class TaskRunJournal @Inject constructor(private val dao: TaskRunDao) {
    private val recoveryLock = Mutex()
    private var recovered = false

    suspend fun recoverInterrupted() = recoveryLock.withLock {
        if (!recovered) {
            dao.recoverInterrupted(System.currentTimeMillis())
            recovered = true
        }
    }

    suspend fun begin(id: String, conversationId: String, instruction: String, modelName: String) {
        val now = System.currentTimeMillis()
        dao.insert(TaskRunEntity(id, conversationId, instruction, modelName, "Running", now, now))
    }

    suspend fun state(id: String, state: String) = dao.updateState(id, state, System.currentTimeMillis())
    suspend fun attachMessage(id: String, messageId: String) = dao.attachMessage(id, messageId, System.currentTimeMillis())
    suspend fun get(id: String): TaskRunEntity? = dao.get(id)
    suspend fun latest(conversationId: String): TaskRunEntity? = dao.latest(conversationId)
}
