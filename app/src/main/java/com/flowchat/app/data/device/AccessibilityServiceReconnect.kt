package com.flowchat.app.data.device

import android.content.ComponentName

/**
 * Recovers Floryx's accessibility service after a process kill without bypassing user revocations:
 * either clears the platform's crashed marker when still selected, or restores a selection cleared
 * by `pkg:android`.
 */
internal class AccessibilityServiceReconnect(private val commands: DeviceCommandExecutor) {
    fun reconnect(userId: Int, component: ComponentName): Boolean {
        val original = readEnabledServices(userId) ?: return false
        val entries = original.split(':')
        val withoutFloryx = entries.filterNot { ComponentName.unflattenFromString(it) == component }
        if (withoutFloryx == entries) return false

        val state = readState(userId) ?: return false
        if (component !in state.enabled || component !in state.crashed || component in state.binding) return false
        if (readEnabledServices(userId) != original) return false

        val temporary = withoutFloryx.joinToString(":").ifEmpty { "null" }
        var restored = false
        try {
            if (writeEnabledServices(userId, temporary)) {
                // Android clears the crash marker when its settings observer sees the component removed.
                for (attempt in 0 until 4) {
                    val current = readState(userId)
                    if (current == null || component !in current.enabled && component !in current.crashed) break
                    Thread.sleep(75)
                }
            }
        } finally {
            // Restore only our temporary value. A concurrent change belongs to its writer.
            val current = readEnabledServices(userId) ?: readEnabledServices(userId)
            if (current == temporary) restored = writeEnabledServices(userId, original)
        }
        return restored
    }

    fun restoreSystemCleared(userId: Int, component: ComponentName): Boolean {
        val state = readState(userId) ?: return false
        if (component in state.enabled || component in state.binding || component in state.crashed) return false
        if (readEnabledServicesWriterPackage(userId) != SystemWriterPackage) return false

        val original = readEnabledServices(userId) ?: return false
        val existing = parseEntries(original)
        if (existing.any { ComponentName.unflattenFromString(it) == component }) return false

        val updated = (existing + component.flattenToString()).joinToString(":")
        if (readEnabledServices(userId) != original) return false
        if (readEnabledServicesWriterPackage(userId) != SystemWriterPackage) return false

        return writeEnabledServices(userId, updated) && writeAccessibilityMaster(userId, enabled = true)
    }

    private fun settingsPrefix(userId: Int) = listOf("settings", "--user", userId.toString())

    private fun readEnabledServices(userId: Int): String? =
        commands.execute(settingsPrefix(userId) + listOf("get", "secure", EnabledServices))
            .takeIf { it.success }?.output

    private fun writeEnabledServices(userId: Int, value: String): Boolean =
        commands.execute(
            settingsPrefix(userId) + if (value == "null") {
                listOf("delete", "secure", EnabledServices)
            } else {
                listOf("put", "secure", EnabledServices, value)
            }
        ).success

    private fun writeAccessibilityMaster(userId: Int, enabled: Boolean): Boolean =
        commands.execute(
            settingsPrefix(userId) + listOf("put", "secure", AccessibilityEnabled, if (enabled) "1" else "0")
        ).success

    private fun parseEntries(raw: String): List<String> =
        if (raw.trim() == "null") emptyList() else raw.split(':').filter { it.isNotBlank() }

    private fun readEnabledServicesWriterPackage(userId: Int): String? {
        val dump = commands.execute(
            listOf(
                "/system/bin/sh",
                "-c",
                "dumpsys -t 1 settings | grep -F -e 'SETTINGS (' -e 'name:$EnabledServices '"
            )
        )
        if (!dump.success) return null
        val section = dump.output.lineSequence()
            .map(String::trim)
            .dropWhile { it != "SECURE SETTINGS (user $userId)" }
            .drop(1)
            .takeWhile { !it.contains("SETTINGS (") }
        val row = section.firstOrNull { it.contains("name:$EnabledServices ") } ?: return null
        return WriterPackagePattern.find(row)?.groupValues?.get(1)
    }

    private fun readState(userId: Int): ServiceState? {
        val dump = commands.execute(listOf("dumpsys", "-t", "1", "accessibility"))
        if (!dump.success) return null
        val lines = dump.output.lineSequence().map(String::trim)
        if (lines.none { it == "currentUserId=$userId" }) return null
        val section = lines.dropWhile { !it.startsWith("attributes:{id=$userId,") }.drop(1)
            .takeWhile { it != "User state[" }.toList()
        fun components(label: String): Set<ComponentName>? = section
            .firstOrNull { it.startsWith("$label:{") }
            ?.substringAfter(':')?.split(',', '{', '}')
            ?.mapNotNull { ComponentName.unflattenFromString(it.trim()) }?.toSet()
        return ServiceState(
            enabled = components("Enabled services") ?: return null,
            crashed = components("Crashed services") ?: return null,
            binding = components("Binding services") ?: return null,
        )
    }

    private data class ServiceState(
        val enabled: Set<ComponentName>,
        val crashed: Set<ComponentName>,
        val binding: Set<ComponentName>,
    )

    private companion object {
        const val EnabledServices = "enabled_accessibility_services"
        const val AccessibilityEnabled = "accessibility_enabled"
        const val SystemWriterPackage = "android"
        val WriterPackagePattern = Regex("""(?:^|\s)pkg:(\S+)""")
    }
}
