package com.flowchat.app.presentation.chat

import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.domain.device.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityReadinessTest {
    private val settings = AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root,
        commandExecutionEnabled = true)
    private val safety = DeviceSafetyState(screenLocked = false, screenInteractive = true, checkedAtMillis = 120L)
    private val accessibility = AccessibilityConnectionState(AccessibilityConnectionStatus.Connected,
        enabled = true, checkedAtMillis = 110L)
    private val root = ShizukuConnectionState(ShizukuConnectionStatus.RootReady,
        capabilities = setOf(DeviceCapability.ScreenBrightness, DeviceCapability.MediaVolume,
            DeviceCapability.ForceStopApp, DeviceCapability.ShellCommands), lastVerifiedAtMillis = 100L)

    private fun readiness(app: AppSettings = settings, connection: ShizukuConnectionState = root,
        service: AccessibilityConnectionState = accessibility, device: DeviceSafetyState = safety,
        sdk: Int = 35, images: Boolean? = true) =
        deviceCapabilityReadiness(app, connection, service, device, sdk, images).associateBy { it.capability }

    @Test fun rootAuthorizationDoesNotInventCapabilitySupportOrServiceBinding() {
        val state = readiness(connection = root.copy(capabilities = emptySet(), lastVerifiedAtMillis = null))
            .getValue(PhoneCapability.Brightness)
        assertEquals(CapabilitySupport.Unknown, state.support)
        assertEquals(CapabilityAvailability.Unknown, state.availability)
        assertEquals(CapabilityAuthorization.Granted, state.power?.authorization)
        assertEquals(CapabilityBinding.OnDemand, state.power?.binding)
        assertEquals(null, state.power?.checkedAtMillis)
        assertTrue(CapabilityLimitation.CapabilityNotVerified in state.limitations)
    }

    @Test fun cachedRootProbeMakesOnlyReportedCapabilitiesReadyOnDemand() {
        val state = readiness(connection = root.copy(capabilities = setOf(DeviceCapability.ScreenBrightness)))
        val brightness = state.getValue(PhoneCapability.Brightness)
        assertEquals(CapabilitySupport.Supported, brightness.support)
        assertEquals(CapabilityAvailability.OnDemand, brightness.availability)
        assertEquals(CapabilityBinding.OnDemand, brightness.power?.binding)
        assertEquals(100L, brightness.power?.checkedAtMillis)
        assertEquals(CapabilitySupport.Unsupported, state.getValue(PhoneCapability.MediaVolume).support)
        assertEquals(CapabilityAvailability.Blocked, state.getValue(PhoneCapability.MediaVolume).availability)
    }

    @Test fun enabledAccessibilityWithoutBindingIsAuthorizedButCannotOperate() {
        val state = readiness(service = accessibility.copy(status = AccessibilityConnectionStatus.Unavailable))
            .getValue(PhoneCapability.InterfaceControls)
        assertEquals(CapabilityAuthorization.Granted, state.accessibility?.authorization)
        assertEquals(CapabilityBinding.Unbound, state.accessibility?.binding)
        assertEquals(CapabilityAvailability.Blocked, state.availability)
        assertTrue(CapabilityLimitation.AccessibilityUnbound in state.limitations)
    }

    @Test fun foregroundControlsAndCaptureDoNotNeedRootButVisualTargetsNeedAnImageModel() {
        val state = readiness(connection = ShizukuConnectionState(), images = false)
        assertEquals(CapabilityAvailability.Available, state.getValue(PhoneCapability.InterfaceControls).availability)
        assertEquals(CapabilityAvailability.Available, state.getValue(PhoneCapability.Screenshot).availability)
        assertEquals(CapabilityAvailability.Blocked, state.getValue(PhoneCapability.VisualTarget).availability)
        assertTrue(CapabilityLimitation.ModelUnsupported in state.getValue(PhoneCapability.VisualTarget).limitations)
        assertEquals(null, state.getValue(PhoneCapability.Screenshot).power)
    }

    @Test fun unknownModelDoesNotBecomeVerifiedByHavingAccessibility() {
        val state = readiness(images = null).getValue(PhoneCapability.VisualTarget)
        assertEquals(CapabilityAvailability.Unknown, state.availability)
        assertTrue(CapabilityLimitation.ModelUnverified in state.limitations)
    }

    @Test fun androidVersionRequirementsMatchCaptureAndExecutionDisplayImplementation() {
        val old = readiness(app = settings.copy(backgroundExecutionEnabled = true), sdk = 29)
        assertEquals(CapabilitySupport.Unsupported, old.getValue(PhoneCapability.Screenshot).support)
        assertEquals(CapabilityAvailability.Blocked, old.getValue(PhoneCapability.Screenshot).availability)
        assertEquals(CapabilitySupport.Unsupported, old.getValue(PhoneCapability.BackgroundDisplay).support)
        val android14 = readiness(app = settings.copy(backgroundExecutionEnabled = true), sdk = 34)
        assertEquals(CapabilityAvailability.Blocked, android14.getValue(PhoneCapability.BackgroundDisplay).availability)
        assertEquals(CapabilityAvailability.Blocked, android14.getValue(PhoneCapability.InterfaceControls).availability)
    }

    @Test fun backgroundPrerequisitesNeverPromiseAppCompatibilityOrPhysicalCommands() {
        val state = readiness(app = settings.copy(backgroundExecutionEnabled = true))
        val background = state.getValue(PhoneCapability.BackgroundDisplay)
        assertEquals(CapabilitySupport.Unknown, background.support)
        assertEquals(CapabilityAvailability.NeedsTaskVerification, background.availability)
        assertEquals(CapabilityBinding.Bound, background.accessibility?.binding)
        assertEquals(CapabilityBinding.OnDemand, background.power?.binding)
        assertEquals(CapabilityAvailability.NeedsTaskVerification, state.getValue(PhoneCapability.Screenshot).availability)
        listOf(PhoneCapability.Brightness, PhoneCapability.MediaVolume, PhoneCapability.ForceStop, PhoneCapability.Shell).forEach {
            assertEquals(CapabilityAvailability.Blocked, state.getValue(it).availability)
            assertTrue(CapabilityLimitation.PhysicalOperationInBackground in state.getValue(it).limitations)
        }
    }

    @Test fun shizukuCannotSupplyTheBackgroundExecutionDisplayEvenWithRootUid() {
        val state = readiness(app = settings.copy(backgroundExecutionEnabled = true, powerConnectionMode = PowerConnectionMode.Shizuku),
            connection = root.copy(status = ShizukuConnectionStatus.ConnectedRoot))
        assertEquals(CapabilityAvailability.Blocked, state.getValue(PhoneCapability.BackgroundDisplay).availability)
        assertTrue(CapabilityLimitation.RootRequired in state.getValue(PhoneCapability.InterfaceControls).limitations)
    }

    @Test fun lockScreenAndScreenOffPreventEveryCapabilityFromReportingReady() {
        listOf(safety.copy(screenLocked = true), safety.copy(screenInteractive = false)).forEach { device ->
            val state = readiness(app = settings.copy(backgroundExecutionEnabled = true), device = device)
            assertTrue(state.values.all { it.availability == CapabilityAvailability.Blocked })
        }
        assertFalse(readiness(device = DeviceSafetyState()).values.any {
            it.availability == CapabilityAvailability.Available || it.availability == CapabilityAvailability.OnDemand
        })
    }

    @Test fun disablingPhoneOrShellCommandsIsSeparateFromServiceAuthorization() {
        val off = readiness(app = settings.copy(powerModeEnabled = false)).getValue(PhoneCapability.Brightness)
        assertEquals(CapabilityAvailability.Disabled, off.availability)
        assertEquals(CapabilityAuthorization.Granted, off.power?.authorization)
        val shell = readiness(app = settings.copy(commandExecutionEnabled = false)).getValue(PhoneCapability.Shell)
        assertEquals(CapabilityAvailability.Disabled, shell.availability)
        assertTrue(CapabilityLimitation.ShellOff in shell.limitations)
    }

    @Test fun deniedPowerPermissionDoesNotDisableIndependentForegroundAccessibility() {
        val state = readiness(connection = root.copy(status = ShizukuConnectionStatus.RootPermissionDenied))
        assertEquals(CapabilityAuthorization.Denied, state.getValue(PhoneCapability.Shell).power?.authorization)
        assertEquals(CapabilityAvailability.Blocked, state.getValue(PhoneCapability.Shell).availability)
        assertEquals(CapabilityAvailability.Available, state.getValue(PhoneCapability.InterfaceControls).availability)
    }
}
