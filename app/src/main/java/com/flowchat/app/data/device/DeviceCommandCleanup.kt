package com.flowchat.app.data.device

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/** Evidence for synchronous typed commands only; retained terminal jobs have a separate owner. */
internal data class DeviceCommandCleanup(
    val closed: Boolean,
    val activeCommands: Int,
    val processGroups: List<Int>,
    val remainingGroups: List<Int>,
    val identityUnknown: Boolean
) {
    val released: Boolean get() = closed && activeCommands == 0 && remainingGroups.isEmpty() && !identityUnknown
}

internal interface DeviceCommandCleanupSource {
    fun commandCleanup(): DeviceCommandCleanup
}

/** Only ESRCH proves absence. Permission errors and other failures remain unknown. */
internal fun processGroupPresent(group: Int): Boolean? {
    require(group > 1)
    return try {
        Os.kill(-group, 0)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ESRCH) false else null
    } catch (_: Exception) { null }
}
