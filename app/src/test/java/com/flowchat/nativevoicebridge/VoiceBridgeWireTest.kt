package com.flowchat.nativevoicebridge

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceBridgeWireTest {
    @Test
    fun hostHelpersFormatPermissionAuthorityAndEndpointForReleaseAndDebug() {
        assertEquals("com.floryx.app", VoiceBridgeWire.RELEASE_PACKAGE)
        assertEquals("com.floryx.app.debug", VoiceBridgeWire.DEV_PACKAGE)
        assertEquals(
            "com.floryx.app.permission.NATIVE_VOICE_BRIDGE",
            VoiceBridgeWire.permissionFor(VoiceBridgeWire.RELEASE_PACKAGE)
        )
        assertEquals(
            "com.floryx.app.native_voice",
            VoiceBridgeWire.authorityFor(VoiceBridgeWire.RELEASE_PACKAGE)
        )
        assertEquals(
            "content://com.floryx.app.native_voice",
            VoiceBridgeWire.endpointFor(VoiceBridgeWire.RELEASE_PACKAGE).toString()
        )
        assertEquals(
            VoiceBridgeWire.PERMISSION,
            VoiceBridgeWire.permissionFor(VoiceBridgeWire.DEV_PACKAGE)
        )
        assertEquals(
            VoiceBridgeWire.AUTHORITY,
            VoiceBridgeWire.authorityFor(VoiceBridgeWire.DEV_PACKAGE)
        )
        assertEquals(
            VoiceBridgeWire.ENDPOINT,
            VoiceBridgeWire.endpointFor(VoiceBridgeWire.DEV_PACKAGE)
        )
    }
}
