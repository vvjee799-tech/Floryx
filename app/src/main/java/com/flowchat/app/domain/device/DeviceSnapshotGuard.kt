package com.flowchat.app.domain.device

/** Binds model element indexes to the exact UI that was shown to the model. */
class DeviceSnapshotGuard {
    private var observed: DeviceScreenSnapshot? = null

    fun remember(screen: DeviceScreenSnapshot?) {
        observed = screen?.takeUnless { it.sensitive }
    }

    fun matches(screenId: String?, current: DeviceScreenSnapshot, targetIndex: Int? = null): Boolean {
        val previous = observed ?: return false
        return screenId != null && screenId == previous.screenId &&
            !current.sensitive && !previous.sensitive &&
            if (targetIndex == null) previous.hasSameUi(current) else {
                val oldTarget = previous.elements.firstOrNull { it.index == targetIndex }
                previous.packageName == current.packageName && previous.activityName == current.activityName &&
                    oldTarget != null && oldTarget == current.elements.firstOrNull { it.index == targetIndex }
            }
    }
}
