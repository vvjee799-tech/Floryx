package com.flowchat.app.device

import android.provider.Settings
import com.flowchat.app.domain.terminal.TerminalToolDefinitions as Terminal
import com.flowchat.app.domain.tools.AgentToolDefinitions as Device
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions as Workspace
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

internal const val S1Memo = "memo.txt"
internal const val S1MemoText = "S1-MEMO-HEAD\nrelease=ORCHID-42\nS1-MEMO-END\n"
internal const val S1LongFile = "guide.txt"
internal const val S1LongEnd = "S1-GUIDE-END-672"
internal val S1LongText = "S1-GUIDE-HEAD\n" + "section: controlled source evidence\n".repeat(2_000) + "$S1LongEnd\n"

internal enum class S1Entry { Chat, Retry, Voice }
internal enum class S1Stop { None, WaitingModel, RunningCommand }

internal class S1Gate {
    private val waiting = CountDownLatch(1)
    private val release = CountDownLatch(1)
    val entered get() = waiting.count == 0L

    fun hold() {
        waiting.countDown()
        check(release.await(12, TimeUnit.SECONDS)) { "Fixture did not release the model" }
    }

    fun open() = release.countDown()
}

internal class S1Scenario(
    val id: Int,
    val name: String,
    val instruction: String,
    val entry: S1Entry = S1Entry.Chat,
    val state: String = "Completed",
    val answer: String? = null,
    val settingsAction: String? = null,
    val prior: Int? = null,
    val stop: S1Stop = S1Stop.None,
    val stopSource: String = "voice_stop",
    val expectNewConversation: Boolean = false,
    val newVoiceSession: Boolean = false,
    val verifyPriorChain: Boolean = false,
    val gate: S1Gate? = null,
    val toolOrder: List<String> = emptyList(),
    val script: S1Request.() -> JsonObject?
) {
    val plan = ContinuousReliabilityModel.Plan { request, turn ->
        S1Request(id, request, turn).script()
    }.also { it.allowClientDisconnect = stop == S1Stop.WaitingModel }
}

internal class S1Request(private val id: Int, private val request: JsonObject, val turn: Int) {
    private val messages get() = request.getValue("messages").jsonArray
    val lastTool: String get() = messages.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
        .jsonObject.getValue("content").jsonPrimitive.content
    val toolJson get() = Json.parseToJsonElement(lastTool).jsonObject

    fun requirePriorInstruction(instruction: String) {
        check(messages.any { message ->
            message.jsonObject["role"]?.jsonPrimitive?.content == "user" &&
                message.toString().contains(instruction)
        }) { "The continuation lost its original instruction" }
    }

    fun tool(name: String, args: JsonObject = buildJsonObject {}): JsonObject {
        check(request["tools"]?.jsonArray?.any {
            it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name
        } == true) { "Task $id did not offer $name" }
        return ContinuousReliabilityModel.tool("call_s1_" + id + "_$turn", name, args)
    }

    fun read(path: String, offset: Long = 0, size: Int = 256) = tool(Workspace.Read, buildJsonObject {
        put("path", path)
        put("byte_offset", offset)
        put("max_bytes", size)
    })

    fun search(literal: String, path: String = S1Memo) = tool(Workspace.Search, buildJsonObject {
        put("path", path)
        put("query", literal)
        put("max_results", 8)
    })

    fun command(command: String, seconds: Int, input: String? = null) = tool(Terminal.Run, buildJsonObject {
        put("environment", "linux")
        put("command", command)
        put("timeout_seconds", seconds)
        input?.let { putJsonArray("workspace_inputs") { add(it) } }
    })

    fun observe() = tool(Device.ObserveScreenToolName, buildJsonObject { put("include_image", false) })

    fun tapSound(): JsonObject {
        check(lastTool.lineSequence().any { it == "package=com.android.settings" }) { "Expected Settings before tap" }
        val label = listOf("Sound & vibration", "提示音和振动", "声音与振动", "声音和振动", "声音与触感")
            .firstOrNull { "\"$it\"" in lastTool } ?: error("Sound settings entry was not observed: " + lastTool.take(3500))
        val screenId = lastTool.lineSequence().first { it.startsWith("screen_id=") }.substringAfter('=')
        return tool(Device.TapUiTargetToolName, buildJsonObject {
            put("screen_id", screenId)
            putJsonObject("target") { put("text", label) }
        })
    }

    fun requireSoundPage(afterTap: Boolean = false) {
        if (afterTap) check("target_action_attempts=1;" in lastTool)
        check(lastTool.lineSequence().any { it == "package=com.android.settings" })
        check(lastTool.contains("Media volume", ignoreCase = true) ||
            lastTool.contains("媒体音量") || lastTool.contains("媒体")) {
            "Media volume was not observed: " + lastTool.take(3500)
        }
    }

    fun requireMemo() {
        check(toolJson.getValue("text").jsonPrimitive.content == S1MemoText)
        check(toolJson.getValue("whole_file_returned").jsonPrimitive.boolean)
    }

    fun requireCommand(status: String): JsonObject = toolJson.also {
        check(it.getValue("status").jsonPrimitive.content == status) { it.toString() }
        check(it.getValue("cleanup").jsonObject.getValue("state").jsonPrimitive.content == "Released")
    }

    fun answer(text: String) = ContinuousReliabilityModel.answer(text)
}

internal fun s1Scenarios(): List<S1Scenario> = buildList {
    addAll(waveOneScenarios())
    for (w in 1..4) {
        addAll(waveFollowupScenarios(w))
    }
}

private fun waveOneScenarios(): List<S1Scenario> {
    val stopChat = S1Gate()
    val stopVoice = S1Gate()
    val followUp = S1Gate()
    val finalFollowUp = S1Gate()
    val failedInstruction = "S1-06 从便签读取发布代号。"
    val memoSizeAnswer = "已找到 memo.txt，大小为 " + S1MemoText.length + " 字节。"
    val longVoice = "便签已核对，发布代号是 ORCHID-42。以下为长回复卡片样本。" +
        "第一段记录读取范围，第二段记录完成结果，第三段记录接续信息。".repeat(6) + "全文已完。"
    val observe = Device.ObserveScreenToolName
    val tap = Device.TapUiTargetToolName
    return listOf(
        S1Scenario(1, "workspace_list_and_stat", "S1-01 找到便签并核对文件大小。", answer = memoSizeAnswer,
            toolOrder = listOf(Workspace.ListFiles, Workspace.Stat)) {
            when (turn) {
                1 -> tool(Workspace.ListFiles)
                2 -> {
                    check(S1Memo in lastTool && S1LongFile in lastTool)
                    tool(Workspace.Stat, buildJsonObject { put("path", S1Memo) })
                }
                3 -> {
                    check(toolJson.getValue("source").jsonObject.getValue("total_bytes").jsonPrimitive.int == S1MemoText.length)
                    answer(memoSizeAnswer)
                }
                else -> error("Unexpected list model request")
            }
        },
        S1Scenario(2, "voice_device_time", "S1-02 请读取设备本地日期时间。", S1Entry.Voice,
            answer = "已经读取设备本地时间。", toolOrder = listOf(Device.DeviceTimeToolName)) {
            if (turn == 1) tool(Device.DeviceTimeToolName) else {
                check(lastTool.contains("timezone", ignoreCase = true) || lastTool.contains("time_zone"))
                answer("已经读取设备本地时间。")
            }
        },
        S1Scenario(3, "settings_read_target", "S1-03 从设置首页找到媒体音量的所在页，保持原设置。",
            answer = "媒体音量位于提示音和振动。", settingsAction = Settings.ACTION_SETTINGS, toolOrder = listOf(observe, tap)) {
            when (turn) {
                1 -> observe()
                2 -> tapSound()
                3 -> { requireSoundPage(afterTap = true); answer("媒体音量位于提示音和振动。") }
                else -> error("Unexpected Settings model request")
            }
        },
        S1Scenario(4, "bounded_long_source", "S1-04 从长文档核对首段和末行，说明范围。",
            answer = "已核对首段和末行 $S1LongEnd。", toolOrder = listOf(Workspace.Read, Workspace.Read)) {
            when (turn) {
                1 -> read(S1LongFile)
                2 -> {
                    check(toolJson.getValue("text").jsonPrimitive.content.startsWith("S1-GUIDE-HEAD"))
                    check(!toolJson.getValue("eof").jsonPrimitive.boolean)
                    read(S1LongFile, S1LongText.length.toLong() - 128)
                }
                3 -> {
                    check(toolJson.getValue("text").jsonPrimitive.content.endsWith("$S1LongEnd\n"))
                    check(!toolJson.getValue("whole_file_returned").jsonPrimitive.boolean)
                    answer("已核对首段和末行 $S1LongEnd。")
                }
                else -> error("Unexpected long file request")
            }
        },
        S1Scenario(5, "cancel_model_before_action", "S1-05 取消等待中的页面任务。", state = "Stopped",
            stop = S1Stop.WaitingModel, gate = stopChat) {
            check(turn == 1)
            stopChat.hold()
            tool(Device.PressBackToolName)
        },
        S1Scenario(6, "model_loading_failure", failedInstruction, state = "Failed") {
            check(turn == 1)
            null
        },
        S1Scenario(7, "linux_command", "S1-07 运行只读 Linux 命令并核对退出值。",
            answer = "命令退出为 0，输出 S1-EXIT-0。", toolOrder = listOf(Terminal.Run)) {
            if (turn == 1) command("printf S1-EXIT-0", 5) else {
                val result = requireCommand("Exited")
                check(result.getValue("exit_code").jsonPrimitive.int == 0 && result.getValue("output").jsonPrimitive.content == "S1-EXIT-0")
                answer("命令退出为 0，输出 S1-EXIT-0。")
            }
        },
        S1Scenario(8, "voice_full_card_single_playback", "S1-08 用语音读取便签，保留长回复卡片。", S1Entry.Voice,
            answer = longVoice, toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) read(S1Memo) else { requireMemo(); answer(longVoice) }
        },
        S1Scenario(9, "recover_previous_load", "S1-09 通过同一会话的重试入口接续。", S1Entry.Retry, prior = 6,
            answer = "恢复后核对发布代号 ORCHID-42。", toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) {
                requirePriorInstruction(failedInstruction)
                read(S1Memo)
            } else { requireMemo(); answer("恢复后核对发布代号 ORCHID-42。") }
        },
        S1Scenario(10, "linux_timeout_then_read", "S1-10 遇到 Linux 命令超时后，只读核对原命令与便签。",
            answer = "命令已超时，未重跑；便签发布代号是 ORCHID-42。",
            toolOrder = listOf(Terminal.Run, Terminal.Read, Workspace.Read)) {
            when (turn) {
                1 -> command("printf S1-TIMEOUT; sleep 8; printf S1-LATE", 1)
                2 -> {
                    val result = requireCommand("TimedOut")
                    check("S1-LATE" !in result.getValue("output").jsonPrimitive.content)
                    tool(Terminal.Read, buildJsonObject {
                        put("command_id", result.getValue("command_id"))
                        put("wait_for_completion", false)
                    })
                }
                3 -> { requireCommand("TimedOut"); read(S1Memo) }
                4 -> { requireMemo(); answer("命令已超时，未重跑；便签发布代号是 ORCHID-42。") }
                else -> error("Unexpected timeout recovery request")
            }
        },
        S1Scenario(11, "voice_followup_receipt_isolation", "S1-11 继续核对同一便签的末行。", S1Entry.Voice,
            answer = "末行是 S1-MEMO-END。", prior = 8, verifyPriorChain = true, gate = followUp,
            toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) { followUp.hold(); read(S1Memo) }
            else { requireMemo(); answer("末行是 S1-MEMO-END。") }
        },
        S1Scenario(12, "workspace_literal_miss", "S1-12 核对便签里是否有 S1-NOT-PRESENT。",
            answer = "全文未包含这个精确字串。", toolOrder = listOf(Workspace.Search)) {
            if (turn == 1) search("S1-NOT-PRESENT") else {
                check(toolJson.getValue("query_absent_in_whole_file").jsonPrimitive.boolean)
                check(toolJson.getValue("match_count").jsonPrimitive.int == 0)
                answer("全文未包含这个精确字串。")
            }
        },
        S1Scenario(13, "page_loading_failure_route", "S1-13 等候的控件未加载时改从已有页面查找媒体音量。",
            answer = "未出现预期控件，已从设置入口找到媒体音量。", settingsAction = Settings.ACTION_SETTINGS,
            toolOrder = listOf(Device.WaitForScreenToolName, observe, tap)) {
            when (turn) {
                1 -> tool(Device.WaitForScreenToolName, buildJsonObject {
                    put("include_image", false)
                    put("timeout_ms", 500)
                    putJsonObject("condition") {
                        put("package_name", "com.android.settings")
                        putJsonObject("element") { put("text", "S1 unavailable loading target") }
                    }
                })
                2 -> {
                    check("condition_matched=false" in lastTool) { lastTool.take(2000) }
                    observe()
                }
                3 -> tapSound()
                4 -> { requireSoundPage(afterTap = true); answer("未出现预期控件，已从设置入口找到媒体音量。") }
                else -> error("Unexpected page recovery request")
            }
        },
        S1Scenario(14, "cancel_owned_linux_process", "S1-14 停止已经启动的 Linux 命令。", state = "Stopped",
            stop = S1Stop.RunningCommand, toolOrder = listOf(Terminal.Run)) {
            check(turn == 1)
            command("printf S1-COMMAND-START; sleep 8; printf S1-LATE", 12)
        },
        S1Scenario(15, "voice_stop_rejects_late_reply", "S1-15 语音停止后丢弃迟到结果。", S1Entry.Voice, "Stopped",
            stop = S1Stop.WaitingModel, gate = stopVoice) {
            check(turn == 1)
            stopVoice.hold()
            answer("S1-LATE-VOICE")
        },
        S1Scenario(16, "missing_file_then_literal_search", "S1-16 原路径不存在时改为查询便签的发布代号。",
            answer = "原路径读取失败，已从便签核对 ORCHID-42。", toolOrder = listOf(Workspace.Read, Workspace.Search)) {
            when (turn) {
                1 -> read("unavailable.txt")
                2 -> { check("error" in toolJson); search("ORCHID-42") }
                3 -> {
                    check(toolJson.getValue("match_count").jsonPrimitive.int == 1 && "ORCHID-42" in lastTool)
                    answer("原路径读取失败，已从便签核对 ORCHID-42。")
                }
                else -> error("Unexpected missing file request")
            }
        },
        S1Scenario(17, "voice_after_stop", "S1-17 停止后发出新语音任务。", S1Entry.Voice, prior = 15,
            expectNewConversation = true, answer = "新语音任务独立完成。") {
            check(turn == 1)
            answer("新语音任务独立完成。")
        },
        S1Scenario(18, "settings_already_on_target", "S1-18 从声音设置页读取媒体音量入口。",
            answer = "当前页已有媒体音量入口。", settingsAction = Settings.ACTION_SOUND_SETTINGS, toolOrder = listOf(observe)) {
            if (turn == 1) observe() else { requireSoundPage(); answer("当前页已有媒体音量入口。") }
        },
        S1Scenario(19, "linux_import_and_verify", "S1-19 导入便签并用 Linux 核对末行。",
            answer = "Linux 读到末行 S1-MEMO-END。", toolOrder = listOf(Terminal.Run)) {
            if (turn == 1) command("tail -n 1 memo.txt", 6, S1Memo) else {
                val result = requireCommand("Exited")
                check(result.getValue("output").jsonPrimitive.content.trim() == "S1-MEMO-END")
                answer("Linux 读到末行 S1-MEMO-END。")
            }
        },
        S1Scenario(20, "voice_same_session_final", "S1-20 同一语音会话的新请求仅读取便签。",
            S1Entry.Voice, prior = 17, verifyPriorChain = true, gate = finalFollowUp,
            answer = "新请求已核对 S1-MEMO-HEAD。", toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) { finalFollowUp.hold(); read(S1Memo) }
            else { requireMemo(); answer("新请求已核对 S1-MEMO-HEAD。") }
        }
    )
}

private fun waveFollowupScenarios(w: Int): List<S1Scenario> {
    val base = w * 20
    val wave = w + 1
    val tag = "W$wave"
    val oddWave = w % 2 == 1
    val stopChat = S1Gate()
    val r2Gate = S1Gate()
    val r3Gate = S1Gate()
    val chatControlGate = S1Gate()
    val stopVoice = S1Gate()
    val afterStopGate = S1Gate()
    val finalVoiceGate = S1Gate()
    val observe = Device.ObserveScreenToolName
    val tap = Device.TapUiTargetToolName

    val statTarget = if (oddWave) S1LongFile else S1Memo
    val statSize = if (oddWave) S1LongText.length else S1MemoText.length
    val statAnswer = "$tag 已找到 $statTarget，大小为 $statSize 字节。"
    val interiorOffset = 14L + 36L * (w * 350)
    val failedInstruction = "S1-${base + 6} $tag 从便签读取发布代号。"
    val longVoice = "$tag 便签已核对，发布代号是 ORCHID-42。以下为长回复卡片样本。" +
        "第一段记录读取范围，第二段记录完成结果，第三段记录接续信息。".repeat(6) + "全文已完。"
    val wakeInterruptStop = oddWave
    val stopSource = if (wakeInterruptStop) "native_interrupt:wake_word" else "voice_stop"
    val linuxVerifyCmd = if (oddWave) "head -n 1 memo.txt" else "tail -n 1 memo.txt"
    val linuxExpectedLine = if (oddWave) "S1-MEMO-HEAD" else "S1-MEMO-END"
    val linuxVerifyAnswer = "$tag Linux 读到 $linuxExpectedLine。"

    return listOf(
        S1Scenario(base + 1, "w${wave}_workspace_list_and_stat", "S1-${base + 1} $tag 列出工作区并核对 $statTarget 大小。",
            answer = statAnswer, toolOrder = listOf(Workspace.ListFiles, Workspace.Stat)) {
            when (turn) {
                1 -> tool(Workspace.ListFiles)
                2 -> {
                    check(S1Memo in lastTool && S1LongFile in lastTool)
                    tool(Workspace.Stat, buildJsonObject { put("path", statTarget) })
                }
                3 -> {
                    check(toolJson.getValue("source").jsonObject.getValue("total_bytes").jsonPrimitive.int == statSize)
                    answer(statAnswer)
                }
                else -> error("Unexpected list model request in $tag")
            }
        },
        S1Scenario(base + 2, "w${wave}_voice_chain_r1_time", "S1-${base + 2} $tag 语音第 1 轮读取设备本地日期时间。",
            S1Entry.Voice, answer = "$tag 语音第 1 轮已读取设备本地时间。",
            toolOrder = listOf(Device.DeviceTimeToolName)) {
            if (turn == 1) tool(Device.DeviceTimeToolName) else {
                check(lastTool.contains("timezone", ignoreCase = true) || lastTool.contains("time_zone"))
                answer("$tag 语音第 1 轮已读取设备本地时间。")
            }
        },
        S1Scenario(base + 3, "w${wave}_settings_read_target", "S1-${base + 3} $tag 从设置首页找到媒体音量的所在页。",
            answer = "$tag 媒体音量位于提示音和振动。", settingsAction = Settings.ACTION_SETTINGS,
            toolOrder = listOf(observe, tap)) {
            when (turn) {
                1 -> observe()
                2 -> tapSound()
                3 -> { requireSoundPage(afterTap = true); answer("$tag 媒体音量位于提示音和振动。") }
                else -> error("Unexpected Settings model request in $tag")
            }
        },
        S1Scenario(base + 4, "w${wave}_bounded_long_source", "S1-${base + 4} $tag 从长文档核对偏移 $interiorOffset 与末行。",
            answer = "$tag 已核对偏移 $interiorOffset 与末行 $S1LongEnd。",
            toolOrder = listOf(Workspace.Read, Workspace.Read)) {
            when (turn) {
                1 -> read(S1LongFile, interiorOffset, 180)
                2 -> {
                    check("section: controlled source evidence" in toolJson.getValue("text").jsonPrimitive.content)
                    check(!toolJson.getValue("eof").jsonPrimitive.boolean)
                    read(S1LongFile, S1LongText.length.toLong() - 128)
                }
                3 -> {
                    check(toolJson.getValue("text").jsonPrimitive.content.endsWith("$S1LongEnd\n"))
                    check(!toolJson.getValue("whole_file_returned").jsonPrimitive.boolean)
                    answer("$tag 已核对偏移 $interiorOffset 与末行 $S1LongEnd。")
                }
                else -> error("Unexpected long file request in $tag")
            }
        },
        S1Scenario(base + 5, "w${wave}_cancel_model_before_action", "S1-${base + 5} $tag 取消等待中的页面任务。",
            state = "Stopped", stop = S1Stop.WaitingModel, gate = stopChat) {
            check(turn == 1)
            stopChat.hold()
            tool(Device.PressBackToolName)
        },
        S1Scenario(base + 6, "w${wave}_model_loading_failure", failedInstruction, state = "Failed") {
            check(turn == 1)
            null
        },
        S1Scenario(base + 7, "w${wave}_linux_command", "S1-${base + 7} $tag 运行只读 Linux 命令并核对退出值。",
            answer = "$tag 命令退出为 0，输出 S1-$tag-EXIT-0。", toolOrder = listOf(Terminal.Run)) {
            if (turn == 1) command("printf S1-$tag-EXIT-0", 5) else {
                val result = requireCommand("Exited")
                check(result.getValue("exit_code").jsonPrimitive.int == 0 &&
                    result.getValue("output").jsonPrimitive.content == "S1-$tag-EXIT-0")
                answer("$tag 命令退出为 0，输出 S1-$tag-EXIT-0。")
            }
        },
        S1Scenario(base + 8, "w${wave}_voice_chain_r2_long_card", "S1-${base + 8} $tag 同会话语音第 2 轮读取便签并写入第 2 条回执。",
            S1Entry.Voice, answer = longVoice, prior = base + 2, verifyPriorChain = true, gate = r2Gate,
            toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) { r2Gate.hold(); read(S1Memo) }
            else { requireMemo(); answer(longVoice) }
        },
        S1Scenario(base + 9, "w${wave}_recover_previous_load", "S1-${base + 9} $tag 通过同一会话的重试入口接续。",
            S1Entry.Retry, prior = base + 6, answer = "$tag 恢复后核对发布代号 ORCHID-42。",
            toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) {
                requirePriorInstruction(failedInstruction)
                read(S1Memo)
            } else { requireMemo(); answer("$tag 恢复后核对发布代号 ORCHID-42。") }
        },
        S1Scenario(base + 10, "w${wave}_linux_timeout_then_read", "S1-${base + 10} $tag 遇到 Linux 命令超时后只读核对原命令与便签。",
            answer = "$tag 命令已超时，未重跑；便签发布代号是 ORCHID-42。",
            toolOrder = listOf(Terminal.Run, Terminal.Read, Workspace.Read)) {
            when (turn) {
                1 -> command("printf S1-$tag-TIMEOUT; sleep 8; printf S1-LATE", 1)
                2 -> {
                    val result = requireCommand("TimedOut")
                    check("S1-LATE" !in result.getValue("output").jsonPrimitive.content)
                    tool(Terminal.Read, buildJsonObject {
                        put("command_id", result.getValue("command_id"))
                        put("wait_for_completion", false)
                    })
                }
                3 -> { requireCommand("TimedOut"); read(S1Memo) }
                4 -> { requireMemo(); answer("$tag 命令已超时，未重跑；便签发布代号是 ORCHID-42。") }
                else -> error("Unexpected timeout recovery request in $tag")
            }
        },
        S1Scenario(base + 11, "w${wave}_voice_chain_r3_after_multi_receipts",
            "S1-${base + 11} $tag 两次写入回执后，在同语音会话发出独立指令检索代号。",
            S1Entry.Voice, answer = "$tag 两次回执写入后独立语音指令已核对 ORCHID-42。",
            prior = base + 8, verifyPriorChain = true, gate = r3Gate,
            toolOrder = listOf(Workspace.Search)) {
            if (turn == 1) {
                r3Gate.hold()
                search("ORCHID-42")
            } else {
                check(toolJson.getValue("match_count").jsonPrimitive.int == 1 && "ORCHID-42" in lastTool)
                answer("$tag 两次回执写入后独立语音指令已核对 ORCHID-42。")
            }
        },
        S1Scenario(base + 12, "w${wave}_chat_independent_after_multi_receipts",
            "S1-${base + 12} $tag 三次语音回执写入后，在同一会话从聊天入口发出独立指令核对不存在字面量。",
            S1Entry.Chat, answer = "$tag 同会话三次语音回执后独立聊天指令确认全文无该字面量。",
            prior = base + 11, verifyPriorChain = true, gate = chatControlGate,
            toolOrder = listOf(Workspace.Search)) {
            if (turn == 1) {
                chatControlGate.hold()
                search("S1-NOT-PRESENT-$tag")
            } else {
                check(toolJson.getValue("query_absent_in_whole_file").jsonPrimitive.boolean)
                check(toolJson.getValue("match_count").jsonPrimitive.int == 0)
                answer("$tag 同会话三次语音回执后独立聊天指令确认全文无该字面量。")
            }
        },
        S1Scenario(base + 13, "w${wave}_page_loading_failure_route", "S1-${base + 13} $tag 等候的控件未加载时改从已有页面查找媒体音量。",
            answer = "$tag 未出现预期控件，已从设置入口找到媒体音量。", settingsAction = Settings.ACTION_SETTINGS,
            toolOrder = listOf(Device.WaitForScreenToolName, observe, tap)) {
            when (turn) {
                1 -> tool(Device.WaitForScreenToolName, buildJsonObject {
                    put("include_image", false)
                    put("timeout_ms", 500)
                    putJsonObject("condition") {
                        put("package_name", "com.android.settings")
                        putJsonObject("element") { put("text", "S1 $tag unavailable loading target") }
                    }
                })
                2 -> {
                    check("condition_matched=false" in lastTool) { lastTool.take(2000) }
                    observe()
                }
                3 -> tapSound()
                4 -> { requireSoundPage(afterTap = true); answer("$tag 未出现预期控件，已从设置入口找到媒体音量。") }
                else -> error("Unexpected page recovery request in $tag")
            }
        },
        S1Scenario(base + 14, "w${wave}_cancel_owned_linux_process", "S1-${base + 14} $tag 停止已经启动的 Linux 命令。",
            state = "Stopped", stop = S1Stop.RunningCommand, toolOrder = listOf(Terminal.Run)) {
            check(turn == 1)
            command("printf S1-COMMAND-START; sleep 8; printf S1-LATE", 12)
        },
        S1Scenario(base + 15, "w${wave}_voice_stop_after_multi_receipts",
            "S1-${base + 15} $tag 多次回执与同会话聊天后语音停止并丢弃迟到结果。",
            S1Entry.Voice, state = "Stopped", prior = base + 12, stop = S1Stop.WaitingModel,
            stopSource = stopSource, verifyPriorChain = true, gate = stopVoice) {
            check(turn == 1)
            stopVoice.hold()
            answer("S1-LATE-VOICE-$tag")
        },
        S1Scenario(base + 16, "w${wave}_missing_file_then_literal_search", "S1-${base + 16} $tag 原路径不存在时改为查询便签的发布代号。",
            answer = "$tag 原路径读取失败，已从便签核对 ORCHID-42。",
            toolOrder = listOf(Workspace.Read, Workspace.Search)) {
            when (turn) {
                1 -> read("unavailable-$tag.txt")
                2 -> { check("error" in toolJson); search("ORCHID-42") }
                3 -> {
                    check(toolJson.getValue("match_count").jsonPrimitive.int == 1 && "ORCHID-42" in lastTool)
                    answer("$tag 原路径读取失败，已从便签核对 ORCHID-42。")
                }
                else -> error("Unexpected missing file request in $tag")
            }
        },
        S1Scenario(base + 17, "w${wave}_voice_after_stop_control",
            "S1-${base + 17} $tag 停止后发出独立语音指令并校验前序 4 条回执。",
            S1Entry.Voice, prior = base + 15, expectNewConversation = !wakeInterruptStop,
            verifyPriorChain = true, gate = afterStopGate,
            answer = "$tag 停止后独立语音指令完成。", toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) { afterStopGate.hold(); read(S1Memo) }
            else { requireMemo(); answer("$tag 停止后独立语音指令完成。") }
        },
        S1Scenario(base + 18, "w${wave}_settings_already_on_target", "S1-${base + 18} $tag 从声音设置页直接读取媒体音量入口。",
            answer = "$tag 当前页已有媒体音量入口。", settingsAction = Settings.ACTION_SOUND_SETTINGS,
            toolOrder = listOf(observe)) {
            if (turn == 1) observe() else { requireSoundPage(); answer("$tag 当前页已有媒体音量入口。") }
        },
        S1Scenario(base + 19, "w${wave}_linux_import_and_verify", "S1-${base + 19} $tag 导入便签并用 Linux 核对 $linuxExpectedLine。",
            answer = linuxVerifyAnswer, toolOrder = listOf(Terminal.Run)) {
            if (turn == 1) command(linuxVerifyCmd, 6, S1Memo) else {
                val result = requireCommand("Exited")
                check(result.getValue("output").jsonPrimitive.content.trim() == linuxExpectedLine)
                answer(linuxVerifyAnswer)
            }
        },
        S1Scenario(base + 20, "w${wave}_voice_final_multi_receipt_control",
            "S1-${base + 20} $tag 五次写入回执后发出独立语音指令核对前序全部回执。",
            S1Entry.Voice, prior = base + 17, newVoiceSession = !oddWave, expectNewConversation = !oddWave,
            verifyPriorChain = true, gate = finalVoiceGate,
            answer = "$tag 五次回执后独立语音对照已核对 S1-MEMO-HEAD。",
            toolOrder = listOf(Workspace.Read)) {
            if (turn == 1) { finalVoiceGate.hold(); read(S1Memo) }
            else { requireMemo(); answer("$tag 五次回执后独立语音对照已核对 S1-MEMO-HEAD。") }
        }
    )
}
