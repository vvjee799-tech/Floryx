package com.flowchat.app.data.device

import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.DeviceExecutionTarget
import com.flowchat.app.domain.device.DeviceTaskControl
import com.flowchat.app.domain.device.PowerConnectionMode
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** A shell start keeps its caller's authority across service waits and independent Root RPCs. */
internal class DeviceCommandStartGuard(
    private val callerContext: CoroutineContext,
    private val settingsStore: AppSettingsStore,
    private val taskControl: DeviceTaskControl,
    private val checkSafety: () -> Unit = {}
) {
    private val target = callerContext[DeviceExecutionTarget]
    private val task = taskControl.currentTask
    val useRoot = (target?.connectionMode ?: settingsStore.state.value.powerConnectionMode) == PowerConnectionMode.Root

    fun checkCanDispatch() {
        callerContext.ensureActive()
        checkSafety()
        val settings = settingsStore.state.value
        target?.requireCurrent(settings.installId, settings.powerModeEnabled, settings.powerConnectionMode,
            settings.backgroundExecutionEnabled, settings.deviceConfigurationVersion)
        taskControl.checkCanAct(task)
        check((settings.powerConnectionMode == PowerConnectionMode.Root) == useRoot) {
            "Power connection changed. Retry the operation."
        }
    }
}
