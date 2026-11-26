package com.flowchat.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.device.AccessibilityConnectionState
import com.flowchat.app.domain.device.DeviceSafetyState
import com.flowchat.app.domain.device.ShizukuConnectionState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DeviceCapabilitySettings(
    appSettings: AppSettings,
    connection: ShizukuConnectionState,
    accessibility: AccessibilityConnectionState,
    safety: DeviceSafetyState,
    sdkInt: Int,
    modelSupportsImages: Boolean?
) {
    val capabilities = deviceCapabilityReadiness(appSettings, connection, accessibility, safety, sdkInt, modelSupportsImages)
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("device_capability_center")) {
        Text(stringResource(R.string.capability_center_title), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 12.dp))
        CapabilityDetailRow(stringResource(R.string.capability_device_safety), stringResource(when {
            safety.screenLocked == true -> R.string.capability_safety_locked
            safety.screenInteractive == false -> R.string.capability_safety_screen_off
            safety.screenLocked == false && safety.screenInteractive == true -> R.string.capability_safety_clear
            else -> R.string.capability_unknown
        }))
        CapabilityDetailRow(stringResource(R.string.capability_device_checked), capabilityCheckTime(safety.checkedAtMillis))
        capabilities.forEach { readiness ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
            CapabilityDetails(readiness)
        }
    }
}

@Composable
private fun CapabilityDetails(readiness: PhoneCapabilityReadiness) {
    var expanded by rememberSaveable(readiness.capability) { mutableStateOf(false) }
    val expandedDescription = stringResource(if (expanded) R.string.capability_collapse else R.string.capability_expand)
    Column(Modifier.fillMaxWidth().testTag("device_capability_${readiness.capability.name}")) {
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .semantics { stateDescription = expandedDescription }
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(readiness.capability.titleRes()), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f))
                Text(expandedDescription, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(readiness.availability.labelRes()), style = MaterialTheme.typography.bodySmall,
                color = if (readiness.availability == CapabilityAvailability.Available) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            CapabilityDetailRow(stringResource(R.string.capability_system_support), stringResource(when (readiness.support) {
                CapabilitySupport.Supported -> R.string.capability_supported
                CapabilitySupport.Unsupported -> R.string.capability_unsupported
                CapabilitySupport.Unknown -> R.string.capability_unknown
            }))
            readiness.accessibility?.let {
                CapabilityServiceDetails(it, R.string.capability_accessibility_authorization,
                    R.string.capability_accessibility_binding, R.string.capability_accessibility_checked)
            }
            readiness.power?.let {
                CapabilityServiceDetails(it, R.string.capability_power_authorization,
                    R.string.capability_power_binding, R.string.capability_power_checked)
            }
            CapabilityDetailRow(stringResource(R.string.capability_current_availability), stringResource(readiness.availability.labelRes()))
            if (readiness.limitations.isNotEmpty()) {
                Text(stringResource(R.string.capability_limitations), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                readiness.limitations.forEach { reason ->
                    Text("• " + stringResource(reason.labelRes()), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CapabilityServiceDetails(service: CapabilityServiceReadiness, authorizationLabel: Int,
    bindingLabel: Int, checkedLabel: Int) {
    CapabilityDetailRow(stringResource(authorizationLabel), stringResource(when (service.authorization) {
        CapabilityAuthorization.Granted -> R.string.capability_authorized
        CapabilityAuthorization.Required -> R.string.capability_authorization_required
        CapabilityAuthorization.Denied -> R.string.capability_authorization_denied
        CapabilityAuthorization.Unknown -> R.string.capability_unknown
    }))
    CapabilityDetailRow(stringResource(bindingLabel), stringResource(when (service.binding) {
        CapabilityBinding.Bound -> R.string.capability_bound
        CapabilityBinding.Unbound -> R.string.capability_unbound
        CapabilityBinding.OnDemand -> R.string.capability_on_demand_binding
    }))
    CapabilityDetailRow(stringResource(checkedLabel), capabilityCheckTime(service.checkedAtMillis))
}

@Composable
private fun CapabilityDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun capabilityCheckTime(time: Long?): String = time?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
} ?: stringResource(R.string.capability_not_checked)

private fun PhoneCapability.titleRes(): Int = when (this) {
    PhoneCapability.InterfaceControls -> R.string.capability_interface_controls
    PhoneCapability.Screenshot -> R.string.capability_screenshot
    PhoneCapability.VisualTarget -> R.string.capability_visual_target
    PhoneCapability.Brightness -> R.string.capability_brightness
    PhoneCapability.MediaVolume -> R.string.capability_media_volume
    PhoneCapability.ForceStop -> R.string.capability_force_stop
    PhoneCapability.Shell -> R.string.capability_shell
    PhoneCapability.BackgroundDisplay -> R.string.capability_background_display
}

private fun CapabilityAvailability.labelRes(): Int = when (this) {
    CapabilityAvailability.Available -> R.string.capability_available
    CapabilityAvailability.OnDemand -> R.string.capability_available_on_demand
    CapabilityAvailability.NeedsTaskVerification -> R.string.capability_needs_task_verification
    CapabilityAvailability.Disabled -> R.string.capability_disabled
    CapabilityAvailability.Blocked -> R.string.capability_blocked
    CapabilityAvailability.Unknown -> R.string.capability_unknown
}

private fun CapabilityLimitation.labelRes(): Int = when (this) {
    CapabilityLimitation.PowerModeOff -> R.string.capability_reason_power_off
    CapabilityLimitation.Locked -> R.string.capability_reason_locked
    CapabilityLimitation.ScreenOff -> R.string.capability_reason_screen_off
    CapabilityLimitation.SafetyUnknown -> R.string.capability_reason_safety_unknown
    CapabilityLimitation.AccessibilityPermission -> R.string.capability_reason_accessibility_permission
    CapabilityLimitation.AccessibilityUnbound -> R.string.capability_reason_accessibility_unbound
    CapabilityLimitation.AccessibilityUnknown -> R.string.capability_reason_accessibility_unknown
    CapabilityLimitation.PowerPermission -> R.string.capability_reason_power_permission
    CapabilityLimitation.PowerPermissionDenied -> R.string.capability_reason_power_denied
    CapabilityLimitation.PowerDisconnected -> R.string.capability_reason_power_disconnected
    CapabilityLimitation.SystemUnsupported -> R.string.capability_reason_system_unsupported
    CapabilityLimitation.CapabilityNotVerified -> R.string.capability_reason_not_verified
    CapabilityLimitation.CapabilityUnavailable -> R.string.capability_reason_unavailable
    CapabilityLimitation.ModelUnsupported -> R.string.capability_reason_model_unsupported
    CapabilityLimitation.ModelUnverified -> R.string.capability_reason_model_unverified
    CapabilityLimitation.BackgroundOff -> R.string.capability_reason_background_off
    CapabilityLimitation.RootRequired -> R.string.capability_reason_root_required
    CapabilityLimitation.PhysicalOperationInBackground -> R.string.capability_reason_physical_background
    CapabilityLimitation.ShellOff -> R.string.capability_reason_shell_off
    CapabilityLimitation.AppCompatibilityUnverified -> R.string.capability_reason_app_compatibility
    CapabilityLimitation.ProtectedWindows -> R.string.capability_reason_protected_windows
    CapabilityLimitation.OnDemandConnection -> R.string.capability_reason_on_demand
}
