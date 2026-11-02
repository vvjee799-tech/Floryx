package com.flowchat.app.data.terminal

/** A blocked pipe reader kills the owned group on host death; no periodic wakeups. */
internal object OwnedProcessGroup {
    fun argv(command: List<String>, marker: String): List<String> = listOf(
        "/system/bin/setsid", "-w", "/system/bin/sh", "-c",
        "group=\$\$; printf '%s%s\\n' '$marker' \"\$group\"; " +
            "exec 3<&0; /system/bin/sh -c 'read -r lifetime; /system/bin/kill -KILL -- \"-\$1\"' " +
            "flowchat-watch \"\$group\" <&3 >/dev/null 2>&1 & " +
            "exec 3<&-; exec \"\$@\" </dev/null",
        "flowchat-owned"
    ) + command
}
