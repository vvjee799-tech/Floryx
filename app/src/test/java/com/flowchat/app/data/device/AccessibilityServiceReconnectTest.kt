package com.flowchat.app.data.device

import com.flowchat.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccessibilityServiceReconnectTest {
    private val component = "${BuildConfig.APPLICATION_ID}/com.flowchat.app.data.device.FlowChatAccessibilityService"
    private val other = "other.app/.Reader"

    @Test fun clearsCrashForTheSelectedComponentAndRestoresTheOriginalList() {
        val original = "$other:$component"
        val device = Device(original, component)

        assertTrue(device.reconnect())
        assertEquals(listOf(other, original), device.writes)
        assertEquals(original, device.selection)
        assertEquals(null, device.crashed)
    }

    @Test fun leavesRevokedHealthyAndOtherUserServicesAlone() {
        val devices = listOf(
            Device("null", component),
            Device(other, component),
            Device(component, null),
            Device(component, component, currentUser = 10),
        )
        for (device in devices) {
            assertFalse(device.reconnect())
            assertTrue(device.writes.isEmpty())
        }
    }

    @Test fun preservesASelectionChangedDuringReconnect() {
        val device = Device(component, component, changeAfterRemoval = other)

        assertFalse(device.reconnect())
        assertEquals(listOf("null"), device.writes)
        assertEquals(other, device.selection)
    }

    @Test fun restoresSystemClearedSelectionWhenClearedByAndroid() {
        val device = Device(
            selection = "null",
            crashed = null,
            writerPackage = "android",
            accessibilityMaster = "0"
        )

        assertTrue(device.restoreSystemCleared())
        assertEquals(listOf(component), device.writes)
        assertEquals(listOf("1"), device.masterWrites)
        assertEquals(component, device.selection)
        assertEquals("1", device.accessibilityMaster)
    }

    @Test fun preservesExistingServicesWhenRestoringSystemClearedSelection() {
        val device = Device(
            selection = other,
            crashed = null,
            writerPackage = "android",
            accessibilityMaster = "1"
        )

        assertTrue(device.restoreSystemCleared())
        assertEquals(listOf("$other:$component"), device.writes)
        assertEquals(listOf("1"), device.masterWrites)
        assertEquals("$other:$component", device.selection)
    }

    @Test fun refusesSystemRestoreForNonSystemWritersOrMissingRow() {
        val writers = listOf("com.android.settings", "com.android.shell", "root", "com.iqoo.secure", null)
        for (writer in writers) {
            val device = Device(
                selection = "null",
                crashed = null,
                writerPackage = writer,
                accessibilityMaster = "0"
            )
            assertFalse("Expected refusal for writer=$writer", device.restoreSystemCleared())
            assertTrue(device.writes.isEmpty())
            assertTrue(device.masterWrites.isEmpty())
        }
    }

    @Test fun isolatesWriterProvenanceByUserSection() {
        val device = Device(
            selection = "null",
            crashed = null,
            writerPackage = "com.android.settings",
            otherUserSecureSection = """
                SECURE SETTINGS (user 10)
                _id:1 name:enabled_accessibility_services pkg:android value:{null}
            """.trimIndent()
        )

        assertFalse(device.restoreSystemCleared())
        assertTrue(device.writes.isEmpty())
        assertTrue(device.masterWrites.isEmpty())
    }

    @Test fun refusesSystemRestoreOnWrongUserActiveServiceOrConcurrentChange() {
        val wrongUser = Device("null", null, currentUser = 10, writerPackage = "android")
        val alreadyEnabled = Device(component, null, writerPackage = "android")
        val alreadyCrashed = Device("null", component, writerPackage = "android")
        val alreadyBinding = Device("null", null, binding = component, writerPackage = "android")
        val concurrentSelectionChange = Device("null", null, writerPackage = "android", changeBeforeWriteTo = other)
        val concurrentWriterChange = Device("null", null, writerPackage = "android", changeWriterBeforeWriteTo = "com.android.settings")

        for (device in listOf(
            wrongUser,
            alreadyEnabled,
            alreadyCrashed,
            alreadyBinding,
            concurrentSelectionChange,
            concurrentWriterChange
        )) {
            assertFalse(device.restoreSystemCleared())
            assertTrue(device.writes.isEmpty())
            assertTrue(device.masterWrites.isEmpty())
        }
    }

    private class Device(
        var selection: String,
        var crashed: String?,
        val currentUser: Int = 0,
        val changeAfterRemoval: String? = null,
        var writerPackage: String? = "com.android.settings",
        var accessibilityMaster: String = "0",
        val binding: String? = null,
        val otherUserSecureSection: String = "",
        val changeBeforeWriteTo: String? = null,
        val changeWriterBeforeWriteTo: String? = null,
    ) : DeviceCommandExecutor {
        val writes = mutableListOf<String>()
        val masterWrites = mutableListOf<String>()
        private var selectionReads = 0
        private var writerReads = 0

        fun reconnect() = FlowChatShizukuService(this).reconnectSelectedAccessibilityService(0)
        fun restoreSystemCleared() = FlowChatShizukuService(this).restoreSystemClearedAccessibilityService(0)

        override fun execute(command: List<String>): DeviceCommandResult {
            if (command == listOf("dumpsys", "-t", "1", "accessibility")) {
                val enabled = selection.split(':').filterNot { it == "null" }.joinToString(", ") { "{$it}" }
                return DeviceCommandResult(true, """
                    currentUserId=$currentUser
                    User state[
                         attributes:{id=0, installedServiceCount=2}
                         Enabled services:{$enabled}
                         Binding services:{${binding.orEmpty()}}
                         Crashed services:{${crashed.orEmpty()}}
                    ]
                """.trimIndent())
            }
            if (command.firstOrNull() == "/system/bin/sh") {
                writerReads += 1
                if (writerReads == 2 && changeWriterBeforeWriteTo != null) {
                    writerPackage = changeWriterBeforeWriteTo
                }
                val row = writerPackage?.let { writer ->
                    val displayValue = if (selection == "null") "{null}" else selection
                    "_id:189738 name:enabled_accessibility_services pkg:$writer value:$displayValue"
                }.orEmpty()
                return DeviceCommandResult(true, buildString {
                    appendLine("CONFIG SETTINGS (user 0)")
                    appendLine("GLOBAL SETTINGS (user 0)")
                    appendLine("SECURE SETTINGS (user 0)")
                    if (row.isNotEmpty()) appendLine(row)
                    if (otherUserSecureSection.isNotEmpty()) appendLine(otherUserSecureSection)
                    appendLine("SYSTEM SETTINGS (user 0)")
                }.trim())
            }
            check(command.take(3) == listOf("settings", "--user", "0"))
            check(command[4] == "secure")
            val key = command[5]
            return when (key) {
                "enabled_accessibility_services" -> when (command[3]) {
                    "get" -> {
                        selectionReads += 1
                        if (selectionReads == 2 && changeBeforeWriteTo != null) {
                            selection = changeBeforeWriteTo
                        }
                        DeviceCommandResult(true, selection)
                    }
                    "put", "delete" -> {
                        selection = command.getOrElse(6) { "null" }
                        writes += selection
                        if (crashed?.let { it !in selection.split(':') } == true) {
                            crashed = null
                            changeAfterRemoval?.let { selection = it }
                        }
                        DeviceCommandResult(true, "")
                    }
                    else -> error("Unexpected command: $command")
                }
                "accessibility_enabled" -> {
                    check(command[3] == "put")
                    accessibilityMaster = command[6]
                    masterWrites += accessibilityMaster
                    DeviceCommandResult(true, "")
                }
                else -> error("Unexpected setting key: $command")
            }
        }
    }
}
