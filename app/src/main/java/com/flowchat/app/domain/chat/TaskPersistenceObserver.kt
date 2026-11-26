package com.flowchat.app.domain.chat

import javax.inject.Inject

/** Durable boundaries, exposed to deterministic crash tests; the injected production observer is a no-op. */
enum class TaskPersistenceBoundary {
    BeforeIntentSaved, IntentSaved, PendingEffectsSaved, ActionReturned, ResultStepSaved, ResultMetadataSaved,
    ConfirmationSaved, ConfirmationAccepted, BeforeCheckpointCommit, CheckpointCommitted
}

class TaskPersistenceObserver(private val observe: suspend (TaskPersistenceBoundary) -> Unit) {
    @Inject constructor() : this({})
    suspend fun reached(boundary: TaskPersistenceBoundary) = observe(boundary)
}
