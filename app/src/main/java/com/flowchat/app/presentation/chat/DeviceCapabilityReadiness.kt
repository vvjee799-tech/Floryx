package com.flowchat.app.presentation.chat

import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.DeviceCapability
import com.flowchat.app.domain.device.DeviceSafetyState
import com.flowchat.app.domain.device.PowerConnectionMode
import com.flowchat.app.domain.device.ShizukuConnectionState
import com.flowchat.app.domain.device.ShizukuConnectionStatus

internal enum class PhoneCapability {
    InterfaceControls, Screenshot, VisualTarget, Brightness, MediaVolume, ForceStop, Shell, BackgroundDisplay
}

internal enum class CapabilitySupport { Supported, Unsupported, Unknown }
internal enum class CapabilityAuthorization { Granted, Required, Denied, Unknown }
internal enum class CapabilityBinding { Bound, Unbound, OnDemand }
internal enum class CapabilityAvailability { Available, OnDemand, NeedsTaskVerification, Disabled, Blocked, Unknown }
internal enum class CapabilityLimitation {
    PowerModeOff, Locked, ScreenOff, SafetyUnknown, AccessibilityPermission, AccessibilityUnbound,
    AccessibilityUnknown, PowerPermission, PowerPermissionDenied, PowerDisconnected, SystemUnsupported,
    CapabilityNotVerified, CapabilityUnavailable, ModelUnsupported, ModelUnverified, BackgroundOff,
    RootRequired, PhysicalOperationInBackground, ShellOff, AppCompatibilityUnverified, ProtectedWindows,
    OnDemandConnection
}

/** Connection checks describe prerequisites, never acceptance of a particular app or operation. */
internal data class PhoneCapabilityReadiness(
    val capability: PhoneCapability,
    val support: CapabilitySupport,
    val availability: CapabilityAvailability,
    val accessibility: CapabilityServiceReadiness?,
    val power: CapabilityServiceReadiness?,
    val limitations: List<CapabilityLimitation>
)

internal data class CapabilityServiceReadiness(
    val authorization: CapabilityAuthorization,
    val binding: CapabilityBinding,
    val checkedAtMillis: Long?
)

internal fun deviceCapabilityReadiness(
    appSettings: AppSettings,
    connection: ShizukuConnectionState,
    accessibility: AccessibilityConnectionState,
    safety: DeviceSafetyState,
    sdkInt: Int,
    modelSupportsImages: Boolean?
): List<PhoneCapabilityReadiness> {
    val accessibilityAuthorization = when {
        accessibility.isConnected || accessibility.enabled == true -> CapabilityAuthorization.Granted
        accessibility.enabled == false -> CapabilityAuthorization.Required
        else -> CapabilityAuthorization.Unknown
    }
    val powerAuthorization = when (connection.status) {
        ShizukuConnectionStatus.ConnectedAdb, ShizukuConnectionStatus.ConnectedRoot,
        ShizukuConnectionStatus.ConnectedDirectRoot, ShizukuConnectionStatus.RootReady -> CapabilityAuthorization.Granted
        ShizukuConnectionStatus.PermissionRequired, ShizukuConnectionStatus.RootPermissionRequired -> CapabilityAuthorization.Required
        ShizukuConnectionStatus.PermissionDenied, ShizukuConnectionStatus.RootPermissionDenied -> CapabilityAuthorization.Denied
        else -> CapabilityAuthorization.Unknown
    }
    val powerBinding = when {
        connection.serviceBound -> CapabilityBinding.Bound
        connection.status == ShizukuConnectionStatus.RootReady -> CapabilityBinding.OnDemand
        else -> CapabilityBinding.Unbound
    }
    val accessibilityBinding = if (accessibility.isConnected) CapabilityBinding.Bound else CapabilityBinding.Unbound

    fun safetyLimitations(): List<CapabilityLimitation> = buildList {
        if (!appSettings.powerModeEnabled) add(CapabilityLimitation.PowerModeOff)
        when {
            safety.screenLocked == true -> add(CapabilityLimitation.Locked)
            safety.screenInteractive == false -> add(CapabilityLimitation.ScreenOff)
            safety.screenLocked == null || safety.screenInteractive == null -> add(CapabilityLimitation.SafetyUnknown)
        }
    }
    fun accessibilityLimitations(): List<CapabilityLimitation> = when {
        accessibility.isConnected -> emptyList()
        accessibility.enabled == false -> listOf(CapabilityLimitation.AccessibilityPermission)
        accessibility.enabled == true -> listOf(CapabilityLimitation.AccessibilityUnbound)
        else -> listOf(CapabilityLimitation.AccessibilityUnknown)
    }
    fun powerLimitations(): List<CapabilityLimitation> = when (powerAuthorization) {
        CapabilityAuthorization.Required -> listOf(CapabilityLimitation.PowerPermission)
        CapabilityAuthorization.Denied -> listOf(CapabilityLimitation.PowerPermissionDenied)
        else -> if (connection.isConnected) emptyList() else listOf(CapabilityLimitation.PowerDisconnected)
    }
    fun supportFor(capability: DeviceCapability): CapabilitySupport = when {
        capability in connection.capabilities -> CapabilitySupport.Supported
        connection.isConnected && connection.lastVerifiedAtMillis != null -> CapabilitySupport.Unsupported
        else -> CapabilitySupport.Unknown
    }
    fun availability(reasons: List<CapabilityLimitation>, onDemand: Boolean = false,
        taskVerification: Boolean = false): CapabilityAvailability = when {
        CapabilityLimitation.PowerModeOff in reasons || CapabilityLimitation.BackgroundOff in reasons ||
            CapabilityLimitation.ShellOff in reasons -> CapabilityAvailability.Disabled
        reasons.any { it in BlockingCapabilityLimitations } -> CapabilityAvailability.Blocked
        reasons.any { it in UnknownCapabilityLimitations } -> CapabilityAvailability.Unknown
        taskVerification -> CapabilityAvailability.NeedsTaskVerification
        onDemand -> CapabilityAvailability.OnDemand
        else -> CapabilityAvailability.Available
    }

    return PhoneCapability.entries.map { capability ->
        val usesAccessibility = capability in AccessibilityPhoneCapabilities
        val background = capability == PhoneCapability.BackgroundDisplay
        val systemCapability = when (capability) {
            PhoneCapability.Brightness -> DeviceCapability.ScreenBrightness
            PhoneCapability.MediaVolume -> DeviceCapability.MediaVolume
            PhoneCapability.ForceStop -> DeviceCapability.ForceStopApp
            PhoneCapability.Shell -> DeviceCapability.ShellCommands
            else -> null
        }
        val minimumSdk = when (capability) {
            PhoneCapability.Screenshot, PhoneCapability.VisualTarget -> 30
            PhoneCapability.BackgroundDisplay -> 35
            else -> 26
        }
        val support = when {
            sdkInt < minimumSdk -> CapabilitySupport.Unsupported
            systemCapability != null -> supportFor(systemCapability)
            background -> CapabilitySupport.Unknown // A display lease has not been opened by this settings check.
            else -> CapabilitySupport.Supported
        }
        val reasons = buildList {
            addAll(safetyLimitations())
            if (sdkInt < minimumSdk) add(CapabilityLimitation.SystemUnsupported)
            if (usesAccessibility) addAll(accessibilityLimitations())
            if (systemCapability != null || background) addAll(powerLimitations())
            if (systemCapability != null) {
                when (support) {
                    CapabilitySupport.Unknown -> add(CapabilityLimitation.CapabilityNotVerified)
                    CapabilitySupport.Unsupported -> add(CapabilityLimitation.CapabilityUnavailable)
                    CapabilitySupport.Supported -> Unit
                }
                if (appSettings.backgroundExecutionEnabled) add(CapabilityLimitation.PhysicalOperationInBackground)
                if (connection.status == ShizukuConnectionStatus.RootReady && support == CapabilitySupport.Supported &&
                    connection.lastVerifiedAtMillis != null) add(CapabilityLimitation.OnDemandConnection)
            }
            if (capability == PhoneCapability.Shell && !appSettings.commandExecutionEnabled) add(CapabilityLimitation.ShellOff)
            if (capability == PhoneCapability.VisualTarget) when (modelSupportsImages) {
                false -> add(CapabilityLimitation.ModelUnsupported)
                null -> add(CapabilityLimitation.ModelUnverified)
                true -> Unit
            }
            if (capability == PhoneCapability.Screenshot || capability == PhoneCapability.VisualTarget) {
                add(CapabilityLimitation.ProtectedWindows)
            }
            if (background) {
                if (!appSettings.backgroundExecutionEnabled) add(CapabilityLimitation.BackgroundOff)
                if (appSettings.powerConnectionMode != PowerConnectionMode.Root) add(CapabilityLimitation.RootRequired)
                add(CapabilityLimitation.AppCompatibilityUnverified)
            }
            // Foreground UI capabilities are routed to the execution display when this switch is on.
            if (usesAccessibility && !background && appSettings.backgroundExecutionEnabled) {
                if (sdkInt < 35) add(CapabilityLimitation.SystemUnsupported)
                if (appSettings.powerConnectionMode != PowerConnectionMode.Root) add(CapabilityLimitation.RootRequired)
                addAll(powerLimitations())
                add(CapabilityLimitation.AppCompatibilityUnverified)
            }
        }.distinct()
        val usesPower = systemCapability != null || background || (usesAccessibility && appSettings.backgroundExecutionEnabled)
        PhoneCapabilityReadiness(
            capability = capability,
            support = support,
            availability = availability(reasons,
                onDemand = usesPower && connection.status == ShizukuConnectionStatus.RootReady,
                taskVerification = CapabilityLimitation.AppCompatibilityUnverified in reasons),
            accessibility = if (usesAccessibility) CapabilityServiceReadiness(accessibilityAuthorization,
                accessibilityBinding, accessibility.checkedAtMillis) else null,
            power = if (usesPower) CapabilityServiceReadiness(powerAuthorization, powerBinding,
                connection.lastVerifiedAtMillis) else null,
            limitations = reasons
        )
    }
}

private val AccessibilityPhoneCapabilities = setOf(PhoneCapability.InterfaceControls, PhoneCapability.Screenshot,
    PhoneCapability.VisualTarget, PhoneCapability.BackgroundDisplay)
private val BlockingCapabilityLimitations = setOf(CapabilityLimitation.Locked, CapabilityLimitation.ScreenOff,
    CapabilityLimitation.AccessibilityPermission, CapabilityLimitation.AccessibilityUnbound,
    CapabilityLimitation.PowerPermission, CapabilityLimitation.PowerPermissionDenied, CapabilityLimitation.PowerDisconnected,
    CapabilityLimitation.SystemUnsupported, CapabilityLimitation.CapabilityUnavailable, CapabilityLimitation.ModelUnsupported,
    CapabilityLimitation.RootRequired, CapabilityLimitation.PhysicalOperationInBackground)
private val UnknownCapabilityLimitations = setOf(CapabilityLimitation.SafetyUnknown,
    CapabilityLimitation.AccessibilityUnknown, CapabilityLimitation.CapabilityNotVerified, CapabilityLimitation.ModelUnverified)
