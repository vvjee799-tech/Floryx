package com.flowchat.app.data.terminal

/** The supervisor owns the lifetime watcher and target as siblings, with no periodic wakeups. */
internal object OwnedProcessGroup {
    fun argv(command: List<String>, marker: String, reportExit: Boolean = false): List<String> = listOf(
        "/system/bin/setsid", "-w", "/system/bin/sh", "-c",
        "group=\$\$; printf '%s%s\\n' '$marker' \"\$group\"; " +
            "exec 3<&0; /system/bin/sh -c 'read -r lifetime; /system/bin/kill -KILL -- \"-\$1\"' " +
            "flowchat-watch \"\$group\" <&3 >/dev/null 2>&1 & watcher=\$!; " +
            // Do not exec PRoot here: it would inherit the watcher as a waitable child
            // and wait for it, while the host waits for PRoot before closing lifetime.
            "exec 3<&-; \"\$@\" </dev/null & target=\$!; wait \"\$target\"; code=\$?; " +
            (if (reportExit) "printf '\\036${marker}EXIT:%s\\037' \"\$code\"; " else "") +
            "/system/bin/kill -KILL \"\$watcher\" 2>/dev/null; wait \"\$watcher\" 2>/dev/null; exit \"\$code\"",
        "flowchat-owned"
    ) + command
}
