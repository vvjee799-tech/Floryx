package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Only system removal of this exact display is release evidence. A null Binder is not. */
internal class ExecutionDisplayCleanupTracker(
    private val displayPresent: (Int) -> Boolean?,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow<Map<String, ExecutionDisplayCleanup>>(emptyMap())
    val state = mutableState.asStateFlow()

    fun requireReusable() = check(state.value.values.none { it.blocksReuse }) {
        "Previous execution display cleanup is unverified. Check cleanup status before starting new phone operations."
    }

    fun creating(ownerId: String) {
        mutableState.update { old ->
            // Keep unresolved evidence; only completed history is bounded.
            val retained = old.values.filter { it.state != ExecutionDisplayState.Released }.associateBy { it.ownerId } +
                old.values.filter { it.state == ExecutionDisplayState.Released }.takeLast(31).associateBy { it.ownerId }
            retained + (ownerId to ExecutionDisplayCleanup(ownerId, ExecutionDisplayState.Creating))
        }
    }

    fun identify(ownerId: String, displayId: Int) {
        require(displayId > 0) { "Execution display identity must not be the physical display." }
        change(ownerId) { it.copy(displayId = displayId,
            state = if (it.state == ExecutionDisplayState.Creating) ExecutionDisplayState.InUse else it.state) }
    }

    fun releasing(ownerId: String) = change(ownerId) {
        it.copy(state = ExecutionDisplayState.Releasing, reason = null, checkedAtMillis = null)
    }

    fun unverified(ownerId: String, reason: ExecutionDisplayCleanupReason) = change(ownerId) {
        if (it.state == ExecutionDisplayState.Released) it else
            it.copy(state = ExecutionDisplayState.Unverified, reason = reason, checkedAtMillis = now())
    }

    fun verify(ownerId: String, creationFinished: Boolean, neverStarted: Boolean = false): Boolean {
        val record = state.value[ownerId] ?: return false
        if (record.state == ExecutionDisplayState.Released) return true
        if (record.state !in setOf(ExecutionDisplayState.Releasing, ExecutionDisplayState.Unverified)) return false
        if (!creationFinished) { unverified(ownerId, ExecutionDisplayCleanupReason.CreationPending); return false }
        val present = try { record.displayId?.let(displayPresent) } catch (_: Exception) {
            unverified(ownerId, ExecutionDisplayCleanupReason.CheckFailed); return false
        }
        if (present == false || (record.displayId == null && neverStarted)) {
            change(ownerId) { it.copy(state = ExecutionDisplayState.Released, reason = null, checkedAtMillis = now()) }
            return true
        }
        unverified(ownerId, if (present == true) ExecutionDisplayCleanupReason.DisplayPresent else ExecutionDisplayCleanupReason.IdentityUnknown)
        return false
    }

    private fun change(ownerId: String, transform: (ExecutionDisplayCleanup) -> ExecutionDisplayCleanup) {
        mutableState.update { old -> old[ownerId]?.let { old + (ownerId to transform(it)) } ?: old }
    }
}
