package com.flowchat.app.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persists per-PID accessibility authorization receipts so `single-cleaner` kills remain verifiable. */
@Singleton
class AccessibilityReconnectStore private constructor(
    private val preferences: SharedPreferences?,
    private var cached: AccessibilityReconnectReceipt
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    )

    private constructor(preferences: SharedPreferences) : this(
        preferences = preferences,
        cached = preferences.loadReceipt()
    )

    internal constructor(
        initial: AccessibilityReconnectReceipt = AccessibilityReconnectReceipt()
    ) : this(null, initial)

    @Synchronized
    internal fun snapshot(): AccessibilityReconnectReceipt = cached

    @Synchronized
    fun recordAuthorized(pid: Int, nowMillis: Long = System.currentTimeMillis()) {
        if (pid <= 0 || (cached.authorizedPid == pid && cached.revokedPid == 0)) return
        persist(
            cached.copy(
                authorizedPid = pid,
                authorizedAtMillis = nowMillis,
                revokedPid = 0,
                revokedAtMillis = 0L
            )
        )
    }

    @Synchronized
    fun recordRevokedIfAuthorized(pid: Int, nowMillis: Long = System.currentTimeMillis()) {
        if (pid <= 0 || cached.authorizedPid != pid || cached.revokedPid == pid) return
        persist(cached.copy(revokedPid = pid, revokedAtMillis = nowMillis))
    }

    @Synchronized
    fun clearAuthorization() {
        if (cached.authorizedPid == 0 && cached.revokedPid == 0) return
        persist(
            cached.copy(
                authorizedPid = 0,
                authorizedAtMillis = 0L,
                revokedPid = 0,
                revokedAtMillis = 0L
            )
        )
    }

    @Synchronized
    fun markExitHandled(pid: Int, timestampMillis: Long) {
        if (cached.handledExitPid == pid && cached.handledExitTimestampMillis == timestampMillis) return
        persist(
            cached.copy(
                handledExitPid = pid,
                handledExitTimestampMillis = timestampMillis
            )
        )
    }

    // Synchronous commit() is required so receipts survive an immediate SIGKILL from single-cleaner.
    @SuppressLint("ApplySharedPref")
    private fun persist(next: AccessibilityReconnectReceipt) {
        cached = next
        preferences?.edit()?.apply {
            putInt(AuthorizedPidKey, next.authorizedPid)
            putLong(AuthorizedAtKey, next.authorizedAtMillis)
            putInt(RevokedPidKey, next.revokedPid)
            putLong(RevokedAtKey, next.revokedAtMillis)
            putInt(HandledExitPidKey, next.handledExitPid)
            putLong(HandledExitTimestampKey, next.handledExitTimestampMillis)
        }?.commit()
    }

    private companion object {
        const val PreferencesName = "accessibility_reconnect_state"
        const val AuthorizedPidKey = "authorized_pid"
        const val AuthorizedAtKey = "authorized_at_millis"
        const val RevokedPidKey = "revoked_pid"
        const val RevokedAtKey = "revoked_at_millis"
        const val HandledExitPidKey = "handled_exit_pid"
        const val HandledExitTimestampKey = "handled_exit_timestamp_millis"

        fun SharedPreferences.loadReceipt(): AccessibilityReconnectReceipt = AccessibilityReconnectReceipt(
            authorizedPid = getInt(AuthorizedPidKey, 0),
            authorizedAtMillis = getLong(AuthorizedAtKey, 0L),
            revokedPid = getInt(RevokedPidKey, 0),
            revokedAtMillis = getLong(RevokedAtKey, 0L),
            handledExitPid = getInt(HandledExitPidKey, 0),
            handledExitTimestampMillis = getLong(HandledExitTimestampKey, 0L)
        )
    }
}
