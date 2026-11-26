package com.flowchat.app.domain.device

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable local-device contract captured at send, before model preparation or queueing. */
data class DeviceExecutionTarget(
    val deviceId: String,
    val ownerConversationId: String,
    val powerEnabled: Boolean,
    val connectionMode: PowerConnectionMode,
    val backgroundExecution: Boolean,
    val configurationVersion: Long,
    val safetyRevision: Long = 0
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DeviceExecutionTarget>

    fun requireCurrent(deviceId: String, powerEnabled: Boolean, connectionMode: PowerConnectionMode,
                       backgroundExecution: Boolean, configurationVersion: Long) {
        if (this.deviceId != deviceId || this.powerEnabled != powerEnabled ||
            this.connectionMode != connectionMode || this.backgroundExecution != backgroundExecution ||
            this.configurationVersion != configurationVersion) {
            throw CancellationException("Phone execution settings changed. Start or resume the task to observe the selected target again.")
        }
    }
}
