package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RootDeviceConnection internal constructor(
    private val executor: DeviceCommandExecutor,
    private val isRootAvailable: () -> Boolean
) {
    @Inject
    constructor() : this(ProcessDeviceCommandExecutor(root = true), { ProcessDeviceCommandExecutor.isRootAvailable() })

    private val mutableState = MutableStateFlow(
        ShizukuConnectionState(ShizukuConnectionStatus.RootPermissionRequired)
    )
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var permissionRequested = false
    private val service by lazy { FlowChatShizukuService(executor, rootCommands = true) }

    suspend fun refresh(requestPermission: Boolean = false) = mutex.withLock {
        if (requestPermission) permissionRequested = true
        if (!isRootAvailable()) {
            mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.RootUnavailable)
            return@withLock
        }
        if (!permissionRequested) {
            if (mutableState.value.status != ShizukuConnectionStatus.RootPermissionDenied) {
                mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.RootPermissionRequired)
            }
            return@withLock
        }
        if (mutableState.value.status != ShizukuConnectionStatus.ConnectedDirectRoot) {
            mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.Connecting)
        }
        val identity = executor.execute(listOf("id", "-u"))
        if (!identity.success || identity.output.lineSequence().none { it.trim() == "0" }) {
            permissionRequested = false
            mutableState.value = ShizukuConnectionState(ShizukuConnectionStatus.RootPermissionDenied)
            return@withLock
        }
        val capabilities = service.probeCapabilities().split(',').mapNotNull { name ->
            DeviceCapability.entries.firstOrNull { it.name == name }
        }.toSet()
        mutableState.value = if (capabilities.isEmpty()) {
            ShizukuConnectionState(ShizukuConnectionStatus.Error, detail = "Root device commands are unavailable.")
        } else {
            ShizukuConnectionState(ShizukuConnectionStatus.ConnectedDirectRoot, capabilities)
        }
    }

    suspend fun requireService(): FlowChatShizukuService {
        refresh()
        check(state.value.status == ShizukuConnectionStatus.ConnectedDirectRoot) {
            "Root is not connected. Grant FlowChat superuser permission first."
        }
        return service
    }
}
