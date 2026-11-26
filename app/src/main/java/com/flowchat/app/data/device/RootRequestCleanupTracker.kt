package com.flowchat.app.data.device

import com.flowchat.app.domain.device.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal class RootRequestCleanupTracker(private val now: () -> Long = System::currentTimeMillis) {
    private val mutableState = MutableStateFlow<Map<String, RootRequestCleanup>>(emptyMap())
    val state = mutableState.asStateFlow()

    fun requireReusable() = check(state.value.values.none { it.blocksReuse }) {
        "Previous Root request cleanup is unverified. Check cleanup before starting new phone operations."
    }

    fun started(id: String, owner: String) {
        mutableState.update { old ->
            val retained = old.values.filter { it.state != RootRequestState.Released } +
                old.values.filter { it.state == RootRequestState.Released }.takeLast(127)
            retained.associateBy { it.requestId } + (id to RootRequestCleanup(id, owner))
        }
    }

    fun releasing(id: String) = change(id) {
        if (it.state == RootRequestState.Released || it.reason == RootRequestCleanupReason.OriginalConnectionUnavailable) it
        else it.copy(state = RootRequestState.Releasing)
    }
    fun unverified(id: String, reason: RootRequestCleanupReason) = change(id) {
        if (it.state == RootRequestState.Released || it.reason == RootRequestCleanupReason.OriginalConnectionUnavailable) it else
            it.copy(state = RootRequestState.Unverified, reason = reason, checkedAtMillis = now())
    }

    fun verify(id: String, rpcFinished: Boolean, evidence: () -> String): Boolean {
        val record = state.value[id] ?: return false
        if (record.state == RootRequestState.Released) return true
        if (record.reason == RootRequestCleanupReason.OriginalConnectionUnavailable) return false
        if (record.state == RootRequestState.InUse) return false
        if (!rpcFinished) { unverified(id, RootRequestCleanupReason.RpcPending); return false }
        try {
            val value = JSONObject(evidence())
            check(value.getString("requestId") == id)
            val groups = value.getJSONArray("processGroups").let { array ->
                List(array.length()) { array.getInt(it).also { group -> check(group > 1) } }
            }
            val reason = when {
                !value.getBoolean("finishReceived") || value.getInt("activeCalls") != 0 -> RootRequestCleanupReason.RequestActive
                value.getBoolean("identityUnknown") || value.getInt("retainedShellCommands") != 0 -> RootRequestCleanupReason.IdentityUnknown
                value.getInt("activeCommands") != 0 || value.getJSONArray("remainingGroups").length() != 0 -> RootRequestCleanupReason.CommandsPresent
                else -> null
            }
            change(id) {
                // Detachment can race a read already in flight. A late reply cannot clear that boundary.
                if (it.state == RootRequestState.Released || it.reason == RootRequestCleanupReason.OriginalConnectionUnavailable) it
                else it.copy(state = if (reason == null) RootRequestState.Released else RootRequestState.Unverified,
                    reason = reason, processGroups = groups, checkedAtMillis = now())
            }
            return state.value[id]?.state == RootRequestState.Released
        } catch (_: Exception) { unverified(id, RootRequestCleanupReason.CheckFailed); return false }
    }

    private fun change(id: String, transform: (RootRequestCleanup) -> RootRequestCleanup) {
        mutableState.update { old -> old[id]?.let { old + (id to transform(it)) } ?: old }
    }
}
