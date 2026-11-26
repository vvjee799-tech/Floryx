package com.flowchat.app.domain.terminal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Process-local handoff. A cancelled waiter must not lose a late Root command identity. */
class CommandStartObserver(
    val prepared: (requestId: String, recheck: suspend () -> Unit) -> Unit,
    val update: (CommandStartUpdate) -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CommandStartObserver>
}

data class CommandStartUpdate(
    val requestId: String,
    val snapshot: CommandSnapshot? = null,
    val notStarted: Boolean = false,
    val reason: CommandCleanupReason? = null
)
