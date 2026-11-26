package com.flowchat.app.data.terminal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.serialization.json.*

/** Fixed OS paths only. No caller-supplied path, shell, Root, process start or execution test. */
internal object PhoneShellFacts {
    internal interface Files {
        fun kind(path: String, followLinks: Boolean): String
        fun linkTarget(path: String): String
        fun executable(path: String): Boolean
    }
    internal class Unavailable(val status: String) : Exception()

    private object NativeFiles : Files {
        private fun <T> read(block: () -> T): T = try { block() } catch (error: ErrnoException) {
            throw Unavailable(when (error.errno) {
                OsConstants.ENOENT, OsConstants.ENOTDIR -> "missing"
                OsConstants.EACCES, OsConstants.EPERM -> "permission_denied"
                else -> "unknown"
            })
        } catch (_: SecurityException) { throw Unavailable("permission_denied") }
        catch (_: Exception) { throw Unavailable("unknown") }
        override fun kind(path: String, followLinks: Boolean): String = read {
            val mode = (if (followLinks) Os.stat(path) else Os.lstat(path)).st_mode
            when { OsConstants.S_ISREG(mode) -> "regular_file"; OsConstants.S_ISLNK(mode) -> "symbolic_link"; else -> "other" }
        }
        override fun linkTarget(path: String): String = read { Os.readlink(path) }
        override fun executable(path: String): Boolean = read { Os.access(path, OsConstants.X_OK) }
    }

    fun observe(files: Files = NativeFiles): JsonObject = buildJsonObject {
        put("source", "app_uid_filesystem_metadata")
        put("execution_tested", false)
        put("scope", "Fixed /system/bin/sh and /system/bin/mksh metadata from the current Android app UID, without Root or commands. Symlink targets are raw link text. Execute access is a permission observation, not a runtime/ABI/script compatibility test. Missing/denied/unknown are distinct; do not retry these same facts via Android ls/getprop just to confirm them.")
        put("paths", buildJsonArray { listOf("/system/bin/sh", "/system/bin/mksh").forEach { path -> add(buildJsonObject pathJson@{
            put("path", path)
            val entry = try { files.kind(path, false) } catch (error: Unavailable) {
                put("entry_status", if (error.status == "missing") "missing" else "unknown")
                put("observation_error", error.status); return@pathJson
            }
            put("entry_status", "present"); put("entry_kind", entry)
            if (entry == "symbolic_link") {
                try { put("link_target", files.linkTarget(path)) }
                catch (error: Unavailable) { put("link_observation_error", error.status) }
            }
            val target = try { if (entry == "symbolic_link") files.kind(path, true) else entry }
            catch (error: Unavailable) {
                put("target_status", if (error.status == "missing") "missing" else "unknown")
                put("target_observation_error", error.status); return@pathJson
            }
            put("target_status", "present"); put("target_kind", target)
            if (target == "regular_file") {
                try { put("executable_access_for_app_uid", files.executable(path)); put("access_status", "observed") }
                catch (error: Unavailable) { put("access_status", "unknown"); put("access_observation_error", error.status) }
            }
        }) } })
    }
}
