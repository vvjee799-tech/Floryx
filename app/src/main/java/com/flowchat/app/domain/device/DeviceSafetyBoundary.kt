package com.flowchat.app.domain.device

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DeviceSafetyReason {
    Locked,
    ScreenOff,
    AccessibilityLost,
    PowerConnectionLost
}

data class DeviceSafetyState(
    val revision: Long = 0,
    val screenLocked: Boolean? = null,
    val screenInteractive: Boolean? = null,
    val checkedAtMillis: Long? = null,
    val reason: DeviceSafetyReason? = null
)

/** A recovered capability never restores permission for an earlier phone operation. */
@Singleton
class DeviceSafetyBoundary @Inject constructor() {
    private val mutableState = MutableStateFlow(DeviceSafetyState())
    val state: StateFlow<DeviceSafetyState> = mutableState.asStateFlow()

    @Synchronized
    fun updateScreen(locked: Boolean?, interactive: Boolean?, checkedAtMillis: Long): DeviceSafetyState {
        val previous = mutableState.value
        val boundaryReason = when {
            locked == true && previous.screenLocked != true -> DeviceSafetyReason.Locked
            interactive == false && previous.screenInteractive != false -> DeviceSafetyReason.ScreenOff
            else -> null
        }
        return previous.copy(
            revision = previous.revision + if (boundaryReason != null) 1 else 0,
            screenLocked = locked,
            screenInteractive = interactive,
            checkedAtMillis = checkedAtMillis,
            reason = boundaryReason ?: previous.reason
        ).also { mutableState.value = it }
    }

    /** Each reported service/permission loss is a distinct event, even before recovery. */
    @Synchronized
    fun invalidate(reason: DeviceSafetyReason) {
        val previous = mutableState.value
        mutableState.value = previous.copy(revision = previous.revision + 1, reason = reason)
    }

    fun requireCurrent(revision: Long) {
        if (revision != mutableState.value.revision) {
            throw CancellationException("Phone control boundary changed; start or resume to observe again.")
        }
    }

    fun requireScreenAvailable() {
        val current = mutableState.value
        val unavailable = when {
            current.screenLocked == true -> "Phone is locked"
            current.screenInteractive == false -> "Phone screen is off"
            current.screenLocked == null || current.screenInteractive == null -> "Phone screen availability is unverified"
            else -> return
        }
        throw CancellationException("$unavailable; start or resume to observe again after the screen is available.")
    }
}
