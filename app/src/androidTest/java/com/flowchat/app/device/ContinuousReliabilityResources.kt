package com.flowchat.app.device

import android.app.Notification
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.flowchat.app.data.db.FlowChatDatabase
import com.flowchat.app.data.device.FlowChatAccessibilityService
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** On-device observation of the existing target process and its test workspace. */
internal class ContinuousReliabilityResources(
    private val context: Context,
    private val automation: UiAutomation,
    private val database: FlowChatDatabase,
    private val workspace: File,
) {
    private val packageName = context.packageName
    private val uid = Process.myUid()
    private val userId = uid / 100_000 // Android's per-user UID range.
    private val processId = Process.myPid()
    private val accessibilityService = ComponentName(context, FlowChatAccessibilityService::class.java)
    // Reading the helper's configured name does not obtain a database connection.
    private val databaseFile = database.openHelper.databaseName?.let(context::getDatabasePath)
    private val databasePaths = databaseFile?.let { file ->
        buildSet {
            add(file.absolutePath)
            runCatching { file.canonicalPath }.getOrNull()?.let(::add)
            if (userId == 0) {
                add(file.absolutePath.replaceFirst("/data/user/0/", "/data/data/"))
                add(file.absolutePath.replaceFirst("/data/data/", "/data/user/0/"))
            }
        }
    }.orEmpty()

    /**
     * Take heap/FD readings before opening any dump pipe. The runner lives in this process,
     * so compare equally instrumented boundaries. This sampler never forces GC or opens DB
     * connections, and the individual readings are sequential.
     */
    fun sample(): JsonObject {
        val started = SystemClock.elapsedRealtime()
        val memory = memory()
        val fds = descriptors()
        val threads = javaThreads()
        val database = databaseFiles()
        val workspace = workspaceFiles(workspace)
        val terminalDir = File(context.filesDir, "terminal")
        val linuxWorkspace = workspaceFiles(File(terminalDir, "workspaces"))
        val commandScratch = workspaceFiles(File(terminalDir, "tmp"))
        val android = androidResources()
        return buildJsonObject {
            put("pid", processId)
            put("uid", uid)
            put("user_id", userId)
            put("elapsed_realtime_ms", started)
            put("sample_duration_ms", SystemClock.elapsedRealtime() - started)
            put("memory", memory)
            put("threads", threads)
            put("fds", fds)
            put("database", database)
            put("workspace", workspace)
            put("linux_workspace", linuxWorkspace)
            put("command_scratch", commandScratch)
            put("android", android)
        }
    }

    private fun memory(): JsonObject {
        val runtime = Runtime.getRuntime()
        val javaTotal = runtime.totalMemory()
        val javaUsed = javaTotal - runtime.freeMemory()
        val nativeAllocated = Debug.getNativeHeapAllocatedSize()
        val nativeTotal = Debug.getNativeHeapSize()
        val status = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.mapNotNull { line ->
                    ProcessStatus.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2].toLong() }
                }.toMap()
            }
        }.getOrNull()
        return buildJsonObject {
            put("rss_kib", status?.get("VmRSS"))
            put("rss_anonymous_kib", status?.get("RssAnon"))
            put("rss_file_kib", status?.get("RssFile"))
            put("rss_shared_kib", status?.get("RssShmem"))
            put("native_heap_allocated_kib", nativeAllocated.takeIf { it >= 0 }?.div(1024))
            put("native_heap_capacity_kib", nativeTotal.takeIf { it >= 0 }?.div(1024))
            put("java_heap_used_kib", javaUsed.takeIf { it >= 0 }?.div(1024))
            put("java_heap_committed_kib", javaTotal / 1024)
            put("java_heap_max_kib", runtime.maxMemory() / 1024)
            put("kernel_thread_count", status?.get("Threads"))
        }
    }

    private fun javaThreads(): JsonObject {
        // Enumerate thread objects without requesting every thread's stack trace.
        val names = runCatching {
            var group = checkNotNull(Thread.currentThread().threadGroup)
            while (true) group = group.parent ?: break
            var capacity = maxOf(32, group.activeCount() + 16)
            var threads = arrayOfNulls<Thread>(capacity)
            var count = group.enumerate(threads, true)
            while (count == capacity) {
                capacity *= 2
                threads = arrayOfNulls(capacity)
                count = group.enumerate(threads, true)
            }
            threads.take(count).filterNotNull().map { it.name }
        }.getOrNull()
        return buildJsonObject {
            put("java_count", names?.size)
            if (names == null) {
                put("by_category", JsonNull)
            } else {
                put("by_category", buildJsonObject {
                    val categories = names.groupingBy { ThreadIndex.replace(it, "#") }.eachCount()
                    categories.toSortedMap().forEach { (category, count) -> put(category, count) }
                })
            }
        }
    }

    private fun descriptors(): JsonObject {
        val counts = linkedMapOf(
            "database" to 0, "database_wal" to 0, "database_shm" to 0, "database_journal" to 0,
            "socket" to 0, "pipe" to 0, "anon_inode" to 0, "device" to 0, "apk" to 0,
            "other" to 0, "unresolved" to 0,
        )
        var enumerationDescriptors = 0
        val descriptorDir = File("/proc/$processId/fd")
        val observed = runCatching {
            Files.newDirectoryStream(descriptorDir.toPath()).use { paths ->
                for (path in paths) {
                    val target = runCatching { Os.readlink(path.toString()) }.getOrNull()?.removeSuffix(" (deleted)")
                    // DirectoryStream itself holds /proc/<pid>/fd open while readlink runs.
                    if (target == descriptorDir.path || target == "/proc/self/fd") {
                        enumerationDescriptors++
                        continue
                    }
                    val category = when {
                        target == null -> "unresolved"
                        target in databasePaths -> "database"
                        target.endsWith("-wal") && target.removeSuffix("-wal") in databasePaths -> "database_wal"
                        target.endsWith("-shm") && target.removeSuffix("-shm") in databasePaths -> "database_shm"
                        target.endsWith("-journal") && target.removeSuffix("-journal") in databasePaths -> "database_journal"
                        target.startsWith("socket:") -> "socket"
                        target.startsWith("pipe:") -> "pipe"
                        target.startsWith("anon_inode:") -> "anon_inode"
                        target.startsWith("/dev/") -> "device"
                        target.endsWith(".apk") -> "apk"
                        else -> "other"
                    }
                    counts[category] = counts.getValue(category) + 1
                }
            }
        }.isSuccess
        val completeDatabaseClassification = observed && counts.getValue("unresolved") == 0 && databaseFile != null
        return buildJsonObject {
            put("total", counts.values.sum().takeIf { observed })
            put("excluded_proc_fd_directory_handles", enumerationDescriptors.takeIf { observed })
            put("by_category", if (observed) buildJsonObject {
                counts.forEach { (category, count) -> put(category, count) }
            } else JsonNull)
            for (category in listOf("database", "database_wal", "database_shm", "database_journal")) {
                put(category, counts.getValue(category).takeIf { completeDatabaseClassification })
            }
        }
    }

    private fun databaseFiles() = buildJsonObject {
        val path = databaseFile?.path
        put("name", databaseFile?.name)
        put("open", database.isOpen)
        put("main_bytes", path?.let(::fileBytes))
        put("wal_bytes", path?.let { fileBytes("$it-wal") })
        put("shm_bytes", path?.let { fileBytes("$it-shm") })
        put("journal_bytes", path?.let { fileBytes("$it-journal") })
        put("pool_connections", databaseConnectionCount())
        put("pool_evidence", "Room exposes no connection count through its public observation API")
    }

    private fun fileBytes(path: String): Long? = try {
        Os.lstat(path).let { if (OsConstants.S_ISREG(it.st_mode)) it.st_size else null }
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) 0L else null
    }

    private fun databaseConnectionCount(): Int? {
        val lines = dumpLines("dumpsys -t 5 dbinfo $processId") ?: return null
        var exactPool = false
        var pools = 0
        var connections = 0
        for (line in lines) {
            val text = line.trim()
            if (text.startsWith("Connection pool for ")) {
                exactPool = text.removePrefix("Connection pool for ").removeSuffix(":") in databasePaths
                if (exactPool) pools++
            } else if (exactPool && ConnectionHeader.matches(text)) {
                connections++
            }
        }
        return connections.takeIf { pools > 0 }
    }

    private fun workspaceFiles(root: File): JsonObject {
        val pending = ArrayDeque<File>().apply { add(root) }
        var files = 0L
        var bytes = 0L
        var tempFiles = 0L
        var tempBytes = 0L
        var symlinks = 0L
        var errors = 0
        var rootExists: Boolean? = null
        while (pending.isNotEmpty()) {
            val file = pending.removeLast()
            val stat = try {
                Os.lstat(file.path)
            } catch (error: ErrnoException) {
                if (file == root && error.errno == OsConstants.ENOENT) rootExists = false else errors++
                continue
            }
            if (file == root) rootExists = true
            when {
                OsConstants.S_ISDIR(stat.st_mode) -> {
                    val children = file.listFiles()
                    if (children == null) errors++ else pending.addAll(children.toList())
                }
                OsConstants.S_ISREG(stat.st_mode) -> {
                    files++
                    bytes += stat.st_size
                    if (TemporaryName.containsMatchIn(file.name)) {
                        tempFiles++
                        tempBytes += stat.st_size
                    }
                }
                OsConstants.S_ISLNK(stat.st_mode) -> symlinks++
            }
        }
        return buildJsonObject {
            put("exists", rootExists)
            put("files", files.takeIf { errors == 0 })
            put("bytes", bytes.takeIf { errors == 0 })
            put("temporary_files", tempFiles.takeIf { errors == 0 })
            put("temporary_bytes", tempBytes.takeIf { errors == 0 })
            put("symlinks", symlinks.takeIf { errors == 0 })
            put("stat_errors", errors)
            put("temporary_suffixes", ".tmp,.temp,.part,.partial")
        }
    }

    private fun androidResources(): JsonObject {
        val active = activeServices()
        val notifications = runCatching {
            context.getSystemService(NotificationManager::class.java)?.activeNotifications
                ?.filter { it.packageName == packageName && it.user == Process.myUserHandle() }
        }.getOrNull()
        val receivers = registeredReceivers()
        return buildJsonObject {
            put("active_services", active?.size)
            put("active_service_components", buildJsonArray {
                active.orEmpty().map { it.component.flattenToShortString() }.sorted().forEach { add(it) }
            })
            put("notifications", notifications?.size)
            put("foreground_notifications", notifications?.count {
                it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            })
            put("registered_receivers", receivers?.size)
            put("registered_receiver_records", buildJsonArray { receivers.orEmpty().forEach { add(it) } })
            put("accessibility", accessibility(active))
        }
    }

    private data class ActiveService(
        val component: ComponentName,
        var hasProcess: Boolean = false,
        var hasBoundConnection: Boolean = false,
    )

    private fun activeServices(): List<ActiveService>? {
        val lines = dumpLines("dumpsys -t 5 activity services $packageName") ?: return null
        if (lines.none { it.startsWith("ACTIVITY MANAGER SERVICES") }) return null
        val records = mutableListOf<ActiveService>()
        var activeUser: Int? = null
        var record: ActiveService? = null
        for (line in lines) {
            val text = line.trim()
            val activeHeader = ActiveServicesHeader.matchEntire(text)
            if (activeHeader != null || (line.startsWith("  ") && !line.startsWith("   ") && text.endsWith(':'))) {
                activeUser = activeHeader?.groupValues?.get(1)?.toIntOrNull()
                record = null
            }
            if (activeUser != userId) continue
            if (text.startsWith("* ServiceRecord{")) {
                val match = ServiceRecord.find(text)
                if (match == null) return null
                val component = ComponentName.unflattenFromString(match.groupValues[2]) ?: return null
                record = null
                if (match.groupValues[1].toIntOrNull() == userId && component.packageName == packageName) {
                    record = ActiveService(component)
                    records.add(record)
                }
            } else if (text.startsWith("app=ProcessRecord{")) {
                record?.hasProcess = true
            } else if (RequestedBinding.containsMatchIn(text)) {
                record?.hasBoundConnection = true
            }
        }
        return records
    }

    private fun registeredReceivers(): List<String>? {
        var inReceiverSection = false
        val lines = dumpLines(
            "dumpsys -t 5 activity broadcasts $packageName",
            keep = { line ->
                val text = line.trimStart()
                when {
                    text.startsWith("ACTIVITY MANAGER BROADCAST STATE") -> true
                    text.startsWith("Registered Receivers:") -> {
                        inReceiverSection = true
                        false
                    }
                    inReceiverSection && text.startsWith("* ReceiverList{") -> true
                    else -> {
                        if (text.isEmpty() || (line.indexOfFirst { !it.isWhitespace() } <= 1 && text.endsWith(':'))) {
                            inReceiverSection = false
                        }
                        false
                    }
                }
            },
        ) ?: return null
        if (lines.none { it.startsWith("ACTIVITY MANAGER BROADCAST STATE") }) return null
        val records = linkedMapOf<String, String>()
        for (line in lines.filter { it.trimStart().startsWith("* ReceiverList{") }) {
            val match = ReceiverRecord.find(line) ?: return null
            val (recordId, pid, process, receiverUid, receiverUser) = match.destructured
            // The system dump is package-filtered. Recheck caller UID and Android user so
            // a shared package name in another profile is excluded.
            if (receiverUid.toIntOrNull() == uid && receiverUser.toIntOrNull() in listOf(userId, -1)) {
                records[recordId] = "$recordId pid=$pid process=$process uid=$receiverUid user=$receiverUser"
            }
        }
        return records.values.sorted()
    }

    private fun accessibility(active: List<ActiveService>?): JsonObject {
        val settingsValue = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        val settingsEntries = settingsValue?.split(':')?.filter(String::isNotBlank)
        val settingEnabled = settingsEntries?.map { ComponentName.unflattenFromString(it) }
            ?.takeIf { entries -> entries.none { it == null } }
            ?.contains(accessibilityService)
        val dump = dumpLines("dumpsys -t 5 accessibility")?.joinToString("\n")
        val currentUser = dump?.let { CurrentUser.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val state = dump?.split("User state[")?.drop(1)?.firstOrNull {
            StateUser.find(it)?.groupValues?.get(1)?.toIntOrNull() == userId
        }
        val enabledInDump = inComponentSection(section(state, "Enabled services"))
        val crashed = inComponentSection(section(state, "Crashed services"))
        val boundSection = section(state, "Bound services")
        val explicitlyBound = inComponentSection(boundSection)
        val activeRecord = active?.firstOrNull { it.component == accessibilityService }
        val systemConnection = active?.let { activeRecord?.let { it.hasProcess && it.hasBoundConnection } ?: false }
        val bound = when {
            currentUser != userId || boundSection == null -> null
            explicitlyBound == true -> systemConnection
            explicitlyBound == false -> false
            // AOSP may print only labels here. Require the exact system binding as well.
            boundSection.contains("Service[") -> systemConnection
            else -> null
        }
        val enabled = when {
            enabledInDump == null -> settingEnabled
            settingEnabled == null || settingEnabled == enabledInDump -> enabledInDump
            else -> null
        }
        return buildJsonObject {
            put("component", accessibilityService.flattenToShortString())
            put("enabled", enabled)
            put("bound", bound)
            put("crashed", crashed)
            put("current_user_id", currentUser)
            put("secure_setting_contains_component", settingEnabled)
            put("enabled_services_contains_component", enabledInDump)
            put("bound_services_contains_component", explicitlyBound)
            put("bound_services_has_labels", boundSection?.contains("Service["))
            put("service_record_has_bound_connection", systemConnection)
            put("same_process_service_instance", FlowChatAccessibilityService.current() != null)
        }
    }

    private fun section(state: String?, name: String): String? = state?.lineSequence()
        ?.map(String::trim)?.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()

    private fun inComponentSection(section: String?): Boolean? {
        if (section == null) return null
        if (section == "{}" || section == "[]") return false
        val components = FlattenedComponent.findAll(section).map {
            ComponentName.unflattenFromString(it.value)
        }.toList()
        return when {
            accessibilityService in components -> true
            components.isNotEmpty() && !section.contains("Service[") -> false
            else -> null
        }
    }

    private fun dumpLines(command: String, keep: (String) -> Boolean = { true }): List<String>? = runCatching {
        var failure = false
        val lines = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().useLines { lines ->
                lines.filter { line ->
                    if (DumpError.containsMatchIn(line)) failure = true
                    keep(line)
                }.toList()
            }
        lines.takeUnless { failure }
    }.getOrNull()

    private companion object {
        val ProcessStatus = Regex("""(VmRSS|RssAnon|RssFile|RssShmem|Threads):\s*(\d+)(?:\s+kB)?\s*""")
        val ThreadIndex = Regex("""(?<=[- :])\d+$""")
        val TemporaryName = Regex("""\.(tmp|temp|part|partial)$""", RegexOption.IGNORE_CASE)
        val ActiveServicesHeader = Regex("""User (\d+) active services:""")
        val ServiceRecord = Regex("""^\* ServiceRecord\{\S+\s+u(\d+)\s+([^\s}]+)""")
        val RequestedBinding = Regex("""\brequested=true\s+received=true\s+hasBound=true\b""")
        val ReceiverRecord = Regex("""\* ReceiverList\{(\S+) (\d+) ([^/\s]+)/(\d+)/u(-?\d+)\s""")
        val CurrentUser = Regex("""(?m)^\s*currentUserId\s*=\s*(\d+)\s*$""")
        val StateUser = Regex("""attributes:\{id=(\d+)(?:,|\})""")
        val ConnectionHeader = Regex("""Connection #\d+:""")
        val FlattenedComponent = Regex("""[\w.]+/[\w.$]+""")
        val DumpError = Regex("""^\s*(?:\*\*\*.*(?:TIMEOUT|TIMED OUT)|Permission Denial:|Can't find service:|Error:|Exception occurred while dumping:)""")
    }
}
