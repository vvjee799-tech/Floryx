package com.flowchat.app.diagnostics

import android.os.Binder
import android.os.Parcel
import com.flowchat.app.data.device.FlowChatShizukuService
import com.flowchat.app.domain.terminal.CommandSnapshot
import com.flowchat.app.domain.terminal.CommandStatus
import com.flowchat.app.shizuku.IFlowChatShizukuService
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/** Debug-only shell identity test for the same AIDL service used by Shizuku. */
object TerminalShellProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(android.os.Process.myUid() == 2000) { "Run this diagnostic as adb shell, not root." }
        val service = FlowChatShizukuService()
        val bridge = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = service.asBinder().transact(code, data, reply, flags)
        }
        val proxy = IFlowChatShizukuService.Stub.asInterface(bridge)
        val json = Json { ignoreUnknownKeys = true }
        fun read(raw: String) = json.decodeFromString<CommandSnapshot>(raw)
        fun await(id: String): CommandSnapshot {
            val end = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < end) {
                val current = read(proxy.readShellCommand(id))
                if (!current.isRunning) return current
                Thread.sleep(50)
            }
            error("Shell diagnostic timed out.")
        }
        val first = read(proxy.startShellCommand("id -u; printf 'FLOWCHAT_SHIZUKU_SHELL_OK\\n'", "/data/local/tmp", 5))
        val result = await(first.id)
        check(result.exitCode == 0 && result.output.contains("2000") && result.output.contains("FLOWCHAT_SHIZUKU_SHELL_OK"))
        val long = read(proxy.startShellCommand("sleep 30", "/data/local/tmp", 45))
        Thread.sleep(200)
        proxy.cancelShellCommand(long.id)
        check(await(long.id).status == CommandStatus.Cancelled)
        val failed = read(proxy.startShellCommand("exit 9", "/data/local/tmp", 5))
        check(await(failed.id).exitCode == 9)
        println("PASS: shell UID 2000, AIDL command start/read/cancel, exit status and process cleanup.")
        kotlin.system.exitProcess(0)
    }
}
