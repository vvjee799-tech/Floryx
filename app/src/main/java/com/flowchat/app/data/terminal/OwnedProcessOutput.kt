package com.flowchat.app.data.terminal

/** Removes only the random supervisor exit frame, preserving output and chunk boundaries. */
internal class OwnedProcessOutput(
    marker: String,
    private val output: (String) -> Unit,
    private val exited: (Int) -> Unit
) {
    private val prefix = "\u001e${marker}EXIT:"
    private var pending = ""

    fun accept(text: String) {
        pending += text
        while (pending.isNotEmpty()) {
            val start = pending.indexOf(prefix)
            if (start < 0) {
                val keep = (minOf(prefix.length - 1, pending.length) downTo 1)
                    .firstOrNull { pending.endsWith(prefix.take(it)) } ?: 0
                emit(pending.dropLast(keep))
                pending = pending.takeLast(keep)
                return
            }
            emit(pending.take(start))
            pending = pending.drop(start)
            val end = pending.indexOf('\u001f', prefix.length)
            if (end < 0 && pending.length <= prefix.length + 4) return
            val code = if (end in prefix.length..prefix.length + 3)
                pending.substring(prefix.length, end).toIntOrNull()?.takeIf { it in 0..255 } else null
            if (code != null) {
                exited(code)
                pending = pending.drop(end + 1)
            } else {
                emit(pending.take(1))
                pending = pending.drop(1)
            }
        }
    }

    fun finish() { emit(pending); pending = "" }
    private fun emit(text: String) { if (text.isNotEmpty()) output(text) }
}
